package com.example.translation


import java.io.File
import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.translation.ui.theme.TranslationTheme


class MainActivity : ComponentActivity() {

    // =========================================================
    // =========================================================
    // VOSK OFFLINE HINDI SPEECH RECOGNITION
    // =========================================================

    private var voskModel: Model? = null
    private var voskRecognizer: Recognizer? = null
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null

    @Volatile
    private var isRecording = false

    private val sampleRate = 16000

    private val audioBufferSize =
        maxOf(
            AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ),
            4096
        )

    private fun initializeVosk(
        onReady: () -> Unit,
        onError: (String) -> Unit
    ) {
        Thread {
            try {
                val modelPath = copyVoskModel()

                if (voskModel == null) {
                    voskModel = Model(modelPath)
                }

                runOnUiThread {
                    onReady()
                }

            } catch (e: Exception) {
                Log.e("Vosk", "Failed to initialize Vosk", e)

                runOnUiThread {
                    onError(
                        e.message
                            ?: "Could not load the Hindi speech model."
                    )
                }
            }
        }.start()
    }

    private fun startVoskRecognition(
        onPartialResult: (String) -> Unit,
        onFinalResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (isRecording) {
            Log.d("Vosk", "Recognition already running")
            return
        }

        val model = voskModel

        if (model == null) {
            onError("Hindi speech model is not ready yet.")
            return
        }

        try {
            // Make sure any previous session is completely finished.
            stopVoskRecognition()

            val recognizer =
                Recognizer(
                    model,
                    sampleRate.toFloat()
                )

            voskRecognizer = recognizer

            val recorder =
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    audioBufferSize
                )

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                recognizer.close()
                voskRecognizer = null
                onError("Could not initialize the microphone.")
                return
            }

            audioRecord = recorder
            isRecording = true

            recorder.startRecording()

            val thread = Thread {
                // Keep a stable reference for this recording session.
                // The UI thread cannot replace/close this reference while
                // the recording thread is inside Vosk.
                val localRecognizer = recognizer
                val buffer = ByteArray(4096)

                try {
                    while (isRecording) {
                        val bytesRead =
                            recorder.read(
                                buffer,
                                0,
                                buffer.size
                            )

                        if (bytesRead <= 0) {
                            continue
                        }

                        // Stop may have been requested while read() was running.
                        if (!isRecording) {
                            break
                        }

                        if (
                            localRecognizer.acceptWaveForm(
                                buffer,
                                bytesRead
                            )
                        ) {
                            val resultJson =
                                localRecognizer.result

                            val text =
                                extractVoskText(resultJson)

                            if (text.isNotBlank()) {
                                runOnUiThread {
                                    onFinalResult(text)
                                }
                            }
                        } else {
                            val partialJson =
                                localRecognizer.partialResult

                            val partialText =
                                extractVoskPartialText(partialJson)

                            if (partialText.isNotBlank()) {
                                runOnUiThread {
                                    onPartialResult(partialText)
                                }
                            }
                        }
                    }

                    // A normal button press sets isRecording=false.
                    // In that case, stopVoskRecognition() will wait for this
                    // thread before closing the recognizer.
                    if (!isRecording) {
                        return@Thread
                    }

                    val finalJson =
                        localRecognizer.finalResult

                    val finalText =
                        extractVoskText(finalJson)

                    if (finalText.isNotBlank()) {
                        runOnUiThread {
                            onFinalResult(finalText)
                        }
                    }

                } catch (e: Exception) {
                    Log.e(
                        "Vosk",
                        "Recognition failed",
                        e
                    )

                    if (isRecording) {
                        runOnUiThread {
                            onError(
                                e.message
                                    ?: "Speech recognition failed."
                            )
                        }
                    }
                } finally {
                    Log.d(
                        "Vosk",
                        "Recording thread finished"
                    )
                }
            }

            recordingThread = thread
            thread.start()

        } catch (e: Exception) {
            Log.e(
                "Vosk",
                "Could not start recognition",
                e
            )

            stopVoskRecognition()

            onError(
                e.message
                    ?: "Could not start speech recognition."
            )
        }
    }

    private fun stopVoskRecognition() {
        // 1. Tell the recording thread to stop.
        isRecording = false

        // 2. Stop microphone input so recorder.read() can return.
        val recorder = audioRecord

        try {
            recorder?.stop()
        } catch (e: Exception) {
            Log.d(
                "Vosk",
                "AudioRecord stop: ${e.message}"
            )
        }

        // 3. Wait for the recording thread to finish all native Vosk work.
        // The recognizer is NOT closed until this thread has finished.
        val thread = recordingThread

        if (
            thread != null &&
            thread != Thread.currentThread()
        ) {
            try {
                thread.join(1000)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()

                Log.d(
                    "Vosk",
                    "Interrupted while waiting for recording thread"
                )
            }
        }

        // 4. Release the microphone.
        try {
            recorder?.release()
        } catch (e: Exception) {
            Log.d(
                "Vosk",
                "AudioRecord release: ${e.message}"
            )
        }

        audioRecord = null
        recordingThread = null

        // 5. Close Vosk only after the recording thread has stopped using it.
        val recognizer = voskRecognizer

        try {
            recognizer?.close()
        } catch (e: Exception) {
            Log.d(
                "Vosk",
                "Recognizer close: ${e.message}"
            )
        }

        voskRecognizer = null
    }

    private fun extractVoskText(json: String): String {
        return try {
            JSONObject(json)
                .optString("text", "")
                .trim()

        } catch (e: Exception) {
            Log.e(
                "Vosk",
                "Could not parse result: $json",
                e
            )

            ""
        }
    }

    private fun extractVoskPartialText(json: String): String {
        return try {
            JSONObject(json)
                .optString("partial", "")
                .trim()

        } catch (e: Exception) {
            Log.e(
                "Vosk",
                "Could not parse partial result: $json",
                e
            )

            ""
        }
    }

    override fun onDestroy() {
        // Stop recognition completely before destroying the Vosk model.
        stopVoskRecognition()

        try {
            voskModel?.close()
        } catch (e: Exception) {
            Log.d(
                "Vosk",
                "Model close: ${e.message}"
            )
        }

        voskModel = null

        super.onDestroy()
    }

    private fun copyVoskModel(): String {
        val modelDir = File(filesDir, "vosk")

        if (modelDir.exists() && modelDir.listFiles()?.isNotEmpty() == true) {
            return modelDir.absolutePath
        }

        fun copyAssetFolder(assetPath: String, destination: File) {
            val files = assets.list(assetPath) ?: return

            if (files.isEmpty()) {
                assets.open(assetPath).use { input ->
                    destination.parentFile?.mkdirs()
                    destination.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            } else {
                destination.mkdirs()

                for (file in files) {
                    copyAssetFolder(
                        "$assetPath/$file",
                        File(destination, file)
                    )
                }
            }
        }

        copyAssetFolder("vosk", modelDir)

        return modelDir.absolutePath
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            TranslationTheme {
                App()
            }
        }
    }

    @Composable
    fun App() {

        var currentPage by remember {
            mutableStateOf("Voice Translation")
        }

        var menuExpanded by remember {
            mutableStateOf(false)
        }

        Box(
            modifier = Modifier.fillMaxSize()
        ) {

            when (currentPage) {

                "Translation" -> TranslationScreen()

                "Voice Translation" -> VoiceScreen()

                "Materials" -> MaterialsScreen()

                "Worksheets" -> WorksheetScreen()

                "Flashcards" -> FlashcardsScreen()

                "Settings" -> SettingsScreen()
            }

            // -----------------------------
            // HAMBURGER MENU
            // -----------------------------

            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 40.dp, end = 16.dp)
            ) {

                Surface(
                    modifier = Modifier.size(46.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xFFE6F4F2)
                ) {

                    TextButton(
                        onClick = {
                            menuExpanded = true
                        }
                    ) {

                        Text(
                            text = "☰",
                            color = Color(0xFF0F766E),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = {
                        menuExpanded = false
                    },
                    modifier = Modifier
                        .width(235.dp),
                    shape = RoundedCornerShape(18.dp),
                    containerColor = Color.White,
                    tonalElevation = 4.dp,
                    shadowElevation = 10.dp
                ) {

                    // Menu heading
                    DropdownMenuItem(
                        text = {
                            Column(
                                modifier = Modifier.padding(vertical = 2.dp)
                            ) {
                                Text(
                                    text = "Navigate",
                                    color = Color(0xFF0F766E),
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "Choose a section",
                                    color = Color(0xFF6B7280),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        },
                        onClick = { },
                        enabled = false,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 18.dp,
                            vertical = 8.dp
                        )
                    )

                    DropdownMenuItem(
                        text = {
                            Text(
                                "Translation",
                                color = if (currentPage == "Translation")
                                    Color(0xFF0F766E)
                                else Color(0xFF1F2937),
                                fontWeight = if (currentPage == "Translation")
                                    FontWeight.Bold
                                else FontWeight.Normal
                            )
                        },
                        leadingIcon = {
                            Text(
                                "Aa",
                                color = Color(0xFF0F766E),
                                fontWeight = FontWeight.Bold
                            )
                        },
                        trailingIcon = {
                            if (currentPage == "Translation") {
                                Text(
                                    "✓",
                                    color = Color(0xFF0F766E),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        },
                        onClick = {
                            currentPage = "Translation"
                            menuExpanded = false
                        },
                        modifier = Modifier.padding(horizontal = 6.dp)
                    )

                    DropdownMenuItem(
                        text = {
                            Text(
                                "Voice Translation",
                                color = if (currentPage == "Voice Translation")
                                    Color(0xFF0F766E)
                                else Color(0xFF1F2937),
                                fontWeight = if (currentPage == "Voice Translation")
                                    FontWeight.Bold
                                else FontWeight.Normal
                            )
                        },
                        leadingIcon = {
                            Text(
                                "♫",
                                color = Color(0xFF0F766E),
                                fontWeight = FontWeight.Bold
                            )
                        },
                        trailingIcon = {
                            if (currentPage == "Voice Translation") {
                                Text(
                                    "✓",
                                    color = Color(0xFF0F766E),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        },
                        onClick = {
                            currentPage = "Voice Translation"
                            menuExpanded = false
                        },
                        modifier = Modifier.padding(horizontal = 6.dp)
                    )

                    DropdownMenuItem(
                        text = {
                            Text(
                                "Materials",
                                color = if (currentPage == "Materials")
                                    Color(0xFF0F766E)
                                else Color(0xFF1F2937),
                                fontWeight = if (currentPage == "Materials")
                                    FontWeight.Bold
                                else FontWeight.Normal
                            )
                        },
                        leadingIcon = {
                            Text(
                                "▤",
                                color = Color(0xFF0F766E),
                                fontWeight = FontWeight.Bold
                            )
                        },
                        trailingIcon = {
                            if (currentPage == "Materials") {
                                Text(
                                    "✓",
                                    color = Color(0xFF0F766E),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        },
                        onClick = {
                            currentPage = "Materials"
                            menuExpanded = false
                        },
                        modifier = Modifier.padding(horizontal = 6.dp)
                    )

                    DropdownMenuItem(
                        text = {
                            Text(
                                "Worksheets",
                                color = if (currentPage == "Worksheets")
                                    Color(0xFF0F766E)
                                else Color(0xFF1F2937),
                                fontWeight = if (currentPage == "Worksheets")
                                    FontWeight.Bold
                                else FontWeight.Normal
                            )
                        },
                        leadingIcon = {
                            Text(
                                "▣",
                                color = Color(0xFF0F766E),
                                fontWeight = FontWeight.Bold
                            )
                        },
                        trailingIcon = {
                            if (currentPage == "Worksheets") {
                                Text(
                                    "✓",
                                    color = Color(0xFF0F766E),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        },
                        onClick = {
                            currentPage = "Worksheets"
                            menuExpanded = false
                        },
                        modifier = Modifier.padding(horizontal = 6.dp)
                    )

                    DropdownMenuItem(
                        text = {
                            Text(
                                "Flashcards",
                                color = if (currentPage == "Flashcards")
                                    Color(0xFF0F766E)
                                else Color(0xFF1F2937),
                                fontWeight = if (currentPage == "Flashcards")
                                    FontWeight.Bold
                                else FontWeight.Normal
                            )
                        },
                        leadingIcon = {
                            Text(
                                "▤",
                                color = Color(0xFF0F766E),
                                fontWeight = FontWeight.Bold
                            )
                        },
                        trailingIcon = {
                            if (currentPage == "Flashcards") {
                                Text(
                                    "✓",
                                    color = Color(0xFF0F766E),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        },
                        onClick = {
                            currentPage = "Flashcards"
                            menuExpanded = false
                        },
                        modifier = Modifier.padding(horizontal = 6.dp)
                    )

                    DropdownMenuItem(
                        text = {
                            Text(
                                "Settings",
                                color = if (currentPage == "Settings")
                                    Color(0xFF0F766E)
                                else Color(0xFF1F2937),
                                fontWeight = if (currentPage == "Settings")
                                    FontWeight.Bold
                                else FontWeight.Normal
                            )
                        },
                        leadingIcon = {
                            Text(
                                "⚙",
                                color = Color(0xFF0F766E),
                                fontWeight = FontWeight.Bold
                            )
                        },
                        trailingIcon = {
                            if (currentPage == "Settings") {
                                Text(
                                    "✓",
                                    color = Color(0xFF0F766E),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        },
                        onClick = {
                            currentPage = "Settings"
                            menuExpanded = false
                        },
                        modifier = Modifier.padding(horizontal = 6.dp)
                    )
                }
            }
        }
    }


    // =========================================================
    // TRANSLATION PAGE
    // =========================================================

    @Composable
    fun TranslationScreen() {

        var hindiText by remember {
            mutableStateOf("")
        }

        var santaliText by remember {
            mutableStateOf("")
        }

        var isLoading by remember {
            mutableStateOf(false)
        }

        var saveMessage by remember {
            mutableStateOf("")
        }

        val teal = Color(0xFF0F766E)
        val lightTeal = Color(0xFFE6F4F2)
        val background = Color(0xFFF7FAF9)

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = background
        ) {

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {

                Spacer(modifier = Modifier.height(35.dp))

                // HEADER

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 55.dp)
                ) {

                    Text(
                        text = "Offline Learning Assistant",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = teal
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "Hindi → Santali",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "Translate educational content offline",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Gray
                    )
                }

                Spacer(modifier = Modifier.height(22.dp))


                // LANGUAGE CARDS

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {

                    Card(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Color.White
                        )
                    ) {

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp)
                        ) {

                            Text(
                                text = "हिन्दी",
                                color = teal,
                                fontWeight = FontWeight.Bold
                            )

                            Text(
                                text = "Devanagari",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                        }
                    }

                    Text(
                        text = "↔",
                        color = teal,
                        fontWeight = FontWeight.Bold
                    )

                    Card(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = lightTeal
                        )
                    ) {

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp)
                        ) {

                            Text(
                                text = "Santali",
                                color = teal,
                                fontWeight = FontWeight.Bold
                            )

                            Text(
                                text = "ᱚᱞ ᱪᱤᱠᱤ",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "⚡ Local translation • No internet required",
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodySmall,
                    color = teal
                )

                Spacer(modifier = Modifier.height(20.dp))


                // INPUT CARD

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color.White
                    )
                ) {

                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {

                            Text(
                                text = "✎  Enter Hindi text",
                                fontWeight = FontWeight.Bold
                            )

                            if (hindiText.isNotEmpty()) {

                                TextButton(
                                    onClick = {
                                        hindiText = ""
                                        santaliText = ""
                                        saveMessage = ""
                                    }
                                ) {

                                    Text(
                                        text = "Clear",
                                        color = teal
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = hindiText,
                            onValueChange = {
                                hindiText = it
                                santaliText = ""
                                saveMessage = ""
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(125.dp),
                            shape = RoundedCornerShape(12.dp),
                            placeholder = {
                                Text(
                                    text = "जैसे: आज हम गिनती सीखेंगे।",
                                    color = Color(0xFF6B7280)
                                )
                            },
                            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color(0xFF1F2937),
                                unfocusedTextColor = Color(0xFF1F2937),
                                disabledTextColor = Color(0xFF6B7280),
                                focusedPlaceholderColor = Color(0xFF6B7280),
                                unfocusedPlaceholderColor = Color(0xFF6B7280),
                                focusedBorderColor = Color(0xFF0F766E),
                                unfocusedBorderColor = Color(0xFF9CA3AF),
                                cursorColor = Color(0xFF0F766E)
                            ),
                            minLines = 4,
                            maxLines = 4
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))


                // QUICK PHRASES

                Text(
                    text = "Quick classroom phrases",
                    modifier = Modifier.fillMaxWidth(),
                    fontWeight = FontWeight.SemiBold,
                    color = Color.DarkGray
                )

                Spacer(modifier = Modifier.height(7.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {

                    QuickPhrase(
                        text = "Greeting",
                        modifier = Modifier.weight(1f)
                    )

                    QuickPhrase(
                        text = "Numbers",
                        modifier = Modifier.weight(1f)
                    )

                    QuickPhrase(
                        text = "Classroom",
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))


                // TRANSLATE BUTTON

                Button(
                    onClick = {

                        isLoading = true
                        santaliText = ""
                        saveMessage = ""

                        Thread {

                            try {

                                val result =
                                    NlpEngine.translateHindiToSantali(
                                        hindiText
                                    )

                                runOnUiThread {

                                    santaliText = result
                                    isLoading = false
                                }

                            } catch (e: Exception) {

                                Log.e(
                                    "Translator",
                                    "Translation failed",
                                    e
                                )

                                runOnUiThread {

                                    santaliText =
                                        "Translation failed: ${e.message}"

                                    isLoading = false
                                }
                            }

                        }.start()
                    },

                    enabled = hindiText.isNotBlank() && !isLoading,

                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),

                    shape = RoundedCornerShape(14.dp),

                    colors = ButtonDefaults.buttonColors(
                        containerColor = teal
                    )
                ) {

                    if (isLoading) {

                        CircularProgressIndicator(
                            modifier = Modifier.size(21.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        Text("Translating...")

                    } else {

                        Text(
                            text = "Translate to Santali  →",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }


                // RESULT

                if (santaliText.isNotEmpty()) {

                    Spacer(modifier = Modifier.height(20.dp))

                    Text(
                        text = "Translation Result",
                        modifier = Modifier.fillMaxWidth(),
                        fontWeight = FontWeight.Bold,
                        color = teal
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = lightTeal
                        )
                    ) {

                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {

                            Text(
                                text = "Hindi",
                                color = teal,
                                fontWeight = FontWeight.Bold
                            )

                            Spacer(modifier = Modifier.height(5.dp))

                            Text(
                                text = hindiText,
                                style = MaterialTheme.typography.bodyLarge
                            )

                            Spacer(modifier = Modifier.height(14.dp))

                            Text(
                                text = "Santali • ᱚᱞ ᱪᱤᱠᱤ",
                                color = teal,
                                fontWeight = FontWeight.Bold
                            )

                            Spacer(modifier = Modifier.height(5.dp))

                            Text(
                                text = santaliText,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )

                            Spacer(modifier = Modifier.height(15.dp))

                            Button(
                                onClick = {
                                    if (hindiText.isBlank() || santaliText.isBlank()) {
                                        return@Button
                                    }

                                    saveMessage = "Saving..."

                                    Thread {
                                        try {
                                            NlpEngine.saveVocabPair(
                                                hindiText,
                                                santaliText
                                            )

                                            runOnUiThread {
                                                saveMessage = "✓ Saved to Materials"
                                            }
                                        } catch (e: Exception) {
                                            Log.e(
                                                "Materials",
                                                "Failed to save translation",
                                                e
                                            )

                                            runOnUiThread {
                                                saveMessage =
                                                    "Could not save material"
                                            }
                                        }
                                    }.start()
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = teal
                                )
                            ) {
                                Text(
                                    text = "＋ Save to Materials",
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            if (saveMessage.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))

                                Text(
                                    text = saveMessage,
                                    modifier = Modifier.fillMaxWidth(),
                                    color = teal,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(30.dp))
            }
        }
    }


    // =========================================================
    // QUICK PHRASE
    // =========================================================

    @Composable
    fun QuickPhrase(
        text: String,
        modifier: Modifier = Modifier
    ) {

        Surface(
            modifier = modifier,
            shape = RoundedCornerShape(10.dp),
            color = Color.White
        ) {

            Text(
                text = text,
                modifier = Modifier.padding(
                    horizontal = 8.dp,
                    vertical = 10.dp
                ),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold
            )
        }
    }


    // =========================================================
    // VOICE PAGE
    // =========================================================

    @Composable
    fun VoiceScreen() {

        val teal = Color(0xFF0F766E)

        var recognizedHindi by remember {
            mutableStateOf("")
        }

        var partialHindi by remember {
            mutableStateOf("")
        }

        var santaliText by remember {
            mutableStateOf("")
        }

        var isListening by remember {
            mutableStateOf(false)
        }

        var isTranslating by remember {
            mutableStateOf(false)
        }

        var isModelLoading by remember {
            mutableStateOf(true)
        }

        var statusText by remember {
            mutableStateOf(
                "Loading Hindi speech model..."
            )
        }

        var errorText by remember {
            mutableStateOf("")
        }

        var saveMessage by remember {
            mutableStateOf("")
        }

        val permissionLauncher =
            rememberLauncherForActivityResult(
                contract =
                    ActivityResultContracts.RequestPermission()
            ) { granted ->

                if (granted) {

                    errorText = ""
                    statusText = "Starting microphone..."

                    startVoskRecognition(
                        onPartialResult = { text ->
                            partialHindi = text
                            statusText = "Listening..."
                        },
                        onFinalResult = { text ->

                            if (text.isNotBlank()) {
                                recognizedHindi = text
                            }

                            partialHindi = ""
                            statusText = "Listening..."
                        },
                        onError = { message ->

                            isListening = false
                            partialHindi = ""
                            statusText =
                                "Recognition stopped"
                            errorText = message
                        }
                    )

                    isListening = true
                    statusText = "Listening..."

                } else {

                    isListening = false
                    statusText =
                        "Microphone permission is required."

                    errorText =
                        "Please allow microphone access to use voice translation."
                }
            }

        androidx.compose.runtime.LaunchedEffect(Unit) {

            initializeVosk(
                onReady = {

                    isModelLoading = false

                    statusText =
                        "Ready — tap the microphone and speak Hindi."
                },
                onError = { message ->

                    isModelLoading = false
                    statusText =
                        "Could not load speech model"

                    errorText = message
                }
            )
        }

        androidx.compose.runtime.DisposableEffect(Unit) {
            onDispose {
                stopVoskRecognition()
            }
        }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFFF7FAF9)
        ) {

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(
                        rememberScrollState()
                    )
                    .padding(20.dp)
            ) {

                Spacer(
                    modifier = Modifier.height(35.dp)
                )

                Text(
                    text = "Voice Translation",
                    style =
                        MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = teal
                )

                Text(
                    text =
                        "Speak Hindi and get Santali translation",
                    color = Color.Gray
                )

                Spacer(
                    modifier = Modifier.height(25.dp)
                )

                // -------------------------------------------------
                // MICROPHONE CARD
                // -------------------------------------------------

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    colors =
                        CardDefaults.cardColors(
                            containerColor = Color.White
                        )
                ) {

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(25.dp),
                        horizontalAlignment =
                            Alignment.CenterHorizontally
                    ) {

                        Text(
                            text = "Hindi → Santali",
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(
                            modifier = Modifier.height(25.dp)
                        )

                        Button(
                            onClick = {

                                if (isModelLoading) {
                                    return@Button
                                }

                                if (isListening) {

                                    stopVoskRecognition()

                                    isListening = false
                                    partialHindi = ""

                                    statusText =
                                        "Ready — tap the microphone and speak Hindi."

                                } else {

                                    errorText = ""

                                    val permissionGranted =
                                        ContextCompat.checkSelfPermission(
                                            this@MainActivity,
                                            Manifest.permission.RECORD_AUDIO
                                        ) ==
                                                PackageManager.PERMISSION_GRANTED

                                    if (permissionGranted) {

                                        startVoskRecognition(
                                            onPartialResult = { text ->
                                                partialHindi = text
                                                statusText =
                                                    "Listening..."
                                            },
                                            onFinalResult = { text ->

                                                if (text.isNotBlank()) {
                                                    recognizedHindi = text
                                                }

                                                partialHindi = ""
                                                statusText =
                                                    "Listening..."
                                            },
                                            onError = { message ->

                                                isListening = false
                                                partialHindi = ""

                                                statusText =
                                                    "Recognition stopped"

                                                errorText = message
                                            }
                                        )

                                        isListening = true
                                        statusText =
                                            "Listening..."

                                    } else {

                                        permissionLauncher.launch(
                                            Manifest.permission.RECORD_AUDIO
                                        )
                                    }
                                }
                            },
                            enabled = !isModelLoading,
                            modifier = Modifier.size(110.dp),
                            shape = RoundedCornerShape(55.dp),
                            colors =
                                ButtonDefaults.buttonColors(
                                    containerColor =
                                        if (isListening) {
                                            Color(0xFFB91C1C)
                                        } else {
                                            teal
                                        }
                                )
                        ) {

                            Text(
                                text =
                                    if (isListening) {
                                        "■"
                                    } else {
                                        "🎙"
                                    },
                                style =
                                    MaterialTheme.typography.headlineLarge
                            )
                        }

                        Spacer(
                            modifier = Modifier.height(15.dp)
                        )

                        Text(
                            text = when {
                                isModelLoading ->
                                    "Loading model..."

                                isListening ->
                                    "Listening — tap to stop"

                                else ->
                                    "Tap to Speak"
                            },
                            fontWeight = FontWeight.Bold,
                            color = teal
                        )

                        Spacer(
                            modifier = Modifier.height(5.dp)
                        )

                        Text(
                            text = statusText,
                            style =
                                MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    }
                }

                Spacer(
                    modifier = Modifier.height(20.dp)
                )

                // -------------------------------------------------
                // RECOGNIZED HINDI
                // -------------------------------------------------

                if (
                    recognizedHindi.isNotEmpty() ||
                    partialHindi.isNotEmpty()
                ) {

                    DemoCard(
                        title = "Recognized Hindi",
                        content =
                            if (partialHindi.isNotEmpty()) {
                                partialHindi
                            } else {
                                recognizedHindi
                            }
                    )

                    Spacer(
                        modifier = Modifier.height(12.dp)
                    )

                    Button(
                        onClick = {

                            val textToTranslate =
                                recognizedHindi.trim()

                            if (textToTranslate.isBlank() || isTranslating) {
                                return@Button
                            }

                            isTranslating = true
                            santaliText = ""
                            saveMessage = ""
                            statusText = "Translating..."
                            errorText = ""

                            Thread {

                                try {

                                    val result =
                                        NlpEngine
                                            .translateHindiToSantali(
                                                textToTranslate
                                            )

                                    runOnUiThread {
                                        santaliText = result
                                        isTranslating = false
                                        statusText = "Translation complete"
                                    }

                                } catch (e: Exception) {

                                    Log.e(
                                        "VoiceTranslator",
                                        "Translation failed",
                                        e
                                    )

                                    runOnUiThread {
                                        santaliText =
                                            "Translation failed: ${e.message ?: "Unknown error"}"
                                        isTranslating = false
                                        statusText = "Translation failed"
                                    }
                                }

                            }.start()
                        },
                        enabled =
                            recognizedHindi.isNotBlank() &&
                                    !isListening &&
                                    !isTranslating,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = teal
                            )
                    ) {

                        if (isTranslating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(21.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )

                            Spacer(
                                modifier = Modifier.width(8.dp)
                            )

                            Text(
                                text = "Translating...",
                                fontWeight = FontWeight.Bold
                            )
                        } else {
                            Text(
                                text = "Translate to Santali →",
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(
                        modifier = Modifier.height(12.dp)
                    )

                    if (santaliText.isNotEmpty()) {

                        DemoCard(
                            title = "Santali Output",
                            content = santaliText
                        )

                        Spacer(
                            modifier = Modifier.height(12.dp)
                        )

                        Button(
                            onClick = {

                                if (
                                    recognizedHindi.isBlank() ||
                                    santaliText.isBlank() ||
                                    santaliText.startsWith("Translation failed")
                                ) {
                                    return@Button
                                }

                                saveMessage = "Saving..."

                                Thread {
                                    try {

                                        NlpEngine.saveVocabPair(
                                            recognizedHindi,
                                            santaliText
                                        )

                                        runOnUiThread {
                                            saveMessage = "✓ Saved to Materials"
                                        }

                                    } catch (e: Exception) {

                                        Log.e(
                                            "Materials",
                                            "Failed to save voice translation",
                                            e
                                        )

                                        runOnUiThread {
                                            saveMessage =
                                                "Could not save material"
                                        }
                                    }
                                }.start()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = teal
                            )
                        ) {
                            Text(
                                text = "＋ Save to Materials",
                                fontWeight = FontWeight.Bold
                            )
                        }

                        if (saveMessage.isNotEmpty()) {

                            Spacer(
                                modifier = Modifier.height(8.dp)
                            )

                            Text(
                                text = saveMessage,
                                modifier = Modifier.align(Alignment.CenterHorizontally),
                                color = teal,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                if (errorText.isNotEmpty()) {

                    Spacer(
                        modifier = Modifier.height(12.dp)
                    )

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors =
                            CardDefaults.cardColors(
                                containerColor =
                                    Color(0xFFFFF1F2)
                            )
                    ) {

                        Text(
                            text = errorText,
                            modifier =
                                Modifier.padding(14.dp),
                            color = Color(0xFF9F1239),
                            style =
                                MaterialTheme.typography.bodySmall
                        )
                    }
                }

                Spacer(
                    modifier = Modifier.height(20.dp)
                )

                Text(
                    text =
                        "Offline speech recognition • No internet required",
                    modifier =
                        Modifier.align(Alignment.CenterHorizontally),
                    style =
                        MaterialTheme.typography.bodySmall,
                    color = teal
                )

                Spacer(
                    modifier = Modifier.height(20.dp)
                )
            }
        }
    }


    // =========================================================
    // MATERIALS PAGE
    // =========================================================

    @Composable
    fun MaterialsScreen() {

        val teal = Color(0xFF0F766E)

        var materials by remember {
            mutableStateOf(
                NlpEngine.getAllVocabPairs()
            )
        }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFFF7FAF9)
        ) {

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {

                Spacer(modifier = Modifier.height(35.dp))

                Text(
                    text = "Saved Materials",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = teal
                )

                Text(
                    text = "Hindi + Santali content for learning resources",
                    color = Color.Gray
                )

                Spacer(modifier = Modifier.height(25.dp))

                if (materials.isEmpty()) {

                    DemoCard(
                        title = "No materials saved",
                        content =
                            "Translate Hindi content and tap " +
                                    "\"Save to Materials\" to add it here."
                    )

                } else {

                    Text(
                        text = "${materials.size} saved item(s)",
                        fontWeight = FontWeight.SemiBold,
                        color = Color.DarkGray
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    materials.forEachIndexed { index, pair ->

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = Color.White
                            )
                        ) {

                            Column(
                                modifier = Modifier.padding(16.dp)
                            ) {

                                Text(
                                    text = "Material ${index + 1}",
                                    fontWeight = FontWeight.Bold,
                                    color = teal
                                )

                                Spacer(modifier = Modifier.height(10.dp))

                                Text(
                                    text = "Hindi",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.Gray
                                )

                                Text(
                                    text = pair.first,
                                    style = MaterialTheme.typography.bodyLarge
                                )

                                Spacer(modifier = Modifier.height(10.dp))

                                Text(
                                    text = "Santali",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.Gray
                                )

                                Text(
                                    text = pair.second,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    Spacer(modifier = Modifier.height(15.dp))

                    Button(
                        onClick = {
                            NlpEngine.clearVocab()
                            materials = emptyList()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFB91C1C)
                        )
                    ) {
                        Text(
                            text = "Clear All Materials",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(25.dp))
            }
        }
    }


    // =========================================================
    // WORKSHEET PAGE
    // =========================================================

    @Composable
    fun WorksheetScreen() {

        val teal = Color(0xFF0F766E)

        var title by remember {
            mutableStateOf("My Santali Worksheet")
        }

        var statusText by remember {
            mutableStateOf("")
        }

        var isGenerating by remember {
            mutableStateOf(false)
        }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFFF7FAF9)
        ) {

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {

                Spacer(modifier = Modifier.height(35.dp))

                Text(
                    text = "Learning Worksheets",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = teal
                )

                Text(
                    text = "Create bilingual classroom resources",
                    color = Color.Gray
                )

                Spacer(modifier = Modifier.height(25.dp))

                // -------------------------------------------------
                // WORKSHEET TITLE
                // -------------------------------------------------

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color.White
                    )
                ) {

                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {

                        Text(
                            text = "Worksheet Title",
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = title,
                            onValueChange = {
                                title = it
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            placeholder = {
                                Text("e.g. Numbers 1–10")
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(15.dp))

                // -------------------------------------------------
                // INFORMATION
                // -------------------------------------------------

                DemoCard(
                    title = "Bilingual Worksheet",
                    content =
                        "Your saved Hindi → Santali translations " +
                                "will be used to create a printable worksheet."
                )

                Spacer(modifier = Modifier.height(15.dp))

                // -------------------------------------------------
                // GENERATE BUTTON
                // -------------------------------------------------

                Button(
                    onClick = {

                        if (isGenerating) {
                            return@Button
                        }

                        isGenerating = true
                        statusText = "Generating worksheet..."

                        Thread {

                            try {

                                val pairs =
                                    NlpEngine.getAllVocabPairs()

                                if (pairs.isEmpty()) {

                                    runOnUiThread {
                                        statusText =
                                            "No saved translations yet. Translate some Hindi text first."
                                        isGenerating = false
                                    }

                                    return@Thread
                                }

                                val pdfPath =
                                    NlpEngine.generateWorksheet(
                                        title = title,
                                        pairs = pairs
                                    )

                                runOnUiThread {

                                    if (pdfPath.isNotBlank()) {

                                        statusText =
                                            "Worksheet created successfully!"

                                        PdfFiles.openPdf(
                                            this@MainActivity,
                                            pdfPath
                                        )

                                    } else {

                                        statusText =
                                            "Could not generate worksheet."
                                    }

                                    isGenerating = false
                                }

                            } catch (e: Exception) {

                                Log.e(
                                    "WORKSHEET",
                                    "Worksheet generation failed",
                                    e
                                )

                                runOnUiThread {

                                    statusText =
                                        "Error: ${e.message}"

                                    isGenerating = false
                                }
                            }

                        }.start()
                    },

                    enabled = !isGenerating,

                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),

                    shape = RoundedCornerShape(14.dp),

                    colors = ButtonDefaults.buttonColors(
                        containerColor = teal
                    )
                ) {

                    if (isGenerating) {

                        CircularProgressIndicator(
                            modifier = Modifier.size(21.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )

                        Spacer(
                            modifier = Modifier.width(8.dp)
                        )

                        Text("Generating...")

                    } else {

                        Text(
                            text = "＋ Create Worksheet",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // -------------------------------------------------
                // STATUS
                // -------------------------------------------------

                if (statusText.isNotEmpty()) {

                    Spacer(
                        modifier = Modifier.height(15.dp)
                    )

                    DemoCard(
                        title = "Status",
                        content = statusText
                    )
                }

                Spacer(
                    modifier = Modifier.height(25.dp)
                )

                // -------------------------------------------------
                // SAVED VOCABULARY
                // -------------------------------------------------

                Text(
                    text = "Saved translations",
                    fontWeight = FontWeight.Bold
                )

                Spacer(
                    modifier = Modifier.height(10.dp)
                )

                val pairs =
                    remember {
                        mutableStateOf(
                            NlpEngine.getAllVocabPairs()
                        )
                    }

                if (pairs.value.isEmpty()) {

                    DemoCard(
                        title = "No translations saved",
                        content =
                            "Translate Hindi text from the Translation " +
                                    "page to add content here."
                    )

                } else {

                    pairs.value.forEach { pair ->

                        DemoCard(
                            title = pair.first,
                            content = pair.second
                        )

                        Spacer(
                            modifier = Modifier.height(8.dp)
                        )
                    }
                }

                Spacer(
                    modifier = Modifier.height(20.dp)
                )
            }
        }
    }


    // =========================================================
    // FLASHCARDS PAGE
    // =========================================================

    @Composable
    fun FlashcardsScreen() {

        val teal = Color(0xFF0F766E)
        val background = Color(0xFFF7FAF9)

        val pairs = remember {
            NlpEngine.getAllVocabPairs()
        }

        var currentIndex by remember {
            mutableStateOf(0)
        }

        var showAnswer by remember {
            mutableStateOf(false)
        }

        var isGenerating by remember {
            mutableStateOf(false)
        }

        var statusText by remember {
            mutableStateOf("")
        }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = background
        ) {

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {

                Spacer(modifier = Modifier.height(35.dp))

                Text(
                    text = "Santali Flashcards",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = teal
                )

                Text(
                    text = "Learn Hindi words with their Santali equivalents",
                    color = Color.Gray
                )

                Spacer(modifier = Modifier.height(25.dp))

                if (pairs.isEmpty()) {

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Color.White
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "No flashcards yet",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF1F2937)
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = "Translate and save some Hindi → Santali words first.",
                                color = Color.Gray
                            )
                        }
                    }

                } else {

                    Text(
                        text = "Card ${currentIndex + 1} of ${pairs.size}",
                        color = Color.Gray,
                        fontWeight = FontWeight.Medium
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp)
                            .clickable {
                                showAnswer = !showAnswer
                            },
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Color.White
                        ),
                        elevation = CardDefaults.cardElevation(4.dp)
                    ) {

                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {

                            Text(
                                text = "HINDI",
                                color = teal,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            Text(
                                text = pairs[currentIndex].first,
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF1F2937)
                            )

                            Spacer(modifier = Modifier.height(22.dp))

                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = Color(0xFFE6F4F2),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(18.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {

                                    if (showAnswer) {
                                        Text(
                                            text = "SANTALI • ᱚᱞ ᱪᱤᱠᱤ",
                                            color = teal,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold
                                        )

                                        Spacer(modifier = Modifier.height(8.dp))

                                        Text(
                                            text = pairs[currentIndex].second,
                                            style = MaterialTheme.typography.headlineMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF1F2937)
                                        )
                                    } else {
                                        Text(
                                            text = "Tap the card to reveal",
                                            color = teal,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {

                        Button(
                            onClick = {
                                currentIndex =
                                    if (currentIndex == 0) pairs.lastIndex
                                    else currentIndex - 1
                                showAnswer = false
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFE2E8F0),
                                contentColor = Color(0xFF1F2937)
                            )
                        ) {
                            Text("← Previous")
                        }

                        Button(
                            onClick = {
                                showAnswer = !showAnswer
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = teal
                            )
                        ) {
                            Text(
                                if (showAnswer) "Hide Answer" else "Reveal Answer",
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Button(
                            onClick = {
                                currentIndex =
                                    (currentIndex + 1) % pairs.size
                                showAnswer = false
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFE2E8F0),
                                contentColor = Color(0xFF1F2937)
                            )
                        ) {
                            Text("Next →")
                        }
                    }

                    Spacer(modifier = Modifier.height(22.dp))

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Color.White
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Text(
                                text = "Printable Flashcards",
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF1F2937)
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            Text(
                                text = "Create a printable PDF from your saved vocabulary.",
                                color = Color.Gray,
                                style = MaterialTheme.typography.bodySmall
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            Button(
                                onClick = {
                                    if (isGenerating) return@Button

                                    isGenerating = true
                                    statusText = "Generating flashcards..."

                                    Thread {
                                        try {
                                            val pdfPath =
                                                NlpEngine.generateFlashcards(pairs)

                                            runOnUiThread {
                                                isGenerating = false

                                                if (pdfPath.isNotBlank()) {
                                                    statusText =
                                                        "Flashcards created successfully!"

                                                    PdfFiles.openPdf(
                                                        this@MainActivity,
                                                        pdfPath
                                                    )
                                                } else {
                                                    statusText =
                                                        "Could not generate flashcards."
                                                }
                                            }

                                        } catch (e: Exception) {
                                            Log.e(
                                                "FLASHCARD_GEN",
                                                "Failed to generate flashcards",
                                                e
                                            )

                                            runOnUiThread {
                                                isGenerating = false
                                                statusText =
                                                    "Flashcard generation failed: ${e.message}"
                                            }
                                        }
                                    }.start()
                                },
                                enabled = !isGenerating,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = teal
                                )
                            ) {

                                if (isGenerating) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(21.dp),
                                        color = Color.White,
                                        strokeWidth = 2.dp
                                    )

                                    Spacer(modifier = Modifier.width(8.dp))

                                    Text("Generating...")

                                } else {
                                    Text(
                                        text = "＋ Create Printable Flashcards",
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    if (statusText.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))

                        DemoCard(
                            title = "Status",
                            content = statusText
                        )
                    }
                }

                Spacer(modifier = Modifier.height(25.dp))
            }
        }
    }


    // =========================================================
    // SETTINGS PAGE
    // =========================================================

    @Composable
    fun SettingsScreen() {

        val teal = Color(0xFF0F766E)

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFFF7FAF9)
        ) {

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {

                Spacer(modifier = Modifier.height(35.dp))

                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = teal
                )

                Text(
                    text = "Manage language and offline AI",
                    color = Color.Gray
                )

                Spacer(modifier = Modifier.height(25.dp))

                DemoCard(
                    title = "Language",
                    content = "Santali • ᱚᱞ ᱪᱤᱠᱤ"
                )

                Spacer(modifier = Modifier.height(12.dp))

                DemoCard(
                    title = "Offline Mode",
                    content = "● Enabled\nAll core processing stays on the device."
                )

                Spacer(modifier = Modifier.height(12.dp))

                DemoCard(
                    title = "AI Models",
                    content = "✓ Hindi Speech Recognition\n✓ Hindi → Santali Translation\n✓ Santali Text-to-Speech"
                )

                Spacer(modifier = Modifier.height(12.dp))

                DemoCard(
                    title = "About",
                    content = "Offline Tribal Learning Assistant\nPrototype Version 1.0"
                )
            }
        }
    }


    // =========================================================
    // REUSABLE DEMO CARD
    // =========================================================

    @Composable
    fun DemoCard(
        title: String,
        content: String
    ) {

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color.White
            )
        ) {

            Column(
                modifier = Modifier.padding(16.dp)
            ) {

                Text(
                    text = title,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(7.dp))

                Text(
                    text = content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.DarkGray
                )
            }
        }
    }
}
