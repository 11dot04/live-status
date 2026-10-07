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

        // 5. Currency Converter ($50, 50 USD, €20, etc.)
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

        // 6. Scientific Physical Constants
        if (handleScientificConstant(rawText)) {
            return
        }

        // 7. Deterministic Stoichiometry & Chemical Formula Breakdown (e.g. C6H12O6, H2SO4)
        if (handleStoichiometryFormula(rawText)) {
            return
        }

        // 8. Pharmacology & Clinical Drug Monograph
        if (handlePharmacologyMonograph(rawText)) {
            return
        }

        // 9. Imperial / Metric Dynamic Unit Normalizer
        if (handleUnitConversion(rawText)) {
            return
        }

        // 10. Date / Relative Day Inspector
        if (handleDateLookup(rawText)) {
            return
        }

        // 11. URLs, Taxonomy (GBIF), or Multi-Part Lexicon
        thread {
            dispatchNetworkInspections(rawText)
        }
    }

    private fun dispatchNetworkInspections(rawText: String) {
        when {
            isUrl(rawText) -> handleUrlInspection(rawText)
            looksLikeBinomialTaxon(rawText) && handleGbifLookup(rawText) -> {}
            else -> handleDictionaryLookup(rawText)
        }
    }

    private fun isUrl(input: String): Boolean {
        return input.startsWith("http://", ignoreCase = true) ||
                input.startsWith("https://", ignoreCase = true) ||
                (input.contains(".") && !input.contains(" ") && input.length >= 4)
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
                title = "Reading Estimate",
                subtitle = "$wordCount words • $charCount characters",
                fullContent = "Calculated at an average reading pace of 230 words per minute.\n\nSample:\n${rawText.take(350)}...",
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
                    fullContent = "Hexadecimal: #${fullHex.uppercase(Locale.ROOT)}\nRed: $r\nGreen: $g\nBlue: $b",
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
                subtitle = "Converted from $original ($tzStr)",
                fullContent = "Local converted time is $localConverted.\nOriginal source time: $original\nResolved Time Zone: $tzId",
                copyText = localConverted
            )
        )
        finish()
    }

    private fun handleUnitConversion(rawText: String): Boolean {
        val unitRegex = Regex("""(?i)^\s*(\d+(?:\.\d+)?)\s*(lbs?|pounds?|st|stone|oz|ounces?|ft|feet|in|inch(?:es)?|mi|miles?|mph|kmh|km/h|knots?|psi|bar|f|c|fahrenheit|celsius)\s*$""")
        val m = unitRegex.find(rawText.trim()) ?: return false

        val value = m.groupValues[1].toDoubleOrNull() ?: return false
        val unit = m.groupValues[2].lowercase(Locale.ROOT)

        val (convertedPill, targetName, convertedExpanded) = when {
            unit.startsWith("lb") || unit.startsWith("pound") -> {
                val kg = value * 0.45359237
                Triple(String.format(Locale.ROOT, "%.1f kg", kg), "Kilograms", "$value lbs converts to ${String.format(Locale.ROOT, "%.2f", kg)} kg")
            }
            unit == "st" || unit == "stone" -> {
                val kg = value * 6.35029
                Triple(String.format(Locale.ROOT, "%.1f kg", kg), "Kilograms", "$value stone converts to ${String.format(Locale.ROOT, "%.2f", kg)} kg")
            }
            unit.startsWith("oz") || unit.startsWith("ounce") -> {
                val g = value * 28.3495
                Triple(String.format(Locale.ROOT, "%.0f g", g), "Grams", "$value oz converts to ${String.format(Locale.ROOT, "%.1f", g)} grams")
            }
            unit == "ft" || unit.startsWith("feet") -> {
                val cm = value * 30.48
                Triple(String.format(Locale.ROOT, "%.0f cm", cm), "Centimeters", "$value ft converts to ${String.format(Locale.ROOT, "%.1f", cm)} cm")
            }
            unit.startsWith("in") -> {
                val cm = value * 2.54
                Triple(String.format(Locale.ROOT, "%.1f cm", cm), "Centimeters", "$value in converts to ${String.format(Locale.ROOT, "%.2f", cm)} cm")
            }
            unit == "mi" || unit.startsWith("mile") -> {
                val km = value * 1.60934
                Triple(String.format(Locale.ROOT, "%.1f km", km), "Kilometers", "$value mi converts to ${String.format(Locale.ROOT, "%.2f", km)} km")
            }
            unit == "mph" -> {
                val kmh = value * 1.60934
                Triple(String.format(Locale.ROOT, "%.0f km/h", kmh), "Kilometers per Hour", "$value mph converts to ${String.format(Locale.ROOT, "%.1f", kmh)} km/h")
            }
            unit.startsWith("knot") -> {
                val kmh = value * 1.852
                Triple(String.format(Locale.ROOT, "%.0f km/h", kmh), "Kilometers per Hour", "$value knots converts to ${String.format(Locale.ROOT, "%.1f", kmh)} km/h")
            }
            unit == "psi" -> {
                val bar = value * 0.0689476
                Triple(String.format(Locale.ROOT, "%.2f bar", bar), "Bar Pressure", "$value psi converts to ${String.format(Locale.ROOT, "%.3f", bar)} bar (${String.format(Locale.ROOT, "%.1f", value * 6.89476)} kPa)")
            }
            unit == "bar" -> {
                val psi = value * 14.5038
                Triple(String.format(Locale.ROOT, "%.1f psi", psi), "Pounds per Square Inch", "$value bar converts to ${String.format(Locale.ROOT, "%.2f", psi)} psi")
            }
            unit == "f" || unit.startsWith("fahrenheit") -> {
                val c = (value - 32.0) * 5.0 / 9.0
                Triple(String.format(Locale.ROOT, "%.1f°C", c), "Celsius", "$value°F converts to ${String.format(Locale.ROOT, "%.1f", c)}°C")
            }
            unit == "c" || unit.startsWith("celsius") -> {
                val f = (value * 9.0 / 5.0) + 32.0
                Triple(String.format(Locale.ROOT, "%.1f°F", f), "Fahrenheit", "$value°C converts to ${String.format(Locale.ROOT, "%.1f", f)}°F")
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
                subtitle = targetName,
                fullContent = convertedExpanded,
                copyText = convertedPill
            )
        )
        finish()
        return true
    }

    private fun handleScientificConstant(rawText: String): Boolean {
        val key = rawText.lowercase(Locale.ROOT).trim()
        val constants = mapOf(
            "c" to Triple("Speed of Light", "2.998 × 10⁸ m/s", "Speed of electromagnetic radiation in a vacuum."),
            "h" to Triple("Planck Constant", "6.626 × 10⁻³⁴ J·s", "Fundamental quantum constant relating photon energy to frequency."),
            "hbar" to Triple("Reduced Planck", "1.055 × 10⁻³⁴ J·s", "Dirac constant ħ = h / 2π used in quantum mechanics."),
            "k_b" to Triple("Boltzmann Constant", "1.381 × 10⁻²³ J/K", "Relates thermal energy to thermodynamic temperature."),
            "kb" to Triple("Boltzmann Constant", "1.381 × 10⁻²³ J/K", "Relates thermal energy to thermodynamic temperature."),
            "n_a" to Triple("Avogadro Constant", "6.022 × 10²³ mol⁻¹", "Number of constituent particles per mole of substance."),
            "na" to Triple("Avogadro Constant", "6.022 × 10²³ mol⁻¹", "Number of constituent particles per mole of substance."),
            "g" to Triple("Gravitational Constant", "6.674 × 10⁻¹¹ N·m²/kg²", "Newtonian constant of universal gravitation."),
            "eps_0" to Triple("Vacuum Permittivity", "8.854 × 10⁻¹² F/m", "Permittivity of free space to electric flux.")
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
                fullContent = "${found.first}\n\n${found.third}",
                copyText = found.second
            )
        )
        finish()
        return true
    }

    private fun handleStoichiometryFormula(rawText: String): Boolean {
        val cleaned = rawText.trim()
        val formulaRegex = Regex("""^([A-Z][a-z]?\d*)+$""")
        if (!formulaRegex.matches(cleaned) || cleaned.length < 2 || cleaned.length > 25) return false

        val masses = mapOf(
            "H" to 1.008, "He" to 4.003, "Li" to 6.94, "Be" to 9.012, "B" to 10.81,
            "C" to 12.011, "N" to 14.007, "O" to 15.999, "F" to 18.998, "Ne" to 20.18,
            "Na" to 22.990, "Mg" to 24.305, "Al" to 26.982, "Si" to 28.085, "P" to 30.974,
            "S" to 32.06, "Cl" to 35.45, "K" to 39.098, "Ca" to 40.078, "Fe" to 55.845,
            "Cu" to 63.546, "Zn" to 65.38, "Br" to 79.904, "Ag" to 107.868, "I" to 126.904,
            "Ba" to 137.327, "Au" to 196.967, "Pb" to 207.2
        )

        val elemRegex = Regex("""([A-Z][a-z]?)(\d*)""")
        val matches = elemRegex.findAll(cleaned).toList()
        if (matches.isEmpty()) return false

        var totalMass = 0.0
        val elemWeights = mutableListOf<Pair<String, Double>>()

        for (m in matches) {
            val elem = m.groupValues[1]
            val count = m.groupValues[2].toIntOrNull() ?: 1
            val atomicMass = masses[elem] ?: return false
            val contribution = atomicMass * count
            totalMass += contribution
            elemWeights.add(Pair(elem, contribution))
        }

        val massStr = String.format(Locale.ROOT, "%.2f g/mol", totalMass)

        // Generate Monospace Stoichiometry Bar
        val barTotalSegments = 24
        val barBuilder = StringBuilder("[")
        val pctStrings = mutableListOf<String>()

        elemWeights.forEach { (elem, weight) ->
            val pct = (weight / totalMass) * 100.0
            pctStrings.add(String.format(Locale.ROOT, "%s %.1f%%", elem, pct))
            val segments = Math.round((weight / totalMass) * barTotalSegments).toInt()
            repeat(segments.coerceAtLeast(1)) { barBuilder.append("█") }
        }
        val currentLen = barBuilder.length - 1
        if (currentLen < barTotalSegments) {
            repeat(barTotalSegments - currentLen) { barBuilder.append("░") }
        } else if (currentLen > barTotalSegments) {
            barBuilder.setLength(barTotalSegments + 1)
        }
        barBuilder.append("]")

        val bodyText = "${barBuilder}\n${pctStrings.joinToString("   ")}\n\nMolar mass calculation determined from standard IUPAC elemental atomic weights."

        LiveStatusReminder.showCustomCapsule(
            context = applicationContext,
            pillText = massStr,
            iconName = "ic_science",
            title = cleaned,
            content = massStr,
            timeoutSeconds = 15,
            actions = listOf(getDismissAction()),
            notificationId = INSPECT_NOTIFICATION_ID,
            detailPayload = LiveStatusReminder.InspectDetailPayload(
                domain = "CHMSTRY",
                title = cleaned,
                subtitle = massStr,
                fullContent = bodyText,
                copyText = "$cleaned: $massStr"
            )
        )
        finish()
        return true
    }

    private fun handlePharmacologyMonograph(rawText: String): Boolean {
        val word = rawText.lowercase(Locale.ROOT).trim()

        val drugs = mapOf(
            "metoprolol" to Triple(
                "Beta-1 Adrenergic Blocker",
                "Competitive cardioselective beta-1 receptor antagonist. Reduces cardiac output, heart rate, and systemic blood pressure.",
                "[ USAN: -olol ] • [ 200mg MAX/DIE ]"
            ),
            "atorvastatin" to Triple(
                "HMG-CoA Reductase Inhibitor",
                "Competitively inhibits 3-hydroxy-3-methylglutaryl-coenzyme A reductase, suppressing hepatic cholesterol biosynthesis and upregulating LDL receptors.",
                "[ USAN: -statin ] • [ 80mg MAX/DIE ]"
            ),
            "amoxicillin" to Triple(
                "Aminopenicillin Antibiotic",
                "Bactericidal beta-lactam that inhibits transpeptidase-mediated bacterial cell wall synthesis during active peptidoglycan replication.",
                "[ USAN: -cillin ] • [ 500mg - 875mg Q12H ]"
            ),
            "paracetamol" to Triple(
                "Analgesic & Antipyretic",
                "Centrally acting cyclooxygenase (COX-3/peroxidase) inhibitor. Crosses blood-brain barrier to alleviate pain and reset hypothalamic thermoregulatory center.",
                "[ INN: Paracetamol / APAP ] • [ 4000mg MAX/DIE ]"
            ),
            "omeprazole" to Triple(
                "Proton Pump Inhibitor",
                "Irreversibly inhibits the gastric H+/K+-ATPase pump in gastric parietal cells, causing profound and prolonged suppression of gastric acid production.",
                "[ USAN: -prazole ] • [ 40mg MAX/DIE ]"
            ),
            "losartan" to Triple(
                "Angiotensin II Receptor Antagonist",
                "Selectively blocks the AT1 receptor subtype, preventing angiotensin II vasoconstriction and aldosterone secretion.",
                "[ USAN: -sartan ] • [ 100mg MAX/DIE ]"
            )
        )

        val entry = drugs[word] ?: run {
            // Suffix pattern recognition fallback
            when {
                word.endsWith("olol") -> Triple("Beta-Adrenergic Blocker", "Antihypertensive and antiarrhythmic agent acting on beta receptors.", "[ USAN: -olol ]")
                word.endsWith("statin") -> Triple("HMG-CoA Reductase Inhibitor", "Lipid-lowering agent reducing endogenous cholesterol synthesis.", "[ USAN: -statin ]")
                word.endsWith("cillin") -> Triple("Beta-Lactam Antibiotic", "Bactericidal antibiotic inhibiting cell wall synthesis.", "[ USAN: -cillin ]")
                word.endsWith("pril") -> Triple("ACE Inhibitor", "Inhibits angiotensin-converting enzyme to promote vasodilation.", "[ USAN: -pril ]")
                word.endsWith("sartan") -> Triple("Angiotensin Receptor Blocker", "Blocks AT1 receptors to lower peripheral resistance.", "[ USAN: -sartan ]")
                word.endsWith("prazole") -> Triple("Proton Pump Inhibitor", "Inhibits gastric parietal H+/K+ ATPase to block acid production.", "[ USAN: -prazole ]")
                else -> null
            }
        } ?: return false

        val titleDisplay = word.replaceFirstChar { it.uppercase(Locale.ROOT) }
        val bodyContent = "${entry.second}\n\n${entry.third}"

        LiveStatusReminder.showCustomCapsule(
            context = applicationContext,
            pillText = entry.first.split(" ").first(),
            iconName = "ic_medication",
            title = titleDisplay,
            content = entry.first,
            timeoutSeconds = 15,
            actions = listOf(getDismissAction()),
            notificationId = INSPECT_NOTIFICATION_ID,
            detailPayload = LiveStatusReminder.InspectDetailPayload(
                domain = "PHRM",
                title = titleDisplay,
                subtitle = entry.first,
                fullContent = bodyContent,
                copyText = "$titleDisplay: ${entry.first}\n\n${entry.second}"
            )
        )
        finish()
        return true
    }

    private fun handleGbifLookup(term: String): Boolean {
        val cleaned = term.trim()

        val offlineTaxa = mapOf(
            "mangifera indica" to Triple("Mango", "Anacardiaceae", "Flowering dicot tree indigenous to tropical Asia. Produces commercially significant drupe fruits with fibrous mesocarp."),
            "panthera tigris" to Triple("Tiger", "Felidae", "Apex apex obligate carnivore. Recognized by dark vertical stripes across reddish-orange pelt."),
            "ficus religiosa" to Triple("Sacred Fig", "Moraceae", "Deciduous hemiepiphytic fig native to the Indian subcontinent with characteristic cordate leaves."),
            "arabidopsis thaliana" to Triple("Thale Cress", "Brassicaceae", "Small flowering plant widely deployed as an essential model organism in plant genetics and genomics."),
            "homo sapiens" to Triple("Human", "Hominidae", "Bipedal hominid primate species characterized by advanced cognitive faculties and encephalization."),
            "pisum sativum" to Triple("Garden Pea", "Fabaceae", "Annual legume widely cultivated for nutritious seed pods; foundation of classical Mendelian genetics.")
        )

        val local = offlineTaxa[cleaned.lowercase(Locale.ROOT)]
        if (local != null) {
            val common = local.first
            val family = local.second
            val notes = local.third
            val body = "Taxon Family: $family\nClassification: Plantae / Animalia\n\n$notes"

            mainHandler.post {
                LiveStatusReminder.showCustomCapsule(
                    context = applicationContext,
                    pillText = common,
                    iconName = "ic_eco",
                    title = cleaned,
                    content = "$family • $common",
                    timeoutSeconds = 16,
                    actions = listOf(getDismissAction()),
                    notificationId = INSPECT_NOTIFICATION_ID,
                    detailPayload = LiveStatusReminder.InspectDetailPayload(
                        domain = "TXNMY",
                        title = cleaned,
                        subtitle = "$family • $common",
                        fullContent = body,
                        copyText = "$cleaned ($common, $family)"
                    )
                )
                finish()
            }
            return true
        }

        // Live GBIF Endpoint
        return try {
            val encoded = URLEncoder.encode(cleaned, "UTF-8")
            val url = URL("https://api.gbif.org/v1/species/match?name=$encoded")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 3500
                readTimeout = 3500
                setRequestProperty("User-Agent", "LiveStatus-Android/1.0")
            }
            if (conn.responseCode != 200) return false

            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val canonical = json.optString("canonicalName", "")
            if (canonical.isBlank()) return false

            val kingdom = json.optString("kingdom", "Taxon")
            val family = json.optString("family", "")
            val status = json.optString("status", "ACCEPTED")

            val sub = if (family.isNotBlank()) "$family • $kingdom" else kingdom
            val body = "Taxon Status: $status\nFamily: $family\nKingdom: $kingdom\n\nIdentified via Global Biodiversity Information Facility taxonomy index."

            mainHandler.post {
                LiveStatusReminder.showCustomCapsule(
                    context = applicationContext,
                    pillText = canonical,
                    iconName = "ic_eco",
                    title = canonical,
                    content = sub,
                    timeoutSeconds = 16,
                    actions = listOf(getDismissAction()),
                    notificationId = INSPECT_NOTIFICATION_ID,
                    detailPayload = LiveStatusReminder.InspectDetailPayload(
                        domain = "TXNMY",
                        title = canonical,
                        subtitle = sub,
                        fullContent = body,
                        copyText = "$canonical ($sub)"
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
                        fullContent = "Resolved Destination:\n$finalDestination\n\nOriginal Source:\n$rawUrl",
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
                        subtitle = "Link Destination",
                        fullContent = rawUrl,
                        copyText = rawUrl
                    )
                )
                finish()
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
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }

            if (dictConn.responseCode == 200) {
                val resp = dictConn.inputStream.bufferedReader().use { it.readText() }
                val jsonArray = JSONArray(resp)
                val entry = jsonArray.getJSONObject(0)

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

                val meanings = entry.optJSONArray("meanings")
                val bodyBuilder = StringBuilder()
                val synonyms = mutableSetOf<String>()

                if (meanings != null) {
                    for (i in 0 until meanings.length()) {
                        val mObj = meanings.getJSONObject(i)
                        val pos = mObj.optString("partOfSpeech", "").lowercase(Locale.ROOT)
                        val defsArr = mObj.optJSONArray("definitions")

                        if (defsArr != null && defsArr.length() > 0) {
                            bodyBuilder.append(pos).append("\n")
                            val limit = minOf(defsArr.length(), 2)
                            for (d in 0 until limit) {
                                val defText = defsArr.getJSONObject(d).optString("definition", "")
                                bodyBuilder.append("  ${d + 1}. ").append(defText).append("\n")
                            }
                            bodyBuilder.append("\n")
                        }

                        val synArr = mObj.optJSONArray("synonyms")
                        if (synArr != null) {
                            for (s in 0 until synArr.length()) {
                                val syn = synArr.optString(s, "")
                                if (syn.isNotBlank()) synonyms.add(syn.lowercase(Locale.ROOT))
                            }
                        }
                    }
                }

                if (synonyms.isNotEmpty()) {
                    bodyBuilder.append("[ SYN: ").append(synonyms.take(3).joinToString(", ")).append(" ]")
                }

                val fullBody = bodyBuilder.toString().trim()
                val pillText = synonyms.minByOrNull { it.length } ?: originalWord

                mainHandler.post {
                    LiveStatusReminder.showCustomCapsule(
                        context = appContext,
                        pillText = pillText,
                        iconName = "ic_capsule_search",
                        title = originalWord.replaceFirstChar { it.uppercase(Locale.ROOT) },
                        content = phonetic.ifBlank { "Definition" },
                        timeoutSeconds = 25,
                        actions = listOf(getDismissAction()),
                        notificationId = INSPECT_NOTIFICATION_ID,
                        detailPayload = LiveStatusReminder.InspectDetailPayload(
                            domain = "LXCN",
                            title = originalWord.replaceFirstChar { it.uppercase(Locale.ROOT) },
                            subtitle = phonetic,
                            fullContent = fullBody,
                            copyText = "$originalWord $phonetic\n\n$fullBody"
                        )
                    )
                    finish()
                }
                return
            }

            mainHandler.post {
                Toast.makeText(appContext, "No definition found", Toast.LENGTH_SHORT).show()
                finish()
            }
        } catch (e: Exception) {
            Log.e("ProcessText", "Lookup error", e)
            mainHandler.post { finish() }
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
                                subtitle = "$amount $baseCurrency Conversion",
                                fullContent = "$amount $baseCurrency = $displayResult\nExchange base: $baseCurrency",
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
}
