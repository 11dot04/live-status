package com.github.jimmy90109.livestatus

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class ProcessTextActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val selectedText = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.trim()

        if (!selectedText.isNullOrBlank() && selectedText.split("\\s+".toRegex()).size <= 3) {
            fetchAndShowDefinition(selectedText)
        }

        finish()
    }

    private fun fetchAndShowDefinition(query: String) {
        val appContext = applicationContext
        thread {
            runCatching {
                val url = URL("https://api.dictionaryapi.dev/api/v2/entries/en/${java.net.URLEncoder.encode(query, "UTF-8")}")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3000
                    readTimeout = 3000
                    requestMethod = "GET"
                }

                if (conn.responseCode == 200) {
                    val response = conn.inputStream.bufferedReader().use { it.readText() }
                    val root = JSONArray(response).getJSONObject(0)
                    val word = root.optString("word", query)
                    val firstMeaning = root.getJSONArray("meanings").getJSONObject(0)
                    val partOfSpeech = firstMeaning.optString("partOfSpeech", "")
                    val definition = firstMeaning.getJSONArray("definitions")
                        .getJSONObject(0)
                        .getString("definition")

                    val pill = if (partOfSpeech.isNotBlank()) {
                        "${partOfSpeech.take(3)} • $word".take(10)
                    } else {
                        word.take(10)
                    }

                    LiveStatusReminder.showCustomCapsule(
                        context = appContext,
                        pillText = pill,
                        iconName = "ic_capsule_search",
                        title = word.replaceFirstChar { it.uppercase() },
                        content = definition,
                        timeoutSeconds = 8
                    )
                }
            }
        }
    }
}
