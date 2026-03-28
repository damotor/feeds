// Copyright (c) 2025 Daniel Monedero-Tortola
package com.example.feeds

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
              color: #666666;
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
            h2 {
                color: #888888;
                font-size: 30px;
                margin-top: 40px;
                border-bottom: 1px solid #333333;
            }
            .util-link {
                font-size: 0.7em;
                margin-left: 10px;
                color: #888888;
                text-decoration: none;
            }
        </style>
        <script>
            function selectLang(lang) {
                var enButton = document.getElementById('en');
                var esButton = document.getElementById('es');
                var enElements = document.getElementsByClassName('en');
                var esElements = document.getElementsByClassName('es');
                var caElements = document.getElementsByClassName('ca');

                if (lang === 'en') {
                    esButton.className = '';
                    enButton.className = 'disabled';
                    Array.from(enElements).forEach(el => el.style.display = 'block');
                    Array.from(esElements).forEach(el => el.style.display = 'none');
                    Array.from(caElements).forEach(el => el.style.display = 'none');
                } else { // es
                    esButton.className = 'disabled';
                    enButton.className = '';
                    Array.from(enElements).forEach(el => el.style.display = 'none');
                    Array.from(esElements).forEach(el => el.style.display = 'block');
                    Array.from(caElements).forEach(el => el.style.display = 'block');
                }
            }

            document.addEventListener('DOMContentLoaded', function() {
                if (window.location.protocol === 'blob:') return;
                const d = new Date();
                // Friday and Saturday: display Spanish and Catalan
                if (d.getDay() == 5 || d.getDay() == 6) {
                    selectLang('es');
                } else {
                    // otherwise show only English
                    selectLang('en');
                }
            });

            function scrollTopAndOpen(newUrl, blobUrl) {
                function performScrollAndNavigate(maxRetries) {
                    window.scrollTo(0, 0);
                    setTimeout(function() {
                        const currentScroll = window.scrollY;
                        if (maxRetries > 0 || currentScroll > 0) {
                            performScrollAndNavigate(maxRetries-1);
                        } else {
                            setTimeout(function() {
                                window.open(blobUrl);
                                URL.revokeObjectURL(blobUrl);
                                window.location.href = newUrl;
                            }, 500);
                        }
                    }, 10);
                }
                performScrollAndNavigate(100);
            }

            function openInNewBackgroundTab(newUrl) {
                const scrollY = Math.floor(window.pageYOffset || window.scrollY || document.documentElement.scrollTop || 0);
                const htmlContent = '<!DOCTYPE html><html><head><meta charset="UTF-8">' +
                    document.head.innerHTML +
                    '<script>document.addEventListener("DOMContentLoaded", function() { window.scrollTo(0, ' + scrollY + '); });<' + '/script>' +
                    '</head><body>' +
                    document.body.innerHTML +
                    '</body></html>';
                const blob = new Blob([htmlContent], { type: 'text/html' });
                const blobUrl = URL.createObjectURL(blob);
                
                scrollTopAndOpen(newUrl, blobUrl);
            }
        </script>
    </head>
    <body>
        <button id="en" onclick="selectLang('en')">EN</button>
        <button id="es" onclick="selectLang('es')">ES+CA</button>"""
            if (sortedPostsItems.isNotEmpty()) {
                val asciiRegex = Regex("[^\\x00-\\xFF]")
                val dateFormatter = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy").withZone(ZoneId.systemDefault())
                var lastDateString = ""

                sortedPostsItems.forEach { item ->
                    item.publishedEpochSeconds?.let { epoch ->
                        if (!item.link.startsWith("https://www.youtube.com/shorts/")) {
                            val dateString = dateFormatter.format(Instant.ofEpochSecond(epoch))
                            if (dateString != lastDateString) {
                                postsHtml += "<h2>$dateString</h2>"
                                lastDateString = dateString
                            }
                            val asciiOnlyTitle = asciiRegex.replace(item.title, "")
                            postsHtml += "<p class='${item.language}'><a  href='${item.link}' onclick='openInNewBackgroundTab(\"${item.link}\");return false;'>${asciiOnlyTitle.lowercase()}</a></p>"
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
