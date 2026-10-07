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

        // Dismiss associated capsule notification when expanded
        val notifId = intent.getIntExtra("EXTRA_NOTIFICATION_ID", -1)
        if (notifId != -1) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(notifId)
        }

        // Hardware Window Blur for Android 12+ (API 31+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes.blurBehindRadius = 70
        }

        val titleText = intent.getStringExtra("EXTRA_TITLE") ?: "LEXEME"
        val subtitleText = intent.getStringExtra("EXTRA_SUBTITLE") ?: "INSPECT // SYSTEM"
        val fullContentText = intent.getStringExtra("EXTRA_FULL_CONTENT") ?: ""
        val copyPayload = intent.getStringExtra("EXTRA_COPY_PAYLOAD") ?: fullContentText

        val density = resources.displayMetrics.density

        // Root Scrim
        val rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#40000000"))
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                finish()
            }
        }

        // Neo-Brutalist Surface Container (Obsidian, 2dp High-Contrast Border, 28dp Radii)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padH = (22 * density).toInt()
            val padV = (20 * density).toInt()
            setPadding(padH, (14 * density).toInt(), padH, padV)
            
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0C0C0E"))
                setStroke((2 * density).toInt(), Color.parseColor("#27272A"))
                cornerRadii = floatArrayOf(
                    28 * density, 28 * density,
                    28 * density, 28 * density,
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

        // 1. Accent Drag Handle (Cyber-Lime)
        val dragHandle = FrameLayout(this).apply {
            val handleWidth = (42 * density).toInt()
            val handleHeight = (4 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(handleWidth, handleHeight).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = (18 * density).toInt()
            }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#CCFF00"))
                cornerRadius = 2 * density
            }
        }
        card.addView(dragHandle)

        // 2. Editorial Category Kicker
        val kickerTag = TextView(this).apply {
            text = subtitleText.uppercase()
            textSize = 10f
            setTextColor(Color.parseColor("#CCFF00"))
            typeface = Typeface.create("sans-serif-condensed-medium", Typeface.BOLD)
            letterSpacing = 0.16f
        }
        card.addView(kickerTag)

        // 3. Monolithic Lexeme / Headline
        val headlineView = TextView(this).apply {
            text = titleText
            textSize = 28f
            setTextColor(Color.parseColor("#FAFAFA"))
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = -0.02f
            setPadding(0, (2 * density).toInt(), 0, (12 * density).toInt())
        }
        card.addView(headlineView)

        // 4. Subtle Border Divider
        val divider = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (1 * density).toInt()
            ).apply {
                bottomMargin = (14 * density).toInt()
            }
            setBackgroundColor(Color.parseColor("#27272A"))
        }
        card.addView(divider)

        // 5. Scrollable Editorial Body
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                weight = 1f
                bottomMargin = (20 * density).toInt()
            }
            isVerticalScrollBarEnabled = false
        }

        val contentView = TextView(this).apply {
            text = formatEditorialBody(fullContentText)
            textSize = 14f
            setTextColor(Color.parseColor("#D4D4D8"))
            setLineSpacing(0f, 1.35f)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setTextIsSelectable(true)
        }
        scrollView.addView(contentView)
        card.addView(scrollView)

        // 6. Tactile Inverted Action Row
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        val copyButton = Button(this).apply {
            text = "COPY DATA"
            textSize = 11f
            setTextColor(Color.parseColor("#0C0C0E"))
            typeface = Typeface.create("sans-serif-condensed-medium", Typeface.BOLD)
            letterSpacing = 0.08f
            
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FAFAFA"))
                cornerRadius = 14 * density
            }
            val padW = (18 * density).toInt()
            setPadding(padW, 0, padW, 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                (38 * density).toInt()
            ).apply {
                marginEnd = (10 * density).toInt()
            }
            
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val clip = ClipData.newPlainText("LiveStatus Detail", copyPayload)
                clipboard?.setPrimaryClip(clip)
                Toast.makeText(this@InspectDetailActivity, "COPIED TO CLIPBOARD", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
        buttonRow.addView(copyButton)

        val dismissButton = Button(this).apply {
            text = "DISMISS"
            textSize = 11f
            setTextColor(Color.parseColor("#A1A1AA"))
            typeface = Typeface.create("sans-serif-condensed-medium", Typeface.BOLD)
            letterSpacing = 0.08f
            
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#18181B"))
                setStroke((1 * density).toInt(), Color.parseColor("#3F3F46"))
                cornerRadius = 14 * density
            }
            val padW = (16 * density).toInt()
            setPadding(padW, 0, padW, 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                (38 * density).toInt()
            )

            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                finish()
            }
        }
        buttonRow.addView(dismissButton)

        card.addView(buttonRow)
        rootLayout.addView(card, cardParams)

        setContentView(rootLayout)
    }

    private fun formatEditorialBody(raw: String): String {
        // Enforces clean spacing between parts-of-speech or numbered list items
        return raw.replace(Regex("""(?m)^([0-9]+\.\s)"""), "\n$1").trim()
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, android.R.anim.fade_out)
    }
}
