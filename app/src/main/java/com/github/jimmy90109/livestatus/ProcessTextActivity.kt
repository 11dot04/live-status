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
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
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

        // 2. Color Preview (#FFF, #FFFFFF, etc.)
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

        // 4. Imperial to Metric Dynamic Normalizer
        if (handleUnitConversion(rawText)) {
            return
        }

        // 5. Scholar: Scientific Physical Constants
        if (handleScientificConstant(rawText)) {
            return
        }

        // 6. Scholar: Chemical Formula Molar Mass
        if (handleMolarMassLookup(rawText)) {
            return
        }

        // 7. Scholar: Pharmacology Drug Stems
        if (handlePharmacologyLookup(rawText)) {
            return
        }

        // 8. Scholar: Binomial Taxonomy
        if (handleTaxonomyLookup(rawText)) {
            return
        }

        // 9. Inline Math (e.g. 4+3, 12 * 8.5)
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

        // 10. Date / Relative Day Inspector
        if (handleDateLookup(rawText)) {
            return
        }

        // 11. Currency Converter ($50, 50 USD, €20, etc.)
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

        // 12. Fallback: Uncapped Dictionary with Details Action
        handleDictionaryLookup(rawText)
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
                title = "Reading Statistics",
                subtitle = "$wordCount words ($charCount characters)",
                fullContent = "Estimated reading time: $timeFormatted (calculated at 230 wpm).\n\nParagraph sample:\n${rawText.take(400)}..."
            )
        )
        finish()
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
            "EST" -> "America/New_York"
            "EDT" -> "America/New_York"
            "PST" -> "America/Los_Angeles"
            "PDT" -> "America/Los_Angeles"
            "CST" -> "America/Chicago"
            "CDT" -> "America/Chicago"
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
            notificationId = INSPECT_NOTIFICATION_ID
        )
        finish()
    }

    private fun handleUnitConversion(rawText: String): Boolean {
        val unitRegex = Regex("""(?i)^\s*(\d+(?:\.\d+)?)\s*(lbs?|pounds?|ft|feet|in|inch|inches|mph|f|fahrenheit)\s*$""")
        val m = unitRegex.find(rawText) ?: return false

        val value = m.groupValues[1].toDoubleOrNull() ?: return false
        val unit = m.groupValues[2].lowercase(Locale.ROOT)

        val (convertedPill, convertedExpanded) = when {
            unit.startsWith("lb") || unit.startsWith("pound") -> {
                val kg = value * 0.45359237
                String.format(Locale.ROOT, "%.1f kg", kg) to "$value lbs = ${String.format(Locale.ROOT, "%.2f", kg)} kg"
            }
            unit == "ft" || unit.startsWith("feet") -> {
                val cm = value * 30.48
                String.format(Locale.ROOT, "%.0f cm", cm) to "$value ft = ${String.format(Locale.ROOT, "%.1f", cm)} cm"
            }
            unit.startsWith("in") -> {
                val cm = value * 2.54
                String.format(Locale.ROOT, "%.1f cm", cm) to "$value in = ${String.format(Locale.ROOT, "%.2f", cm)} cm"
            }
            unit == "mph" -> {
                val kmh = value * 1.60934
                String.format(Locale.ROOT, "%.0f km/h", kmh) to "$value mph = ${String.format(Locale.ROOT, "%.1f", kmh)} km/h"
            }
            unit == "f" || unit.startsWith("fahrenheit") -> {
                val c = (value - 32.0) * 5.0 / 9.0
                String.format(Locale.ROOT, "%.1f°C", c) to "$value°F = ${String.format(Locale.ROOT, "%.1f", c)}°C"
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
            notificationId = INSPECT_NOTIFICATION_ID
        )
        finish()
        return true
    }

    private fun handleScientificConstant(rawText: String): Boolean {
        val key = rawText.lowercase(Locale.ROOT).trim()
        val constants = mapOf(
            "c" to Triple("c (Speed of Light)", "2.998 × 10⁸ m/s", "Exact speed of electromagnetic radiation in vacuum"),
            "h" to Triple("h (Planck's)", "6.626 × 10⁻³⁴ J·s", "Quantum of electromagnetic action relating energy to frequency"),
            "hbar" to Triple("ħ (Reduced Planck)", "1.055 × 10⁻³⁴ J·s", "ħ = h / (2π)"),
            "k_b" to Triple("kB (Boltzmann)", "1.381 × 10⁻²³ J/K", "Relates average kinetic energy of particles with temperature"),
            "kb" to Triple("kB (Boltzmann)", "1.381 × 10⁻²³ J/K", "Relates average kinetic energy of particles with temperature"),
            "n_a" to Triple("NA (Avogadro)", "6.022 × 10²³ mol⁻¹", "Number of constituent particles in one mole"),
            "na" to Triple("NA (Avogadro)", "6.022 × 10²³ mol⁻¹", "Number of constituent particles in one mole"),
            "g" to Triple("G (Gravitational)", "6.674 × 10⁻¹¹ N·m²/kg²", "Newtonian constant of gravitation"),
            "eps_0" to Triple("ε₀ (Vacuum Permittivity)", "8.854 × 10⁻¹² F/m", "Capability of vacuum to permit electric fields"),
            "mu_0" to Triple("μ₀ (Vacuum Permeability)", "1.257 × 10⁻⁶ N/A²", "Magnetic constant in free space")
        )

        val found = constants[key] ?: return false
        LiveStatusReminder.showCustomCapsule(
            context = applicationContext,
            pillText = found.second.split(" ").take(3).joinToString(" "),
            iconName = "ic_capsule_search",
            title = found.first,
            content = "${found.second} • ${found.third}",
            timeoutSeconds = 15,
            actions = listOf(getDismissAction()),
            notificationId = INSPECT_NOTIFICATION_ID,
            detailPayload = LiveStatusReminder.InspectDetailPayload(
                title = found.first,
                subtitle = "SI Standard Constant",
                fullContent = "Value: ${found.second}\n\nDescription: ${found.third}"
            )
        )
        finish()
        return true
    }

    private fun handleMolarMassLookup(rawText: String): Boolean {
        val formulaRegex = Regex("""^[A-Z][a-z]?(\d+)?([A-Z][a-z]?(\d+)?)*$""")
        val text = rawText.trim()
        if (!formulaRegex.matches(text) || text.length < 2 || text.length > 20) return false

        val masses = mapOf(
            "H" to 1.008, "He" to 4.003, "Li" to 6.941, "C" to 12.011, "N" to 14.007,
            "O" to 15.999, "F" to 18.998, "Na" to 22.990, "Mg" to 24.305, "Al" to 26.982,
            "Si" to 28.086, "P" to 30.974, "S" to 32.065, "Cl" to 35.453, "K" to 39.098,
            "Ca" to 40.078, "Fe" to 55.845, "Cu" to 63.546, "Zn" to 65.38, "Br" to 79.904,
            "Ag" to 107.868, "I" to 126.904, "Ba" to 137.327, "Au" to 196.967, "Pb" to 207.2
        )

        val elemRegex = Regex("""([A-Z][a-z]?)(\d*)""")
        var totalMass = 0.0
        val breakdown = mutableListOf<String>()

        elemRegex.findAll(text).forEach { m ->
            val elem = m.groupValues[1]
            val count = m.groupValues[2].toIntOrNull() ?: 1
            val mass = masses[elem] ?: return false
            val elemTotal = mass * count
            totalMass += elemTotal
            breakdown.add("$elem: ${String.format(Locale.ROOT, "%.2f", elemTotal)} g")
        }

        val pillStr = String.format(Locale.ROOT, "%.2f g/mol", totalMass)
        LiveStatusReminder.showCustomCapsule(
            context = applicationContext,
            pillText = pillStr,
            iconName = "ic_capsule_search",
            title = "$text Molar Mass",
            content = "$pillStr • Mass breakdown available in details",
            timeoutSeconds = 15,
            actions = listOf(getDismissAction()),
            notificationId = INSPECT_NOTIFICATION_ID,
            detailPayload = LiveStatusReminder.InspectDetailPayload(
                title = "$text Stoichiometry",
                subtitle = "Total Molar Mass: $pillStr",
                fullContent = "Elemental components:\n" + breakdown.joinToString("\n")
            )
        )
        finish()
        return true
    }

    private fun handlePharmacologyLookup(rawText: String): Boolean {
        val word = rawText.lowercase(Locale.ROOT).trim()
        val stems = mapOf(
            "olol" to ("Beta-Blocker" to "Antihypertensive / antiarrhythmic (blocks β-adrenergic receptors)"),
            "pril" to ("ACE Inhibitor" to "Inhibits angiotensin-converting enzyme to lower blood pressure"),
            "sartan" to ("ARB (Angiotensin Blocker)" to "Blocks angiotensin II receptor type 1 for hypertension"),
            "statin" to ("HMG-CoA Reductase Inhibitor" to "Lowers LDL cholesterol and cardiovascular risk"),
            "prazole" to ("Proton Pump Inhibitor" to "Suppresses gastric acid secretion in stomach lining"),
            "cillin" to ("Penicillin Antibiotic" to "Inhibits bacterial cell wall synthesis (bactericidal)"),
            "floxacin" to ("Fluoroquinolone" to "Inhibits bacterial DNA gyrase and topoisomerase IV"),
            "dipine" to ("Calcium Channel Blocker" to "Dihydropyridine L-type CCB; causes vasodilation")
        )

        val match = stems.entries.firstOrNull { word.endsWith(it.key) } ?: return false
        val drugClass = match.value.first
        val mech = match.value.second

        LiveStatusReminder.showCustomCapsule(
            context = applicationContext,
            pillText = drugClass.split(" ").first(),
            iconName = "ic_capsule_search",
            title = "${word.replaceFirstChar { it.uppercase() }} ($drugClass)",
            content = mech,
            timeoutSeconds = 15,
            actions = listOf(getDismissAction()),
            notificationId = INSPECT_NOTIFICATION_ID,
            detailPayload = LiveStatusReminder.InspectDetailPayload(
                title = "${word.replaceFirstChar { it.uppercase() }} Monograph",
                subtitle = drugClass,
                fullContent = "Pharmacological Class: $drugClass\n\nMechanism of Action:\n$mech"
            )
        )
        finish()
        return true
    }

    private fun handleTaxonomyLookup(rawText: String): Boolean {
        val cleaned = rawText.trim()
        val taxonomy = mapOf(
            "mangifera indica" to Triple("Mango", "Anacardiaceae", "Dicot tree; drupe fruit with stony endocarp"),
            "pisum sativum" to Triple("Garden Pea", "Fabaceae", "Mendelian genetics model; leguminous plant"),
            "homo sapiens" to Triple("Human", "Hominidae", "Primate mammal; bipedal hominid"),
            "solanum tuberosum" to Triple("Potato", "Solanaceae", "Nightshade family; edible underground stem tuber"),
            "rana tigrina" to Triple("Bullfrog", "Ranidae", "Common amphibian model organism"),
            "panthera tigris" to Triple("Tiger", "Felidae", "Apex carnivore mammal of genus Panthera"),
            "escherichia coli" to Triple("E. coli", "Enterobacteriaceae", "Gram-negative, facultatively anaerobic rod bacterium"),
            "arabidopsis thaliana" to Triple("Thale Cress", "Brassicaceae", "Model organism for plant biology and genetics")
        )

        val found = taxonomy[cleaned.lowercase(Locale.ROOT)] ?: return false
        val commonName = found.first
        val family = found.second
        val notes = found.third

        LiveStatusReminder.showCustomCapsule(
            context = applicationContext,
            pillText = commonName,
            iconName = "ic_capsule_search",
            title = "$cleaned ($commonName)",
            content = "Family: $family • $notes",
            timeoutSeconds = 15,
            actions = listOf(getDismissAction()),
            notificationId = INSPECT_NOTIFICATION_ID,
            detailPayload = LiveStatusReminder.InspectDetailPayload(
                title = cleaned,
                subtitle = "Common name: $commonName",
                fullContent = "Family: $family\n\nBiological Context:\n$notes"
            )
        )
        finish()
        return true
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
                notificationId = INSPECT_NOTIFICATION_ID
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
                pillText = formatted,
                iconName = "ic_equals",
                title = "Calculation",
                content = "$originalText = $formatted",
                timeoutSeconds = 15,
                actions = listOf(getDismissAction()),
                notificationId = INSPECT_NOTIFICATION_ID
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
            notificationId = INSPECT_NOTIFICATION_ID
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

                    mainHandler.post {
                        LiveStatusReminder.showCustomCapsule(
                            context = appContext,
                            pillText = formatted,
                            iconName = iconName,
                            title = "$amount $baseCurrency Conversion",
                            content = "$amount $baseCurrency = ${if (isTargetInr) "₹" else "$"}$formatted",
                            timeoutSeconds = 20,
                            actions = listOf(getDismissAction()),
                            notificationId = INSPECT_NOTIFICATION_ID
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

        thread {
            try {
                val encoded = java.net.URLEncoder.encode(cleanWordLower, "UTF-8")

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
                    .replaceFirstChar { it.lowercase() }

                val posConstraint = when (partOfSpeech) {
                    "adj" -> "&lc=noun"
                    "v" -> "&lc=to"
                    else -> ""
                }

                val synUrl = URL("https://api.datamuse.com/words?rel_syn=$encoded$posConstraint&max=10")
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
                        val word = synArray.getJSONObject(i).optString("word").lowercase(Locale.ROOT)
                        if (word.isNotBlank() && !word.contains(" ") && word != cleanWordLower) {
                            synList.add(word)
                        }
                    }
                    shortestSynonym = synList.filter { it.length <= 7 }.minByOrNull { it.length }
                }

                val baseWord = if (originalWord.all { it.isUpperCase() }) originalWord else cleanWordLower
                val displayWord = shortestSynonym ?: baseWord
                val posPrefix = if (partOfSpeech.isNotBlank()) "${partOfSpeech} • " else ""
                val pill = "$posPrefix$displayWord"

                val webIntent = PendingIntent.getActivity(
                    appContext,
                    cleanWordLower.hashCode(),
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
                        title = if (partOfSpeech.isNotBlank()) "$baseWord ($partOfSpeech)" else baseWord,
                        content = cleanDefinition,
                        timeoutSeconds = calculatedTimeout,
                        actions = listOf(getDismissAction(), webAction),
                        notificationId = INSPECT_NOTIFICATION_ID,
                        detailPayload = LiveStatusReminder.InspectDetailPayload(
                            title = baseWord.replaceFirstChar { it.uppercase() },
                            subtitle = if (partOfSpeech.isNotBlank()) "Part of speech: $partOfSpeech" else "",
                            fullContent = "Definition:\n$cleanDefinition\n\nShortest synonym: ${shortestSynonym ?: "None identified"}",
                            copyText = cleanDefinition
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e("ProcessText", "Lookup error", e)
            } finally {
                mainHandler.post { finish() }
            }
        }
    }
}
