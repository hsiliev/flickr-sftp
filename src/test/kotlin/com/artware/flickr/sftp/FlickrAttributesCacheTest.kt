package com.artware.flickr.sftp

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import java.nio.file.attribute.PosixFileAttributes
import com.flickr4java.flickr.auth.Auth
import com.flickr4java.flickr.Flickr
import com.flickr4java.flickr.people.User
import com.flickr4java.flickr.photos.Photo
import com.flickr4java.flickr.photosets.Photoset
import com.flickr4java.flickr.collections.Collection as FlickrCollection
import com.flickr4java.flickr.collections.CollectionsInterface
import com.flickr4java.flickr.photos.PhotosInterface
import com.flickr4java.flickr.photosets.PhotosetsInterface
import java.nio.file.Path

class FlickrAttributesCacheTest {

    @Test
    fun testCacheIsPopulatedDuringDirectoryListing() {
        val provider = FlickrFileSystemProvider()
        val flickr = mock(Flickr::class.java)
        val auth = mock(Auth::class.java)
        val user = mock(User::class.java)
        
        `when`(user.id).thenReturn("user123")
        `when`(user.username).thenReturn("testuser")
        `when`(auth.user).thenReturn(user)
        
        val collInterface = mock(CollectionsInterface::class.java)
        val photosInterface = mock(PhotosInterface::class.java)
        val photosetsInterface = mock(PhotosetsInterface::class.java)

        `when`(flickr.collectionsInterface).thenReturn(collInterface)
        `when`(flickr.photosInterface).thenReturn(photosInterface)
        `when`(flickr.photosetsInterface).thenReturn(photosetsInterface)
        
        val fs = spy(FlickrFileSystem(provider, flickr, auth))

        val collection = mock(FlickrCollection::class.java)
        `when`(collection.title).thenReturn("Collection1")
        `when`(collInterface.getTree(null, "user123")).thenReturn(listOf(collection))

        val photoset = mock(Photoset::class.java)
        `when`(photoset.title).thenReturn("Album1")
        `when`(photoset.id).thenReturn("album123")
        `when`(collection.photosets).thenReturn(listOf(photoset))

        val photo = mock(Photo::class.java)
        `when`(photo.id).thenReturn("photo123")
        `when`(photo.getId()).thenReturn("photo123")
        `when`(photo.secret).thenReturn("secret123")
        `when`(photo.title).thenReturn("Photo1")
        `when`(photo.originalFormat).thenReturn("jpg")
        `when`(photo.tags).thenReturn(emptyList())
        
        // Mock getPhotos to return our photo sequence
        doReturn(listOf(photo).asSequence()).`when`(fs).getPhotos("album123")
        
        `when`(photosInterface.getInfo("photo123", "secret123")).thenReturn(photo)

        val dirPath = FlickrPath(fs, "/Collection1/Album1")
        
        val stream = provider.newDirectoryStream(dirPath, null)
        val files = stream.toList()
        assertEquals(1, files.size)
        
        // At this point, attributes should be in provider's attributesCache
        
        // Reset only the stubbing for getPhotos to be sure it's not called again
        clearInvocations(fs)
        
        val photoPath = files[0]
        val attrs = provider.readAttributes(photoPath, PosixFileAttributes::class.java)
        assertNotNull(attrs)
        
        verify(fs, times(0)).getPhotos("album123")
    }

    @Test
    fun testAlbumPhotosCacheEfficiency() {
        val provider = FlickrFileSystemProvider()
        val flickr = mock(Flickr::class.java)
        val auth = mock(Auth::class.java)
        val user = mock(User::class.java)

        `when`(user.id).thenReturn("user123")
        `when`(user.username).thenReturn("testuser")
        `when`(auth.user).thenReturn(user)

        val collInterface = mock(CollectionsInterface::class.java)
        val photosInterface = mock(PhotosInterface::class.java)
        val photosetsInterface = mock(PhotosetsInterface::class.java)

        `when`(flickr.collectionsInterface).thenReturn(collInterface)
        `when`(flickr.photosInterface).thenReturn(photosInterface)
        `when`(flickr.photosetsInterface).thenReturn(photosetsInterface)

        val fs = spy(FlickrFileSystem(provider, flickr, auth))

        val collection = mock(FlickrCollection::class.java)
        `when`(collection.title).thenReturn("C")
        `when`(collInterface.getTree(null, "user123")).thenReturn(listOf(collection))

        val photoset = mock(Photoset::class.java)
        `when`(photoset.title).thenReturn("A")
        `when`(photoset.id).thenReturn("album123")
        `when`(collection.photosets).thenReturn(listOf(photoset))

        val photo1 = mock(Photo::class.java)
        `when`(photo1.id).thenReturn("p1")
        `when`(photo1.getId()).thenReturn("p1")
        `when`(photo1.title).thenReturn("pic1")
        `when`(photo1.originalFormat).thenReturn("jpg")
        `when`(photo1.tags).thenReturn(emptyList())

        val photo2 = mock(Photo::class.java)
        `when`(photo2.id).thenReturn("p2")
        `when`(photo2.getId()).thenReturn("p2")
        `when`(photo2.title).thenReturn("pic2")
        `when`(photo2.originalFormat).thenReturn("jpg")
        `when`(photo2.tags).thenReturn(emptyList())

        doReturn(listOf(photo1, photo2).asSequence()).`when`(fs).getPhotos("album123")

        val photo1Path = FlickrPath(fs, "/C/A/pic1.jpg")
        val photo2Path = FlickrPath(fs, "/C/A/pic2.jpg")

        provider.readAttributes(photo1Path, PosixFileAttributes::class.java)
        verify(fs, times(1)).getPhotos("album123")

        provider.readAttributes(photo2Path, PosixFileAttributes::class.java)
        verify(fs, times(1)).getPhotos("album123")
    }

    @Test
    fun testCacheIsClearedOnDirectoryChange() {
        val provider = FlickrFileSystemProvider()
        val flickr = mock(Flickr::class.java)
        val auth = mock(Auth::class.java)
        val user = mock(User::class.java)

        `when`(user.id).thenReturn("user123")
        `when`(user.username).thenReturn("testuser")
        `when`(auth.user).thenReturn(user)

        val collInterface = mock(CollectionsInterface::class.java)
        val photosInterface = mock(PhotosInterface::class.java)
        val photosetsInterface = mock(PhotosetsInterface::class.java)

        `when`(flickr.collectionsInterface).thenReturn(collInterface)
        `when`(flickr.photosInterface).thenReturn(photosInterface)
        `when`(flickr.photosetsInterface).thenReturn(photosetsInterface)

        val fs = spy(FlickrFileSystem(provider, flickr, auth))

        val collection = mock(FlickrCollection::class.java)
        `when`(collection.title).thenReturn("C")
        `when`(collInterface.getTree(null, "user123")).thenReturn(listOf(collection))

        val photoset1 = mock(Photoset::class.java)
        `when`(photoset1.title).thenReturn("A1")
        `when`(photoset1.id).thenReturn("album1")
        
        val photoset2 = mock(Photoset::class.java)
        `when`(photoset2.title).thenReturn("A2")
        `when`(photoset2.id).thenReturn("album2")
        
        `when`(collection.photosets).thenReturn(listOf(photoset1, photoset2))

        val photo1 = mock(Photo::class.java)
        `when`(photo1.id).thenReturn("p1")
        `when`(photo1.getId()).thenReturn("p1")
        `when`(photo1.title).thenReturn("pic1")
        `when`(photo1.originalFormat).thenReturn("jpg")
        
        val photo2 = mock(Photo::class.java)
        `when`(photo2.id).thenReturn("p2")
        `when`(photo2.getId()).thenReturn("p2")
        `when`(photo2.title).thenReturn("pic2")
        `when`(photo2.originalFormat).thenReturn("jpg")

        doReturn(listOf(photo1).asSequence()).`when`(fs).getPhotos("album1")
        doReturn(listOf(photo2).asSequence()).`when`(fs).getPhotos("album2")

        val album1Path = FlickrPath(fs, "/C/A1")
        val album2Path = FlickrPath(fs, "/C/A2")

        // 1. List Album 1
        provider.newDirectoryStream(album1Path, null).toList()
        assertTrue(fs.attributesCache.containsKey("/C/A1/pic1.jpg"))

        // 2. List Album 2 -> This should trigger notifyAccess("album2") and clear Album 1's cache
        provider.newDirectoryStream(album2Path, null).toList()
        
        // Album 1's photo should be gone from cache
        assertFalse(fs.attributesCache.containsKey("/C/A1/pic1.jpg"))
        // Album 2's photo should be present
        assertTrue(fs.attributesCache.containsKey("/C/A2/pic2.jpg"))
    }
}
