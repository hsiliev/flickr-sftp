package com.artware.flickr.sftp

import com.flickr4java.flickr.Flickr
import com.flickr4java.flickr.auth.Auth
import com.flickr4java.flickr.photos.Photo
import com.flickr4java.flickr.photos.PhotoList
import com.flickr4java.flickr.photosets.PhotosetsInterface
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.mockito.ArgumentMatchers.anySet
import java.util.concurrent.TimeUnit

class FlickrFileSystemTest {

    @Test
    fun testPhotoCaching() {
        val provider = mock(FlickrFileSystemProvider::class.java)
        val flickr = mock(Flickr::class.java)
        val auth = mock(Auth::class.java)
        val photosetsInterface = mock(PhotosetsInterface::class.java)

        `when`(flickr.photosetsInterface).thenReturn(photosetsInterface)

        val albumId = "album123"
        val photo1 = Photo().apply { id = "p1"; title = "Photo 1" }
        val photoList = PhotoList<Photo>()
        photoList.add(photo1)

        `when`(photosetsInterface.getPhotos(eq(albumId), anySet(), anyInt(), anyInt(), anyInt())).thenReturn(photoList)

        val fs = FlickrFileSystem(provider, flickr, auth)

        // First access - should fetch from API
        val photos1 = fs.getPhotos(albumId).toList()
        assertEquals(1, photos1.size)
        assertEquals("p1", photos1[0].id)

        // Second access - should hit cache
        val photos2 = fs.getPhotos(albumId).toList()
        assertEquals(photos1.size, photos2.size)
        assertEquals(photos1[0].id, photos2[0].id)
        verify(photosetsInterface, times(1)).getPhotos(eq(albumId), anySet(), anyInt(), anyInt(), anyInt())

        // Access different album - should clear previous cache and fetch new
        val albumId2 = "album456"
        val photo2 = Photo().apply { id = "p2"; title = "Photo 2" }
        val photoList2 = PhotoList<Photo>()
        photoList2.add(photo2)
        `when`(photosetsInterface.getPhotos(eq(albumId2), anySet(), anyInt(), anyInt(), anyInt())).thenReturn(photoList2)

        val photos3 = fs.getPhotos(albumId2).toList()
        assertEquals(1, photos3.size)
        assertEquals("p2", photos3[0].id)

        // Access original album again - should fetch again (cache was cleared)
        val photos4 = fs.getPhotos(albumId).toList()
        verify(photosetsInterface, times(2)).getPhotos(eq(albumId), anySet(), anyInt(), anyInt(), anyInt())
    }

    @Test
    fun testNotifyAccess() {
        val provider = mock(FlickrFileSystemProvider::class.java)
        val flickr = mock(Flickr::class.java)
        val auth = mock(Auth::class.java)
        val photosetsInterface = mock(PhotosetsInterface::class.java)

        `when`(flickr.photosetsInterface).thenReturn(photosetsInterface)

        val albumId = "album123"
        val photoList = PhotoList<Photo>().apply { add(Photo()) }
        `when`(photosetsInterface.getPhotos(eq(albumId), anySet(), anyInt(), anyInt(), anyInt())).thenReturn(photoList)

        val fs = FlickrFileSystem(provider, flickr, auth)
        
        fs.getPhotos(albumId).toList()
        verify(photosetsInterface, times(1)).getPhotos(eq(albumId), anySet(), anyInt(), anyInt(), anyInt())

        // Notify access to same album - cache should be kept
        fs.notifyAccess(albumId)
        fs.getPhotos(albumId).toList()
        verify(photosetsInterface, times(1)).getPhotos(eq(albumId), anySet(), anyInt(), anyInt(), anyInt())

        // Notify access to null (root) - cache should be cleared
        fs.notifyAccess(null)
        fs.getPhotos(albumId).toList()
        verify(photosetsInterface, times(2)).getPhotos(eq(albumId), anySet(), anyInt(), anyInt(), anyInt())
    }
}
