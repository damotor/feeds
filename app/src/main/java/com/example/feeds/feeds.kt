// Copyright (c) 2025 Daniel Monedero-Tortola
package com.example.feeds

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class FeedItem(
    val language: String,
    val title: String,
    val url: String
)

val feedItemComparator = compareBy<FeedItem> { it.language.lowercase() }.thenBy { it.title.lowercase() }

data class PostItem(
    val title: String,
    val link: String,
    var language: String,
    val publishedEpochSeconds: Long?
)

@RequiresApi(Build.VERSION_CODES.Q)
suspend fun generateFeeds(context: Context): String = withContext(Dispatchers.IO) {
    var logs = "Processing started..."

    val feedItems = loadFeedsFile(context)

    if (feedItems == null) {
        logs = "Error: Failed to load or parse feeds.csv.\n$logs"
        return@withContext logs
    }

    if (feedItems.isEmpty()) {
        logs = "No feed items found in feeds.csv or all lines were empty/malformed.\n$logs"
        return@withContext logs
    }

    logs = "Loaded ${feedItems.size} feed urls. Starting processing...\n$logs"

    var asyncLogs = ""
    val (allPostsItems, fetchAndParseLogs) = fetchAndParseFeedsConcurrently(feedItems)
    asyncLogs = fetchAndParseLogs + asyncLogs
    val sortedPostsItems = allPostsItems.sortedByDescending { it.publishedEpochSeconds }

    var postsHtml = """<!DOCTYPE html>
<html lang="en">
    <head>
        <title>Feed Posts</title>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <style>
            a {
                color: #ffffff;
            }
            a:visited {
                color: #666666;
            }
            button { 
                height: 50px;
                width: 31%;
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
            h3 {
                color: #888888;
                margin-top: 20px;
                border-bottom: 1px solid #333333;
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
                // Friday: display Spanish and Catalan
                if (d.getDay() == 5) {
                    selectLang('es');
                } else {
                    // otherwise show only English
                    selectLang('en');
                }
            });

            function openInNewBackgroundTab(newUrl) {
                const scrollY = Math.floor(window.pageYOffset || window.scrollY || document.documentElement.scrollTop || 0);
                const htmlContent = '<!DOCTYPE html><html><head><meta charset="UTF-8">' +
                    document.head.innerHTML +
                    '<script>document.addEventListener("DOMContentLoaded", function() { ' +
                    'window.scrollTo(0, ' + scrollY + '); ' +
                    'if (window.location.protocol === "blob:") setTimeout(() => URL.revokeObjectURL(window.location.href), 1000); ' +
                    '});<' + '/script>' +
                    '</head><body>' +
                    document.body.innerHTML +
                    '</body></html>';
                const blob = new Blob([htmlContent], { type: 'text/html' });
                const blobUrl = URL.createObjectURL(blob);
                
                const a = document.createElement('a');
                a.href = blobUrl;
                a.target = '_blank';
                a.rel = 'noopener';
                document.body.appendChild(a);
                a.click();
                document.body.removeChild(a);

                window.location.href = newUrl;
            }
        </script>
    </head>
    <body>
        <button id="en" onclick="selectLang('en')">EN</button>
        <button onclick="window.location.href='feeds://edit'">⚙</button>
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
                        postsHtml += "<h3>$dateString</h3>"
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

    return@withContext asyncLogs + logs
}
