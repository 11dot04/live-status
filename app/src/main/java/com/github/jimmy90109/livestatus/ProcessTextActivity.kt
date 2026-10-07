package com.github.jimmy90109.livestatus

import android.app.Activity
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
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
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class ProcessTextActivity : Activity() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val INSPECT_NOTIFICATION_ID = 9003

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rawText = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.trim()

        if (rawText.isNullOrBlank()) {
            finish()
            return
        }

        // 1. Reading Speed & Text Statistics (>= 25 words)
        val wordList = rawText.split(Regex("""\s+""")).filter { it.isNotBlank() }
        if (wordList.size >= 25) {
            handleReadingStats(rawText, wordList)
            return
        }

        // 2. Color Swatch Preview (#FFF, #FFFFFF, etc.)
        val hexRegex = Regex("(?i)^#?([0-9a-f]{6}|[0-9a-f]{3})$")
        val hexMatch = hexRegex.find(rawText)
        if (hexMatch != null) {
            handleColorLookup(hexMatch.groupValues[1])
            return
        }

        // 3. Time Zone Instant Teleport (e.g., 4:30 PM EST, 18:00 UTC, 9 AM PST)
        val timezoneRegex = Regex("""(?i)^\s*(\d{1,2})(?::(\d{2}))?\s*(am|pm)?\s*(est|edt|pst|pdt|cst|cdt|gmt|utc|ist|jst|bst)\s*$""")
        val tzMatch = timezoneRegex.find(rawText)
        if (tzMatch != null) {
            handleTimezoneLookup(tzMatch, rawText)
            return
        }

        // 4. Inline Math (e.g. 4+3, 12 * 8.5)
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

        // 5. Date / Relative Day Inspector
        if (handleDateLookup(rawText)) {
            return
        }

        // 6. Currency Converter ($50, 50 USD, €20, etc.)
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

        // 7. Scientific Physical Constants
        if (handleScientificConstant(rawText)) {
            return
        }

        // 8. Unit Conversions: Local fast-path with API fallback
        val unitMatch = Regex("""(?i)^\s*(\d+(?:\.\d+)?)\s*([a-zA-Z°/³²]{1,12})\s*$""").find(rawText)
        if (unitMatch != null) {
            val valNum = unitMatch.groupValues[1].toDoubleOrNull()
            val unitStr = unitMatch.groupValues[2].lowercase(Locale.ROOT)
            if (valNum != null) {
                if (handleLocalUnitConversion(valNum, unitStr, rawText)) {
                    return
                } else {
                    // Fallback to DuckDuckGo conversion API in a background thread
                    thread {
                        if (!handleApiUnitConversion(rawText, valNum, unitStr)) {
                            dispatchScholarlyOrLexicon(rawText)
                        }
                    }
                    return
                }
            }
        }

        // 9. URLs, Scientific Lookups, or Lexicon
        thread {
            dispatchScholarlyOrLexicon(rawText)
        }
    }

    private fun dispatchScholarlyOrLexicon(rawText: String) {
        when {
            isUrl(rawText) -> handleUrlInspection(rawText)
            looksLikeBinomialTaxon(rawText) && handleGbifLookup(rawText) -> {}
            looksLikeChemicalOrDrug(rawText) && handlePubChemLookup(rawText) -> {}
            else -> handleDictionaryLookup(rawText)
        }
    }

    private fun isUrl(input: String): Boolean {
        return input.startsWith("http://", ignoreCase = true) ||
                input.startsWith("https://", ignoreCase = true) ||
                (input.contains(".") && !input.contains(" ") && input.length >= 4)
    }

    private fun looksLikeChemicalOrDrug(input: String): Boolean {
        val hasFormulaPattern = Regex("""^[A-Z][a-z]?\d*([A-Z][a-z]?\d*)+$""").matches(input)
        val hasDrugSuffix = listOf("olol", "cillin", "mab", "pril", "statin", "prazole", "sartan", "tidine", "ine", "ol")
            .any { input.lowercase(Locale.ROOT).endsWith(it) }
        return hasFormulaPattern || hasDrugSuffix
    }

    private fun looksLikeBinomialTaxon(input: String): Boolean {
        val parts = input.trim().split(" ")
        return parts.size == 2 && parts[0].first().isUpperCase() && parts[1].first().isLowerCase()
    }

    private fun getDismissAction(): Notification.Action {
        val dismissIntent = Intent(applicationContext, CapsuleDismissReceiver::class.java).apply {
            putExtra("notification_id", INSPECT_NOTIFICATION_ID)
        }
        val pIntent = PendingIntent.getBroadcast(
            applicationContext,
            INSPECT_NOTIFICATION_ID,
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Action.Builder(null, "Dismiss", pIntent).build()
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

    private fun handleReadingStats(rawText: String, words: List<String>) {
        val wordCount = words.size
        val charCount = rawText.length
        val minutes = wordCount / 230.0
        val secondsTotal = (minutes * 60).toInt()
        val m = secondsTotal / 60
        val s = secondsTotal % 60

        val pillTime = if (m == 0) "${s}s read" else "~${m}m read"
        val timeFormatted = if (m > 0) "${m}m ${s}s" else "${s}s"

        LiveStatusReminder.showCustomCapsule(
            context = applicationContext,
            pillText = pillTime,
            iconName = "ic_capsule_search",
            title = "Reading Estimate",
            content = "$wordCount words • $charCount chars • $timeFormatted read",
            timeoutSeconds = 12,
            actions = listOf(getDismissAction()),
            notificationId = INSPECT_NOTIFICATION_ID,
            detailPayload = LiveStatusReminder.InspectDetailPayload(
                domain = "LXCN",
                title = "READING METRICS",
                subtitle = "$wordCount words ($charCount characters)",
                fullContent = "Estimated reading time: $timeFormatted (at 230 wpm).\n\nText sample:\n${rawText.take(400)}...",
                copyText = rawText
            )
        )
        finish()
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

            LiveStatusReminder.showCustomCapsule(
                context = applicationContext,
                pillText = "#${fullHex.lowercase(Locale.ROOT)}",
                iconName = null,
                title = "Color Swatch",
                content = "RGB: ($r, $g, $b) • HEX: #${fullHex.lowercase(Locale.ROOT)}",
                timeoutSeconds = 15,
                customIcon = createColorDotIcon(parsedColor),
                actions = listOf(getDismissAction()),
                notificationId = INSPECT_NOTIFICATION_ID,
                detailPayload = LiveStatusReminder.InspectDetailPayload(
                    domain = "CHMSTRY",
                    title = "#${fullHex.uppercase(Locale.ROOT)}",
                    subtitle = "RGB ($r, $g, $b)",
                    fullContent = "HEX: #${fullHex.uppercase(Locale.ROOT)}\nRGB: rgb($r, $g, $b)\nAlpha: 255",
                    copyText = "#${fullHex.uppercase(Locale.ROOT)}"
                )
            )
        } catch (_: Exception) {
            Toast.makeText(this, "Invalid Color", Toast.LENGTH_SHORT).show()
        } finally {
            finish()
        }
    }

    private fun handleTimezoneLookup(match: MatchResult, original: String) {
        val hourRaw = match.groupValues[1].toIntOrNull() ?: return
        val minRaw = match.groupValues[2].toIntOrNull() ?: 0
        val ampm = match.groupValues[3].lowercase(Locale.ROOT)
        val tzStr = match.groupValues[4].uppercase(Locale.ROOT)

        var hour = hourRaw
        if (ampm == "pm" && hour < 12) hour += 12
        if (ampm == "am" && hour == 12) hour = 0

        val tzId = when (tzStr) {
            "EST", "EDT" -> "America/New_York"
            "PST", "PDT" -> "America/Los_Angeles"
            "CST", "CDT" -> "America/Chicago"
            "GMT", "UTC" -> "UTC"
            "BST" -> "Europe/London"
            "IST" -> "Asia/Kolkata"
            "JST" -> "Asia/Tokyo"
            else -> "UTC"
        }

        val sourceTz = TimeZone.getTimeZone(tzId)
        val cal = Calendar.getInstance(sourceTz).apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minRaw)
            set(Calendar.SECOND, 0)
        }

        val localFormat = SimpleDateFormat("h:mm a z", Locale.getDefault())
        localFormat.timeZone = TimeZone.getDefault()
        val localConverted = localFormat.format(cal.time)

        val pillFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
        pillFormat.timeZone = TimeZone.getDefault()

        LiveStatusReminder.showCustomCapsule(
            context = applicationContext,
            pillText = pillFormat.format(cal.time),
            iconName = "ic_capsule_search",
            title = "Time Conversion",
            content = "$original = $localConverted",
            timeoutSeconds = 12,
            actions = listOf(getDismissAction()),
            notificationId = INSPECT_NOTIFICATION_ID,
            detailPayload = LiveStatusReminder.InspectDetailPayload(
                domain = "LXCN",
                title = localConverted,
                subtitle = "Source: $original ($tzStr)",
                fullContent = "Local: $localConverted\nOriginal: $original\nZone: $tzId",
                copyText = localConverted
            )
        )
        finish()
    }

    private fun handleLocalUnitConversion(value: Double, unit: String, rawText: String): Boolean {
        val (convertedPill, convertedExpanded) = when {
            unit == "f" || unit.startsWith("fahrenheit") -> {
                val c = (value - 32.0) * 5.0 / 9.0
                String.format(Locale.ROOT, "%.1f°C", c) to "$value°F = ${String.format(Locale.ROOT, "%.1f", c)}°C"
            }
            unit == "c" || unit.startsWith("celsius") -> {
                val f = (value * 9.0 / 5.0) + 32.0
                String.format(Locale.ROOT, "%.1f°F", f) to "$value°C = ${String.format(Locale.ROOT, "%.1f", f)}°F"
            }
            unit == "lb" || unit == "lbs" || unit.startsWith("pound") -> {
                val kg = value * 0.45359237
                String.format(Locale.ROOT, "%.1f kg", kg) to "$value lbs = ${String.format(Locale.ROOT, "%.2f", kg)} kg"
            }
            unit == "stone" || unit == "st" -> {
                val kg = value * 6.35029
                String.format(Locale.ROOT, "%.1f kg", kg) to "$value stone = ${String.format(Locale.ROOT, "%.2f", kg)} kg"
            }
            unit == "oz" || unit.startsWith("ounce") -> {
                val g = value * 28.3495
                String.format(Locale.ROOT, "%.0f g", g) to "$value oz = ${String.format(Locale.ROOT, "%.1f", g)} g"
            }
            unit == "ft" || unit.startsWith("feet") -> {
                val cm = value * 30.48
                String.format(Locale.ROOT, "%.0f cm", cm) to "$value ft = ${String.format(Locale.ROOT, "%.1f", cm)} cm"
            }
            unit.startsWith("in") -> {
                val cm = value * 2.54
                String.format(Locale.ROOT, "%.1f cm", cm) to "$value in = ${String.format(Locale.ROOT, "%.2f", cm)} cm"
            }
            unit == "mi" || unit.startsWith("mile") -> {
                val km = value * 1.60934
                String.format(Locale.ROOT, "%.1f km", km) to "$value mi = ${String.format(Locale.ROOT, "%.2f", km)} km"
            }
            unit == "mph" -> {
                val kmh = value * 1.60934
                String.format(Locale.ROOT, "%.0f km/h", kmh) to "$value mph = ${String.format(Locale.ROOT, "%.1f", kmh)} km/h"
            }
            unit == "knot" || unit == "knots" -> {
                val kmh = value * 1.852
                String.format(Locale.ROOT, "%.0f km/h", kmh) to "$value knots = ${String.format(Locale.ROOT, "%.1f", kmh)} km/h"
            }
            unit == "psi" -> {
                val bar = value * 0.0689476
                String.format(Locale.ROOT, "%.2f bar", bar) to "$value psi = ${String.format(Locale.ROOT, "%.3f", bar)} bar"
            }
            unit == "bar" -> {
                val psi = value * 14.5038
                String.format(Locale.ROOT, "%.1f psi", psi) to "$value bar = ${String.format(Locale.ROOT, "%.2f", psi)} psi"
            }
            else -> return false
        }

        LiveStatusReminder.showCustomCapsule(
            context = applicationContext,
            pillText = convertedPill,
            iconName = "ic_capsule_search",
            title = "Unit Conversion",
            content = convertedExpanded,
            timeoutSeconds = 12,
            actions = listOf(getDismissAction()),
            notificationId = INSPECT_NOTIFICATION_ID,
            detailPayload = LiveStatusReminder.InspectDetailPayload(
                domain = "CHMSTRY",
                title = convertedPill,
                subtitle = rawText,
                fullContent = convertedExpanded,
                copyText = convertedPill
            )
        )
        finish()
        return true
    }

    private fun handleApiUnitConversion(rawText: String, value: Double, unit: String): Boolean {
        return try {
            val query = URLEncoder.encode("$rawText in metric", "UTF-8")
            val url = URL("https://api.duckduckgo.com/?q=$query&format=json&no_html=1")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 3000
                readTimeout = 3000
            }
            if (conn.responseCode != 200) return false

            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            var answer = json.optString("Answer", "")
            if (answer.isBlank()) answer = json.optString("AbstractText", "")
            if (answer.isBlank()) return false

            val cleanAnswer = answer.replace(Regex("""<[^>]*>"""), "").trim()
            mainHandler.post {
                LiveStatusReminder.showCustomCapsule(
                    context = applicationContext,
                    pillText = cleanAnswer.take(16),
                    iconName = "ic_capsule_search",
                    title = "Unit Conversion",
                    content = cleanAnswer,
                    timeoutSeconds = 14,
                    actions = listOf(getDismissAction()),
                    notificationId = INSPECT_NOTIFICATION_ID,
                    detailPayload = LiveStatusReminder.InspectDetailPayload(
                        domain = "CHMSTRY",
                        title = cleanAnswer,
                        subtitle = rawText,
                        fullContent = "$rawText = $cleanAnswer",
                        copyText = cleanAnswer
                    )
                )
                finish()
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun handleUrlInspection(rawUrl: String) {
        val target = if (!rawUrl.startsWith("http")) "https://$rawUrl" else rawUrl
        try {
            var currentUrl = URL(target)
            var connection = currentUrl.openConnection() as HttpURLConnection
            connection.requestMethod = "HEAD"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 3500
            connection.readTimeout = 3500

            var redirects = 0
            while (redirects < 5) {
                connection.connect()
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location") ?: break
                    currentUrl = URL(currentUrl, location)
                    connection.disconnect()
                    connection = currentUrl.openConnection() as HttpURLConnection
                    connection.requestMethod = "HEAD"
                    connection.instanceFollowRedirects = false
                    redirects++
                } else {
                    break
                }
            }
            val finalHost = currentUrl.host.removePrefix("www.")
            val finalDestination = currentUrl.toString()
            connection.disconnect()

            mainHandler.post {
                LiveStatusReminder.showCustomCapsule(
                    context = applicationContext,
                    pillText = finalHost,
                    iconName = "ic_capsule_search",
                    title = finalHost,
                    content = finalDestination,
                    timeoutSeconds = 15,
                    actions = listOf(getDismissAction()),
                    notificationId = INSPECT_NOTIFICATION_ID,
                    detailPayload = LiveStatusReminder.InspectDetailPayload(
                        domain = "URL",
                        title = finalHost,
                        subtitle = "Redirect Resolved",
                        fullContent = "Target:\n$finalDestination\n\nOriginal:\n$rawUrl",
                        copyText = finalDestination
                    )
                )
                finish()
            }
        } catch (_: Exception) {
            val fallbackHost = runCatching { URL(target).host.removePrefix("www.") }.getOrDefault("LINK")
            mainHandler.post {
                LiveStatusReminder.showCustomCapsule(
                    context = applicationContext,
                    pillText = fallbackHost,
                    iconName = "ic_capsule_search",
                    title = fallbackHost,
                    content = rawUrl,
                    timeoutSeconds = 12,
                    actions = listOf(getDismissAction()),
                    notificationId = INSPECT_NOTIFICATION_ID,
                    detailPayload = LiveStatusReminder.InspectDetailPayload(
                        domain = "URL",
                        title = fallbackHost,
                        subtitle = "Raw Link",
                        fullContent = rawUrl,
                        copyText = rawUrl
                    )
                )
                finish()
            }
        }
    }

    private fun handleScientificConstant(rawText: String): Boolean {
        val key = rawText.lowercase(Locale.ROOT).trim()
        val constants = mapOf(
            "c" to Triple("Speed of Light", "2.998 × 10⁸ m/s", "Speed of electromagnetic radiation in vacuum"),
            "h" to Triple("Planck Constant", "6.626 × 10⁻³⁴ J·s", "Quantum of electromagnetic action relating energy to frequency"),
            "hbar" to Triple("Reduced Planck", "1.055 × 10⁻³⁴ J·s", "ħ = h / (2π)"),
            "k_b" to Triple("Boltzmann Constant", "1.381 × 10⁻²³ J/K", "Relates kinetic energy of particles with temperature"),
            "kb" to Triple("Boltzmann Constant", "1.381 × 10⁻²³ J/K", "Relates kinetic energy of particles with temperature"),
            "n_a" to Triple("Avogadro Constant", "6.022 × 10²³ mol⁻¹", "Number of constituent particles in one mole"),
            "na" to Triple("Avogadro Constant", "6.022 × 10²³ mol⁻¹", "Number of constituent particles in one mole"),
            "g" to Triple("Gravitational Constant", "6.674 × 10⁻¹¹ N·m²/kg²", "Newtonian constant of gravitation"),
            "eps_0" to Triple("Vacuum Permittivity", "8.854 × 10⁻¹² F/m", "Capability of vacuum to permit electric fields"),
            "mu_0" to Triple("Vacuum Permeability", "1.257 × 10⁻⁶ N/A²", "Magnetic constant in free space")
        )

        val found = constants[key] ?: return false
        LiveStatusReminder.showCustomCapsule(
            context = applicationContext,
            pillText = found.second.split(" ").take(3).joinToString(" "),
            iconName = "ic_functions",
            title = found.first,
            content = "${found.second} • ${found.third}",
            timeoutSeconds = 15,
            actions = listOf(getDismissAction()),
            notificationId = INSPECT_NOTIFICATION_ID,
            detailPayload = LiveStatusReminder.InspectDetailPayload(
                domain = "CHMSTRY",
                title = found.second,
                subtitle = found.first,
                fullContent = "${found.second}\n\n${found.third}",
                copyText = found.second
            )
        )
        finish()
        return true
    }

    private fun handlePubChemLookup(term: String): Boolean {
        return try {
            val encoded = URLEncoder.encode(term, "UTF-8")
            val url = URL("https://pubchem.ncbi.nlm.nih.gov/rest/pug/compound/name/$encoded/property/MolecularFormula,MolecularWeight,IUPACName,Title/JSON")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 3000
                readTimeout = 3000
            }
            if (conn.responseCode != 200) return false

            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val props = json.getJSONObject("PropertyTable").getJSONArray("Properties").getJSONObject(0)

            val formula = props.optString("MolecularFormula", "")
            val weight = props.optDouble("MolecularWeight", 0.0)
            val name = props.optString("Title", term)
            val iupac = props.optString("IUPACName", "")

            val weightStr = if (weight > 0) String.format(Locale.ROOT, "%.2f g/mol", weight) else ""
            val bodyBuilder = StringBuilder()
            if (formula.isNotBlank()) bodyBuilder.append("Formula: $formula\n")
            if (iupac.isNotBlank()) bodyBuilder.append("IUPAC: $iupac\n")

            val domain = if (looksLikeChemicalOrDrug(term) && !formula.equals(term, ignoreCase = true)) "PHRM" else "CHMSTRY"

            mainHandler.post {
                LiveStatusReminder.showCustomCapsule(
                    context = applicationContext,
                    pillText = if (weightStr.isNotBlank()) weightStr else formula,
                    iconName = "ic_science",
                    title = name.uppercase(Locale.ROOT),
                    content = if (weightStr.isNotBlank()) "$formula • $weightStr" else formula,
                    timeoutSeconds = 18,
                    actions = listOf(getDismissAction()),
                    notificationId = INSPECT_NOTIFICATION_ID,
                    detailPayload = LiveStatusReminder.InspectDetailPayload(
                        domain = domain,
                        title = name.uppercase(Locale.ROOT),
                        subtitle = weightStr,
                        fullContent = bodyBuilder.toString().trim(),
                        copyText = "$name ($formula) $weightStr"
                    )
                )
                finish()
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun handleGbifLookup(term: String): Boolean {
        return try {
            val encoded = URLEncoder.encode(term, "UTF-8")
            val url = URL("https://api.gbif.org/v1/species/match?name=$encoded")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 3000
                readTimeout = 3000
            }
            if (conn.responseCode != 200) return false

            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val matchType = json.optString("matchType", "NONE")
            if (matchType == "NONE") return false

            val canonical = json.optString("canonicalName", term)
            val kingdom = json.optString("kingdom", "")
            val family = json.optString("family", "")
            val status = json.optString("status", "ACCEPTED")

            val subtitle = if (kingdom.isNotBlank() && family.isNotBlank()) "$kingdom • $family" else kingdom
            val body = "Taxon Status: $status\nFamily: $family\nClassification: $kingdom"

            mainHandler.post {
                LiveStatusReminder.showCustomCapsule(
                    context = applicationContext,
                    pillText = canonical,
                    iconName = "ic_eco",
                    title = canonical,
                    content = subtitle,
                    timeoutSeconds = 18,
                    actions = listOf(getDismissAction()),
                    notificationId = INSPECT_NOTIFICATION_ID,
                    detailPayload = LiveStatusReminder.InspectDetailPayload(
                        domain = "TXNMY",
                        title = canonical,
                        subtitle = subtitle,
                        fullContent = body,
                        copyText = "$canonical ($subtitle)"
                    )
                )
                finish()
            }
            true
        } catch (_: Exception) {
            false
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
                pillText = formatted,
                iconName = "ic_equals",
                title = "Calculation",
                content = "$originalText = $formatted",
                timeoutSeconds = 15,
                actions = listOf(getDismissAction()),
                notificationId = INSPECT_NOTIFICATION_ID,
                detailPayload = LiveStatusReminder.InspectDetailPayload(
                    domain = "CHMSTRY",
                    title = formatted,
                    subtitle = originalText,
                    fullContent = "$originalText = $formatted",
                    copyText = formatted
                )
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
            title = "Date",
            content = "$formattedDateDisplay ($pillText)",
            timeoutSeconds = 15,
            actions = listOf(getDismissAction()),
            notificationId = INSPECT_NOTIFICATION_ID,
            detailPayload = LiveStatusReminder.InspectDetailPayload(
                domain = "LXCN",
                title = pillText,
                subtitle = formattedDateDisplay,
                fullContent = "$formattedDateDisplay ($pillText)",
                copyText = formattedDateDisplay
            )
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

                    val iconName = if (isTargetInr) "ic_rupee" else "ic_capsule_search"
                    val displayResult = "${if (isTargetInr) "₹" else "$"}$formatted"

                    mainHandler.post {
                        LiveStatusReminder.showCustomCapsule(
                            context = appContext,
                            pillText = formatted,
                            iconName = iconName,
                            title = "$amount $baseCurrency Conversion",
                            content = "$amount $baseCurrency = $displayResult",
                            timeoutSeconds = 20,
                            actions = listOf(getDismissAction()),
                            notificationId = INSPECT_NOTIFICATION_ID,
                            detailPayload = LiveStatusReminder.InspectDetailPayload(
                                domain = "CHMSTRY",
                                title = displayResult,
                                subtitle = "$amount $baseCurrency Exchange Rate",
                                fullContent = "$amount $baseCurrency = $displayResult\nBase: $baseCurrency",
                                copyText = displayResult
                            )
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
        val originalWord = rawText.replace(Regex("[^a-zA-Z0-9\\s-]"), "").trim().split("\\s+".toRegex()).firstOrNull() ?: ""

        if (originalWord.isBlank()) {
            finish()
            return
        }

        val cleanWordLower = originalWord.lowercase(Locale.ROOT)
        val appContext = applicationContext

        try {
            val encoded = URLEncoder.encode(cleanWordLower, "UTF-8")
            val dictUrl = URL("https://api.dictionaryapi.dev/api/v2/entries/en/$encoded")
            val dictConn = (dictUrl.openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 4000
            }

            if (dictConn.responseCode == 200) {
                val resp = dictConn.inputStream.bufferedReader().use { it.readText() }
                val jsonArray = JSONArray(resp)
                val entry = jsonArray.getJSONObject(0)

                // Extract IPA phonetics
                var phonetic = entry.optString("phonetic", "")
                if (phonetic.isBlank()) {
                    val phoneticsArr = entry.optJSONArray("phonetics")
                    if (phoneticsArr != null) {
                        for (i in 0 until phoneticsArr.length()) {
                            val p = phoneticsArr.getJSONObject(i).optString("text", "")
                            if (p.isNotBlank()) {
                                phonetic = p
                                break
                            }
                        }
                    }
                }

                // Extract definitions across all parts of speech
                val meanings = entry.optJSONArray("meanings")
                val bodyBuilder = StringBuilder()
                val synonyms = mutableSetOf<String>()

                if (meanings != null) {
                    var counter = 1
                    for (i in 0 until meanings.length()) {
                        val meaningObj = meanings.getJSONObject(i)
                        val partOfSpeech = meaningObj.optString("partOfSpeech", "").uppercase(Locale.ROOT)
                        val defsArr = meaningObj.optJSONArray("definitions")

                        if (defsArr != null && defsArr.length() > 0) {
                            val firstDef = defsArr.getJSONObject(0).optString("definition", "")
                            bodyBuilder.append(String.format(Locale.ROOT, "%02d. %s\n%s\n\n", counter++, partOfSpeech, firstDef))
                        }

                        val synArr = meaningObj.optJSONArray("synonyms")
                        if (synArr != null) {
                            for (s in 0 until synArr.length()) {
                                val syn = synArr.optString(s, "")
                                if (syn.isNotBlank()) synonyms.add(syn.uppercase(Locale.ROOT))
                            }
                        }
                    }
                }

                if (synonyms.isNotEmpty()) {
                    bodyBuilder.append("[ SYN: ").append(synonyms.take(3).joinToString(", ")).append(" ]")
                }

                val fullEditorialBody = bodyBuilder.toString().trim()
                val shortestSyn = synonyms.minByOrNull { it.length }?.lowercase(Locale.ROOT)
                val pillText = shortestSyn ?: originalWord

                mainHandler.post {
                    LiveStatusReminder.showCustomCapsule(
                        context = appContext,
                        pillText = pillText,
                        iconName = "ic_capsule_search",
                        title = originalWord.replaceFirstChar { it.uppercase() },
                        content = phonetic.ifBlank { "Dictionary Lookup" },
                        timeoutSeconds = 25,
                        actions = listOf(getDismissAction()),
                        notificationId = INSPECT_NOTIFICATION_ID,
                        detailPayload = LiveStatusReminder.InspectDetailPayload(
                            domain = "LXCN",
                            title = originalWord.replaceFirstChar { it.uppercase() },
                            subtitle = phonetic,
                            fullContent = fullEditorialBody,
                            copyText = "$originalWord $phonetic\n\n$fullEditorialBody"
                        )
                    )
                    finish()
                }
                return
            }

            // Fallback to Datamuse if word is missing in primary dictionary
            val defUrl = URL("https://api.datamuse.com/words?sp=$encoded&md=d&max=1")
            val defConn = (defUrl.openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 4000
            }

            var definitionText = ""
            var pos = ""
            if (defConn.responseCode == 200) {
                val resp = defConn.inputStream.bufferedReader().use { it.readText() }
                val array = JSONArray(resp)
                if (array.length() > 0) {
                    val defs = array.getJSONObject(0).optJSONArray("defs")
                    if (defs != null && defs.length() > 0) {
                        val parts = defs.getString(0).split("\t", limit = 2)
                        if (parts.size > 1) {
                            pos = parts[0].trim().uppercase(Locale.ROOT)
                            definitionText = parts[1].trim()
                        } else {
                            definitionText = parts[0].trim()
                        }
                    }
                }
            }

            if (definitionText.isNotBlank()) {
                val body = "01. $pos\n$definitionText"
                mainHandler.post {
                    LiveStatusReminder.showCustomCapsule(
                        context = appContext,
                        pillText = originalWord,
                        iconName = "ic_capsule_search",
                        title = originalWord.replaceFirstChar { it.uppercase() },
                        content = definitionText,
                        timeoutSeconds = 20,
                        actions = listOf(getDismissAction()),
                        notificationId = INSPECT_NOTIFICATION_ID,
                        detailPayload = LiveStatusReminder.InspectDetailPayload(
                            domain = "LXCN",
                            title = originalWord.replaceFirstChar { it.uppercase() },
                            subtitle = "",
                            fullContent = body,
                            copyText = "$originalWord\n$body"
                        )
                    )
                    finish()
                }
            } else {
                mainHandler.post {
                    Toast.makeText(appContext, "No definition found", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        } catch (e: Exception) {
            Log.e("ProcessText", "Lookup error", e)
            mainHandler.post { finish() }
        }
    }
}
