package com.artware.flickr.sftp

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import java.nio.file.ReadOnlyFileSystemException
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.NoSuchFileException
import com.flickr4java.flickr.auth.Auth
import com.flickr4java.flickr.Flickr
import com.flickr4java.flickr.collections.CollectionsInterface
import com.flickr4java.flickr.collections.Collection as FlickrCollection
import com.flickr4java.flickr.RequestContext
import com.flickr4java.flickr.people.User

class FlickrFileSystemProviderTest {

    @Test
    fun testReadAttributesPosix() {
        val provider = FlickrFileSystemProvider()
        val flickr = mock(Flickr::class.java)
        val auth = mock(Auth::class.java)
        val user = mock(User::class.java)
        val fs = FlickrFileSystem(provider, flickr, auth)
        val collInterface = mock(CollectionsInterface::class.java)

        `when`(auth.user).thenReturn(user)
        `when`(user.id).thenReturn("user123")
        `when`(user.username).thenReturn("testuser")
        `when`(flickr.collectionsInterface).thenReturn(collInterface)
        `when`(collInterface.getTree(null, "user123")).thenReturn(emptyList())

        val path = FlickrPath(fs, "/")
        
        val basicAttrs = provider.readAttributes(path, BasicFileAttributes::class.java)
        assertTrue(basicAttrs.isDirectory)
        
        val posixAttrs = provider.readAttributes(path, PosixFileAttributes::class.java)
        assertTrue(posixAttrs.isDirectory)
        assertEquals("testuser", posixAttrs.owner().name)
        assertNotNull(posixAttrs.permissions())
    }

    @Test
    fun testReadAttributesString() {
        val provider = FlickrFileSystemProvider()
        val flickr = mock(Flickr::class.java)
        val auth = mock(Auth::class.java)
        val user = mock(User::class.java)
        val fs = FlickrFileSystem(provider, flickr, auth)
        val collInterface = mock(CollectionsInterface::class.java)

        `when`(auth.user).thenReturn(user)
        `when`(user.id).thenReturn("user123")
        `when`(user.username).thenReturn("testuser")
        `when`(flickr.collectionsInterface).thenReturn(collInterface)
        `when`(collInterface.getTree(null, "user123")).thenReturn(emptyList())

        val path = FlickrPath(fs, "/")
        
        val attrs = provider.readAttributes(path, "posix:permissions,owner")
        assertNotNull(attrs["permissions"])
        assertEquals("testuser", (attrs["owner"] as java.nio.file.attribute.UserPrincipal).name)
    }

    @Test
    fun testWriteFails() {
        val provider = FlickrFileSystemProvider()
        val fs = mock(FlickrFileSystem::class.java)
        val path = FlickrPath(fs, "/Coll/Album/Photo1.jpg")
        
        assertThrows(ReadOnlyFileSystemException::class.java) {
            provider.newByteChannel(path, mutableSetOf(StandardOpenOption.WRITE))
        }
    }

    @Test
    fun testNewFileChannel() {
        val provider = FlickrFileSystemProvider()
        val flickr = mock(Flickr::class.java)
        val auth = mock(Auth::class.java)
        val user = mock(User::class.java)
        val fs = spy(FlickrFileSystem(provider, flickr, auth))
        val collInterface = mock(com.flickr4java.flickr.collections.CollectionsInterface::class.java)
        val photosInterface = mock(com.flickr4java.flickr.photos.PhotosInterface::class.java)
        val photosetsInterface = mock(com.flickr4java.flickr.photosets.PhotosetsInterface::class.java)

        `when`(auth.user).thenReturn(user)
        `when`(user.id).thenReturn("user123")
        `when`(flickr.collectionsInterface).thenReturn(collInterface)
        `when`(flickr.photosInterface).thenReturn(photosInterface)
        `when`(flickr.photosetsInterface).thenReturn(photosetsInterface)

        val collection = mock(com.flickr4java.flickr.collections.Collection::class.java)
        `when`(collection.title).thenReturn("Austria")
        `when`(collInterface.getTree(null, "user123")).thenReturn(listOf(collection))

        val photoset = mock(com.flickr4java.flickr.photosets.Photoset::class.java)
        `when`(photoset.title).thenReturn("2014 - Wien")
        `when`(photoset.id).thenReturn("album123")
        `when`(collection.photosets).thenReturn(listOf(photoset))

        val photo = mock(com.flickr4java.flickr.photos.Photo::class.java)
        `when`(photo.id).thenReturn("photo123")
        `when`(photo.title).thenReturn("Pic")
        `when`(photo.secret).thenReturn("secret")
        
        doReturn(listOf(photo).asSequence()).`when`(fs).getPhotos("album123")
        
        val photoList = com.flickr4java.flickr.photos.PhotoList<com.flickr4java.flickr.photos.Photo>().apply { add(photo) }
        `when`(photosetsInterface.getPhotos(eq("album123"), anySet(), anyInt(), anyInt(), anyInt())).thenReturn(photoList)

        val imageData = "fake image data".toByteArray()
        `when`(photosInterface.getImageAsStream(photo, com.flickr4java.flickr.photos.Size.ORIGINAL))
            .thenReturn(java.io.ByteArrayInputStream(imageData))
        `when`(photosInterface.getExif("photo123", "secret")).thenReturn(emptyList())

        val path = FlickrPath(fs, "/Austria/2014 - Wien/Pic.jpg")
        
        val channel = provider.newFileChannel(path, mutableSetOf(StandardOpenOption.READ))
        assertNotNull(channel)
        assertEquals(imageData.size.toLong(), channel.size())
        
        val buffer = java.nio.ByteBuffer.allocate(imageData.size)
        channel.read(buffer)
        assertArrayEquals(imageData, buffer.array())
        
        channel.close()
    }
}
