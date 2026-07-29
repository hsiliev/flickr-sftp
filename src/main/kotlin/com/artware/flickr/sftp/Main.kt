package com.artware.flickr.sftp

import org.apache.sshd.common.AttributeRepository
import org.apache.sshd.common.file.FileSystemFactory
import org.apache.sshd.common.session.SessionContext
import org.apache.sshd.scp.server.ScpCommandFactory
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.UserAuthNoneFactory
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.password.UserAuthPasswordFactory
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import java.io.File
import java.nio.file.FileSystem
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*

fun main() {
    val props = Properties()
    val configFile = File("config.properties")
    if (configFile.exists()) {
        configFile.inputStream().use { props.load(it) }
    }

    // Set SLF4J properties from config.properties before any logger is initialized
    props.stringPropertyNames().forEach { name ->
        if (name.startsWith("org.slf4j.simpleLogger.")) {
            System.setProperty(name, props.getProperty(name))
        }
    }

    val apiKey = props.getProperty("flickr.apiKey")
    val sharedSecret = props.getProperty("flickr.sharedSecret")

    if (apiKey.isNullOrBlank() || sharedSecret.isNullOrBlank()) {
        println("Please provide flickr.apiKey and flickr.sharedSecret in config.properties")
        return
    }

    val flickrService = FlickrService(apiKey, sharedSecret)
    val auth = flickrService.authenticate()

    val provider = FlickrFileSystemProvider()

    val sshd = SshServer.setUpDefaultServer()
    sshd.host = "127.0.0.1"
    sshd.port = 2222
    sshd.keyPairProvider = SimpleGeneratorHostKeyProvider(File("hostkey.ser").toPath())
    
    sshd.subsystemFactories = listOf(SftpSubsystemFactory())
    sshd.commandFactory = ScpCommandFactory()
    
    val fileSystemKey = AttributeRepository.AttributeKey<FileSystem>()
    
    // Use a factory that creates a new FileSystem per session to support per-session caching
    sshd.fileSystemFactory = object : FileSystemFactory {
        override fun createFileSystem(session: SessionContext): FileSystem {
            var fs = session.getAttribute(fileSystemKey)
            if (fs == null) {
                fs = FlickrFileSystem(provider, flickrService.getFlickr(), auth)
                session.setAttribute(fileSystemKey, fs)
            }
            return fs!!
        }

        override fun getUserHomeDir(session: SessionContext): Path {
            return createFileSystem(session).getPath("/")
        }
    }
    
    // Allow any username/password
    sshd.passwordAuthenticator = PasswordAuthenticator { username, password, session -> true }
    
    // Enable 'none' and 'password' authentication
    sshd.userAuthFactories = listOf(
        UserAuthNoneFactory.INSTANCE,
        UserAuthPasswordFactory.INSTANCE
    )
    
    // Allow 'none' authentication property
    sshd.properties["allow-none-auth"] = true

    sshd.start()
    println("SSH Server started on port 2222.")
    println("Connect with 'sftp -P 2222 localhost'")
    
    Thread.sleep(Long.MAX_VALUE)
}
