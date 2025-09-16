package com.example.feeds

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.IOException
import kotlin.text.toByteArray

private const val TAG_MEDIA_STORE_INIT = "MediaStoreInit"
private const val TAG_FEED_LOADER = "FeedLoader"
const val FEEDS_TXT_FILE_NAME = "feeds.txt"
// Get feeds using https://getrssfeed.com/
const val INITIAL_CONTENT =
    """en,Slashdot,https://rss.slashdot.org/Slashdot/slashdotMain
en,TED,https://www.youtube.com/feeds/videos.xml?channel_id=UCAuUUnT6oDeKwE6v1NGQxug"""


/**
 * Ensures a file with the given filename exists in the public Downloads directory.
 * If it doesn't exist, it's created using MediaStore and written with initialContent.
 * If it already exists, its MediaStore URI is returned.
 *
 * @param context The application context.
 * @param targetFilename The desired display name of the file in the Downloads directory.
 * @param mimeType The MIME type of the file (e.g., "text/plain", "text/html").
 * @param initialContent The content to write to the file if it's newly created.
 * @return The content URI (content://) of the file in MediaStore if successful, null otherwise.
 */
@RequiresApi(Build.VERSION_CODES.Q)
fun initializeFileIfNotFound(
    context: Context,
    targetFilename: String = FEEDS_TXT_FILE_NAME,
    mimeType: String = "text/plain",
    initialContent: String = INITIAL_CONTENT
): Uri? {
    val resolver = context.contentResolver

    // 1. Check if the file already exists in Downloads via MediaStore
    val queryUri: Uri =
        MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    val projection = arrayOf(
        MediaStore.MediaColumns._ID, // Needed to build the URI if found
        MediaStore.MediaColumns.DISPLAY_NAME
    )

    // Query for files with the specific display name in the Downloads directory
    val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
    val selectionArgs = arrayOf(targetFilename)

    // For API 29+, ensure we are querying within the Downloads directory
    // This part of the selection is more complex if not using RELATIVE_PATH directly.
    // Simpler if RELATIVE_PATH can be part of the query, but that's for API 29+.
    // For broad compatibility, we might just query by name and assume it's in Downloads
    // or rely on MediaStore placing it there during insert.
    // A more robust pre-Q query might involve BUCKET_DISPLAY_NAME or DATA path.

    var existingFileUri: Uri?
    try {
        resolver.query(queryUri, projection, selection, selectionArgs, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val id = cursor.getLong(idColumn)
                existingFileUri = Uri.withAppendedPath(queryUri, id.toString())
                Log.i(TAG_MEDIA_STORE_INIT, "File '$targetFilename' already exists in Downloads: $existingFileUri")
                // Here, you could decide if you want to overwrite or just return the URI.
                // This function currently just returns the URI if found.
                return existingFileUri
            }
        }
    } catch (e: Exception) {
        Log.e(TAG_MEDIA_STORE_INIT, "Error querying MediaStore for '$targetFilename': ${e.message}", e)
        // Proceed to attempt creation, or return null if query failure is critical
    }

    // 2. If not found, create it and write initial content
    Log.i(TAG_MEDIA_STORE_INIT, "File '$targetFilename' not found in Downloads. Attempting to create.")
    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, targetFilename)
        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        // IS_PENDING can be useful if the write operation is lengthy
        // put(MediaStore.MediaColumns.IS_PENDING, 1)
        // For pre-Q, the system largely determines the path for Downloads.
        // Specifying MediaStore.Downloads.DATA directly in ContentValues is often problematic.
    }

    var newFileUri: Uri? = null
    try {
        newFileUri = resolver.insert(queryUri, contentValues)
        if (newFileUri == null) {
            Log.e(TAG_MEDIA_STORE_INIT, "MediaStore.insert() returned null URI for '$targetFilename'.")
            return null
        }

        // Write initial content
        resolver.openOutputStream(newFileUri).use { outputStream ->
            if (outputStream == null) {
                throw IOException("Failed to get output stream for new MediaStore URI: $newFileUri")
            }
            outputStream.write(initialContent.toByteArray(Charsets.UTF_8))
            Log.i(TAG_MEDIA_STORE_INIT, "Successfully created and wrote initial content to '$targetFilename': $newFileUri")
        }

        // If IS_PENDING was used:
        // if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        //     contentValues.clear()
        //     contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
        //     resolver.update(newFileUri, contentValues, null, null)
        // }
        return newFileUri

    } catch (e: Exception) {
        Log.e(TAG_MEDIA_STORE_INIT, "Error creating or writing to '$targetFilename' in Downloads: ${e.message}", e)
        // Clean up if URI was created but write failed
        if (newFileUri != null) {
            try {
                resolver.delete(newFileUri, null, null)
                Log.d(TAG_MEDIA_STORE_INIT, "Cleaned up incomplete MediaStore entry for '$targetFilename'")
            } catch (deleteEx: Exception) {
                Log.e(TAG_MEDIA_STORE_INIT, "Error cleaning up MediaStore entry for '$targetFilename': $deleteEx")
            }
        }
        return null
    }
}

/**
 * Reads the "feeds.txt" file from the app-specific directory on external storage
 * and parses each line, expecting a certain format.
 *
 * @param context The application context.
 * @return A list of parsed [FeedItem] objects if successful, or null if an error occurs.
 */
@RequiresApi(Build.VERSION_CODES.Q)
fun loadFeedsFile(context: Context): List<FeedItem>? {
    // 1. Ensure "feeds.txt" exists in Downloads and get its URI
    val fileUri: Uri? =initializeFileIfNotFound(context)

    if (fileUri == null) {
        Log.e(TAG_FEED_LOADER, "Failed to initialize or find '$FEEDS_TXT_FILE_NAME' in Downloads.")
        return null // Critical failure to get the file URI
    }

    Log.d(TAG_FEED_LOADER, "Accessing '$FEEDS_TXT_FILE_NAME' from Downloads using URI: $fileUri")

    // 2. Read the file content using the MediaStore URI
    val parsedFeedItems = mutableListOf<FeedItem>()
    try {
        context.contentResolver.openInputStream(fileUri)?.use { inputStream ->
            BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val trimmedLine = line?.trim()
                    if (trimmedLine.isNullOrBlank() || trimmedLine.startsWith("#")) { // Skip empty lines or comments
                        continue
                    }
                    val parts = trimmedLine.split(",", limit = 3) // language,title,url
                    if (parts.size == 3) {
                        val language = parts[0].trim()
                        val title = parts[1].trim()
                        val url = parts[2].trim()
                        if (language.isNotEmpty() && title.isNotEmpty() && url.isNotEmpty()) {
                            parsedFeedItems.add(FeedItem(language, title, url))
                        } else {
                            Log.w(TAG_FEED_LOADER, "Skipping malformed line (empty parts): '$trimmedLine'")
                        }
                    } else {
                        Log.w(TAG_FEED_LOADER, "Skipping malformed line (incorrect parts count): '$trimmedLine'")
                    }
                }
            }
        } ?: Log.e(TAG_FEED_LOADER, "ContentResolver.openInputStream() returned null for URI: $fileUri")
    } catch (e: IOException) {
        Log.e(TAG_FEED_LOADER, "IOException while reading '$FEEDS_TXT_FILE_NAME' from Downloads: ${e.message}", e)
        // Depending on desired behavior, you might return null or an empty list here.
        // Returning what was parsed so far, or empty if parsing just started.
        return parsedFeedItems // Or null if any read error is critical
    } catch (e: SecurityException) {
        Log.e(TAG_FEED_LOADER, "SecurityException while reading '$FEEDS_TXT_FILE_NAME' from Downloads: ${e.message}", e)
        return null // Likely a permission issue or URI access problem
    } catch (e: Exception) {
        Log.e(TAG_FEED_LOADER, "Unexpected error while reading or parsing '$FEEDS_TXT_FILE_NAME': ${e.message}", e)
        return null // Or parsedFeedItems, depending on how critical this is.
    }

    if (parsedFeedItems.isEmpty()) {
        Log.i(TAG_FEED_LOADER, "'$FEEDS_TXT_FILE_NAME' was empty or all lines were malformed.")
    } else {
        Log.i(TAG_FEED_LOADER, "Successfully loaded and parsed ${parsedFeedItems.size} feed items from Downloads.")
    }

    return parsedFeedItems
}
