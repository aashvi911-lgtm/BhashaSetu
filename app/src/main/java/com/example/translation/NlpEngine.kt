package com.example.translation

import com.chaquo.python.Python

object NlpEngine {

    private val python: Python by lazy {
        Python.getInstance()
    }

    // =========================================================
    // TRANSLATION
    // =========================================================

    fun translateHindiToSantali(text: String): String {
        val startTime = System.nanoTime()

        return try {
            val module = python.getModule("translator")
            val result = module.callAttr("translate", text)

            val endTime = System.nanoTime()
            val timeMs = (endTime - startTime) / 1_000_000.0

            android.util.Log.d(
                "TRANSLATION_TIME",
                "Translation took %.2f ms".format(timeMs)
            )

            result.toString()

        } catch (e: Exception) {
            android.util.Log.e(
                "TRANSLATION_TIME",
                "Translation failed",
                e
            )

            "Translation failed: ${e.message}"
        }
    }


    // =========================================================
    // VOCABULARY STORE
    // =========================================================

    fun saveVocabPair(
        hindiText: String,
        santaliText: String
    ) {
        try {
            val module = python.getModule("vocab_store")

            module.callAttr(
                "add_pair",
                hindiText,
                santaliText
            )

            android.util.Log.d(
                "VOCAB_STORE",
                "Saved vocabulary pair"
            )

        } catch (e: Exception) {
            android.util.Log.e(
                "VOCAB_STORE",
                "Failed to save vocabulary pair",
                e
            )
        }
    }


    fun getAllVocabPairs(): List<Pair<String, String>> {
        return try {
            val module = python.getModule("vocab_store")
            val result = module.callAttr("get_all_pairs")

            val pairs = mutableListOf<Pair<String, String>>()

            for (item in result.asList()) {
                val row = item.asList()

                if (row.size >= 2) {
                    pairs.add(
                        row[0].toString() to row[1].toString()
                    )
                }
            }

            pairs

        } catch (e: Exception) {
            android.util.Log.e(
                "VOCAB_STORE",
                "Failed to load vocabulary pairs",
                e
            )

            emptyList()
        }
    }


    fun clearVocab() {
        try {
            val module = python.getModule("vocab_store")
            module.callAttr("clear_all")

        } catch (e: Exception) {
            android.util.Log.e(
                "VOCAB_STORE",
                "Failed to clear vocabulary",
                e
            )
        }
    }


    // =========================================================
    // WORKSHEET GENERATION
    // =========================================================

    fun generateWorksheet(
        title: String,
        pairs: List<Pair<String, String>>
    ): String {

        return try {
            val module =
                python.getModule("worksheet_generator")

            // Convert Kotlin pairs into:
            // [[hindi, santali], [hindi, santali], ...]

            val pythonPairs =
                pairs.map {
                    listOf(
                        it.first,
                        it.second
                    )
                }

            val result = module.callAttr(
                "generate_worksheet",
                title,
                pythonPairs
            )

            android.util.Log.d(
                "WORKSHEET_GEN",
                "Worksheet generated: $result"
            )

            result.toString()

        } catch (e: Exception) {

            android.util.Log.e(
                "WORKSHEET_GEN",
                "Failed to generate worksheet",
                e
            )

            ""
        }
    }


    // =========================================================
    // FLASHCARD GENERATION
    // =========================================================

    fun generateFlashcards(
        pairs: List<Pair<String, String>>
    ): String {

        return try {
            val module =
                python.getModule("flashcard_generator")

            val pythonPairs =
                pairs.map {
                    listOf(
                        it.first,
                        it.second
                    )
                }

            val result = module.callAttr(
                "generate_flashcards",
                pythonPairs
            )

            android.util.Log.d(
                "FLASHCARD_GEN",
                "Flashcards generated: $result"
            )

            result.toString()

        } catch (e: Exception) {

            android.util.Log.e(
                "FLASHCARD_GEN",
                "Failed to generate flashcards",
                e
            )

            ""
        }
    }
}