// Copyright (c) 2025 Daniel Monedero-Tortola
package com.example.feeds

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import com.opencsv.CSVReader
import com.opencsv.exceptions.CsvValidationException
import java.io.InputStreamReader
import java.io.IOException

private const val TAG_MEDIA_STORE_INIT = "MediaStoreInit"
private const val TAG_FEED_LOADER = "FeedLoader"
const val FEEDS_CSV_FILE_NAME = "feeds.csv"

/**
 * Ensures a file with the given filename exists in the public Documents directory.
 * If it doesn't exist, it's created using MediaStore and written with initial_feeds.csv.
 * If it already exists, its MediaStore URI is returned.
 *
 * @param context The application context.
 * @param targetFilename The desired display name of the file in the Documents directory.
 * @param mimeType The MIME type of the file (e.g., "text/csv").
 * @return The content URI (content://) of the file in MediaStore if successful, null otherwise.
 */
@RequiresApi(Build.VERSION_CODES.Q)
fun initializeFileIfNotFound(
    context: Context,
    targetFilename: String = FEEDS_CSV_FILE_NAME,
    mimeType: String = "text/csv"
): Uri? {
    val resolver = context.contentResolver
    val queryUri: Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val documentsPath = "${Environment.DIRECTORY_DOCUMENTS}/"

    val projection = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.DISPLAY_NAME
    )

    // Query for a file with the specific display name in the Documents directory
    val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
    val selectionArgs = arrayOf(targetFilename, documentsPath)

    try {
        resolver.query(queryUri, projection, selection, selectionArgs, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val id = cursor.getLong(idColumn)
                val existingFileUri = Uri.withAppendedPath(queryUri, id.toString())
                Log.i(TAG_MEDIA_STORE_INIT, "File '$targetFilename' already exists in Documents: $existingFileUri")
                return existingFileUri
            }
        }
    } catch (e: Exception) {
        Log.e(TAG_MEDIA_STORE_INIT, "Error querying MediaStore for '$targetFilename' in Documents: ${e.message}", e)
    }

    // If not found, create it in the Documents directory
    Log.i(TAG_MEDIA_STORE_INIT, "File '$targetFilename' not found. Attempting to create in Documents.")
    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, targetFilename)
        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
        put(MediaStore.MediaColumns.RELATIVE_PATH, documentsPath)
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
                throw IOException("Failed to get output stream for new URI: $newFileUri")
            }
            val resourceId = R.raw.initial_feeds
            context.resources.openRawResource(resourceId).use { it.copyTo(outputStream) }
            Log.i(TAG_MEDIA_STORE_INIT, "Successfully created and wrote initial content to '$targetFilename' in Documents.")
        }
        return newFileUri

    } catch (e: Exception) {
        Log.e(TAG_MEDIA_STORE_INIT, "Error creating or writing to '$targetFilename' in Documents: ${e.message}", e)
        newFileUri?.let { resolver.delete(it, null, null) } // Cleanup
        return null
    }
}

fun parseCsvFromUriWithLibrary(context: Context, fileUri: Uri): List<FeedItem>? {
    val parsedFeedItems = mutableListOf<FeedItem>()
    try {
        context.contentResolver.openInputStream(fileUri)?.use { inputStream ->
            val csvReader = CSVReader(InputStreamReader(inputStream, Charsets.UTF_8))
            csvReader.use { reader ->
                var nextLine: Array<String>?
                while (reader.readNext().also { nextLine = it } != null) {
                    val lineArray = nextLine!!
                    if (lineArray.getOrNull(0)?.trim()?.startsWith("#") == true) continue
                    if (lineArray.size >= 3) {
                        val language = lineArray[0].trim()
                        val title = lineArray[1].trim()
                        val url = lineArray[2].trim()
                        if (language.isNotBlank() && title.isNotBlank() && url.isNotBlank()) {
                            parsedFeedItems.add(FeedItem(language, title, url))
                        }
                    } else {
                        Log.w(TAG_FEED_LOADER, "Skipping malformed line: ${lineArray.joinToString(",")}")
                    }
                }
            }
        } ?: Log.e(TAG_FEED_LOADER, "ContentResolver returned null for URI: $fileUri")
    } catch (e: Exception) {
        Log.e(TAG_FEED_LOADER, "Error reading or parsing '$fileUri': ${e.message}", e)
        return null
    }
    return parsedFeedItems
}

/**
 * Reads the "feeds.csv" file from the public Documents directory on external storage.
 *
 * @param context The application context.
 * @return A list of parsed [FeedItem] objects if successful, or null if an error occurs.
 */
@RequiresApi(Build.VERSION_CODES.Q)
fun loadFeedsFile(context: Context): List<FeedItem>? {
    val fileUri: Uri? = initializeFileIfNotFound(context)

    if (fileUri == null) {
        Log.e(TAG_FEED_LOADER, "Failed to initialize or find '$FEEDS_CSV_FILE_NAME' in Documents.")
        return null
    }

    Log.d(TAG_FEED_LOADER, "Accessing '$FEEDS_CSV_FILE_NAME' from Documents using URI: $fileUri")
    val parsedFeedItems = parseCsvFromUriWithLibrary(context, fileUri)

    if (parsedFeedItems.isNullOrEmpty()) {
        Log.i(TAG_FEED_LOADER, "'$FEEDS_CSV_FILE_NAME' was empty or all lines were malformed.")
    } else {
        Log.i(TAG_FEED_LOADER, "Successfully loaded ${parsedFeedItems.size} feed items from Documents.")
    }

    return parsedFeedItems
}