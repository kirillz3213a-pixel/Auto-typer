package com.autotyper

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.Toast
import kotlin.random.Random

class TyperService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: TyperService? = null
    }

    private val handler = Handler(Looper.getMainLooper())
    private var overlay: View? = null

    private var typing = false
    private var checkPending = false
    private var pos = 0
    private var base = ""
    private var lostFocusTicks = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        disarm()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}

    // ---------- управление ----------

    fun arm() {
        typing = false
        TyperState.armed = true
        showOverlay()
    }

    fun disarm() {
        TyperState.armed = false
        typing = false
        checkPending = false
        handler.removeCallbacksAndMessages(null)
        hideOverlay()
    }

    // ---------- ждём тап по полю ----------

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!TyperState.armed || typing) return
        if (event.packageName == packageName) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                if (checkPending) return
                checkPending = true
                handler.postDelayed({
                    checkPending = false
                    tryStart()
                }, 150)
            }
        }
    }

    private fun focusedInput(): AccessibilityNodeInfo? {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        return if (node != null && node.isEditable) node else null
    }

    private fun tryStart() {
        if (!TyperState.armed || typing) return
        val node = focusedInput() ?: return
        if (node.packageName == packageName) return
        base = if (node.isShowingHintText) "" else node.text?.toString() ?: ""
        pos = 0
        lostFocusTicks = 0
        typing = true
        tick()
    }

    // ---------- сам ввод ----------

    private fun tick() {
        if (!typing) return
        val full = TyperState.text
        if (pos >= full.length) {
            finish()
            return
        }

        val node = focusedInput()
        if (node == null) {
            // поле потеряло фокус — ждём до ~10 секунд
            lostFocusTicks++
            if (lostFocusTicks > 40) {
                Toast.makeText(this, "Поле потеряно — остановлено", Toast.LENGTH_SHORT).show()
                disarm()
                return
            }
            handler.postDelayed({ tick() }, 250)
            return
        }
        lostFocusTicks = 0

        var end = pos
        repeat(TyperState.step.coerceAtLeast(1)) {
            if (end < full.length) end = full.offsetByCodePoints(end, 1)
        }
        pos = end

        val result = base + full.substring(0, pos)
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                result
            )
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

        // курсор в конец
        val sel = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, result.length)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, result.length)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)

        var d = TyperState.delayMs
        if (TyperState.jitter && d > 0) {
            d = (d * (0.5 + Random.nextDouble())).toLong()
        }
        handler.postDelayed({ tick() }, d)
    }

    private fun finish() {
        Toast.makeText(this, "Готово ✓", Toast.LENGTH_SHORT).show()
        disarm()
    }

    // ---------- плавающая кнопка СТОП ----------

    private fun showOverlay() {
        if (overlay != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val btn = Button(this).apply {
            text = "■ СТОП"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#D32F2F"))
            setOnClickListener { disarm() }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            y = 160
            x = 16
        }
        wm.addView(btn, lp)
        overlay = btn
    }

    private fun hideOverlay() {
        val v = overlay ?: return
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (_: Exception) {
        }
        overlay = null
    }
}
