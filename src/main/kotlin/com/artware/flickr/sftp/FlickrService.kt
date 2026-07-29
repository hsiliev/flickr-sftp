package com.artware.flickr.sftp

import com.flickr4java.flickr.Flickr
import com.flickr4java.flickr.REST
import com.flickr4java.flickr.auth.Auth
import com.flickr4java.flickr.auth.Permission
import com.sun.net.httpserver.HttpServer
import java.awt.Desktop
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CountDownLatch

class FlickrService(private val apiKey: String, private val sharedSecret: String) {
    private val flickr = Flickr(apiKey, sharedSecret, REST())

    fun authenticate(): Auth {
        val authInterface = flickr.authInterface

        val latch = CountDownLatch(1)
        var verifier: String? = null
        
        val server = HttpServer.create(InetSocketAddress(0), 0)
        val actualPort = server.address.port
        val callbackUrl = "http://localhost:$actualPort/callback"
        server.createContext("/callback") { exchange ->
            val query = exchange.requestURI.query
            verifier = query?.split("&")?.find { it.startsWith("oauth_verifier=") }?.substringAfter("=")

            val response = if (verifier != null) {
                "Authentication successful! You can close this window and return to the terminal."
            } else {
                "Authentication failed or cancelled."
            }

            exchange.sendResponseHeaders(200, response.length.toLong())
            exchange.responseBody.use { it.write(response.toByteArray()) }
            latch.countDown()
        }

        server.start()

        try {
            val requestToken = authInterface.getRequestToken(callbackUrl)
            val url = authInterface.getAuthorizationUrl(requestToken, Permission.READ)

            println("Follow this URL to authorize yourself on Flickr (it should open in your browser automatically):")
            println(url)

            try {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    Desktop.getDesktop().browse(URI(url))
                }
            } catch (e: Exception) {
                // Fallback if browser can't be opened
            }

            println("Waiting for authorization via callback on $callbackUrl ...")
            latch.await()

            if (verifier == null) {
                throw IllegalStateException("Failed to obtain verifier code from callback.")
            }

            val accessToken = authInterface.getAccessToken(requestToken, verifier)
            val auth = authInterface.checkToken(accessToken)
            println("Authentication successful for user: ${auth.user.username}")

            return auth
        } finally {
            server.stop(0)
        }
    }

    fun getFlickr(): Flickr = flickr
}
