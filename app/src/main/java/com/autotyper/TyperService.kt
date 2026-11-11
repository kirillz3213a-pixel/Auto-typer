package com.autotyper

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
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
import java.util.Locale

class TyperService : AccessibilityService() {
    companion object {
        @Volatile
        var instance: TyperService? = null
    }

    private val handler = Handler(Looper.getMainLooper())
    private var overlay: View? = null
    private var typing = false
    private var checkPending = false
    private var parts: List<String> = emptyList()
    private var partIdx = 0
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

    fun arm() {
        typing = false
        parts = emptyList()
        TyperState.armed = true
        showOverlay()
    }

    fun disarm() {
        TyperState.armed = false
        typing = false
        checkPending = false
        parts = emptyList()
        partIdx = 0
        handler.removeCallbacksAndMessages(null)
        hideOverlay()
    }

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

        parts = if (TyperState.splitMode) {
            TyperState.text.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }
        } else {
            listOf(TyperState.text)
        }
        if (parts.isEmpty()) {
            disarm()
            return
        }

        partIdx = 0
        base = if (node.isShowingHintText) "" else node.text?.toString() ?: ""
        pos = 0
        lostFocusTicks = 0
        typing = true
        tick()
    }

    private fun tick() {
        if (!typing) return
        if (partIdx !in parts.indices) {
            disarm()
            return
        }
        val full = parts[partIdx]
        if (pos >= full.length) {
            onPartComplete()
            return
        }

        val node = focusedInput()
        if (node == null) {
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
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, result)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        val sel = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, result.length)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, result.length)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
        var d = TyperState.delayMs
        if (TyperState.jitter && d > 0) d = (d * (0.5 + Random.nextDouble())).toLong()
        handler.postDelayed({ tick() }, d)
    }

    private fun onPartComplete() {
        if (!TyperState.autoSend && !TyperState.splitMode) {
            finish()
            return
        }
        handler.postDelayed({
            if (!typing) return@postDelayed
            clickSend()
            if (partIdx == parts.lastIndex) {
                finish()
            } else {
                handler.postDelayed({
                    if (!typing) return@postDelayed
                    partIdx++
                    pos = 0
                    base = ""
                    lostFocusTicks = 0
                    tick()
                }, TyperState.pauseMs)
            }
        }, 400)
    }

    private fun clickSend(): Boolean {
        val root = rootInActiveWindow
        if (root != null && clickSendInTree(root)) return true
        if (Build.VERSION.SDK_INT >= 30) {
            return focusedInput()?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id) == true
        }
        return false
    }

    private fun clickSendInTree(node: AccessibilityNodeInfo): Boolean {
        if (!node.isEditable) {
            val shortText = node.text?.toString()?.takeIf { it.length <= 12 }.orEmpty()
            val label = "${node.contentDescription ?: ""} ${node.viewIdResourceName ?: ""} $shortText"
                .lowercase(Locale.ROOT)
            val sendLabels = listOf("send", "отправ", "надісл", "надсил", "відправ", "submit")
            if (sendLabels.any { label.contains(it) }) {
                var clickable: AccessibilityNodeInfo? = node
                while (clickable != null) {
                    if (clickable.isClickable && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        return true
                    }
                    clickable = clickable.parent
                }
            }
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            if (clickSendInTree(child)) return true
        }
        return false
    }

    private fun finish() {
        Toast.makeText(this, "Готово ✓", Toast.LENGTH_SHORT).show()
        disarm()
    }

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
