// Copyright (c) 2025 Daniel Monedero-Tortola
package com.example.feeds

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.toMutableStateList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import java.io.StringReader
import com.opencsv.CSVWriter
import java.io.StringWriter
import com.example.feeds.ui.theme.FeedsTheme
import kotlin.system.exitProcess

enum class AppMode {
    LOGS, EDITOR
}

class MainActivity : ComponentActivity() {
    private val exitHandler = Handler(Looper.getMainLooper())
    private var isEditing = false
    private var appModeState = mutableStateOf(AppMode.LOGS)

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC

        handleIntent(intent)

        enableEdgeToEdge()
        setContent {
            FeedsTheme {
                FeedsApp(
                    initialMode = appModeState.value,
                    onModeChange = { mode -> 
                        isEditing = (mode == AppMode.EDITOR)
                        appModeState.value = mode
                    }
                )
            }
        }
        scheduleAppExit()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.data
        if (data?.scheme == "feeds" && data.host == "edit") {
            appModeState.value = AppMode.EDITOR
        }
    }

    private fun scheduleAppExit() {
        val exitRunnable = object : Runnable {
            override fun run() {
                if (isEditing) {
                    // Check again in 30 seconds
                    exitHandler.postDelayed(this, 30000)
                } else {
                    Log.i("MainActivity", "Exiting application now.")
                    finishAndRemoveTask()
                    exitProcess(0)
                }
            }
        }
        exitHandler.postDelayed(exitRunnable, 30000)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@RequiresApi(Build.VERSION_CODES.Q)
@Composable
fun FeedsApp(initialMode: AppMode, onModeChange: (AppMode) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember(initialMode) { mutableStateOf(initialMode) }
    var logs by remember { mutableStateOf("Processing started...") }
    var showExitConfirmation by remember { mutableStateOf(false) }
    var pendingChanges by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(true) }

    LaunchedEffect(mode) {
        onModeChange(mode)
    }

    LaunchedEffect(Unit) {
        val newLogs = generateFeeds(context)
        logs = newLogs
        isRefreshing = false
        if (!newLogs.contains("No posts found.")) {
            openUrlWithAppChooser(context, "http://localhost:8080/")
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(if (mode == AppMode.LOGS) "Feeds Logs" else "Edit Feeds") },
                actions = {
                    if (mode == AppMode.LOGS) {
                        IconButton(
                            onClick = {
                                isRefreshing = true
                                scope.launch {
                                    val newLogs = generateFeeds(context)
                                    logs = newLogs
                                    isRefreshing = false
                                }
                            },
                            enabled = !isRefreshing
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh Logs")
                        }
                        IconButton(onClick = { mode = AppMode.EDITOR }) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit Feeds")
                        }
                    }
                },
                navigationIcon = {
                    if (mode == AppMode.EDITOR) {
                        IconButton(onClick = {
                            if (pendingChanges) {
                                showExitConfirmation = true
                            } else {
                                mode = AppMode.LOGS
                            }
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            when (mode) {
                AppMode.LOGS -> {
                    FeedLogs(logs)
                }
                AppMode.EDITOR -> {
                    FeedsEditor(
                        onExit = {
                            if (pendingChanges) {
                                showExitConfirmation = true
                            } else {
                                mode = AppMode.LOGS
                            }
                        },
                        onSave = {
                            mode = AppMode.LOGS
                            isRefreshing = true
                            scope.launch {
                                val newLogs = generateFeeds(context)
                                logs = newLogs
                                isRefreshing = false
                            }
                        },
                        onChanges = { pendingChanges = it }
                    )
                }
            }
        }

        if (isRefreshing) {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Refreshing Feeds...") },
                text = { Text("Please wait while the feeds are being processed.") },
                confirmButton = {}
            )
        }
    }

    if (showExitConfirmation) {
        AlertDialog(
            onDismissRequest = { showExitConfirmation = false },
            title = { Text("Unsaved Changes") },
            text = { Text("You have unsaved changes. Are you sure you want to exit without saving?") },
            confirmButton = {
                Button(onClick = {
                    showExitConfirmation = false
                    pendingChanges = false
                    mode = AppMode.LOGS
                }) {
                    Text("Exit")
                }
            },
            dismissButton = {
                Button(onClick = { showExitConfirmation = false }) {
                    Text("Stay")
                }
            }
        )
    }
}

@Composable
fun FeedLogs(logs: String) {
    TextField(
        value = logs,
        onValueChange = {},
        label = { Text("Logs") },
        modifier = Modifier.fillMaxSize(),
        readOnly = true
    )
}

@RequiresApi(Build.VERSION_CODES.Q)
@Composable
fun FeedsEditor(onExit: () -> Unit, onSave: () -> Unit, onChanges: (Boolean) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val initialText = remember { readFeedsFileText(context) ?: "" }
    
    val feedItems = remember {
        parseCsvText(initialText).toMutableStateList()
    }
    
    // Editor State
    var currentLanguage by remember { mutableStateOf("en") }
    var currentName by remember { mutableStateOf("") }
    var currentUrl by remember { mutableStateOf("") }
    var selectedIndex by remember { mutableStateOf<Int?>(null) }
    var showLangMenu by remember { mutableStateOf(false) }
    val languages = listOf("en", "es", "ca")
    val scope = rememberCoroutineScope()
    var isDetecting by remember { mutableStateOf(false) }

    val currentSerialized = serializeItems(feedItems)
    val hasChanges = remember(currentSerialized) {
        currentSerialized != initialText
    }

    // History for Undo/Redo
    val history = remember { mutableStateListOf<List<FeedItem>>() }
    val redoStack = remember { mutableStateListOf<List<FeedItem>>() }

    LaunchedEffect(hasChanges) {
        onChanges(hasChanges)
    }

    fun saveToHistory() {
        history.add(feedItems.toList())
        if (history.size > 50) history.removeAt(0)
        redoStack.clear()
    }

    fun clearInputs() {
        currentLanguage = "en"
        currentName = ""
        currentUrl = ""
        selectedIndex = null
    }

    BackHandler(onBack = onExit)

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // --- Top Input Section ---
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    Button(onClick = { showLangMenu = true }) {
                        Text(currentLanguage)
                    }
                    DropdownMenu(expanded = showLangMenu, onDismissRequest = { showLangMenu = false }) {
                        languages.forEach { lang ->
                            DropdownMenuItem(
                                text = { Text(lang) },
                                onClick = {
                                    currentLanguage = lang
                                    showLangMenu = false
                                }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedTextField(
                    value = currentName,
                    onValueChange = { currentName = it },
                    label = { Text("Name") },
                    modifier = Modifier.weight(1f)
                )
            }
            OutlinedTextField(
                value = currentUrl,
                onValueChange = { currentUrl = it },
                label = { Text("RSS URL") },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                trailingIcon = {
                    if (isDetecting) {
                        CircularProgressIndicator(modifier = Modifier.padding(8.dp))
                    } else {
                        IconButton(onClick = {
                            if (currentUrl.isNotBlank()) {
                                isDetecting = true
                                scope.launch {
                                    val discoveryResult = discoverRssUrl(currentUrl)
                                    if (discoveryResult.isSuccess) {
                                        currentUrl = discoveryResult.getOrNull() ?: currentUrl
                                    }
                                    isDetecting = false
                                }
                            }
                        }) {
                            Icon(Icons.Default.Search, contentDescription = "Detect RSS")
                        }
                    }
                }
            )
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Button(
                    onClick = {
                        if (currentName.isNotBlank() && currentUrl.isNotBlank()) {
                            saveToHistory()
                            feedItems.add(FeedItem(currentLanguage, currentName, currentUrl))
                            feedItems.sortWith(feedItemComparator)
                            clearInputs()
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Add")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        selectedIndex?.let { index ->
                            saveToHistory()
                            feedItems[index] = FeedItem(currentLanguage, currentName, currentUrl)
                            feedItems.sortWith(feedItemComparator)
                            clearInputs()
                        }
                    },
                    enabled = selectedIndex != null,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Update")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text("Existing Feeds:", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
        
        // --- List Section ---
        LazyColumn(modifier = Modifier.weight(1f)) {
            itemsIndexed(feedItems) { index, item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable {
                            currentLanguage = item.language
                            currentName = item.title
                            currentUrl = item.url
                            selectedIndex = index
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("${item.language} | ${item.title}", style = androidx.compose.material3.MaterialTheme.typography.bodyLarge)
                        Text(item.url, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = {
                        saveToHistory()
                        feedItems.removeAt(index)
                        if (selectedIndex == index) clearInputs()
                        else if (selectedIndex != null && selectedIndex!! > index) selectedIndex = selectedIndex!! - 1
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = androidx.compose.ui.graphics.Color.Red)
                    }
                }
            }
        }

        // --- Bottom Actions ---
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = {
                    if (history.isNotEmpty()) {
                        val last = history.removeAt(history.size - 1)
                        redoStack.add(feedItems.toList())
                        feedItems.clear()
                        feedItems.addAll(last)
                    }
                },
                enabled = history.isNotEmpty(),
                modifier = Modifier.padding(end = 4.dp)
            ) {
                Text("Undo")
            }
            Button(
                onClick = {
                    if (redoStack.isNotEmpty()) {
                        val next = redoStack.removeAt(redoStack.size - 1)
                        history.add(feedItems.toList())
                        feedItems.clear()
                        feedItems.addAll(next)
                    }
                },
                enabled = redoStack.isNotEmpty()
            ) {
                Text("Redo")
            }
            Box(modifier = Modifier.weight(1f))
            Button(
                onClick = {
                    val newText = serializeItems(feedItems)
                    if (saveFeedsFileText(context, newText)) {
                        onSave()
                    }
                },
                enabled = hasChanges
            ) {
                Icon(Icons.Default.Done, contentDescription = null)
                Text("Save")
            }
        }
    }
}

private fun parseCsvText(text: String): List<FeedItem> {
    val items = mutableListOf<FeedItem>()
    try {
        val reader = com.opencsv.CSVReader(StringReader(text))
        var nextLine: Array<String>?
        while (reader.readNext().also { nextLine = it } != null) {
            val line = nextLine!!
            if (line.isEmpty() || line[0].startsWith("#")) continue
            if (line.size >= 3) {
                items.add(FeedItem(line[0].trim(), line[1].trim(), line[2].trim()))
            }
        }
    } catch (e: Exception) {
        Log.e("FeedsEditor", "Error parsing CSV: ${e.message}")
    }
    return items.sortedWith(feedItemComparator)
}

private fun serializeItems(items: List<FeedItem>): String {
    val writer = StringWriter()
    val csvWriter = CSVWriter(writer)
    writer.append("# Format: language, title, rss_url\n")
    writer.append("# Lines starting with # are ignored.\n")
    items.forEach { item ->
        csvWriter.writeNext(arrayOf(item.language, item.title, item.url), false)
    }
    return writer.toString()
}
