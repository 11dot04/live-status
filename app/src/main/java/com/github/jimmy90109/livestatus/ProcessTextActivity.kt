package com.github.jimmy90109.livestatus

import android.app.Activity
import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
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

        // 1. Color Preview (e.g. #FFF, #FFFFFF, 0xFFFFFF)
        val hexRegex = Regex("(?i)^#?([0-9a-f]{6}|[0-9a-f]{3})$")
        val hexMatch = hexRegex.find(rawText)
        if (hexMatch != null) {
            handleColorLookup(hexMatch.groupValues[1])
            return
        }

        // 2. Quick Currency Converter (e.g., $50, 50 USD, €20, £15, ₹1200)
        val currencyRegex = Regex("(?i)^([$€£¥₹])?\\s*(\\d+(?:\\.\\d+)?)\\s*([a-z]{3})?$")
        val currMatch = currencyRegex.find(rawText)
        if (currMatch != null) {
            val symbol = currMatch.groupValues[1]
            val amount = currMatch.groupValues[2].toDoubleOrNull()
            val code = currMatch.groupValues[3].uppercase(Locale.ROOT)
            if (amount != null && (symbol.isNotBlank() || code.isNotBlank())) {
                handleCurrencyLookup(amount, symbol, code)
                return
            }
        }

        // 3. Word Definition & Synonym Lookup
        handleDictionaryLookup(rawText)
    }

    private fun createColorDotIcon(colorInt: Int): Icon {
        val size = 64
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorInt
            style = Paint.Style.FILL
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        return Icon.createWithBitmap(bitmap)
    }

    private fun handleColorLookup(hexPart: String) {
        try {
            val fullHex = if (hexPart.length == 3) {
                "${hexPart[0]}${hexPart[0]}${hexPart[1]}${hexPart[1]}${hexPart[2]}${hexPart[2]}"
            } else {
                hexPart
            }
            val parsedColor = Color.parseColor("#$fullHex")
            val r = Color.red(parsedColor)
            val g = Color.green(parsedColor)
            val b = Color.blue(parsedColor)
            val dotIcon = createColorDotIcon(parsedColor)

            val dismissIntent = PendingIntent.getBroadcast(
                applicationContext,
                9002,
                Intent(applicationContext, CapsuleDismissReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val dismissAction = Notification.Action.Builder(null, "Dismiss", dismissIntent).build()

            LiveStatusReminder.showCustomCapsule(
                context = applicationContext,
                pillText = "#${fullHex.uppercase(Locale.ROOT)}",
                iconName = null,
                title = "Color Swatch",
                content = "RGB: ($r, $g, $b) • HEX: #${fullHex.uppercase(Locale.ROOT)}",
                timeoutSeconds = 15,
                customIcon = dotIcon,
                actions = listOf(dismissAction)
            )
        } catch (_: Exception) {
            Toast.makeText(this, "Invalid Color", Toast.LENGTH_SHORT).show()
        } finally {
            finish()
        }
    }

    private fun handleCurrencyLookup(amount: Double, symbol: String, code: String) {
        val appContext = applicationContext
        val baseCurrency = when {
            symbol == "$" || code == "USD" -> "USD"
            symbol == "€" || code == "EUR" -> "EUR"
            symbol == "£" || code == "GBP" -> "GBP"
            symbol == "₹" || code == "INR" -> "INR"
            code.isNotBlank() -> code
            else -> "USD"
        }

        thread {
            try {
                val url = URL("https://open.er-api.com/v6/latest/$baseCurrency")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 4000
                    readTimeout = 4000
                }

                if (conn.responseCode == 200) {
                    val resp = conn.inputStream.bufferedReader().use { it.readText() }
                    val rates = org.json.JSONObject(resp).getJSONObject("rates")

                    val targetRate = if (baseCurrency == "INR") rates.optDouble("USD", 0.0) else rates.optDouble("INR", 0.0)
                    val targetCode = if (baseCurrency == "INR") "USD" else "INR"
                    val converted = amount * targetRate

                    val formattedTarget = String.format(Locale.ROOT, "%.2f", converted)
                    val pill = "≈ $targetCode $formattedTarget"

                    val dismissIntent = PendingIntent.getBroadcast(
                        appContext,
                        9002,
                        Intent(appContext, CapsuleDismissReceiver::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    val dismissAction = Notification.Action.Builder(null, "Dismiss", dismissIntent).build()

                    mainHandler.post {
                        LiveStatusReminder.showCustomCapsule(
                            context = appContext,
                            pillText = pill.take(12),
                            iconName = "ic_capsule_payment",
                            title = "$amount $baseCurrency Conversion",
                            content = "$amount $baseCurrency = $targetCode $formattedTarget",
                            timeoutSeconds = 20,
                            actions = listOf(dismissAction)
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("ProcessText", "Currency conversion failed", e)
            } finally {
                mainHandler.post { finish() }
            }
        }
    }

    private fun handleDictionaryLookup(rawText: String) {
        val cleanWord = rawText.replace(Regex("[^a-zA-Z0-9\\s-]"), "").trim().split("\\s+".toRegex()).firstOrNull() ?: ""

        if (cleanWord.isBlank()) {
            finish()
            return
        }

        val appContext = applicationContext

        thread {
            try {
                val encoded = java.net.URLEncoder.encode(cleanWord.lowercase(Locale.ROOT), "UTF-8")

                // 1. Definition lookup
                val defUrl = URL("https://api.datamuse.com/words?sp=$encoded&md=d&max=1")
                val defConn = (defUrl.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 6000
                    readTimeout = 6000
                    setRequestProperty("User-Agent", "LiveStatus-Android/1.0")
                }

                var definitionText = ""
                var partOfSpeech = ""

                if (defConn.responseCode == 200) {
                    val resp = defConn.inputStream.bufferedReader().use { it.readText() }
                    val array = JSONArray(resp)
                    if (array.length() > 0) {
                        val firstEntry = array.getJSONObject(0)
                        val defs = firstEntry.optJSONArray("defs")
                        if (defs != null && defs.length() > 0) {
                            val rawDef = defs.getString(0)
                            val parts = rawDef.split("\t", limit = 2)
                            if (parts.size > 1) {
                                partOfSpeech = parts[0].trim()
                                definitionText = parts[1].trim()
                            } else {
                                definitionText = parts[0].trim()
                            }
                        }
                    }
                }

                if (definitionText.isBlank()) {
                    mainHandler.post {
                        Toast.makeText(appContext, "No definition found", Toast.LENGTH_SHORT).show()
                    }
                    return@thread
                }

                // Strip domain/parenthetical clutter from the front
                val cleanDefinition = definitionText
                    .replace(Regex("^\\([^)]*\\)\\s*"), "")
                    .replaceFirstChar { it.uppercase() }

                // 2. Shortest synonym lookup for pill
                val synUrl = URL("https://api.datamuse.com/words?rel_syn=$encoded&max=5")
                val synConn = (synUrl.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 4000
                    readTimeout = 4000
                    setRequestProperty("User-Agent", "LiveStatus-Android/1.0")
                }

                var shortestSynonym: String? = null
                if (synConn.responseCode == 200) {
                    val synResp = synConn.inputStream.bufferedReader().use { it.readText() }
                    val synArray = JSONArray(synResp)
                    val synList = mutableListOf<String>()
                    for (i in 0 until synArray.length()) {
                        val word = synArray.getJSONObject(i).optString("word")
                        if (word.isNotBlank() && !word.contains(" ")) {
                            synList.add(word)
                        }
                    }
                    shortestSynonym = synList.filter { it.length <= 9 }.minByOrNull { it.length }
                }

                val pill = if (!shortestSynonym.isNullOrBlank()) {
                    "≈ $shortestSynonym"
                } else {
                    cleanWord.take(10)
                }

                val titleFormatted = if (partOfSpeech.isNotBlank()) {
                    "${cleanWord.replaceFirstChar { it.uppercase() }} ($partOfSpeech)"
                } else {
                    cleanWord.replaceFirstChar { it.uppercase() }
                }

                // Action 1: Dismiss
                val dismissIntent = PendingIntent.getBroadcast(
                    appContext,
                    9002,
                    Intent(appContext, CapsuleDismissReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val dismissAction = Notification.Action.Builder(null, "Dismiss", dismissIntent).build()

                // Action 2: Open full Google definition in browser
                val webIntent = PendingIntent.getActivity(
                    appContext,
                    cleanWord.hashCode(),
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=define+$encoded")),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val webAction = Notification.Action.Builder(null, "Full Dictionary", webIntent).build()

                // Adaptive reading time: 10s baseline + 1.2s per word
                val wordCount = cleanDefinition.split("\\s+".toRegex()).size
                val calculatedTimeout = (10 + (wordCount * 1.2)).toInt().coerceIn(15, 60)

                mainHandler.post {
                    LiveStatusReminder.showCustomCapsule(
                        context = appContext,
                        pillText = pill,
                        iconName = "ic_capsule_search",
                        title = titleFormatted,
                        content = cleanDefinition,
                        timeoutSeconds = calculatedTimeout,
                        actions = listOf(dismissAction, webAction)
                    )
                }
            } catch (e: Exception) {
                Log.e("ProcessText", "Lookup error", e)
                mainHandler.post {
                    Toast.makeText(appContext, "Network error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                mainHandler.post { finish() }
            }
        }
    }
}
