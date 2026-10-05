package com.github.jimmy90109.livestatus

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class ProcessTextActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val selectedText = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.trim()

        if (selectedText.isNullOrBlank()) {
            finish()
            return
        }

        // Clean out trailing punctuation and whitespace
        val cleanWord = selectedText.replace(Regex("[^a-zA-Z0-9\\s-]"), "").trim()
        val appContext = applicationContext

        thread {
            try {
                val encoded = java.net.URLEncoder.encode(cleanWord.lowercase(), "UTF-8")
                val apiUrl = URL("https://api.dictionaryapi.dev/api/v2/entries/en/$encoded")
                val conn = (apiUrl.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 5000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "LiveStatus-Android/1.0")
                    setRequestProperty("Accept", "application/json")
                }

                val responseCode = conn.responseCode
                if (responseCode == 200) {
                    val stream = BufferedReader(InputStreamReader(conn.inputStream))
                    val response = stream.use { it.readText() }
                    val jsonArray = JSONArray(response)
                    val root = jsonArray.getJSONObject(0)
                    val word = root.optString("word", cleanWord)
                    val meanings = root.getJSONArray("meanings")
                    val firstMeaning = meanings.getJSONObject(0)
                    val partOfSpeech = firstMeaning.optString("partOfSpeech", "")
                    val definitions = firstMeaning.getJSONArray("definitions")
                    val definitionText = definitions.getJSONObject(0).getString("definition")

                    val pillLabel = if (partOfSpeech.isNotBlank()) {
                        "${partOfSpeech.take(3)} • $word"
                    } else {
                        word
                    }

                    LiveStatusReminder.showCustomCapsule(
                        context = appContext,
                        pillText = pillLabel.take(12),
                        iconName = "ic_capsule_search",
                        title = word.replaceFirstChar { it.uppercase() },
                        content = definitionText,
                        timeoutSeconds = 8
                    )
                } else {
                    Log.w("ProcessText", "Dictionary API returned code: $responseCode")
                }
            } catch (e: Exception) {
                Log.e("ProcessText", "Failed to fetch definition", e)
            } finally {
                runOnUiThread {
                    finish()
                }
            }
        }
    }
}
