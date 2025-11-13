// Copyright (c) 2025 Daniel Monedero-Tortola
package com.example.feeds

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri

/**
 * Opens the given URL, always prompting the user to choose an application
 * from a list of apps that can handle the URL (e.g., browsers).
 *
 * @param context The Context used to launch the intent (e.g., Activity, ApplicationContext).
 * @param urlString The URL to open. Must be a well-formed URI string.
 * @param chooserTitle Optional title for the app chooser dialog.
 * @return True if the chooser intent was successfully launched, false otherwise.
 */
fun openUrlWithAppChooser(
    context: Context,
    urlString: String,
    chooserTitle: CharSequence = "Open with..." // Default title
): Boolean {
    val tag = "OpenUrlChooser"

    if (urlString.isBlank()) {
        Log.w(tag, "URL string is blank, cannot open.")
        return false
    }

    Log.d(tag, "Attempting to open URL with chooser: '$urlString'")

    val uri: Uri
    try {
        uri = urlString.toUri()
        if (uri.scheme == null || (uri.scheme != "http" && uri.scheme != "https")) {
            Log.w(
                tag,
                "URL scheme is not http or https. May not find browsers. Scheme: ${uri.scheme}"
            )
        }
    } catch (e: Exception) {
        Log.e(tag, "Invalid URL string format: $urlString", e)
        // Toast.makeText(context, "Invalid URL format.", Toast.LENGTH_SHORT).show()
        return false
    }

    val targetIntent = Intent(Intent.ACTION_VIEW, uri)

    /*if (targetIntent.resolveActivity(context.packageManager) == null) {
        Log.w(tag, "No application found to handle URL: $urlString")
        Toast.makeText(context, "No app found to open this link.", Toast.LENGTH_LONG).show()
        return false
    }*/

    val chooserIntent = Intent.createChooser(targetIntent, chooserTitle).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    return try {
        context.startActivity(chooserIntent)
        Log.i(tag, "Successfully launched app chooser for URL: $urlString")
        true
    } catch (e: Exception) {
        Log.e(tag, "Could not launch app chooser for URL: $urlString", e)
        // Toast.makeText(context, "Could not open the link.", Toast.LENGTH_LONG).show()
        false
    }
}