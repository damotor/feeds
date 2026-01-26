// Copyright (c) 2025 Daniel Monedero-Tortola
package com.example.feeds

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

// Data class to represent a parsed feed item (optional, but good for structure)
// Modify this based on what each line in your feeds.csv represents.
data class FeedItem(
    val language: String,
    val title: String,
    val url: String
)

data class PostItem(
    val title: String,
    val link: String,
    var language: String,
    val publishedEpochSeconds: Long?
)

/**
* Runs a suspending block and helps retrieve its result synchronously in a blocking manner
* using a CountDownLatch.
*
* THIS IS A BLOCKING CALL and should not be used on the main thread in UI applications
* if the block takes significant time.
*
* @param block The suspending block to execute. It should return the value (e.g., logs string)
*              that needs to be passed back.
* @param timeoutMillis The maximum time to wait for the block to complete.
* @return The result from the block, or null if it times out or an error occurs.
*/
fun <T> runSuspendingBlockAndWaitForResult(
    block: suspend () -> T,
    timeoutMillis: Long = 10000 // Default 10 seconds timeout
): T? {
    val latch = CountDownLatch(1)
    val resultRef = AtomicReference<Result<T>?>(null) // To store the result or exception

    block.startCoroutine(object : Continuation<T> {
        override val context = EmptyCoroutineContext
        override fun resumeWith(result: Result<T>) {
            resultRef.set(result)
            latch.countDown() // Signal completion
        }
    })

    return try {
        if (latch.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
            resultRef.get()?.getOrThrow() // If successful, return value, otherwise throw exception
        } else {
            println("Warning: Timed out waiting for suspending block to complete.")
            null // Timeout
        }
    } catch (e: Exception) {
        println("Error during suspending block execution: ${e.message}")
        e.printStackTrace()
        null
    }
}

@RequiresApi(Build.VERSION_CODES.Q)
fun generateFeeds(context: Context): String {
    var logs = "Processing started..."
    val feedItems = loadFeedsFile(context)

    if (feedItems == null) {
        logs = "Error: Failed to load or parse feeds.csv.\n$logs"
        return logs
    }

    if (feedItems.isEmpty()) {
        logs = "No feed items found in feeds.csv or all lines were empty/malformed.\n$logs"
        return logs
    }

    logs = "Loaded ${feedItems.size} feed urls. Starting processing...\n$logs"

    // Now, run the async part and wait for its logs
    val logsFromAsyncBlock: String? = runSuspendingBlockAndWaitForResult(
        block = {
            var asyncLogs = ""
            // --- Start of the code previously in runSuspendingWithResultCallback's block ---
            val (allPostsItems, fetchAndParseLogs) = fetchAndParseFeedsConcurrently(feedItems)
            asyncLogs = fetchAndParseLogs + asyncLogs
            val sortedPostsItems = allPostsItems.sortedByDescending { it.publishedEpochSeconds }

            var postsHtml = """<!DOCTYPE html>
<html lang="en">
    <head>
        <title>Feed Posts</title>
        <meta charset="UTF-8">
        <style>
            a {
                color: #ffffff;
            }
            a:visited {
              color: #cccccc;
            }
            button { 
                height: 100px;
                width: 49%;
                font-size: 40px;
                background-color: #000000;
                color: #ffffff;
            }
            button.disabled {
                border: 1px solid #999999;
                background-color: #000000;
                color: #666666;
            }
            body {
                background-color: #000000;
                margin: 0% 2% 0% 2%;
            }
        </style>
        <script>
            function toggleDisplay(selector) {
                // change button class
                var button = document.getElementById(selector);
                if (button !== null) {
                    if (button.className === 'disabled') {
                        button.className = '';
                    } else {
                        button.className = 'disabled';
                    }
                }
                // toggle display property
                var elements = document.getElementsByClassName(selector);
                Array.from(elements).forEach((el) => {
                    el.style.display = (el.style.display === 'none') ? 'block' : 'none';
                });
            }
            document.addEventListener('DOMContentLoaded', function() {
                // if buttons haven't been toggled yet
                if (document.getElementById('en').className != 'disabled' && document.getElementById('es').className != 'disabled') {
                    const d = new Date();
                    // Friday and Saturday: display Spanish and English by default, otherwise display only English
                    if (d.getDay() == 5 || d.getDay() == 6) {
                        // on Friday show only Spanish and Catalan initially
                        toggleDisplay('en');
                    } else {
                        // otherwise show only English
                        toggleDisplay('es');
                        toggleDisplay('ca');
                    }
                }
            });
            function openInNewBackgroundTab(newUrl) {
                const scrollY = Math.floor(window.scrollY);
                const htmlContent = '<!DOCTYPE html><html><head><meta charset="UTF-8">' +
                    document.head.innerHTML +
                    '<script>document.addEventListener("DOMContentLoaded", function() { window.scrollTo(0, ' + scrollY + '); });<' + '/script>' +
                    '</head><body>' +
                    document.body.innerHTML +
                    '</body></html>';
                const blob = new Blob([htmlContent], { type: 'text/html' });
                const url = URL.createObjectURL(blob);
                
                window.open(url);
                URL.revokeObjectURL(url);
                
                setTimeout(() => {
                    // Try to scroll to top in multiple ways
                    window.scrollTo(0, 0);
                    document.body.scrollTop = 0; // For Safari
                    document.documentElement.scrollTop = 0; // For Chrome, Firefox, IE and Opera

                    window.location.href = newUrl;
                }, 250);
            }
        </script>
    </head>
    <body>
        <button id="en" onclick="toggleDisplay('en')">EN</button>
        <button id="es" onclick="toggleDisplay('es');toggleDisplay('ca')">ES+CA</button>"""
            if (sortedPostsItems.isNotEmpty()) {
                val asciiRegex = Regex("[^\\x00-\\xFF]")
                sortedPostsItems.forEach { item ->
                    item.publishedEpochSeconds?.let { _ ->
                        if (!item.link.startsWith("https://www.youtube.com/shorts/")) {
                            val asciiOnlyTitle = asciiRegex.replace(item.title, "")
                            postsHtml += "<p class='${item.language}'><a  href='#' onclick='openInNewBackgroundTab(\"${item.link}\");return false;'>${asciiOnlyTitle.lowercase()}</a></p>"
                            return@forEach
                        }
                    }
                }
                postsHtml += "</body></html>"

                val serverInstance =
                    startSimpleHttpServer(8080, postsHtml) // Your existing function
                asyncLogs = if (serverInstance == null) {
                    "Error: starting server failed\n$asyncLogs"
                } else {
                    "Server started on port ${serverInstance.listeningPort}. Access at http://localhost:8080/\n$asyncLogs"
                }
            } else {
                asyncLogs = "No posts found.\n$asyncLogs"
            }

            // --- End of the code previously in runSuspendingWithResultCallback's block ---
            asyncLogs // This is the string returned by the block
        },
        timeoutMillis = 30000 // Wait up to 30 seconds for the async operations
    )

    if (logsFromAsyncBlock != null) {
        return logsFromAsyncBlock + logs // Combine and return
    } else {
        val errorMsg = "Error: Failed to get logs from asynchronous block (timed out or error).\n"
        return errorMsg + logs
    }
}