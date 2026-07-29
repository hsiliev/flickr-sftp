package com.artware.flickr.sftp

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock

class FlickrPathTest {
    private val fs = mock(FlickrFileSystem::class.java)

    @Test
    fun testNormalization() {
        val path1 = FlickrPath(fs, "/")
        assertEquals("/", path1.normalizedPath)
        
        val path2 = FlickrPath(fs, "/.")
        assertEquals("/", path2.normalizedPath)

        val path3 = FlickrPath(fs, "/set/../set/./album")
        assertEquals("/set/album", path3.normalizedPath)
    }

    @Test
    fun testParts() {
        val path = FlickrPath(fs, "/set/album")
        assertEquals(listOf("set", "album"), path.getParts())
        
        val pathDot = FlickrPath(fs, "/.")
        assertEquals(emptyList<String>(), pathDot.getParts())
    }

    @Test
    fun testRelative() {
        val path = FlickrPath(fs, "set/album")
        assertFalse(path.isAbsolute)
        assertEquals("set/album", path.toString())
        assertEquals(2, path.nameCount)
    }

    @Test
    fun testUrlEncoded() {
        val path = FlickrPath(fs, "/Austria/2014%20-%20Wien/46044811631.jpg")
        assertEquals(listOf("Austria", "2014 - Wien", "46044811631.jpg"), path.getParts())
    }

    @Test
    fun testRelativize() {
        val path1 = FlickrPath(fs, "/Coll/Album/Photo.jpg")
        val path2 = FlickrPath(fs, "/Coll/Album")
        
        val rel = path2.relativize(path1)
        assertEquals("Photo.jpg", rel.toString())
        
        val path3 = FlickrPath(fs, "/OtherColl")
        val rel2 = path1.relativize(path3)
        assertEquals("../../../OtherColl", rel2.toString())
    }

    @Test
    fun testNormalizationWithDots() {
        val path = FlickrPath(fs, "../../OtherColl")
        assertEquals("../../OtherColl", path.toString())
    }
}
