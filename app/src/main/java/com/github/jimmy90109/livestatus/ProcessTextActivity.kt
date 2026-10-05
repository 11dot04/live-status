package com.github.jimmy90109.livestatus

import android.app.Activity
import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
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

        // 1. Color Preview (e.g., #FFF, #FFFFFF, 0xFFFFFF)
        val hexRegex = Regex("(?i)^#?([0-9a-f]{6}|[0-9a-f]{3})$")
        val hexMatch = hexRegex.find(rawText)
        if (hexMatch != null) {
            handleColorLookup(hexMatch.groupValues[1])
            return
        }

        // 2. Inline Math / Arithmetic (e.g., 4+3, 12 * 8.5, 1250 / 4)
        val mathRegex = Regex("""^\s*(-?\d+(?:\.\d+)?)\s*([\+\-\*\/xX×÷])\s*(-?\d+(?:\.\d+)?)\s*$""")
        val mathMatch = mathRegex.find(rawText)
        if (mathMatch != null) {
            handleMathEvaluation(
                mathMatch.groupValues[1],
                mathMatch.groupValues[2],
                mathMatch.groupValues[3],
                rawText
            )
            return
        }

        // 3. Date / Countdown Inspector (e.g., "24 Oct", "2026-10-24", "24/10/2026")
        if (handleDateLookup(rawText)) {
            return
        }

        // 4. Currency Converter ($50, 50 USD, €20, £15, etc.)
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

        // 5. Fallback: Dictionary + Short Synonym
        handleDictionaryLookup(rawText)
    }

    private fun getDismissAction(): Notification.Action {
        val dismissIntent = PendingIntent.getBroadcast(
            applicationContext,
            9002,
            Intent(applicationContext, CapsuleDismissReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Action.Builder(null, "Dismiss", dismissIntent).build()
    }

    // --- Dynamic Bitmaps ---

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

    private fun createRupeeIcon(): Icon {
        val size = 64
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1B5E20") // Dark green accent
            style = Paint.Style.FILL
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, bgPaint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 36f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        val yPos = (canvas.height / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f)
        canvas.drawText("₹", canvas.width / 2f, yPos, textPaint)
        return Icon.createWithBitmap(bitmap)
    }

    // --- Modules ---

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

            LiveStatusReminder.showCustomCapsule(
                context = applicationContext,
                pillText = "#${fullHex.uppercase(Locale.ROOT)}",
                iconName = null,
                title = "Color Swatch",
                content = "RGB: ($r, $g, $b) • HEX: #${fullHex.uppercase(Locale.ROOT)}",
                timeoutSeconds = 15,
                customIcon = createColorDotIcon(parsedColor),
                actions = listOf(getDismissAction())
            )
        } catch (_: Exception) {
            Toast.makeText(this, "Invalid Color", Toast.LENGTH_SHORT).show()
        } finally {
            finish()
        }
    }

    private fun handleMathEvaluation(numA: String, rawOp: String, numB: String, originalText: String) {
        val a = numA.toDoubleOrNull()
        val b = numB.toDoubleOrNull()
        if (a == null || b == null) {
            finish()
            return
        }

        val op = when (rawOp) {
            "x", "X", "×" -> "*"
            "÷" -> "/"
            else -> rawOp
        }

        val result = when (op) {
            "+" -> a + b
            "-" -> a - b
            "*" -> a * b
            "/" -> if (b != 0.0) a / b else null
            else -> null
        }

        if (result != null) {
            val formatted = if (result % 1.0 == 0.0) {
                result.toLong().toString()
            } else {
                String.format(Locale.ROOT, "%.3f", result).trimEnd('0').trimEnd('.')
            }

            LiveStatusReminder.showCustomCapsule(
                context = applicationContext,
                pillText = "= $formatted",
                iconName = "ic_capsule_search",
                title = "Calculation",
                content = "$originalText = $formatted",
                timeoutSeconds = 15,
                actions = listOf(getDismissAction())
            )
        }
        finish()
    }

    private fun handleDateLookup(rawText: String): Boolean {
        val datePatterns = listOf(
            "dd MMM yyyy", "dd MMMM yyyy", "dd MMM", "dd MMMM",
            "yyyy-MM-dd", "dd/MM/yyyy", "dd-MM-yyyy", "MM/dd/yyyy"
        )

        val cleanDateText = rawText.trim()
        val now = Calendar.getInstance()
        var parsedDate: Date? = null

        for (pattern in datePatterns) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.ENGLISH).apply { isLenient = false }
                val d = sdf.parse(cleanDateText)
                if (d != null) {
                    val cal = Calendar.getInstance().apply { time = d }
                    // Default to current year if omitted
                    if (!pattern.contains("y")) {
                        cal.set(Calendar.YEAR, now.get(Calendar.YEAR))
                        if (cal.before(now)) cal.add(Calendar.YEAR, 1)
                    }
                    parsedDate = cal.time
                    break
                }
            } catch (_: Exception) { }
        }

        if (parsedDate == null) return false

        val diffMillis = parsedDate.time - now.timeInMillis
        val daysDiff = TimeUnit.MILLISECONDS.toDays(diffMillis).toInt()

        val pillText = when {
            daysDiff == 0 -> "Today"
            daysDiff == 1 -> "Tomorrow"
            daysDiff > 1 -> "in ${daysDiff}d"
            daysDiff == -1 -> "Yesterday"
            else -> "${Math.abs(daysDiff)}d ago"
        }

        val formattedDateDisplay = SimpleDateFormat("EEE, dd MMM yyyy", Locale.getDefault()).format(parsedDate)

        LiveStatusReminder.showCustomCapsule(
            context = applicationContext,
            pillText = pillText,
            iconName = "ic_capsule_search",
            title = "Date Inspector",
            content = "$formattedDateDisplay ($pillText)",
            timeoutSeconds = 15,
            actions = listOf(getDismissAction())
        )
        finish()
        return true
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
                // Free, open rates updated constantly against central bank daily feeds
                val url = URL("https://open.er-api.com/v6/latest/$baseCurrency")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 4000
                    readTimeout = 4000
                }

                if (conn.responseCode == 200) {
                    val resp = conn.inputStream.bufferedReader().use { it.readText() }
                    val rates = JSONObject(resp).getJSONObject("rates")

                    val targetRate = if (baseCurrency == "INR") rates.optDouble("USD", 0.0) else rates.optDouble("INR", 0.0)
                    val isTargetInr = baseCurrency != "INR"
                    val converted = amount * targetRate

                    val formatted = if (converted >= 100) {
                        String.format(Locale.ROOT, "%.0f", converted)
                    } else {
                        String.format(Locale.ROOT, "%.2f", converted)
                    }

                    // Save 3 characters by using ₹ symbol and custom Rupee icon
                    val pill = if (isTargetInr) "≈ ₹$formatted" else "≈ $$formatted"
                    val icon = if (isTargetInr) createRupeeIcon() else null

                    mainHandler.post {
                        LiveStatusReminder.showCustomCapsule(
                            context = appContext,
                            pillText = pill,
                            iconName = if (icon == null) "ic_capsule_search" else null,
                            title = "$amount $baseCurrency Conversion",
                            content = "$amount $baseCurrency = ${if (isTargetInr) "₹" else "$"}$formatted",
                            timeoutSeconds = 20,
                            customIcon = icon,
                            actions = listOf(getDismissAction())
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("ProcessText", "Forex error", e)
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

                val cleanDefinition = definitionText
                    .replace(Regex("^\\([^)]*\\)\\s*"), "")
                    .replaceFirstChar { it.uppercase() }

                // 2. Fetch short synonym
                val synUrl = URL("https://api.datamuse.com/words?rel_syn=$encoded&max=6")
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
                    shortestSynonym = synList.filter { it.length <= 7 }.minByOrNull { it.length }
                }

                // Format: "adj • syn" or "n • word"
                val displayWord = shortestSynonym ?: cleanWord
                val posPrefix = if (partOfSpeech.isNotBlank()) "${partOfSpeech.take(3)} • " else ""
                val pill = "$posPrefix$displayWord".take(12)

                val webIntent = PendingIntent.getActivity(
                    appContext,
                    cleanWord.hashCode(),
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=define+$encoded")),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val webAction = Notification.Action.Builder(null, "Dictionary", webIntent).build()

                val wordCount = cleanDefinition.split("\\s+".toRegex()).size
                val calculatedTimeout = (10 + (wordCount * 1.2)).toInt().coerceIn(15, 60)

                mainHandler.post {
                    LiveStatusReminder.showCustomCapsule(
                        context = appContext,
                        pillText = pill,
                        iconName = "ic_capsule_search",
                        title = "${cleanWord.replaceFirstChar { it.uppercase() }} ($partOfSpeech)",
                        content = cleanDefinition,
                        timeoutSeconds = calculatedTimeout,
                        actions = listOf(getDismissAction(), webAction)
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
