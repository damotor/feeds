package com.example.feeds

import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope // For structured concurrency
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import javax.xml.parsers.SAXParserFactory

// Assume FeedItem and PostItem data classes are defined as in the prompt
// data class FeedItem(val language: String, val title: String, val url: String)
// data class PostItem(val title: String, val link: String, val publishedEpochSeconds: Long?)

// Assume fetchUrlContentAsString and parseFeedString functions are defined
// as previously discussed. For completeness, minimal versions are included below.

/**
 * Fetches the content of a URL and returns its body as a String.
 */
suspend fun fetchUrlContentAsString(
    urlString: String,
    connectTimeout: Int = 15000,
    readTimeout: Int = 15000
): Result<String> {
    return withContext(Dispatchers.IO) {
        try {
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = connectTimeout
            connection.readTimeout = readTimeout
            // connection.setRequestProperty("User-Agent", "MyAppName/1.0")


            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val contentType = connection.contentType
                var charset = StandardCharsets.UTF_8
                if (contentType != null) {
                    val params = contentType.split(";")
                    params.forEach { param ->
                        val trimmedParam = param.trim()
                        if (trimmedParam.startsWith("charset=", ignoreCase = true)) {
                            try {
                                charset = java.nio.charset.Charset.forName(trimmedParam.substring("charset=".length))
                            } catch (e: Exception) {
                                Log.w("fetchUrl", "Unsupported charset: ${trimmedParam.substring("charset=".length)}, defaulting to UTF-8: $e")
                            }
                            return@forEach
                        }
                    }
                }
                BufferedReader(InputStreamReader(connection.inputStream, charset)).use { reader ->
                    Result.success(reader.readText())
                }
            } else {
                Result.failure(Exception("HTTP Error: $responseCode ${connection.responseMessage} for URL $urlString"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

// Assume parseFeedString is defined (implementation from previous examples)
// For brevity, here's a placeholder signature.
// Actual implementation would involve SAX parsing for Atom & RSS.
enum class FeedType { ATOM, RSS, UNKNOWN }

fun identifyFeedTypeHeuristic(xmlString: String): FeedType {
    // Basic heuristic (can be improved)
    if (xmlString.contains("<feed", ignoreCase = true) && xmlString.contains("http://www.w3.org/2005/Atom", ignoreCase = true)) return FeedType.ATOM
    if (xmlString.contains("<feed", ignoreCase = true) && xmlString.contains("http://www.w3.org/2005/Atom", ignoreCase = true)) return FeedType.ATOM
    if (xmlString.contains("<rss", ignoreCase = true) || xmlString.contains("http://purl.org/rss", ignoreCase = true)) return FeedType.RSS
    return FeedType.UNKNOWN
}

@RequiresApi(Build.VERSION_CODES.O)
fun parseDateToEpochSeconds(dateString: String): Long? {
    try { return OffsetDateTime.parse(dateString, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toEpochSecond() } catch (_: DateTimeParseException) { /* try next */ }
    try { return ZonedDateTime.parse(dateString, DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond() } catch (_: DateTimeParseException) { /* try next */ }
    try { val formatter = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss z", Locale.ENGLISH); return ZonedDateTime.parse(dateString, formatter).toEpochSecond() } catch (e: DateTimeParseException) {
        Log.w("ParseDate", "Failed to parse date: $dateString. $e")
    }
    return null
}

class AtomSaxFeedHandler(private val language: String) : DefaultHandler() {
    private val entries = mutableListOf<PostItem>()
    private var currentEntryTitle: String? = null; private var currentEntryLink: String? = null
    private var currentEntryPublishedDate: String? = null; private var currentEntryUpdatedDate: String? = null
    private var currentElementContent = StringBuilder(); private var inEntryElement = false
    private var inTitleElement = false; private var inPublishedElement = false; private var inUpdatedElement = false
    fun getExtractedEntries(): List<PostItem> = entries
    override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
        currentElementContent = StringBuilder(); val elementName = qName?.lowercase()
        if (elementName == "entry") { inEntryElement = true; currentEntryTitle = null; currentEntryLink = null; currentEntryPublishedDate = null; currentEntryUpdatedDate = null }
        else if (inEntryElement) {
            when (elementName) {
                "title" -> inTitleElement = true
                "link" -> currentEntryLink = attributes?.getValue("href")?.trim()
                "published" -> inPublishedElement = true
                "updated" -> inUpdatedElement = true
            }
        }
    }
    override fun characters(ch: CharArray?, start: Int, length: Int) { if (ch != null && inEntryElement && (inTitleElement || inPublishedElement || inUpdatedElement)) currentElementContent.append(String(ch, start, length)) }
    @RequiresApi(Build.VERSION_CODES.O)
    override fun endElement(uri: String?, localName: String?, qName: String?) {
        val elementName = qName?.lowercase()
        if (inEntryElement) {
            val content = currentElementContent.toString().trim()
            when (elementName) {
                "title" -> if (inTitleElement) { currentEntryTitle = content; inTitleElement = false }
                "published" -> if (inPublishedElement) { currentEntryPublishedDate = content; inPublishedElement = false }
                "updated" -> if (inUpdatedElement) { currentEntryUpdatedDate = content; inUpdatedElement = false }
                "entry" -> {
                    val title = currentEntryTitle; val link = currentEntryLink
                    val dateString = currentEntryPublishedDate ?: currentEntryUpdatedDate
                    val publishedEpoch = dateString?.let { parseDateToEpochSeconds(it) }
                    if (!title.isNullOrEmpty() && !link.isNullOrEmpty()) entries.add(PostItem(title, link, language, publishedEpoch))
                    else Log.w("AtomHandler", "Skipped entry: title='${title ?: "N/A"}', link='${link ?: "N/A"}'")
                    inEntryElement = false
                }
            }
        }
    }
}
class RssSaxFeedHandler(private val language: String) : DefaultHandler() {
    private val items = mutableListOf<PostItem>()
    private var currentItemTitle: String? = null; private var currentItemLink: String? = null; private var currentItemPubDate: String? = null
    private var currentElementContent = StringBuilder(); private var inItemElement = false
    private var inTitleElement = false; private var inLinkElement = false; private var inPubDateElement = false
    fun getExtractedEntries(): List<PostItem> = items
    override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
        currentElementContent = StringBuilder(); val elementName = qName?.lowercase()
        if (elementName == "item") { inItemElement = true; currentItemTitle = null; currentItemLink = null; currentItemPubDate = null }
        else if (inItemElement) {
            when (elementName) {
                "title" -> inTitleElement = true
                "link" -> inLinkElement = true
                "pubdate", "dc:date" -> inPubDateElement = true
            }
        }
    }
    override fun characters(ch: CharArray?, start: Int, length: Int) { if (ch != null && inItemElement && (inTitleElement || inLinkElement || inPubDateElement)) currentElementContent.append(String(ch, start, length)) }
    @RequiresApi(Build.VERSION_CODES.O)
    override fun endElement(uri: String?, localName: String?, qName: String?) {
        val elementName = qName?.lowercase()
        if (inItemElement) {
            val content = currentElementContent.toString().trim()
            when (elementName) {
                "title" -> if (inTitleElement) { currentItemTitle = content; inTitleElement = false }
                "link" -> if (inLinkElement) { currentItemLink = content; inLinkElement = false }
                "pubdate", "dc:date" -> if (inPubDateElement) { currentItemPubDate = content; inPubDateElement = false }
                "item" -> {
                    val title = currentItemTitle; val link = currentItemLink; val dateString = currentItemPubDate
                    val publishedEpoch = dateString?.let { parseDateToEpochSeconds(it) }
                    if (!title.isNullOrEmpty() && !link.isNullOrEmpty()) items.add(PostItem(title, link, language, publishedEpoch))
                    else Log.w("RssHandler", "Skipped item: title='${title ?: "N/A"}', link='${link ?: "N/A"}'")
                    inItemElement = false
                }
            }
        }
    }
}

fun parseAtomFeedString(atomFeedXmlString: String, language: String): Result<List<PostItem>> {
    return try {
        val factory = SAXParserFactory.newInstance(); val parser = factory.newSAXParser()
        val handler = AtomSaxFeedHandler(language)
        parser.parse(InputSource(StringReader(atomFeedXmlString)), handler)
        val extractedEntities = handler.getExtractedEntries()
        extractedEntities.forEach { it.language = language }
        Result.success(extractedEntities)
    } catch (e: Exception) { Log.e("ParseAtom", "Error parsing Atom: ${e.message}", e); Result.failure(e) }
}
fun parseRssFeedString(rssFeedXmlString: String, language: String): Result<List<PostItem>> {
    return try {
        val factory = SAXParserFactory.newInstance(); val parser = factory.newSAXParser()
        val handler = RssSaxFeedHandler(language)
        parser.parse(InputSource(StringReader(rssFeedXmlString)), handler)
        val extractedEntities = handler.getExtractedEntries()
        extractedEntities.forEach { it.language = language }
        Result.success(extractedEntities)
    } catch (e: Exception) { Log.e("ParseRss", "Error parsing RSS: ${e.message}", e); Result.failure(e) }
}


fun parseFeedString(feedXmlString: String, language: String): Result<List<PostItem>> {
    if (feedXmlString.isBlank()) return Result.failure(IllegalArgumentException("Feed string is blank"))
    return when (identifyFeedTypeHeuristic(feedXmlString)) {
        FeedType.ATOM -> parseAtomFeedString(feedXmlString, language)
        FeedType.RSS -> parseRssFeedString(feedXmlString, language)
        FeedType.UNKNOWN -> Result.failure(IllegalArgumentException("Unknown or unsupported feed type"))
    }
}


/**
 * Fetches and parses multiple feed URLs concurrently.
 *
 * @param feeds A list of FeedItem objects, each containing a URL to an RSS or Atom feed.
 * @return A Pair where:
 *            - first: List<PostItem> containing all successfully parsed posts from all feeds.
 *            - second: String containing logs of the fetching and parsing process.
 */
suspend fun fetchAndParseFeedsConcurrently(feeds: List<FeedItem>): Pair<List<PostItem>, String> {
    val allParsedPosts = mutableListOf<PostItem>()
    val operationLogs = StringBuilder() // Use StringBuilder for efficient string concatenation

    if (feeds.isEmpty()) {
        operationLogs.append("No feeds provided to fetchAndParseFeedsConcurrently.\n")
        return Pair(emptyList(), operationLogs.toString())
    }

    //operationLogs.append("Starting concurrent fetch and parse for ${feeds.size} feeds...\n")

    coroutineScope {
        val deferredResults = feeds.map { feedItem ->
            async(Dispatchers.IO) { // Use Dispatchers.IO for network and parsing
                val logPrefix = "Feed '${feedItem.title}' (${feedItem.url}): "
                val individualFeedLogs = StringBuilder()
                var postsForThisFeed: List<PostItem> = emptyList()

                //individualFeedLogs.append("${logPrefix}Starting fetch.\n")
                // Log.d("FeedFetcher", "Fetching feed: ${feedItem.title} from ${feedItem.url}") // Example using Android Log
                val fetchResult = fetchUrlContentAsString(feedItem.url)

                fetchResult.fold(
                    onSuccess = { xmlString ->
                        //individualFeedLogs.append("${logPrefix}Successfully fetched ${xmlString.length} bytes. Starting parse.\n")
                        // Log.d("FeedFetcher", "Successfully fetched ${feedItem.title}. Length: ${xmlString.length}")
                        val parseResult = parseFeedString(xmlString, feedItem.language)
                        parseResult.fold(
                            onSuccess = { posts ->
                                postsForThisFeed = posts
                                if (posts.isEmpty()) {
                                    individualFeedLogs.append("Error: $logPrefix Parsed 0 items.\n")
                                } else {
                                    //individualFeedLogs.append("${logPrefix}Successfully parsed ${posts.size} items.\n")
                                }
                                // Log.i("FeedFetcher", "Successfully parsed ${posts.size} items from ${feedItem.title}")
                            },
                            onFailure = { error ->
                                individualFeedLogs.append("Error: ${logPrefix}Parse FAILED: ${error.message}\n")
                                // Log.e("FeedFetcher", "Failed to parse feed ${feedItem.title}: ${error.message}", error)
                            }
                        )
                    },
                    onFailure = { error ->
                        individualFeedLogs.append("Error: ${logPrefix}Fetch FAILED: ${error.message}\n")
                        // Log.e("FeedFetcher", "Failed to fetch feed ${feedItem.title}: ${error.message}", error)
                    }
                )
                // Return a pair for each async task: its posts and its specific logs
                Pair(postsForThisFeed, individualFeedLogs.toString())
            }
        }

        // Wait for all async operations to complete and collect their results
        val results: List<Pair<List<PostItem>, String>> = deferredResults.awaitAll()

        // Process results
        results.forEach { (postsFromSingleFeed, singleFeedLog) ->
            allParsedPosts.addAll(postsFromSingleFeed)
            operationLogs.insert(0,singleFeedLog) // Append individual logs to the main log
        }
    }

    operationLogs.insert(0,"All feed processing finished. Total posts retrieved: ${allParsedPosts.size}\n")
    // Log.i("FeedFetcher", "All feeds processed. Total posts retrieved: ${allParsedPosts.size}")
    return Pair(allParsedPosts, operationLogs.toString())
}

// Dummy Log for environments without Android Log
object Log {
    //fun d(tag: String, msg: String) = println("D/$tag: $msg")
    fun i(tag: String, msg: String) = println("I/$tag: $msg")
    fun w(tag: String, msg: String, tr: Throwable? = null) = println("W/$tag: $msg ${tr?.message ?: ""}")
    fun e(tag: String, msg: String, tr: Throwable? = null) = println("E/$tag: $msg ${tr?.message ?: ""}")
}