package dev.unifiedcall.flutter_unified_call

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

class IncomingCallActivity : Activity() {
    private lateinit var runtime: CallRuntime
    private var callId: String? = null
    private val update: (Map<String, Any?>) -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(28, 43, 72)
        window.navigationBarColor = Color.rgb(8, 15, 29)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        runtime = CallRuntime.get(this)
        runtime.listeners.add(update)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent) {
        callId = intent.getStringExtra("callId")
        if (intent.action == "accepted") {
            callId?.let(::accept)
            return
        }
        render()
    }

    private fun accept(id: String) {
        runtime.act(id, "accepted")
        if (runtime.store.get(id)?.getString("state") != "accepted") return
        if (!runtime.isAppForeground) {
            packageManager.getLaunchIntentForPackage(packageName)?.let {
                startActivity(
                    it.putExtra("unifiedCallId", id)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                )
            }
        }
        finish()
    }

    private fun render() {
        val id = callId ?: return finish()
        val call = runtime.store.get(id) ?: return finish()
        val state = call.optString("state")
        if (state == "ended") return finish()

        val config = call.optJSONObject("config") ?: return finish()
        val callerName = config.optString("callerName").ifBlank { "Unknown caller" }
        val callerHandle = config.optString("callerHandle")
        val isVideo = config.optString("mediaType") == "video"
        val isRinging = state == "ringing"
        val chinese = resources.configuration.locales[0].language == "zh"

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(38), dp(28), dp(32))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.rgb(28, 43, 72), Color.rgb(8, 15, 29)),
            )
        }

        content.addView(textView(
            if (isRinging) localized(chinese, "来电", "INCOMING CALL")
            else localized(chinese, "通话中", "CALL IN PROGRESS"),
            12f,
            Color.rgb(177, 192, 216),
            bold = true,
        ).apply { letterSpacing = 0.18f })
        content.addView(spacer(52))
        content.addView(callerAvatar(callerName))
        content.addView(spacer(30))
        content.addView(textView(callerName, 32f, Color.WHITE, bold = true).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        content.addView(spacer(10))
        content.addView(textView(
            when {
                state == "connected" -> localized(chinese, "已接通", "Connected")
                state == "accepted" -> localized(chinese, "正在接通", "Connecting")
                isVideo -> localized(chinese, "视频来电", "Incoming video call")
                else -> localized(chinese, "语音来电", "Incoming audio call")
            },
            16f,
            Color.rgb(190, 202, 222),
        ))
        if (callerHandle.isNotBlank()) {
            content.addView(spacer(8))
            content.addView(textView(callerHandle, 15f, Color.rgb(153, 169, 195)))
        }

        content.addView(View(this), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val options = runtime.store.options()
        val declineText = callActionLabel(options.optString("declineText"), "Decline", chinese, "挂断", "Decline")
        val acceptText = callActionLabel(options.optString("acceptText"), "Accept", chinese, "接听", "Accept")
        if (isRinging) {
            actions.addView(actionButton(
                false,
                declineText,
                Color.rgb(226, 72, 88),
            ) {
                runtime.act(id, "declined", "declined")
                finish()
            }, weightedActionParams())
            actions.addView(actionButton(
                true,
                acceptText,
                Color.rgb(39, 185, 120),
            ) { accept(id) }, weightedActionParams())
        } else {
            actions.addView(actionButton(
                false,
                localized(chinese, "结束通话", "End call"),
                Color.rgb(226, 72, 88),
            ) {
                runtime.act(id, "ended", "localEnded")
                finish()
            }, weightedActionParams())
        }
        content.addView(actions, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(8, 15, 29))
            addView(content, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ))
        }
        setContentView(root)
    }

    private fun callerAvatar(name: String): TextView = TextView(this).apply {
        val initial = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        text = initial
        textSize = 38f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.rgb(95, 132, 218), Color.rgb(105, 88, 194)),
        ).apply { shape = GradientDrawable.OVAL }
        layoutParams = LinearLayout.LayoutParams(dp(104), dp(104))
        elevation = dp(8).toFloat()
        contentDescription = localized(resources.configuration.locales[0].language == "zh", "来电者头像", "Caller avatar")
    }

    private fun actionButton(
        accepting: Boolean,
        label: String,
        color: Int,
        onClick: () -> Unit,
    ): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER

        val circle = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        val button = ImageButton(this@IncomingCallActivity).apply {
            setImageDrawable(PhoneIconDrawable(accepting))
            background = RippleDrawable(
                ColorStateList.valueOf(Color.argb(110, 255, 255, 255)),
                circle,
                GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) },
            )
            contentDescription = label
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            isClickable = true
            isFocusable = true
            setPadding(dp(18), dp(18), dp(18), dp(18))
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.9f).scaleY(0.9f).setDuration(90).start()
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                }
                false
            }
            setOnClickListener { isEnabled = false; onClick() }
        }
        addView(button, LinearLayout.LayoutParams(dp(72), dp(72)))
        addView(spacer(12))
        addView(textView(label, 14f, Color.WHITE, bold = true))
    }

    private fun textView(
        text: String,
        size: Float,
        color: Int,
        bold: Boolean = false,
    ): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        gravity = Gravity.CENTER
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun spacer(heightDp: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(heightDp),
        )
    }

    private fun weightedActionParams() = LinearLayout.LayoutParams(
        0,
        LinearLayout.LayoutParams.WRAP_CONTENT,
        1f,
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun localized(chinese: Boolean, zh: String, en: String) = if (chinese) zh else en

    private fun callActionLabel(value: String, default: String, chinese: Boolean, zh: String, en: String): String =
        if (value.isBlank() || value == default) localized(chinese, zh, en) else value

    private class PhoneIconDrawable(private val accepting: Boolean) : android.graphics.drawable.Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
        private val path = Path()

        override fun draw(canvas: Canvas) {
            val side = minOf(bounds.width(), bounds.height()).toFloat()
            val save = canvas.save()
            canvas.translate(bounds.centerX().toFloat(), bounds.centerY().toFloat())
            canvas.scale(side / 48f, side / 48f)
            if (!accepting) canvas.rotate(135f)
            path.reset()
            path.moveTo(-18f, -17f); path.quadTo(-21f, -14f, -19f, -8f)
            path.quadTo(-13f, 9f, 3f, 18f); path.quadTo(9f, 21f, 13f, 17f)
            path.lineTo(18f, 11f); path.quadTo(19f, 9f, 16f, 7f)
            path.lineTo(8f, 2f); path.quadTo(6f, 1f, 4f, 4f)
            path.lineTo(1f, 8f); path.quadTo(-7f, 3f, -10f, -5f)
            path.lineTo(-6f, -8f); path.quadTo(-4f, -10f, -6f, -12f)
            path.lineTo(-12f, -19f); path.quadTo(-15f, -21f, -18f, -17f)
            path.close()
            canvas.drawPath(path, paint)
            canvas.restoreToCount(save)
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Android")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }

    override fun onDestroy() {
        if (::runtime.isInitialized) runtime.listeners.remove(update)
        super.onDestroy()
    }
}
