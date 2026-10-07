package com.github.jimmy90109.livestatus

import android.app.Activity
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class InspectDetailActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // API 31+ Hardware Gaussian Blur behind the floating sheet
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes.blurBehindRadius = 60
        }
        window.setBackgroundDrawableResource(android.R.color.transparent)

        val notifId = intent.getIntExtra("EXTRA_NOTIFICATION_ID", -1)
        if (notifId != -1) {
            (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.cancel(notifId)
        }

        val domainTag = intent.getStringExtra("EXTRA_DOMAIN") ?: "LXCN"
        val titleText = intent.getStringExtra("EXTRA_TITLE") ?: ""
        val subtitleText = intent.getStringExtra("EXTRA_SUBTITLE") ?: ""
        val bodyContent = intent.getStringExtra("EXTRA_FULL_CONTENT") ?: ""
        val copyPayload = intent.getStringExtra("EXTRA_COPY_PAYLOAD") ?: bodyContent

        val palette = resolvePalette(domainTag)
        val density = resources.displayMetrics.density

        // Root scrim
        val rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#33000000"))
            setOnClickListener { finish() }
        }

        // Floating Card: Detached margins, no outline/stroke
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val padH = (20 * density).toInt()
            val padV = (22 * density).toInt()
            setPadding(padH, padV, padH, padV)

            background = GradientDrawable().apply {
                setColor(palette.glassBg)
                cornerRadius = 24 * density
            }
            isClickable = true
        }

        val cardParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM
            val marginH = (16 * density).toInt()
            val marginB = (28 * density).toInt() // Detached cleanly from the bottom
            setMargins(marginH, 0, marginH, marginB)
        }

        // Single-column, un-wrapped vertical spine
        val spineView = VerticalSpineView(this, domainTag.uppercase(), palette.accent)
        val spineParams = LinearLayout.LayoutParams(
            (26 * density).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_VERTICAL
            marginEnd = (14 * density).toInt()
        }
        card.addView(spineView, spineParams)

        // Main content column
        val contentColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        // Title: Serif for words/taxa, Tabular Monospace for pure numbers/stoichiometry
        val isPureFormulaOrNumber = domainTag == "CHMSTRY" && titleText.any { it.isDigit() }
        val headline = TextView(this).apply {
            text = titleText
            setTextColor(Color.WHITE)
            if (isPureFormulaOrNumber) {
                typeface = Typeface.MONOSPACE
                textSize = 24f
            } else {
                typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
                textSize = 28f
                letterSpacing = -0.02f
            }
        }
        contentColumn.addView(headline)

        // Subtitle (IPA or secondary designation)
        if (subtitleText.isNotBlank()) {
            val sub = TextView(this).apply {
                text = subtitleText
                textSize = 13f
                setTextColor(palette.accent)
                typeface = Typeface.MONOSPACE
                letterSpacing = 0.04f
                setPadding(0, 0, 0, (12 * density).toInt())
            }
            contentColumn.addView(sub)
        } else {
            headline.setPadding(0, 0, 0, (12 * density).toInt())
        }

        // Body: Standard Sans-Serif for readability
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (16 * density).toInt()
            }
            isVerticalScrollBarEnabled = false
        }

        val body = TextView(this).apply {
            text = bodyContent
            textSize = 14f
            setTextColor(Color.parseColor("#E0E0E0"))
            setLineSpacing(0f, 1.38f)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setTextIsSelectable(true)
        }
        scroll.addView(body)
        contentColumn.addView(scroll)

        // Bottom Action Buttons
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        val copyBtn = Button(this).apply {
            text = "COPY"
            textSize = 11f
            setTextColor(Color.BLACK)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.08f
            background = GradientDrawable().apply {
                setColor(palette.accent)
                cornerRadius = 14 * density
            }
            setPadding((16 * density).toInt(), 0, (16 * density).toInt(), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                (36 * density).toInt()
            ).apply { marginEnd = (10 * density).toInt() }

            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                val cb = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cb?.setPrimaryClip(ClipData.newPlainText("LiveStatus", copyPayload))
                Toast.makeText(this@InspectDetailActivity, "Copied", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
        actions.addView(copyBtn)

        val closeBtn = Button(this).apply {
            text = "DISMISS"
            textSize = 11f
            setTextColor(Color.parseColor("#CCCCCC"))
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.08f
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#222226"))
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
        actions.addView(closeBtn)

        contentColumn.addView(actions)
        card.addView(contentColumn)
        rootLayout.addView(card, cardParams)

        setContentView(rootLayout)
    }

    private class VerticalSpineView(context: Context, private val text: String, color: Int) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = 32f
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.22f
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val textWidth = paint.measureText(text)
            setMeasuredDimension((28 * resources.displayMetrics.density).toInt(), textWidth.toInt() + 30)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.save()
            canvas.translate(width / 2f + 8f, height.toFloat() - 8f)
            canvas.rotate(-90f)
            canvas.drawText(text, 0f, 0f, paint)
            canvas.restore()
        }
    }

    private data class Palette(val glassBg: Int, val accent: Int)

    private fun resolvePalette(domain: String): Palette {
        return when (domain) {
            "PHRM", "MDCN" -> Palette(
                glassBg = Color.parseColor("#F2160804"),
                accent = Color.parseColor("#F43B00") // Signal Vermilion
            )
            "CHMSTRY", "MLR" -> Palette(
                glassBg = Color.parseColor("#F2141305"),
                accent = Color.parseColor("#F9E800") // Hazard Ochre
            )
            "TXNMY", "BTNY" -> Palette(
                glassBg = Color.parseColor("#F207140B"),
                accent = Color.parseColor("#00FF66") // Acid Emerald
            )
            "URL", "DOMN" -> Palette(
                glassBg = Color.parseColor("#F2080A14"),
                accent = Color.parseColor("#2E5BFF") // Hyper Cobalt
            )
            else -> Palette(
                glassBg = Color.parseColor("#F20C0D0E"),
                accent = Color.parseColor("#CCFF00") // Toxic Lime
            )
        }
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, android.R.anim.fade_out)
    }
}
