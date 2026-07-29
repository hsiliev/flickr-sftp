package com.artware.flickr.sftp

import com.flickr4java.flickr.Flickr
import com.flickr4java.flickr.auth.Auth
import com.flickr4java.flickr.collections.Collection as FlickrCollection
import com.flickr4java.flickr.photos.Extras
import com.flickr4java.flickr.photos.Photo
import com.flickr4java.flickr.photosets.Photoset
import com.flickr4java.flickr.photos.Exif as FlickrExif
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.*
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.UserPrincipalLookupService
import java.nio.file.spi.FileSystemProvider
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

class FlickrFileSystem(
    private val provider: FlickrFileSystemProvider,
    val flickr: Flickr,
    val auth: Auth
) : FileSystem() {
    private val logger = KotlinLogging.logger {}

    override fun provider(): FileSystemProvider = provider

    override fun close() {
        scannerTask?.cancel(true)
    }

    override fun isOpen(): Boolean = true

    private var cachedAlbumId: String? = null
    private var photoProvider: PhotoProvider? = null
    private var lastAccessTime: Long = 0
    internal var scannerTask: Future<*>? = null

    internal val attributesCache = ConcurrentHashMap<String, PosixFileAttributes>()
    internal val albumPhotosCache = ConcurrentHashMap<String, Map<String, Photo>>()
    internal val sizeCache = ConcurrentHashMap<String, Long>()
    internal val exifCache = ConcurrentHashMap<String, List<FlickrExif>>()
    
    private var collectionTree: List<FlickrCollection>? = null
    private var orphanAlbums: List<Photoset>? = null

    @Synchronized
    fun getCollections(): List<FlickrCollection> {
        if (collectionTree == null) {
            val userId = auth.user.id
            collectionTree = flickr.collectionsInterface.getTree(null, userId)
            logger.debug { "Fetched collection tree for user $userId, found ${collectionTree?.size} top-level collections" }
        }
        return collectionTree ?: emptyList()
    }

    @Synchronized
    fun getOrphanAlbums(): List<Photoset> {
        if (orphanAlbums == null) {
            val userId = auth.user.id
            val allAlbums = flickr.photosetsInterface.getList(userId).photosets
            val tree = getCollections()
            val albumsInTree = mutableSetOf<String>()
            
            fun collectAlbumIds(collections: List<FlickrCollection>) {
                collections.forEach { 
                    it.photosets.forEach { albumsInTree.add(it.id) }
                    collectAlbumIds(it.collections)
                }
            }
            collectAlbumIds(tree)
            orphanAlbums = allAlbums.filter { !albumsInTree.contains(it.id) }
            logger.debug { "Found ${orphanAlbums?.size} orphan albums for user $userId" }
        }
        return orphanAlbums ?: emptyList()
    }

    @Synchronized
    fun getPhotos(albumId: String): Sequence<Photo> {
        val now = System.currentTimeMillis()
        if (cachedAlbumId == albumId && photoProvider != null && (now - lastAccessTime) < TimeUnit.MINUTES.toMillis(30)) {
            lastAccessTime = now
            return photoProvider?.getSequence() ?: emptySequence()
        }

        logger.debug { "Fetching photos for album $albumId (initial)" }
        val provider = PhotoProvider(this, albumId)
        cachedAlbumId = albumId
        photoProvider = provider
        lastAccessTime = now
        return provider.getSequence()
    }

    @Synchronized
    fun notifyAccess(albumId: String?) {
        if (albumId != cachedAlbumId) {
            scannerTask?.cancel(true)
            scannerTask = null
            cachedAlbumId = albumId
            photoProvider = null
            
            attributesCache.clear()
            albumPhotosCache.clear()
        }
    }

    private class PhotoProvider(val fs: FlickrFileSystem, val albumId: String) {
        private val photos = mutableListOf<Photo>()
        private var currentPage = 1
        private var totalPages = 1
        private var finished = false
        private val extras = setOf(Extras.ORIGINAL_FORMAT, Extras.URL_O, Extras.URL_M, Extras.URL_L, Extras.DATE_TAKEN, Extras.DATE_UPLOAD, Extras.MACHINE_TAGS, Extras.TAGS)

        fun getSequence(): Sequence<Photo> = sequence {
            var i = 0
            while (true) {
                val photo = synchronized(this@PhotoProvider) {
                    if (i < photos.size) {
                        photos[i]
                    } else if (!finished) {
                        fetchNextPage()
                        if (i < photos.size) photos[i] else null
                    } else {
                        null
                    }
                } ?: break
                yield(photo)
                i++
            }
        }

        private fun fetchNextPage() {
            try {
                val res = fs.flickr.photosetsInterface.getPhotos(albumId, extras, Flickr.PRIVACY_LEVEL_NO_FILTER, 500, currentPage)
                photos.addAll(res)
                totalPages = res.pages
                if (currentPage >= totalPages || res.isEmpty()) {
                    finished = true
                }
                currentPage++
            } catch (e: Exception) {
                finished = true
                throw e
            }
        }
    }

    override fun isReadOnly(): Boolean = true

    override fun getSeparator(): String = "/"

    override fun getRootDirectories(): MutableIterable<Path> = mutableListOf(FlickrPath(this, "/"))

    override fun getFileStores(): MutableIterable<FileStore> = mutableListOf(provider.getFileStore(getPath("/")))

    override fun supportedFileAttributeViews(): MutableSet<String> = mutableSetOf("basic", "posix", "owner")

    override fun getPath(first: String, vararg more: String): Path {
        var fullPath = first
        for (m in more) {
            fullPath += "/$m"
        }
        return FlickrPath(this, fullPath)
    }

    override fun getPathMatcher(syntaxAndPattern: String?): PathMatcher {
        throw UnsupportedOperationException()
    }

    override fun getUserPrincipalLookupService(): UserPrincipalLookupService {
        throw UnsupportedOperationException()
    }

    override fun newWatchService(): WatchService {
        throw UnsupportedOperationException()
    }
}
