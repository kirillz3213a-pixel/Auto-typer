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
    private var partIndex = 0
    private var actionIndex = 0
    private var currentText = StringBuilder()
    private var base = ""
    private var lostFocusTicks = 0
    private var lastTypo = false

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
        partIndex = 0
        actionIndex = 0
        currentText = StringBuilder()
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
        if (rawParts.isEmpty()) {
            disarm()
            return
        }

        parts = rawParts.map(::parsePart)
        partIndex = 0
        actionIndex = 0
        currentText = StringBuilder()
        base = if (Build.VERSION.SDK_INT >= 26 && node.isShowingHintText) "" else node.text?.toString() ?: ""
        lostFocusTicks = 0
        lastTypo = false
        typing = true
        tick()
    }

    private fun parsePart(input: String): List<Action> {
        val result = mutableListOf<Action>()
        var cursor = 0
        val regex = Regex("\\{([^\\}]+)\\}")
        for (match in regex.findAll(input)) {
            val before = input.substring(cursor, match.range.first)
            appendText(result, before)

            val token = match.groupValues[1].trim()
            when {
                token.equals("ENTER", ignoreCase = true) -> result += Action.Enter
                token.equals("BACK", ignoreCase = true) -> result += Action.Back
                token.equals("SEND", ignoreCase = true) -> result += Action.Send
                token.startsWith("WAIT:", ignoreCase = true) -> {
                    val ms = token.substringAfter(':').toLongOrNull()?.coerceIn(0, 60000) ?: 0L
                    result += Action.Wait(ms)
                }
                else -> result += Action.Text(match.value)
            }
            cursor = match.range.last + 1
        }
        appendText(result, input.substring(cursor))
        return result
    }

    private fun appendText(target: MutableList<Action>, text: String) {
        var index = 0
        while (index < text.length) {
            val next = text.offsetByCodePoints(index, 1)
            target += Action.Text(text.substring(index, next))
            index = next
        }
    }

    private fun tick() {
        if (!typing) return
        if (partIndex >= parts.size) {
            finish()
            return
        }

        val actions = parts[partIndex]
        if (actionIndex >= actions.size) {
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

        when (val action = actions[actionIndex]) {
            is Action.Text -> handleTextAction(node, actions)
            Action.Enter -> {
                actionIndex++
                if (Build.VERSION.SDK_INT >= 30) {
                    node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                } else {
                    currentText.append('\n')
                    setTextAndCursor(node, base + currentText)
                }
                postDelayForNext()
            }
            Action.Back -> {
                actionIndex++
                if (currentText.isNotEmpty()) {
                    val lastCodeIndex = currentText.length
                    val deleteFrom = currentText.offsetByCodePoints(lastCodeIndex, -1)
                    currentText.delete(deleteFrom, lastCodeIndex)
                    setTextAndCursor(node, base + currentText)
                }
                lastTypo = false
                postDelayForNext()
            }
            is Action.Wait -> {
                actionIndex++
                handler.postDelayed({ tick() }, action.ms.coerceIn(0, 60000))
            }
            Action.Send -> {
                actionIndex++
                clickSend()
                postDelayForNext()
            }
        }
    }

    private fun handleTextAction(node: AccessibilityNodeInfo, actions: List<Action>) {
        val chunk = StringBuilder()
        var index = actionIndex
        var count = 0
        while (index < actions.size && count < TyperState.step.coerceAtLeast(1)) {
            val current = actions[index]
            if (current !is Action.Text) break
            chunk.append(current.value)
            index++
            count++
        }
        actionIndex = index
        val nextValue = chunk.toString()
        if (nextValue.isEmpty()) {
            if (actionIndex < actions.size) tick() else onPartComplete()
            return
        }

        if (TyperState.humanize) {
            val firstChar = nextValue.firstOrNull() ?: return
            val shouldTypo = firstChar.isLetter() && !lastTypo &&
                (currentText.isNotEmpty() || partIndex > 0) &&
                Random.nextInt(100) < TyperState.typoPercent.coerceIn(0, 30)
            if (shouldTypo) {
                val wrong = randomWrongLetter(firstChar) ?: nextValue
                currentText.append(wrong)
                setTextAndCursor(node, base + currentText)
                val notice = Random.nextLong(150, 401)
                handler.postDelayed({
                    if (!typing) return@postDelayed
                    val lastLength = currentText.length
                    val delFrom = currentText.offsetByCodePoints(lastLength, -1)
                    currentText.delete(delFrom, lastLength)
                    setTextAndCursor(node, base + currentText)
                    handler.postDelayed({
                        if (!typing) return@postDelayed
                        currentText.append(nextValue)
                        setTextAndCursor(node, base + currentText)
                        lastTypo = true
                        postDelayForNext()
                    }, Random.nextLong(80, 201))
                }, notice)
                return
            }
        }

        currentText.append(nextValue)
        setTextAndCursor(node, base + currentText)
        lastTypo = false
        postDelayForNext()
    }

    private fun randomWrongLetter(ch: Char): String? {
        val source = when {
            ch in 'а'..'я' || ch in 'А'..'Я' || ch == 'ё' || ch == 'Ё' -> "йцукенгшщзхфывапролджэячсмитьбю"
            ch in 'a'..'z' || ch in 'A'..'Z' -> "qwertyuiopasdfghjklzxcvbnm"
            else -> null
        } ?: return null
        val wrong = source.random().toString()
        return if (ch.isUpperCase()) wrong.uppercase(Locale.ROOT) else wrong
    }

    private fun postDelayForNext() {
        var delay = TyperState.delayMs
        if (TyperState.humanize && currentText.isNotEmpty()) {
            val lastChar = currentText.last()
            delay = when (lastChar) {
                ' ' -> (delay * 1.6).toLong()
                ',', '.', '!', '?', ':', ';' -> (delay * 2.5).toLong()
                '\n' -> (delay * 3.0).toLong()
                else -> delay
            }
        }
        if (TyperState.jitter && delay > 0) {
            delay = (delay * (0.5 + Random.nextDouble())).toLong()
        }
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
            if (partIndex == parts.lastIndex) {
                finish()
            } else {
                handler.postDelayed({
                    if (!typing) return@postDelayed
                    partIndex++
                    actionIndex = 0
                    currentText = StringBuilder()
                    base = ""
                    lostFocusTicks = 0
                    lastTypo = false
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
            val keywords = listOf("send", "отправ", "надісл", "надсил", "відправ", "submit")
            if (keywords.any { label.contains(it) }) {
                var candidate: AccessibilityNodeInfo? = node
                while (candidate != null) {
                    if (candidate.isClickable && candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        return true
                    }
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
