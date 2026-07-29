package com.artware.flickr.sftp

import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.*
import java.nio.file.WatchEvent.Kind
import java.nio.file.WatchEvent.Modifier

class FlickrPath(private val fileSystem: FlickrFileSystem, private val path: String) : Path {
    
    val normalizedPath: String
    private val isAbsolute: Boolean

    init {
        var p = try {
            URLDecoder.decode(path.replace('\\', '/'), StandardCharsets.UTF_8.name())
        } catch (e: Exception) {
            path.replace('\\', '/')
        }
        isAbsolute = p.startsWith("/")
        
        val parts = p.split("/").filter { it.isNotEmpty() && it != "." }
        val result = mutableListOf<String>()
        for (part in parts) {
            if (part == "..") {
                if (result.isNotEmpty()) {
                    if (result.last() == "..") {
                        result.add("..")
                    } else {
                        result.removeAt(result.size - 1)
                    }
                } else if (!isAbsolute) {
                    result.add("..")
                }
            } else {
                result.add(part)
            }
        }
        normalizedPath = (if (isAbsolute) "/" else "") + result.joinToString("/")
    }

    private fun split(): List<String> = normalizedPath.split("/").filter { it.isNotEmpty() }

    override fun getFileSystem(): FileSystem = fileSystem

    override fun isAbsolute(): Boolean = isAbsolute

    override fun getRoot(): Path? = if (isAbsolute) FlickrPath(fileSystem, "/") else null

    override fun getFileName(): Path? {
        val parts = split()
        return if (parts.isEmpty()) {
            if (isAbsolute && normalizedPath == "/") null
            else if (!isAbsolute && normalizedPath == "") null
            else if (!isAbsolute) FlickrPath(fileSystem, normalizedPath)
            else null
        } else {
            FlickrPath(fileSystem, parts.last())
        }
    }

    override fun getParent(): Path? {
        if (normalizedPath == "/" || normalizedPath == "") return null
        val lastSlash = normalizedPath.lastIndexOf('/')
        return if (lastSlash == -1) null
        else if (lastSlash == 0) FlickrPath(fileSystem, "/")
        else FlickrPath(fileSystem, normalizedPath.substring(0, lastSlash))
    }

    override fun getNameCount(): Int = split().size

    override fun getName(index: Int): Path {
        val parts = split()
        if (index < 0 || index >= parts.size) throw IllegalArgumentException()
        return FlickrPath(fileSystem, parts[index])
    }

    override fun subpath(beginIndex: Int, endIndex: Int): Path {
        val parts = split()
        if (beginIndex < 0 || endIndex > parts.size || beginIndex >= endIndex) throw IllegalArgumentException()
        return FlickrPath(fileSystem, parts.subList(beginIndex, endIndex).joinToString("/"))
    }

    override fun startsWith(other: Path): Boolean {
        if (other.fileSystem != fileSystem) return false
        val otherPath = other as FlickrPath
        if (this.isAbsolute != otherPath.isAbsolute) return false
        val thisParts = this.split()
        val otherParts = otherPath.split()
        if (thisParts.size < otherParts.size) return false
        return thisParts.subList(0, otherParts.size) == otherParts
    }

    override fun startsWith(other: String): Boolean = startsWith(fileSystem.getPath(other))

    override fun endsWith(other: Path): Boolean {
        if (other.fileSystem != fileSystem) return false
        val otherPath = other as FlickrPath
        if (otherPath.isAbsolute && !this.isAbsolute) return false
        val thisParts = this.split()
        val otherParts = otherPath.split()
        if (thisParts.size < otherParts.size) return false
        return thisParts.subList(thisParts.size - otherParts.size, thisParts.size) == otherParts
    }

    override fun endsWith(other: String): Boolean = endsWith(fileSystem.getPath(other))

    override fun normalize(): Path = this

    override fun resolve(other: Path): Path {
        if (other.isAbsolute) return other
        val otherPath = other.toString()
        if (otherPath.isEmpty()) return this
        val separator = if (normalizedPath.endsWith("/") || normalizedPath.isEmpty()) "" else "/"
        return FlickrPath(fileSystem, "$normalizedPath$separator$otherPath")
    }

    override fun resolve(other: String): Path = resolve(fileSystem.getPath(other))

    override fun resolveSibling(other: Path): Path = parent?.resolve(other) ?: other

    override fun resolveSibling(other: String): Path = parent?.resolve(other) ?: fileSystem.getPath(other)

    override fun relativize(other: Path): Path {
        if (other !is FlickrPath) throw IllegalArgumentException("Not a FlickrPath")
        if (this.isAbsolute != other.isAbsolute) throw IllegalArgumentException("Mixing absolute and relative paths")
        
        val thisParts = this.split()
        val otherParts = other.split()
        
        var commonCount = 0
        while (commonCount < thisParts.size && commonCount < otherParts.size && thisParts[commonCount] == otherParts[commonCount]) {
            commonCount++
        }
        
        val result = mutableListOf<String>()
        for (i in commonCount until thisParts.size) {
            result.add("..")
        }
        for (i in commonCount until otherParts.size) {
            result.add(otherParts[i])
        }
        
        return FlickrPath(fileSystem, result.joinToString("/"))
    }

    override fun toUri(): URI = URI("flickr", null, if (isAbsolute) normalizedPath else "/$normalizedPath", null)

    override fun toAbsolutePath(): Path = if (isAbsolute) this else FlickrPath(fileSystem, "/$normalizedPath")

    override fun toRealPath(vararg options: LinkOption): Path = toAbsolutePath()

    override fun toFile(): File {
        throw UnsupportedOperationException()
    }

    override fun register(watcher: WatchService, events: Array<out Kind<*>>, vararg modifiers: Modifier?): WatchKey {
        throw UnsupportedOperationException()
    }

    override fun register(watcher: WatchService, vararg events: Kind<*>): WatchKey {
        throw UnsupportedOperationException()
    }

    override fun iterator(): MutableIterator<Path> {
        return split().map { FlickrPath(fileSystem, it) as Path }.toMutableList().iterator()
    }

    override fun compareTo(other: Path): Int = toString().compareTo(other.toString())

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FlickrPath) return false
        return normalizedPath == other.normalizedPath && fileSystem == other.fileSystem
    }

    override fun hashCode(): Int {
        var result = fileSystem.hashCode()
        result = 31 * result + normalizedPath.hashCode()
        return result
    }

    override fun toString(): String = normalizedPath
    
    fun getParts() = split()
}
