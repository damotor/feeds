// Copyright (c) 2025 Daniel Monedero-Tortola
package com.example.feeds

import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.example.feeds.ui.theme.FeedsTheme
import kotlin.system.exitProcess

class MainActivity : ComponentActivity() {
    private val exitHandler = Handler(Looper.getMainLooper())

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onCreate(savedInstanceState: Bundle?) {
        val logs = generateFeeds(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FeedsTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Feeds(
                        logs = logs,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
        // Only open browser if we retrieved posts
        if (!logs.contains("No posts found.")) {
            openUrlWithAppChooser(this, "http://localhost:8080/")
        }
        scheduleAppExit()
    }

    private fun scheduleAppExit() {
        val exitRunnable = Runnable {
            Log.i("MainActivity", "Exiting application now.")
            finishAndRemoveTask()
            exitProcess(0) // Ensures the process is killed. Use with caution.
        }
        exitHandler.postDelayed(exitRunnable, 20000)
    }
}

@Composable
fun Feeds(logs: String, modifier: Modifier = Modifier) {
    TextField(
        value = logs,
        onValueChange = {},
        label = { Text("Logs") },
        modifier = modifier,
        readOnly = true
    )
}

@Preview(showBackground = true)
@Composable
fun FeedsPreview() {
    FeedsTheme {
        Feeds("Android")
    }
}