// Copyright (c) 2025 Daniel Monedero-Tortola
package com.example.feeds

import fi.iki.elonen.NanoHTTPD
import java.io.IOException

// Custom server class that extends NanoHTTPD
private class AlwaysOkServer(
    port: Int,
    private val responseString: String,
    private val mimeType: String = MIME_PLAINTEXT // Default to plain text
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response? {
        // You can log request details if needed.
        // For Android, use: Log.d("SimpleServer", "Request: ${session.method} ${session.uri}")
        // System.out.println("Request: ${session.method} ${session.uri}");

        // Always return HTTP 200 OK with the specified string and MIME type
        return newFixedLengthResponse(Response.Status.OK, mimeType, responseString)
    }
}

/**
 * Starts a simple local HTTP server that always returns HTTP 200 OK
 * with the provided response string.
 *
 * NOTE: For Android, ensure you have the INTERNET permission in your AndroidManifest.xml:
 * <uses-permission android:name="android.permission.INTERNET" />
 *
 * And add the NanoHTTPD dependency to your build.gradle:
 * implementation("org.nanohttpd:nanohttpd:2.3.1") // Or latest version
 *
 * @param port The port number for the server to listen on (e.g., 8080).
 * @param responseString The string to return in the HTTP response body.
 * @param mimeType The MIME type of the response string (e.g., NanoHTTPD.MIME_PLAINTEXT, NanoHTTPD.MIME_HTML).
 *                 Defaults to "text/plain".
 * @return The NanoHTTPD server instance if started successfully, null otherwise.
 *         Call server.stop() on the returned instance when you want to shut down the server.
 */
fun startSimpleHttpServer(
    port: Int = 8080, // Default port
    responseString: String = "Hello from local server!", // Default response
    mimeType: String = NanoHTTPD.MIME_HTML // Default MIME type
): NanoHTTPD? {
    return try {
        val server = AlwaysOkServer(port, responseString, mimeType)
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) // false means it's not a daemon thread
        println("Local HTTP server started on http://localhost:$port/")
        println("Serving content: \"$responseString\" with MIME type: $mimeType")
        // For Android, use Log.i("HttpServer", "Local HTTP server started on port $port")
        server
    } catch (e: IOException) {
        System.err.println("Could not start server on port $port: ${e.message}")
        // For Android, use Log.e("HttpServer", "Could not start server on port $port", e)
        null
    } catch (e: Exception) {
        System.err.println("An unexpected error occurred while starting the server: ${e.message}")
        // For Android, use Log.e("HttpServer", "Unexpected error starting server", e)
        null
    }
}