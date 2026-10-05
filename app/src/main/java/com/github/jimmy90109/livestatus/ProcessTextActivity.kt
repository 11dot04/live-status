package com.github.jimmy90109.livestatus

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class ProcessTextActivity : Activity() {

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rawText = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.trim()

        if (rawText.isNullOrBlank()) {
            finish()
            return
        }

        // Clean out punctuation/whitespace and isolate target word
        val cleanWord = rawText.replace(Regex("[^a-zA-Z0-9\\s-]"), "").trim().split("\\s+".toRegex()).firstOrNull() ?: ""

        if (cleanWord.isBlank()) {
            finish()
            return
        }

        val appContext = applicationContext

        thread {
            try {
                val encoded = java.net.URLEncoder.encode(cleanWord.lowercase(), "UTF-8")
                // Datamuse API: lightweight, fast, returns definitions via md=d flag
                val apiUrl = URL("https://api.datamuse.com/words?sp=$encoded&md=d&max=1")
                val conn = (apiUrl.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile)")
                    setRequestProperty("Accept", "application/json")
                }

                val code = conn.responseCode
                if (code == 200) {
                    val response = conn.inputStream.bufferedReader().use { it.readText() }
                    val array = JSONArray(response)

                    if (array.length() > 0) {
                        val firstEntry = array.getJSONObject(0)
                        val word = firstEntry.optString("word", cleanWord)
                        val defs = firstEntry.optJSONArray("defs")

                        if (defs != null && defs.length() > 0) {
                            // Datamuse formats definitions as: "n\tdefinition text" or "adj\tdefinition text"
                            val rawDef = defs.getString(0)
                            val parts = rawDef.split("\t", limit = 2)
                            val partOfSpeech = if (parts.size > 1) parts[0].trim() else ""
                            val definitionText = if (parts.size > 1) parts[1].trim() else parts[0].trim()

                            val pillLabel = if (partOfSpeech.isNotBlank()) {
                                "${partOfSpeech.take(3)} • $word"
                            } else {
                                word
                            }

                            mainHandler.post {
                                LiveStatusReminder.showCustomCapsule(
                                    context = appContext,
                                    pillText = pillLabel.take(12),
                                    iconName = "ic_capsule_search",
                                    title = word.replaceFirstChar { it.uppercase() },
                                    content = definitionText,
                                    timeoutSeconds = 8
                                )
                            }
                        } else {
                            mainHandler.post {
                                Toast.makeText(appContext, "No definition found", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        mainHandler.post {
                            Toast.makeText(appContext, "Word not found", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    mainHandler.post {
                        Toast.makeText(appContext, "Lookup error ($code)", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("ProcessText", "Lookup failure", e)
                mainHandler.post {
                    Toast.makeText(appContext, "Network error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                mainHandler.post {
                    finish()
                }
            }
        }
    }
}
