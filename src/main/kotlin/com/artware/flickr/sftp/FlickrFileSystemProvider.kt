package com.artware.flickr.sftp

import com.flickr4java.flickr.Flickr
import com.flickr4java.flickr.RequestContext
import com.flickr4java.flickr.photosets.Photoset
import com.flickr4java.flickr.photos.Photo
import com.flickr4java.flickr.photos.Size
import com.flickr4java.flickr.collections.Collection as FlickrCollection
import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.commons.imaging.Imaging
import org.apache.commons.imaging.formats.jpeg.JpegImageMetadata
import org.apache.commons.imaging.formats.jpeg.exif.ExifRewriter
import org.apache.commons.imaging.formats.tiff.constants.ExifTagConstants
import org.apache.commons.imaging.formats.tiff.constants.TiffTagConstants
import org.apache.commons.imaging.formats.tiff.write.TiffOutputSet
import java.net.URI
import java.net.URL
import java.net.HttpURLConnection
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel
import java.nio.file.*
import java.nio.file.attribute.*
import java.nio.file.spi.FileSystemProvider
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.io.IOException
import java.io.ByteArrayOutputStream
import com.flickr4java.flickr.photos.Exif as FlickrExif

class FlickrFileSystemProvider : FileSystemProvider() {
    private val logger = KotlinLogging.logger {}

    private val scannerExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "flickr-scanner").apply { isDaemon = true }
    }

    override fun getScheme(): String = "flickr"

    override fun newFileSystem(uri: URI, env: Map<String, *>): FileSystem {
        throw UnsupportedOperationException("Use custom constructor")
    }

    override fun getFileSystem(uri: URI): FileSystem {
        throw UnsupportedOperationException()
    }

    override fun getPath(uri: URI): Path {
        throw UnsupportedOperationException()
    }

    private sealed class ResolvedPath {
        object Root : ResolvedPath()
        data class Collection(val collection: FlickrCollection) : ResolvedPath()
        data class Album(val album: Photoset) : ResolvedPath()
        data class Photo(val album: Photoset, val photo: com.flickr4java.flickr.photos.Photo) : ResolvedPath()
        object NotFound : ResolvedPath()
    }

    private fun resolvePath(fs: FlickrFileSystem, parts: List<String>): ResolvedPath {
        if (parts.isEmpty()) return ResolvedPath.Root
        
        val collections = fs.getCollections()
        val resolved = resolveRecursive(fs, collections, parts)
        if (resolved !is ResolvedPath.NotFound) return resolved
        
        // Try orphan albums at top level
        val first = parts[0]
        val remaining = parts.drop(1)
        val orphans = fs.getOrphanAlbums()
        val album = orphans.find { sanitize(it.title) == first }
        if (album != null) {
            if (remaining.isEmpty()) return ResolvedPath.Album(album)
            if (remaining.size == 1) {
                val photo = findPhotoInAlbum(fs, album, remaining[0])
                if (photo != null) return ResolvedPath.Photo(album, photo)
            }
        }
        
        return ResolvedPath.NotFound
    }

    private fun resolveRecursive(fs: FlickrFileSystem, collections: List<FlickrCollection>, parts: List<String>): ResolvedPath {
        if (parts.isEmpty()) return ResolvedPath.NotFound
        
        val first = parts[0]
        val remaining = parts.drop(1)
        
        val collection = collections.find { sanitize(it.title) == first }
        if (collection != null) {
            if (remaining.isEmpty()) return ResolvedPath.Collection(collection)
            return resolveInCollection(fs, collection, remaining)
        }
        
        return ResolvedPath.NotFound
    }

    private fun resolveInCollection(fs: FlickrFileSystem, collection: FlickrCollection, parts: List<String>): ResolvedPath {
        if (parts.isEmpty()) return ResolvedPath.Collection(collection)
        
        val first = parts[0]
        val remaining = parts.drop(1)
        
        // 1. Try sub-collections
        val subCollection = collection.collections.find { sanitize(it.title) == first }
        if (subCollection != null) {
            if (remaining.isEmpty()) return ResolvedPath.Collection(subCollection)
            return resolveInCollection(fs, subCollection, remaining)
        }
        
        // 2. Try albums
        val album = collection.photosets.find { sanitize(it.title) == first }
        if (album != null) {
            if (remaining.isEmpty()) return ResolvedPath.Album(album)
            if (remaining.size == 1) {
                val photo = findPhotoInAlbum(fs, album, remaining[0])
                if (photo != null) return ResolvedPath.Photo(album, photo)
            }
        }
        
        return ResolvedPath.NotFound
    }

    private fun findPhotoInAlbum(fs: FlickrFileSystem, album: Photoset, fileName: String): Photo? {
        val cached = fs.albumPhotosCache[album.id]
        if (cached != null) return cached[fileName]
        
        val photos = fs.getPhotos(album.id).toList()
        val names = getPhotoNames(photos)
        fs.albumPhotosCache[album.id] = names
        return names[fileName]
    }

    override fun newDirectoryStream(dir: Path, filter: DirectoryStream.Filter<in Path>?): DirectoryStream<Path> {
        val fPath = dir as FlickrPath
        val fs = fPath.fileSystem as FlickrFileSystem
        val parts = fPath.getParts()
        logger.trace { "newDirectoryStream: $fPath" }

        RequestContext.getRequestContext().auth = fs.auth

        val pathSequence: Sequence<Path> = try {
            val resolved = resolvePath(fs, parts)
            when (resolved) {
                is ResolvedPath.Root -> {
                    val collections = fs.getCollections().asSequence().map { collection ->
                        createPath(dir, collection.title, true, fs, createDirectoryAttributes(fs, dir.resolve(sanitize(collection.title)) as FlickrPath))
                    }
                    val orphans = fs.getOrphanAlbums().asSequence().map { album ->
                        createPath(dir, album.title, true, fs, createDirectoryAttributes(fs, dir.resolve(sanitize(album.title)) as FlickrPath))
                    }
                    collections + orphans
                }
                is ResolvedPath.Collection -> {
                    val subCollections = resolved.collection.collections.asSequence().map { coll ->
                        createPath(dir, coll.title, true, fs, createDirectoryAttributes(fs, dir.resolve(sanitize(coll.title)) as FlickrPath))
                    }
                    val albums = resolved.collection.photosets.asSequence().map { album ->
                        createPath(dir, album.title, true, fs, createDirectoryAttributes(fs, dir.resolve(sanitize(album.title)) as FlickrPath))
                    }
                    subCollections + albums
                }
                is ResolvedPath.Album -> {
                    val album = resolved.album
                    fs.notifyAccess(album.id)
                    val photos = fs.getPhotos(album.id).toList()
                    fetchTagsUpfront(fs, photos)
                    startBackgroundScan(fs, album.id, photos.asSequence())
                    
                    val names = getPhotoNames(photos)
                    fs.albumPhotosCache[album.id] = names
                    
                    names.entries.asSequence().map { entry ->
                        val photo = entry.value
                        val name = entry.key
                        val p = dir.resolve(name)
                        val attrs = createAttributes(fs, p as FlickrPath, photo)
                        fs.attributesCache[p.normalizedPath] = attrs
                        p
                    }.toList().asSequence()
                }
                else -> throw NoSuchFileException(dir.toString())
            }
        } catch (e: Exception) {
            logger.error(e) { "Error in newDirectoryStream for $dir: ${e.message}" }
            if (e is NoSuchFileException) throw e
            if (e is IOException) throw e
            throw IOException(e)
        }

        return object : DirectoryStream<Path> {
            override fun iterator(): MutableIterator<Path> {
                val iterator = if (filter == null) pathSequence.iterator()
                else pathSequence.filter { filter.accept(it) }.iterator()

                return object : MutableIterator<Path> {
                    override fun hasNext(): Boolean = iterator.hasNext()
                    override fun next(): Path = iterator.next()
                    override fun remove() = throw UnsupportedOperationException()
                }
            }
            override fun close() {}
        }
    }

    private fun getPhotos(fs: FlickrFileSystem, albumId: String): Sequence<Photo> {
        return fs.getPhotos(albumId)
    }

    private fun createPath(dir: Path, title: String, isDir: Boolean, fs: FlickrFileSystem, attrs: PosixFileAttributes): Path {
        val p = dir.resolve(sanitize(title))
        fs.attributesCache[(p as FlickrPath).normalizedPath] = attrs
        return p
    }

    private fun findCollection(fs: FlickrFileSystem, name: String): FlickrCollection? {
        val resolved = resolvePath(fs, listOf(name))
        return (resolved as? ResolvedPath.Collection)?.collection
    }

    private fun findAlbum(fs: FlickrFileSystem, collectionName: String, albumName: String): Photoset? {
        val resolved = resolvePath(fs, listOf(collectionName, albumName))
        return (resolved as? ResolvedPath.Album)?.album
    }

    private fun findPhoto(fs: FlickrFileSystem, collectionName: String, albumName: String, fileName: String): Photo? {
        val resolved = resolvePath(fs, listOf(collectionName, albumName, fileName))
        return (resolved as? ResolvedPath.Photo)?.photo
    }

    internal fun sanitize(name: String?): String {
        if (name.isNullOrBlank()) return "unknown"
        return name.replace('/', '_').replace('\\', '_').replace(':', '_').replace('*', '_').replace('?', '_').replace('"', '_').replace('<', '_').replace('>', '_').replace('|', '_')
    }

    private fun isFlickrId(photo: Photo): Boolean {
        val title = photo.title
        val id = photo.id
        if (title.isNullOrBlank()) return true
        if (title == id) return true
        return title.length in 10..15 && title.all { it.isDigit() }
    }

    internal fun fetchTagsUpfront(fs: FlickrFileSystem, photos: List<Photo>) {
        val photosMissingTags = photos.filter { photo ->
            // Only fetch if title looks like an ID and we don't have the special tags
            if (!isFlickrId(photo)) return@filter false
            
            val allTags = photo.tags
            val hasOriginalNameTag = allTags?.any { 
                it.raw != null && (it.raw.startsWith("file:name=") || it.raw.startsWith("oscp:rawfilename=") || it.raw.startsWith("original:filename=")) 
            } ?: false
            
            !hasOriginalNameTag
        }
        
        if (photosMissingTags.isEmpty()) return
        
        logger.debug { "Fetching tags upfront for ${photosMissingTags.size} photos" }
        
        val executor = Executors.newFixedThreadPool(10)
        try {
            val futures = photosMissingTags.map { photo ->
                executor.submit {
                    try {
                        val info = fs.flickr.photosInterface.getInfo(photo.id, photo.secret)
                        photo.tags = info.tags
                    } catch (e: Exception) {
                        logger.warn(e) { "Failed to fetch info for photo ${photo.id}" }
                    }
                }
            }
            // Use a longer timeout or no timeout at all (since we have a thread pool)
            // But we don't want to block forever. Let's use 2 minutes.
            futures.forEach { 
                try {
                    it.get(2, TimeUnit.MINUTES)
                } catch (e: Exception) {
                    logger.warn { "Timeout or error fetching tags for a photo: ${e.message}" }
                }
            }
        } finally {
            executor.shutdown()
        }
    }

    internal fun getPhotoName(photo: Photo): String {
        val allTags = photo.tags
        val originalFileName = allTags?.asSequence()
            ?.filter { it.raw != null && (it.raw.startsWith("file:name=") || it.raw.startsWith("oscp:rawfilename=") || it.raw.startsWith("original:filename=")) }
            ?.map { it.raw.substringAfter('=') }
            ?.firstOrNull()

        val title = when {
            !originalFileName.isNullOrBlank() -> {
                val name = originalFileName
                if (name.contains('.')) name.substringBeforeLast('.') else name
            }
            !photo.title.isNullOrBlank() -> photo.title
            !photo.id.isNullOrBlank() -> photo.id
            else -> "unknown"
        }
        val ext = photo.originalFormat ?: "jpg"
        val sanitized = sanitize(title)
        return if (sanitized.endsWith(".$ext", ignoreCase = true)) sanitized else "$sanitized.$ext"
    }

    internal fun getPhotoNames(photos: List<Photo>): Map<String, Photo> {
        val names = mutableMapOf<String, Photo>()
        val groupedByPrettyName = photos.groupBy { getPhotoName(it) }

        groupedByPrettyName.forEach { (prettyName, photoList) ->
            if (photoList.size == 1) {
                names[prettyName] = photoList[0]
            } else {
                photoList.forEach { photo ->
                    val ext = photo.originalFormat ?: "jpg"
                    val dotExt = ".$ext"
                    val base = if (prettyName.endsWith(dotExt, ignoreCase = true)) {
                        prettyName.substring(0, prettyName.length - dotExt.length)
                    } else {
                        prettyName
                    }
                    names["$base (${photo.id})$dotExt"] = photo
                }
            }
        }
        return names
    }

    private fun getExif(fs: FlickrFileSystem, photo: Photo): List<FlickrExif> {
        return fs.exifCache.computeIfAbsent(photo.id) {
            try {
                fs.flickr.photosInterface.getExif(photo.id, photo.secret).toList()
            } catch (e: Exception) {
                logger.warn(e) { "Failed to fetch EXIF for photo ${photo.id}" }
                emptyList()
            }
        }
    }

    private fun embedMetadata(imageBytes: ByteArray, photo: Photo, exifs: List<FlickrExif>): ByteArray {
        try {
            val metadata = Imaging.getMetadata(imageBytes) as? JpegImageMetadata
            val outputSet = metadata?.exif?.outputSet ?: TiffOutputSet()
            val exifDirectory = outputSet.getOrCreateExifDirectory()
            val rootDirectory = outputSet.getOrCreateRootDirectory()

            // Add description from Flickr title/description
            val description = photo.description ?: photo.title
            if (!description.isNullOrBlank()) {
                rootDirectory.removeField(TiffTagConstants.TIFF_TAG_IMAGE_DESCRIPTION)
                rootDirectory.add(TiffTagConstants.TIFF_TAG_IMAGE_DESCRIPTION, description)
            }

            // Map some common Flickr EXIF to JPEG EXIF
            exifs.forEach { flickrExif ->
                try {
                    val tag = flickrExif.tag
                    val value = flickrExif.clean ?: flickrExif.raw
                    if (value != null) {
                        when (tag) {
                            "Make" -> {
                                rootDirectory.removeField(TiffTagConstants.TIFF_TAG_MAKE)
                                rootDirectory.add(TiffTagConstants.TIFF_TAG_MAKE, value)
                            }
                            "Model" -> {
                                rootDirectory.removeField(TiffTagConstants.TIFF_TAG_MODEL)
                                rootDirectory.add(TiffTagConstants.TIFF_TAG_MODEL, value)
                            }
                            "DateTimeOriginal" -> {
                                exifDirectory.removeField(ExifTagConstants.EXIF_TAG_DATE_TIME_ORIGINAL)
                                exifDirectory.add(ExifTagConstants.EXIF_TAG_DATE_TIME_ORIGINAL, value)
                            }
                        }
                    }
                } catch (e: Exception) {
                    logger.warn { "Failed to add tag ${flickrExif.tag}: ${e.message}" }
                }
            }

            // Also add all as UserComment for visibility
            val userComment = exifs.joinToString("\n") { "${it.label ?: it.tag}: ${it.clean ?: it.raw}" }
            if (userComment.isNotBlank()) {
                exifDirectory.removeField(ExifTagConstants.EXIF_TAG_USER_COMMENT)
                exifDirectory.add(ExifTagConstants.EXIF_TAG_USER_COMMENT, userComment)
            }

            val os = ByteArrayOutputStream()
            ExifRewriter().updateExifMetadataLossless(imageBytes, os, outputSet)
            return os.toByteArray()
        } catch (e: Exception) {
            logger.warn(e) { "Failed to embed metadata for photo ${photo.id}" }
            return imageBytes
        }
    }

    private fun startBackgroundScan(fs: FlickrFileSystem, albumId: String, photos: Sequence<Photo>) {
        val task = fs.scannerTask
        if (task != null && !task.isDone) return
        
        fs.scannerTask = scannerExecutor.submit {
            try {
                logger.debug { "Starting background scan for album $albumId" }
                RequestContext.getRequestContext().auth = fs.auth
                for (photo in photos) {
                    if (Thread.interrupted()) break
                    
                    // 1. Fetch EXIF
                    if (!fs.exifCache.containsKey(photo.id)) {
                        getExif(fs, photo)
                    }
                    
                    // 2. Fetch Size
                    if (!fs.sizeCache.containsKey(photo.id)) {
                        fetchPhotoSize(fs, photo)
                    }
                }
                logger.debug { "Finished background scan for album $albumId" }
            } catch (e: Exception) {
                // Background, ignore
            }
        }
    }

    private fun fetchPhotoSize(fs: FlickrFileSystem, photo: Photo) {
        try {
            val url = photo.originalUrl ?: photo.mediumUrl ?: return
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "HEAD"
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            val size = conn.contentLengthLong
            if (size > 0) {
                fs.sizeCache[photo.id] = size
            }
        } catch (e: Exception) {
            // Ignore
        }
    }


    private fun getPhotoSize(fs: FlickrFileSystem, photo: Photo): Long {
        return fs.sizeCache[photo.id] ?: 0L
    }

    private fun checkWriteAccess(options: Set<OpenOption>) {
        if (options.contains(StandardOpenOption.WRITE) ||
            options.contains(StandardOpenOption.APPEND) ||
            options.contains(StandardOpenOption.TRUNCATE_EXISTING) ||
            options.contains(StandardOpenOption.DELETE_ON_CLOSE) ||
            options.contains(StandardOpenOption.CREATE) ||
            options.contains(StandardOpenOption.CREATE_NEW)
        ) {
            throw ReadOnlyFileSystemException()
        }
    }

    private fun fetchPhotoBytes(path: Path): ByteArray {
        val fPath = path as FlickrPath
        val fs = fPath.fileSystem as FlickrFileSystem
        val parts = fPath.getParts()

        RequestContext.getRequestContext().auth = fs.auth
        val resolved = resolvePath(fs, parts)
        if (resolved !is ResolvedPath.Photo) throw NoSuchFileException(path.toString())
        val photo = resolved.photo

        val stream = try {
            logger.debug { "Fetching original image for ${photo.id}" }
            fs.flickr.photosInterface.getImageAsStream(photo, Size.ORIGINAL)
        } catch (e: Exception) {
            logger.warn { "Original image not available for ${photo.id}, falling back to medium: ${e.message}" }
            try {
                fs.flickr.photosInterface.getImageAsStream(photo, Size.MEDIUM)
            } catch (e2: Exception) {
                throw IOException("Failed to fetch image from Flickr for photo ${photo.id}", e2)
            }
        }
        var bytes = stream.readAllBytes()
        val exifs = getExif(fs, photo)
        if (exifs.isNotEmpty()) {
            bytes = embedMetadata(bytes, photo, exifs)
        }
        fs.sizeCache[photo.id] = bytes.size.toLong()
        return bytes
    }

    override fun <A : BasicFileAttributes> readAttributes(path: Path, type: Class<A>, vararg options: LinkOption): A {
        logger.trace { "readAttributes: $path, type: ${type.name}" }
        
        val fPath = path as FlickrPath
        val fs = fPath.fileSystem as FlickrFileSystem
        val cached = fs.attributesCache[fPath.normalizedPath]
        if (cached != null) {
            if (type.isInstance(cached)) {
                return type.cast(cached)
            }
        }

        val attrs = getAttributes(path)
        fs.attributesCache[fPath.normalizedPath] = attrs
        
        if (type.isInstance(attrs)) {
            return type.cast(attrs)
        }
        throw UnsupportedOperationException("Type ${type.name} not supported")
    }

    private fun getAttributes(path: Path): PosixFileAttributes {
        val fPath = path as FlickrPath
        val fs = fPath.fileSystem as FlickrFileSystem
        
        val cached = fs.attributesCache[fPath.normalizedPath]
        if (cached != null) {
            return cached
        }

        val parts = fPath.getParts()

        RequestContext.getRequestContext().auth = fs.auth

        val resolved = resolvePath(fs, parts)
        val attrs = when (resolved) {
            is ResolvedPath.Root -> createDirectoryAttributes(fs, fPath)
            is ResolvedPath.Collection -> createDirectoryAttributes(fs, fPath)
            is ResolvedPath.Album -> {
                fs.notifyAccess(resolved.album.id)
                createDirectoryAttributes(fs, fPath)
            }
            is ResolvedPath.Photo -> createAttributes(fs, fPath, resolved.photo)
            else -> throw NoSuchFileException(path.toString())
        }
        
        fs.attributesCache[fPath.normalizedPath] = attrs
        return attrs
    }

    private fun createAttributes(fs: FlickrFileSystem, path: FlickrPath, photo: Photo): PosixFileAttributes {
        return FlickrFileAttributes(
            isDir = false,
            isRegularFile = true,
            sizeProvider = { getPhotoSize(fs, photo) },
            lastModified = photo.datePosted?.time ?: photo.dateAdded?.time ?: 0L,
            creationTime = photo.dateTaken?.time ?: 0L,
            fileKey = path.normalizedPath,
            owner = fs.auth.user.username ?: "flickr",
            permissions = "rw-r--r--"
        )
    }

    private fun createDirectoryAttributes(fs: FlickrFileSystem, path: FlickrPath): PosixFileAttributes {
        return FlickrFileAttributes(
            isDir = true,
            isRegularFile = false,
            sizeProvider = { 0L },
            lastModified = 0L,
            creationTime = 0L,
            fileKey = path.normalizedPath,
            owner = fs.auth.user.username ?: "flickr",
            permissions = "rwxr-xr-x"
        )
    }

    override fun newByteChannel(path: Path, options: MutableSet<out OpenOption>, vararg attrs: FileAttribute<*>?): SeekableByteChannel {
        logger.trace { "newByteChannel: $path, options: $options" }
        checkWriteAccess(options)
        try {
            val bytes = fetchPhotoBytes(path)
            return MemoryByteChannel(bytes)
        } catch (e: Exception) {
            logger.error(e) { "Error in newByteChannel for $path: ${e.message}" }
            if (e is IOException || e is RuntimeException) throw e
            throw IOException(e)
        }
    }

    override fun newFileChannel(path: Path, options: MutableSet<out OpenOption>, vararg attrs: FileAttribute<*>?): FileChannel {
        logger.trace { "newFileChannel: $path, options: $options" }
        checkWriteAccess(options)
        try {
            val bytes = fetchPhotoBytes(path)
            val tempFile = Files.createTempFile("flickr-", ".jpg")
            Files.write(tempFile, bytes)
            return FileChannel.open(tempFile, StandardOpenOption.READ, StandardOpenOption.DELETE_ON_CLOSE)
        } catch (e: Exception) {
            logger.error(e) { "Error in newFileChannel for $path: ${e.message}" }
            if (e is IOException || e is RuntimeException) throw e
            throw IOException(e)
        }
    }

    override fun checkAccess(path: Path, vararg modes: AccessMode) {
        logger.trace { "checkAccess: $path ${modes.contentToString()}" }
        readAttributes(path, BasicFileAttributes::class.java)
    }

    // Other methods can be left unimplemented or throw exception
    override fun createDirectory(dir: Path, vararg attrs: FileAttribute<*>?) { throw ReadOnlyFileSystemException() }
    override fun delete(path: Path) { throw ReadOnlyFileSystemException() }
    override fun copy(source: Path, target: Path, vararg options: CopyOption?) { throw ReadOnlyFileSystemException() }
    override fun move(source: Path, target: Path, vararg options: CopyOption?) { throw ReadOnlyFileSystemException() }
    override fun isSameFile(path: Path, path2: Path): Boolean = path == path2
    override fun isHidden(path: Path): Boolean = false
    override fun getFileStore(path: Path): FileStore {
        logger.trace { "getFileStore: $path" }
        return object : FileStore() {
            override fun name(): String = "flickr"
            override fun type(): String = "flickr"
            override fun isReadOnly(): Boolean = true
            override fun getTotalSpace(): Long = 1024L * 1024 * 1024 * 1024 // 1 TB dummy
            override fun getUsableSpace(): Long = 1024L * 1024 * 1024 * 1024
            override fun getUnallocatedSpace(): Long = 0
            override fun getAttribute(attribute: String): Any? = null
            override fun <V : FileStoreAttributeView> getFileStoreAttributeView(type: Class<V>): V? = null
            override fun supportsFileAttributeView(type: Class<out FileAttributeView>): Boolean {
                return type == BasicFileAttributeView::class.java || 
                       type == PosixFileAttributeView::class.java ||
                       type == FileOwnerAttributeView::class.java
            }
            override fun supportsFileAttributeView(name: String): Boolean {
                return name == "basic" || name == "posix" || name == "owner"
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <V : FileAttributeView> getFileAttributeView(path: Path, type: Class<V>, vararg options: LinkOption): V? {
        logger.trace { "getFileAttributeView: $path as ${type.simpleName}" }
        if (type.isAssignableFrom(PosixFileAttributeView::class.java)) {
            return object : PosixFileAttributeView {
                override fun name(): String = "posix"
                override fun readAttributes(): PosixFileAttributes {
                    return this@FlickrFileSystemProvider.readAttributes(path, PosixFileAttributes::class.java, *options)
                }
                override fun setTimes(lastModifiedTime: FileTime?, lastAccessTime: FileTime?, creationTime: FileTime?) {
                    throw ReadOnlyFileSystemException()
                }
                override fun setPermissions(perms: Set<PosixFilePermission>?) {
                    throw ReadOnlyFileSystemException()
                }
                override fun setGroup(group: GroupPrincipal?) {
                    throw ReadOnlyFileSystemException()
                }
                override fun getOwner(): UserPrincipal {
                    return readAttributes().owner()
                }
                override fun setOwner(owner: UserPrincipal?) {
                    throw ReadOnlyFileSystemException()
                }
            } as V
        } else if (type.isAssignableFrom(BasicFileAttributeView::class.java)) {
            return object : BasicFileAttributeView {
                override fun name(): String = "basic"
                override fun readAttributes(): BasicFileAttributes {
                    return this@FlickrFileSystemProvider.readAttributes(path, BasicFileAttributes::class.java, *options)
                }
                override fun setTimes(lastModifiedTime: FileTime?, lastAccessTime: FileTime?, creationTime: FileTime?) {
                    throw ReadOnlyFileSystemException()
                }
            } as V
        } else if (type.isAssignableFrom(FileOwnerAttributeView::class.java)) {
            return object : FileOwnerAttributeView {
                override fun name(): String = "owner"
                override fun getOwner(): UserPrincipal {
                    return this@FlickrFileSystemProvider.readAttributes(path, PosixFileAttributes::class.java, *options).owner()
                }
                override fun setOwner(owner: UserPrincipal?) {
                    throw ReadOnlyFileSystemException()
                }
            } as V
        }
        return null
    }

    override fun readAttributes(path: Path, attributes: String, vararg options: LinkOption): MutableMap<String, Any> {
        val viewAndAttrs = attributes.split(":")
        val view = if (viewAndAttrs.size > 1) viewAndAttrs[0] else "basic"
        val attrList = if (viewAndAttrs.size > 1) viewAndAttrs[1] else viewAndAttrs[0]

        val posixAttrs = getAttributes(path)
        val map = mutableMapOf<String, Any>()

        val allRequested = attrList == "*"
        val requestedList = if (allRequested) emptyList() else attrList.split(",")

        fun addIfRequested(v: String, name: String, value: Any?) {
            val isRequestedView = view == v || (v == "basic" && (view == "posix" || view == "owner"))
            if (value != null && isRequestedView && (allRequested || requestedList.contains(name))) {
                map[name] = value
            }
        }

        addIfRequested("basic", "isRegularFile", posixAttrs.isRegularFile)
        addIfRequested("basic", "isDirectory", posixAttrs.isDirectory)
        addIfRequested("basic", "isSymbolicLink", posixAttrs.isSymbolicLink)
        addIfRequested("basic", "isOther", posixAttrs.isOther)
        addIfRequested("basic", "size", posixAttrs.size())
        addIfRequested("basic", "lastModifiedTime", posixAttrs.lastModifiedTime())
        addIfRequested("basic", "lastAccessTime", posixAttrs.lastAccessTime())
        addIfRequested("basic", "creationTime", posixAttrs.creationTime())
        addIfRequested("basic", "fileKey", posixAttrs.fileKey())

        addIfRequested("posix", "permissions", posixAttrs.permissions())
        addIfRequested("posix", "owner", posixAttrs.owner())
        addIfRequested("posix", "group", posixAttrs.group())

        addIfRequested("owner", "owner", posixAttrs.owner())

        return map
    }

    override fun setAttribute(path: Path, attribute: String, value: Any, vararg options: LinkOption?) { throw ReadOnlyFileSystemException() }
}

class FlickrUserPrincipal(private val name: String) : UserPrincipal {
    override fun getName(): String = name
    override fun toString(): String = name
}

class FlickrGroupPrincipal(private val name: String) : GroupPrincipal {
    override fun getName(): String = name
    override fun toString(): String = name
}

class FlickrFileAttributes(
    private val isDir: Boolean,
    private val isRegularFile: Boolean,
    private val sizeProvider: () -> Long,
    private val lastModified: Long,
    private val creationTime: Long,
    private val fileKey: Any?,
    private val owner: String,
    private val permissions: String
) : PosixFileAttributes {
    override fun lastModifiedTime(): FileTime = FileTime.fromMillis(lastModified)
    override fun lastAccessTime(): FileTime = lastModifiedTime()
    override fun creationTime(): FileTime = FileTime.fromMillis(creationTime)
    override fun isRegularFile(): Boolean = isRegularFile
    override fun isDirectory(): Boolean = isDir
    override fun isSymbolicLink(): Boolean = false
    override fun isOther(): Boolean = false
    override fun size(): Long = sizeProvider()
    override fun fileKey(): Any? = fileKey
    override fun owner(): UserPrincipal = FlickrUserPrincipal(owner)
    override fun group(): GroupPrincipal = FlickrGroupPrincipal("flickr")
    override fun permissions(): Set<PosixFilePermission> = PosixFilePermissions.fromString(permissions)
}

private class MemoryByteChannel(private val bytes: ByteArray) : SeekableByteChannel {
    private var position = 0L
    private var open = true

    override fun read(dst: ByteBuffer): Int {
        if (!isOpen) throw ClosedChannelException()
        if (position >= bytes.size) return -1
        val len = Math.min(dst.remaining(), (bytes.size - position).toInt())
        dst.put(bytes, position.toInt(), len)
        position += len
        return len
    }

    override fun write(src: ByteBuffer): Int = throw UnsupportedOperationException()
    override fun position(): Long = position
    override fun position(newPosition: Long): SeekableByteChannel {
        position = newPosition
        return this
    }
    override fun size(): Long = bytes.size.toLong()
    override fun truncate(size: Long): SeekableByteChannel = throw UnsupportedOperationException()
    override fun isOpen(): Boolean = open
    override fun close() { open = false }
}
