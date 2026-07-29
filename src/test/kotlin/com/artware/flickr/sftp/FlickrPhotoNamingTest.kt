package com.artware.flickr.sftp

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import com.flickr4java.flickr.photos.Photo
import com.flickr4java.flickr.Flickr
import com.flickr4java.flickr.auth.Auth
import com.flickr4java.flickr.photosets.Photoset
import java.nio.file.NoSuchFileException

class FlickrPhotoNamingTest {

    @Test
    fun testGetPhotoNamesNoCollisions() {
        val provider = FlickrFileSystemProvider()
        
        val p1 = mock(Photo::class.java)
        `when`(p1.id).thenReturn("1")
        `when`(p1.title).thenReturn("Beach")
        `when`(p1.originalFormat).thenReturn("jpg")
        
        val p2 = mock(Photo::class.java)
        `when`(p2.id).thenReturn("2")
        `when`(p2.title).thenReturn("Mountain")
        `when`(p2.originalFormat).thenReturn("png")
        
        val photos = listOf(p1, p2)
        
        val names = provider.getPhotoNames(photos)
        
        assertEquals(2, names.size)
        assertEquals(p1, names["Beach.jpg"])
        assertEquals(p2, names["Mountain.png"])
    }

    @Test
    fun testGetPhotoNamesWithCollisions() {
        val provider = FlickrFileSystemProvider()
        
        val p1 = mock(Photo::class.java)
        `when`(p1.id).thenReturn("1")
        `when`(p1.title).thenReturn("Beach")
        `when`(p1.originalFormat).thenReturn("jpg")
        
        val p2 = mock(Photo::class.java)
        `when`(p2.id).thenReturn("2")
        `when`(p2.title).thenReturn("Beach")
        `when`(p2.originalFormat).thenReturn("jpg")
        
        val p3 = mock(Photo::class.java)
        `when`(p3.id).thenReturn("3")
        `when`(p3.title).thenReturn("Beach")
        `when`(p3.originalFormat).thenReturn("png")
        
        val photos = listOf(p1, p2, p3)
        
        val names = provider.getPhotoNames(photos)
        
        assertEquals(3, names.size)
        assertEquals(p1, names["Beach (1).jpg"])
        assertEquals(p2, names["Beach (2).jpg"])
        assertEquals(p3, names["Beach.png"]) // No collision for png extension
    }

    @Test
    fun testGetPhotoNamesWithExistingExtension() {
        val provider = FlickrFileSystemProvider()
        
        val p1 = mock(Photo::class.java)
        `when`(p1.id).thenReturn("1")
        `when`(p1.title).thenReturn("Beach.jpg")
        `when`(p1.originalFormat).thenReturn("jpg")
        
        val photos = listOf(p1)
        
        val names = provider.getPhotoNames(photos)
        
        assertEquals(1, names.size)
        assertEquals(p1, names["Beach.jpg"])
    }
    
    @Test
    fun testGetPhotoNameFallbackToId() {
        val provider = FlickrFileSystemProvider()
        
        val p1 = mock(Photo::class.java)
        `when`(p1.id).thenReturn("12345")
        `when`(p1.title).thenReturn("")
        `when`(p1.originalFormat).thenReturn(null)
        `when`(p1.tags).thenReturn(null)
        
        val photos = listOf(p1)
        
        val names = provider.getPhotoNames(photos)
        
        assertEquals(1, names.size)
        assertEquals(p1, names["12345.jpg"])
    }

    @Test
    fun testGetPhotoNameFromMachineTag() {
        val provider = FlickrFileSystemProvider()

        val p1 = mock(Photo::class.java)
        val tag = mock(com.flickr4java.flickr.tags.Tag::class.java)
        `when`(tag.raw).thenReturn("file:name=RealName.png")

        `when`(p1.id).thenReturn("12345")
        `when`(p1.title).thenReturn("Wrong Title")
        `when`(p1.originalFormat).thenReturn("png")
        `when`(p1.tags).thenReturn(listOf(tag))

        val photos = listOf(p1)

        val names = provider.getPhotoNames(photos)

        assertEquals(1, names.size)
        assertEquals(p1, names["RealName.png"])
    }

    @Test
    fun testFetchTagsUpfront() {
        val provider = FlickrFileSystemProvider()
        val fs = mock(FlickrFileSystem::class.java)
        val flickr = mock(Flickr::class.java)
        val photosInterface = mock(com.flickr4java.flickr.photos.PhotosInterface::class.java)
        
        `when`(fs.flickr).thenReturn(flickr)
        `when`(flickr.photosInterface).thenReturn(photosInterface)
        
        val p1 = Photo().apply { id = "45132933675"; secret = "s1"; title = "45132933675" }
        // p1 has no tags
        
        val p2 = Photo().apply { id = "22222222222"; secret = "s2"; title = "22222222222" }
        val tag = com.flickr4java.flickr.tags.Tag().apply { raw = "file:name=AlreadyHas.jpg" }
        p2.tags = listOf(tag)
        
        val info1 = Photo().apply { 
            tags = listOf(com.flickr4java.flickr.tags.Tag().apply { raw = "file:name=FetchedUpfront.jpg" }) 
        }
        `when`(photosInterface.getInfo("45132933675", "s1")).thenReturn(info1)
        
        provider.fetchTagsUpfront(fs, listOf(p1, p2))
        
        verify(photosInterface, times(1)).getInfo("45132933675", "s1")
        verify(photosInterface, never()).getInfo(eq("22222222222"), anyString())
        
        assertEquals("file:name=FetchedUpfront.jpg", p1.tags.first().raw)
        assertEquals("file:name=AlreadyHas.jpg", p2.tags.first().raw)
    }
}
