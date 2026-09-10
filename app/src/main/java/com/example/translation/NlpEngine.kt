package com.example.translation

import com.chaquo.python.Python

object NlpEngine {

    private val python: Python by lazy {
        Python.getInstance()
    }

    fun translateHindiToSantali(text: String): String {
        val startTime = System.nanoTime()

        return try {
            val python = Python.getInstance()
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
}