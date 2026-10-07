package com.github.jimmy90109.livestatus

import android.app.Activity
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class InspectDetailActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Clear associated active pill
        val notifId = intent.getIntExtra("EXTRA_NOTIFICATION_ID", -1)
        if (notifId != -1) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(notifId)
        }

        // Domain extraction & dynamic theming
        val domainTag = intent.getStringExtra("EXTRA_DOMAIN") ?: "LXCN"
        val titleText = intent.getStringExtra("EXTRA_TITLE") ?: "LEXEME"
        val phoneticSubtitle = intent.getStringExtra("EXTRA_SUBTITLE") ?: ""
        val bodyContent = intent.getStringExtra("EXTRA_FULL_CONTENT") ?: ""
        val copyPayload = intent.getStringExtra("EXTRA_COPY_PAYLOAD") ?: bodyContent

        val palette = resolvePalette(domainTag)
        val density = resources.displayMetrics.density

        // Root Scrim: clean transparent pass-through
        val rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#4D000000"))
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                finish()
            }
        }

        // Frosted Glass Smoked Acrylic Card
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val padH = (20 * density).toInt()
            val padV = (24 * density).toInt()
            setPadding(padH, padV, padH, padV)

            background = GradientDrawable().apply {
                setColor(palette.glassBg)
                setStroke((1.5f * density).toInt(), palette.strokeColor)
                cornerRadii = floatArrayOf(
                    32 * density, 32 * density,
                    32 * density, 32 * density,
                    0f, 0f, 0f, 0f
                )
            }
            isClickable = true
        }

        val cardParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM
        }

        // 1. Vertical Gutter Ribbon (-90° Rotated Monospace Banner)
        val verticalSpine = TextView(this).apply {
            text = domainTag.uppercase()
            textSize = 11f
            setTextColor(palette.accent)
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.22f
            rotation = -90f
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(
                (28 * density).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginEnd = (12 * density).toInt()
            }
        }
        card.addView(verticalSpine)

        // 2. Main Content Flow
        val contentColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        // Lexeme Title: High-contrast Serif for words/taxa, Tabular Monospace for formulas/numbers
        val isNumericOrFormula = titleText.any { it.isDigit() } ||
                titleText.contains(Regex("""[=+\-×÷/%$€¥₹]""")) ||
                domainTag == "CHMSTRY"

        val headlineView = TextView(this).apply {
            text = titleText
            setTextColor(Color.parseColor("#FAFAFA"))
            if (isNumericOrFormula) {
                typeface = Typeface.MONOSPACE
                textSize = 24f
                letterSpacing = 0.02f
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    fontFeatureSettings = "tnum"
                }
            } else {
                typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
                textSize = 28f
                letterSpacing = -0.02f
            }
        }
        contentColumn.addView(headlineView)

        // Subtitle slot: IPA Phonetics or Scientific Nomenclature
        if (phoneticSubtitle.isNotBlank()) {
            val subView = TextView(this).apply {
                text = phoneticSubtitle
                textSize = 13f
                setTextColor(palette.accent)
                typeface = Typeface.MONOSPACE
                letterSpacing = 0.05f
                setPadding(0, (2 * density).toInt(), 0, (10 * density).toInt())
            }
            contentColumn.addView(subView)
        } else {
            headlineView.setPadding(0, 0, 0, (10 * density).toInt())
        }

        // Scrollable Multi-definition / Scholarly Body
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (18 * density).toInt()
            }
            isVerticalScrollBarEnabled = false
        }

        val bodyView = TextView(this).apply {
            text = bodyContent
            textSize = 14f
            setTextColor(Color.parseColor("#E4E4E7"))
            setLineSpacing(0f, 1.35f)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setTextIsSelectable(true)
        }
        scrollView.addView(bodyView)
        contentColumn.addView(scrollView)

        // 3. Tactile Minimalist Actions
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        val copyButton = Button(this).apply {
            text = "CPY"
            textSize = 11f
            setTextColor(Color.parseColor("#09090B"))
            typeface = Typeface.create("sans-serif-condensed-medium", Typeface.BOLD)
            letterSpacing = 0.12f

            background = GradientDrawable().apply {
                setColor(palette.accent)
                cornerRadius = 14 * density
            }
            setPadding((16 * density).toInt(), 0, (16 * density).toInt(), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                (36 * density).toInt()
            ).apply {
                marginEnd = (10 * density).toInt()
            }

            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val clip = ClipData.newPlainText("LiveStatus Data", copyPayload)
                clipboard?.setPrimaryClip(clip)
                Toast.makeText(this@InspectDetailActivity, "COPIED", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
        actionRow.addView(copyButton)

        val dismissButton = Button(this).apply {
            text = "CLS"
            textSize = 11f
            setTextColor(Color.parseColor("#A1A1AA"))
            typeface = Typeface.create("sans-serif-condensed-medium", Typeface.BOLD)
            letterSpacing = 0.12f

            background = GradientDrawable().apply {
                setColor(Color.parseColor("#18181B"))
                setStroke((1 * density).toInt(), Color.parseColor("#27272A"))
                cornerRadius = 14 * density
            }
            setPadding((14 * density).toInt(), 0, (14 * density).toInt(), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                (36 * density).toInt()
            )

            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                finish()
            }
        }
        actionRow.addView(dismissButton)

        contentColumn.addView(actionRow)
        card.addView(contentColumn)
        rootLayout.addView(card, cardParams)

        setContentView(rootLayout)
    }

    private data class Palette(val glassBg: Int, val strokeColor: Int, val accent: Int)

    private fun resolvePalette(domain: String): Palette {
        return when (domain) {
            "PHRM", "MDCN" -> Palette(
                glassBg = Color.parseColor("#E6140804"),
                strokeColor = Color.parseColor("#4DF43B00"),
                accent = Color.parseColor("#F43B00") // Signal Vermilion
            )
            "CHMSTRY", "MLR" -> Palette(
                glassBg = Color.parseColor("#E6141305"),
                strokeColor = Color.parseColor("#4DF9E800"),
                accent = Color.parseColor("#F9E800") // Hazard Ochre
            )
            "TXNMY", "BTNY" -> Palette(
                glassBg = Color.parseColor("#E607140B"),
                strokeColor = Color.parseColor("#4D00FF66"),
                accent = Color.parseColor("#00FF66") // Acid Emerald
            )
            "URL", "DOMN" -> Palette(
                glassBg = Color.parseColor("#E6080A14"),
                strokeColor = Color.parseColor("#4D2E5BFF"),
                accent = Color.parseColor("#2E5BFF") // Hyper Cobalt
            )
            else -> Palette( // LXCN / Default
                glassBg = Color.parseColor("#E60C0D0E"),
                strokeColor = Color.parseColor("#4DCCFF00"),
                accent = Color.parseColor("#CCFF00") // Toxic Lime
            )
        }
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, android.R.anim.fade_out)
    }
}
