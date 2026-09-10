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
        if (isRecording) return

        val model = voskModel

        if (model == null) {
            onError("Hindi speech model is not ready yet.")
            return
        }

        try {
            voskRecognizer?.close()

            voskRecognizer =
                Recognizer(
                    model,
                    sampleRate.toFloat()
                )

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
                onError("Could not initialize the microphone.")
                return
            }

            audioRecord = recorder
            isRecording = true

            recorder.startRecording()

            recordingThread = Thread {

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

                        val recognizer =
                            voskRecognizer ?: break

                        if (
                            recognizer.acceptWaveForm(
                                buffer,
                                bytesRead
                            )
                        ) {
                            val resultJson =
                                recognizer.result

                            val text =
                                extractVoskText(resultJson)

                            if (text.isNotBlank()) {
                                runOnUiThread {
                                    onFinalResult(text)
                                }
                            }

                        } else {
                            val partialJson =
                                recognizer.partialResult

                            val partialText =
                                extractVoskPartialText(
                                    partialJson
                                )

                            if (partialText.isNotBlank()) {
                                runOnUiThread {
                                    onPartialResult(partialText)
                                }
                            }
                        }
                    }

                    val recognizer =
                        voskRecognizer

                    if (recognizer != null) {

                        val finalJson =
                            recognizer.finalResult

                        val finalText =
                            extractVoskText(finalJson)

                        if (finalText.isNotBlank()) {
                            runOnUiThread {
                                onFinalResult(finalText)
                            }
                        }
                    }

                } catch (e: Exception) {

                    Log.e(
                        "Vosk",
                        "Recognition failed",
                        e
                    )

                    runOnUiThread {
                        onError(
                            e.message
                                ?: "Speech recognition failed."
                        )
                    }
                }
            }

            recordingThread?.start()

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
        isRecording = false

        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }

        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }

        audioRecord = null
        recordingThread = null

        try {
            voskRecognizer?.close()
        } catch (_: Exception) {
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
        stopVoskRecognition()

        try {
            voskModel?.close()
        } catch (_: Exception) {
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

                "Worksheets" -> WorksheetScreen()

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
                    }
                ) {

                    DropdownMenuItem(
                        text = {
                            Text("Translation")
                        },
                        onClick = {
                            currentPage = "Translation"
                            menuExpanded = false
                        }
                    )

                    DropdownMenuItem(
                        text = {
                            Text("Voice Translation")
                        },
                        onClick = {
                            currentPage = "Voice Translation"
                            menuExpanded = false
                        }
                    )

                    DropdownMenuItem(
                        text = {
                            Text("Worksheets")
                        },
                        onClick = {
                            currentPage = "Worksheets"
                            menuExpanded = false
                        }
                    )

                    DropdownMenuItem(
                        text = {
                            Text("Settings")
                        },
                        onClick = {
                            currentPage = "Settings"
                            menuExpanded = false
                        }
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
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(125.dp),
                            shape = RoundedCornerShape(12.dp),
                            placeholder = {
                                Text(
                                    "जैसे: आज हम गिनती सीखेंगे।"
                                )
                            },
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
                                text = "Santali • ᱚᱞ ᱪᱤᱠᱤ",
                                color = teal,
                                fontWeight = FontWeight.Bold
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            Text(
                                text = santaliText,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
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

                            if (textToTranslate.isBlank()) {
                                return@Button
                            }

                            Thread {

                                try {

                                    val result =
                                        NlpEngine
                                            .translateHindiToSantali(
                                                textToTranslate
                                            )

                                    runOnUiThread {
                                        santaliText = result
                                    }

                                } catch (e: Exception) {

                                    Log.e(
                                        "VoiceTranslator",
                                        "Translation failed",
                                        e
                                    )

                                    runOnUiThread {

                                        santaliText =
                                            "Translation failed: ${e.message}"
                                    }
                                }

                            }.start()
                        },
                        enabled =
                            recognizedHindi.isNotBlank() &&
                                    !isListening,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = teal
                            )
                    ) {

                        Text(
                            text = "Translate to Santali →",
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(
                        modifier = Modifier.height(12.dp)
                    )

                    if (santaliText.isNotEmpty()) {

                        DemoCard(
                            title = "Santali Output",
                            content = santaliText
                        )
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
    // WORKSHEET PAGE
    // =========================================================

    @Composable
    fun WorksheetScreen() {

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


                DemoCard(
                    title = "Create New Worksheet",
                    content = "Generate Hindi + Santali learning material"
                )

                Spacer(modifier = Modifier.height(15.dp))

                Button(
                    onClick = { },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = teal
                    )
                ) {

                    Text(
                        text = "＋ Create Worksheet",
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(25.dp))

                Text(
                    text = "Recent Resources",
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(10.dp))

                DemoCard(
                    title = "📄 Numbers 1–10",
                    content = "Hindi + Santali • Beginner"
                )

                Spacer(modifier = Modifier.height(10.dp))

                DemoCard(
                    title = "📄 Classroom Vocabulary",
                    content = "Common teacher instructions"
                )

                Spacer(modifier = Modifier.height(10.dp))

                DemoCard(
                    title = "📄 Basic Greetings",
                    content = "Everyday classroom phrases"
                )
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