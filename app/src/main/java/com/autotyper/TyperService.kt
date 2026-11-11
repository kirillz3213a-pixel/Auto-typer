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
import java.util.Locale
import kotlin.random.Random

class TyperService : AccessibilityService() {
    companion object {
        @Volatile var instance: TyperService? = null
    }

    private sealed class Action {
        data class Text(val value: String) : Action()
        object Enter : Action()
        object Back : Action()
        data class Wait(val ms: Long) : Action()
        object Send : Action()
    }

    private val handler = Handler(Looper.getMainLooper())
    private var overlay: View? = null
    private var typing = false
    private var checkPending = false
    private var parts: List<List<Action>> = emptyList()
    private var partIdx = 0
    private var actionIdx = 0
    private var typed = StringBuilder()
    private var base = ""
    private var lostFocusTicks = 0
    private var lastCharWasTypo = false

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
        actionIdx = 0
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
        val rawParts = if (TyperState.splitMode) {
            TyperState.text.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }
        } else {
            listOf(TyperState.text)
        }
        parts = rawParts.map(::parseActions)
        if (parts.isEmpty()) {
            disarm()
            return
        }
        partIdx = 0
        actionIdx = 0
        typed = StringBuilder()
        base = if (Build.VERSION.SDK_INT >= 26 && node.isShowingHintText) "" else node.text?.toString() ?: ""
        lostFocusTicks = 0
        lastCharWasTypo = false
        typing = true
        tick()
    }

    private fun parseActions(part: String): List<Action> {
        val result = mutableListOf<Action>()
        val tokenRegex = Regex("\\{([^{}]*)}")
        var cursor = 0
        for (match in tokenRegex.findAll(part)) {
            appendTextActions(result, part.substring(cursor, match.range.first))
            val token = match.groupValues[1].trim()
            when {
                token.equals("ENTER", ignoreCase = true) -> result += Action.Enter
                token.equals("BACK", ignoreCase = true) -> result += Action.Back
                token.equals("SEND", ignoreCase = true) -> result += Action.Send
                token.matches(Regex("WAIT:\\d+", RegexOption.IGNORE_CASE)) -> {
                    val ms = token.substringAfter(':').toLongOrNull() ?: 0L
                    result += Action.Wait(ms.coerceIn(0, 60000))
                }
                else -> appendTextActions(result, match.value)
            }
            cursor = match.range.last + 1
        }
        appendTextActions(result, part.substring(cursor))
        return result
    }

    private fun appendTextActions(target: MutableList<Action>, text: String) {
        var index = 0
        while (index < text.length) {
            val next = text.offsetByCodePoints(index, 1)
            target += Action.Text(text.substring(index, next))
            index = next
        }
    }

    private fun tick() {
        if (!typing) return
        if (partIdx !in parts.indices) {
            disarm()
            return
        }
        val actions = parts[partIdx]
        if (actionIdx >= actions.size) {
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

        when (val action = actions[actionIdx]) {
            is Action.Text -> typeTextRun(actions, node)
            Action.Enter -> {
                actionIdx++
                if (Build.VERSION.SDK_INT >= 30) {
                    node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                } else {
                    typed.append('\n')
                    setTextAndCursor(node, base + typed)
                }
                postNextWithDelay("\n")
            }
            Action.Back -> {
                actionIdx++
                if (typed.isNotEmpty()) {
                    val end = typed.length
                    val start = typed.offsetByCodePoints(end, -1)
                    typed.delete(start, end)
                    setTextAndCursor(node, base + typed)
                }
                lastCharWasTypo = false
                postNextWithDelay("")
            }
            is Action.Wait -> {
                actionIdx++
                handler.postDelayed({ tick() }, action.ms)
            }
            Action.Send -> {
                actionIdx++
                clickSend()
                postNextWithDelay("")
            }
        }
    }

    private fun typeTextRun(actions: List<Action>, node: AccessibilityNodeInfo) {
        val limit = if (TyperState.humanize) 1 else TyperState.step.coerceAtLeast(1)
        var end = actionIdx
        val chunk = StringBuilder()
        while (end < actions.size && end - actionIdx < limit) {
            val next = actions[end] as? Action.Text ?: break
            chunk.append(next.value)
            end++
        }
        if (chunk.isEmpty()) {
            actionIdx++
            tick()
            return
        }
        actionIdx = end
        val value = chunk.toString()
        val cp = value.codePointAt(0)
        val letter = Character.isLetter(cp)
        val canTypo = TyperState.humanize && typed.isNotEmpty() && letter && !lastCharWasTypo &&
            Random.nextInt(100) < TyperState.typoPercent.coerceIn(0, 30)
        if (canTypo) {
            val wrong = randomWrongLetter(cp) ?: run {
                appendCorrectText(node, value)
                return
            }
            typed.append(wrong)
            setTextAndCursor(node, base + typed)
            val noticeDelay = Random.nextLong(150, 401)
            handler.postDelayed({
                if (!typing) return@postDelayed
                if (typed.isNotEmpty()) {
                    val lastStart = typed.offsetByCodePoints(typed.length, -1)
                    typed.delete(lastStart, typed.length)
                }
                focusedInput()?.let { setTextAndCursor(it, base + typed) }
                handler.postDelayed({
                    if (!typing) return@postDelayed
                    focusedInput()?.let {
                        typed.append(value)
                        setTextAndCursor(it, base + typed)
                    }
                    lastCharWasTypo = true
                    postNextWithDelay(value)
                }, Random.nextLong(80, 201))
            }, noticeDelay)
        } else {
            appendCorrectText(node, value)
        }
    }

    private fun appendCorrectText(node: AccessibilityNodeInfo, value: String) {
        typed.append(value)
        setTextAndCursor(node, base + typed)
        lastCharWasTypo = false
        postNextWithDelay(value)
    }

    private fun randomWrongLetter(codePoint: Int): String? {
        val alphabet = when (codePoint) {
            in 0x0400..0x052F -> "йцукенгшщзхфывапролджэячсмитьбю"
            in 0x0041..0x007A -> "qwertyuiopasdfghjklzxcvbnm"
            else -> return null
        }
        val original = String(Character.toChars(codePoint))
        var wrong = alphabet.random().toString()
        if (original.firstOrNull()?.isUpperCase() == true) wrong = wrong.uppercase(Locale.ROOT)
        return wrong
    }

    private fun postNextWithDelay(lastText: String) {
        var delay = TyperState.delayMs
        if (TyperState.humanize) {
            delay = (delay * when (lastText.lastOrNull()) {
                ' ' -> 1.6
                ',', '.', '!', '?', ':', ';' -> 2.5
                '\n' -> 3.0
                else -> 1.0
            }).toLong()
        }
        if (TyperState.jitter && delay > 0) delay = (delay * (0.5 + Random.nextDouble())).toLong()
        handler.postDelayed({ tick() }, delay.coerceAtLeast(0))
    }

    private fun setTextAndCursor(node: AccessibilityNodeInfo, value: CharSequence) {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        val selection = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, value.length)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, value.length)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection)
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
                    actionIdx = 0
                    typed = StringBuilder()
                    base = ""
                    lostFocusTicks = 0
                    lastCharWasTypo = false
                    tick()
                }, TyperState.pauseMs.coerceIn(0, 60000))
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
            val keys = listOf("send", "отправ", "надісл", "надсил", "відправ", "submit")
            if (keys.any(label::contains)) {
                var candidate: AccessibilityNodeInfo? = node
                while (candidate != null) {
                    if (candidate.isClickable) return candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    candidate = candidate.parent
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
        val view = overlay ?: return
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view)
        } catch (_: Exception) {
        }
        overlay = null
    }
}
