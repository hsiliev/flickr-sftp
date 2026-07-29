package com.artware.flickr.sftp

import com.flickr4java.flickr.Flickr
import com.flickr4java.flickr.auth.Auth
import com.flickr4java.flickr.photos.Exif
import com.flickr4java.flickr.photos.Photo
import com.flickr4java.flickr.photos.PhotosInterface
import com.flickr4java.flickr.photos.Size
import com.flickr4java.flickr.photosets.PhotosetsInterface
import com.flickr4java.flickr.collections.CollectionsInterface
import com.flickr4java.flickr.collections.Collection as FlickrCollection
import com.flickr4java.flickr.photosets.Photoset
import org.apache.commons.imaging.Imaging
import org.apache.commons.imaging.formats.jpeg.JpegImageMetadata
import org.apache.commons.imaging.formats.tiff.constants.ExifTagConstants
import org.apache.commons.imaging.formats.tiff.constants.TiffTagConstants
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import java.io.ByteArrayOutputStream
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import java.nio.ByteBuffer
import java.nio.file.attribute.BasicFileAttributes

class FlickrMetadataTest {

    @Test
    fun testExifEmbedding() {
        val flickr = mock(Flickr::class.java)
        val photosInterface = mock(PhotosInterface::class.java)
        val auth = mock(Auth::class.java)
        val user = com.flickr4java.flickr.people.User()
        user.id = "user123"
        `when`(auth.user).thenReturn(user)
        `when`(flickr.photosInterface).thenReturn(photosInterface)

        val photoId = "p1"
        val secret = "s1"
        val photo = Photo().apply { 
            id = photoId
            setSecret(secret)
            title = "My Photo"
            description = "This is a description"
        }

        // Mock empty image
        val bufferedImage = BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB)
        val baos = ByteArrayOutputStream()
        ImageIO.write(bufferedImage, "jpg", baos)
        val imageBytes = baos.toByteArray()

        `when`(photosInterface.getImageAsStream(eq(photo), eq(Size.ORIGINAL))).thenReturn(imageBytes.inputStream())
        
        val exif1 = Exif().apply { tag = "Make"; label = "Make"; raw = "Canon" }
        val exif2 = Exif().apply { tag = "Model"; label = "Model"; raw = "EOS" }
        val exifs = listOf(exif1, exif2)
        `when`(photosInterface.getExif(eq(photoId), eq(secret))).thenReturn(exifs)

        val provider = FlickrFileSystemProvider()
        val fs = FlickrFileSystem(provider, flickr, auth)

        // Mock structure for findPhoto
        val collectionsInterface = mock(CollectionsInterface::class.java)
        `when`(flickr.collectionsInterface).thenReturn(collectionsInterface)
        val album = Photoset().apply { id = "a1"; title = "Album 1" }
        val collection = FlickrCollection().apply { title = "Coll 1"; photosets = listOf(album) }
        `when`(collectionsInterface.getTree(null, "user123")).thenReturn(listOf(collection))
        val photosetsInterface = mock(PhotosetsInterface::class.java)
        `when`(flickr.photosetsInterface).thenReturn(photosetsInterface)
        val photoList = com.flickr4java.flickr.photos.PhotoList<Photo>().apply { add(photo) }
        `when`(photosetsInterface.getPhotos(eq("a1"), anySet(), anyInt(), anyInt(), anyInt())).thenReturn(photoList)

        val path = FlickrPath(fs, "/Coll 1/Album 1/My Photo.jpg")
        
        // Before download, size should be 0 (according to my change)
        val attrsBefore = provider.readAttributes(path, BasicFileAttributes::class.java)
        assertEquals(0L, attrsBefore.size())

        val channel = provider.newByteChannel(path, mutableSetOf())
        
        val size = channel.size()
        assertTrue(size > 0)

        // After download started (and size populated), it should have a size
        val attrsAfter = provider.readAttributes(path, BasicFileAttributes::class.java)
        assertEquals(size, attrsAfter.size())
        
        val buffer = ByteBuffer.allocate(size.toInt())
        channel.read(buffer)
        val resultBytes = buffer.array()

        // Verify metadata using Imaging
        val metadata = Imaging.getMetadata(resultBytes) as JpegImageMetadata
        val exif = metadata.exif
        
        val makeField = exif.findField(TiffTagConstants.TIFF_TAG_MAKE)
        assertNotNull(makeField)
        assertEquals("Canon", makeField!!.stringValue)
        
        val modelField = exif.findField(TiffTagConstants.TIFF_TAG_MODEL)
        assertNotNull(modelField)
        assertEquals("EOS", modelField!!.stringValue)

        val descField = exif.findField(TiffTagConstants.TIFF_TAG_IMAGE_DESCRIPTION)
        assertNotNull(descField)
        assertEquals("This is a description", descField!!.stringValue)

        val userComment = exif.findField(ExifTagConstants.EXIF_TAG_USER_COMMENT).stringValue
        assertTrue(userComment.contains("Make: Canon"))
        assertTrue(userComment.contains("Model: EOS"))
    }
}
