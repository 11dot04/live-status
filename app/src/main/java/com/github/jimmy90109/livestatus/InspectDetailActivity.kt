package com.github.jimmy90109.livestatus

import android.app.Activity
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
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

        // Dismiss the associated notification when the user views the expanded sheet
        val notifId = intent.getIntExtra("EXTRA_NOTIFICATION_ID", -1)
        if (notifId != -1) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(notifId)
        }

        val titleText = intent.getStringExtra("EXTRA_TITLE") ?: "Details"
        val subtitleText = intent.getStringExtra("EXTRA_SUBTITLE")
        val fullContentText = intent.getStringExtra("EXTRA_FULL_CONTENT") ?: ""
        val copyPayload = intent.getStringExtra("EXTRA_COPY_PAYLOAD") ?: fullContentText

        // Dimmed backdrop wrapper
        val rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#80000000"))
            setOnClickListener { finish() }
        }

        // Bottom Sheet Card container
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val density = resources.displayMetrics.density
            val pad = (20 * density).toInt()
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E1E1E"))
                cornerRadii = floatArrayOf(
                    24 * density, 24 * density, // top-left
                    24 * density, 24 * density, // top-right
                    0f, 0f, 0f, 0f
                )
            }
            // Prevent outside touches on the card itself from dismissing
            isClickable = true
        }

        val cardParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM
        }

        // 1. Header Title
        val titleView = TextView(this).apply {
            text = titleText
            textSize = 20f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        card.addView(titleView)

        // 2. Subtitle (Optional)
        if (!subtitleText.isNullOrBlank()) {
            val subtitleView = TextView(this).apply {
                text = subtitleText
                textSize = 14f
                setTextColor(Color.parseColor("#B0B0B0"))
                setPadding(0, 4, 0, 0)
            }
            card.addView(subtitleView)
        }

        // 3. Scrollable Expanded Content (Bypasses ColorOS 2-line cap)
        val scrollView = ScrollView(this).apply {
            val scrollParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                val density = resources.displayMetrics.density
                topMargin = (16 * density).toInt()
                bottomMargin = (16 * density).toInt()
            }
            layoutParams = scrollParams
        }

        val contentView = TextView(this).apply {
            text = fullContentText
            textSize = 15f
            setTextColor(Color.parseColor("#E0E0E0"))
            setLineSpacing(0f, 1.25f)
            setTextIsSelectable(true)
        }
        scrollView.addView(contentView)
        card.addView(scrollView)

        // 4. Action Buttons (Copy & Dismiss)
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        val copyButton = Button(this).apply {
            text = "Copy"
            setTextColor(Color.parseColor("#90CAF9"))
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val clip = ClipData.newPlainText("LiveStatus Detail", copyPayload)
                clipboard?.setPrimaryClip(clip)
                Toast.makeText(this@InspectDetailActivity, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
        buttonRow.addView(copyButton)

        val closeButton = Button(this).apply {
            text = "Close"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { finish() }
        }
        buttonRow.addView(closeButton)

        card.addView(buttonRow)
        rootLayout.addView(card, cardParams)

        setContentView(rootLayout)
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, android.R.anim.fade_out)
    }
}
