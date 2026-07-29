package com.artware.flickr.sftp

import com.flickr4java.flickr.Flickr
import com.flickr4java.flickr.auth.Auth
import com.flickr4java.flickr.collections.Collection as FlickrCollection
import com.flickr4java.flickr.photos.Photo
import com.flickr4java.flickr.photosets.Photoset
import com.flickr4java.flickr.photosets.PhotosetsInterface
import com.flickr4java.flickr.collections.CollectionsInterface
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import java.nio.file.NoSuchFileException
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlickrTreeTest {

    @Test
    fun testNestedCollectionResolution() {
        val provider = FlickrFileSystemProvider()
        val flickr = mock(Flickr::class.java)
        val auth = mock(Auth::class.java)
        val user = com.flickr4java.flickr.people.User().apply { id = "user123" }
        `when`(auth.user).thenReturn(user)
        
        val fs = FlickrFileSystem(provider, flickr, auth)
        
        val collInterface = mock(CollectionsInterface::class.java)
        `when`(flickr.collectionsInterface).thenReturn(collInterface)
        
        // Structure: /A/B/Album1
        val album1 = Photoset().apply { id = "album1"; title = "Album1" }
        val collectionB = FlickrCollection().apply { 
            title = "B"
            photosets = listOf(album1)
        }
        val collectionA = FlickrCollection().apply { 
            title = "A"
            collections = listOf(collectionB)
        }
        
        `when`(collInterface.getTree(null, "user123")).thenReturn(listOf(collectionA))
        
        val pathA = fs.getPath("/A")
        val attrsA = provider.readAttributes(pathA, java.nio.file.attribute.BasicFileAttributes::class.java)
        assertTrue(attrsA.isDirectory)
        
        val pathB = fs.getPath("/A/B")
        val attrsB = provider.readAttributes(pathB, java.nio.file.attribute.BasicFileAttributes::class.java)
        assertTrue(attrsB.isDirectory)
        
        val pathAlbum = fs.getPath("/A/B/Album1")
        val attrsAlbum = provider.readAttributes(pathAlbum, java.nio.file.attribute.BasicFileAttributes::class.java)
        assertTrue(attrsAlbum.isDirectory)
    }

    @Test
    fun testOrphanAlbumResolution() {
        val provider = FlickrFileSystemProvider()
        val flickr = mock(Flickr::class.java)
        val auth = mock(Auth::class.java)
        val user = com.flickr4java.flickr.people.User().apply { id = "user123" }
        `when`(auth.user).thenReturn(user)
        
        val fs = FlickrFileSystem(provider, flickr, auth)
        
        val collInterface = mock(CollectionsInterface::class.java)
        val setsInterface = mock(PhotosetsInterface::class.java)
        `when`(flickr.collectionsInterface).thenReturn(collInterface)
        `when`(flickr.photosetsInterface).thenReturn(setsInterface)
        
        `when`(collInterface.getTree(null, "user123")).thenReturn(emptyList())
        
        val orphanAlbum = Photoset().apply { id = "orphan"; title = "Orphan" }
        val photoList = com.flickr4java.flickr.photosets.Photosets().apply { photosets = listOf(orphanAlbum) }
        `when`(setsInterface.getList("user123")).thenReturn(photoList)
        
        val path = fs.getPath("/Orphan")
        val attrs = provider.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java)
        assertTrue(attrs.isDirectory)
    }
}
