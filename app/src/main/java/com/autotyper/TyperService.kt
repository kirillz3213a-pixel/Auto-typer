package com.autotyper

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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

        private const val MIN_SET_TEXT_INTERVAL_MS = 30L
        private const val MAX_SEND_TREE_DEPTH = 40
        private const val MAX_SEND_TREE_NODES = 1500
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
    private var cachedInput: AccessibilityNodeInfo? = null
    private var lostFocusTicks = 0
    private var lastTypo = false
    private var sendAttemptedInPart = false
    private var lastSetTextAt = 0L

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
        cachedInput = null
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
        cachedInput = null
        handler.removeCallbacksAndMessages(null)
        hideOverlay()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (typing || !TyperState.armed) return
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
        return if (node != null && node.isEditable && node.isFocused) node else null
    }

    private fun currentInput(): AccessibilityNodeInfo? {
        val cached = cachedInput
        if (cached != null) {
            val refreshed = try { cached.refresh() } catch (_: Exception) { false }
            if (refreshed && cached.isEditable && cached.isFocused) return cached
        }
        return focusedInput().also { cachedInput = it }
    }

    private fun tryStart() {
        if (!TyperState.armed || typing) return
        val node = focusedInput() ?: return
        if (node.packageName == packageName) return

        val rawParts = if (TyperState.splitMode) {
            val split = if (TyperState.splitNewline) {
                TyperState.text.split(Regex("[,，\\r\\n]+"))
            } else {
                TyperState.text.split(',', '，')
            }
            split.map { it.trim() }.filter { it.isNotEmpty() }
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
        base = ""
        cachedInput = node
        lostFocusTicks = 0
        lastTypo = false
        sendAttemptedInPart = false
        lastSetTextAt = 0L
        typing = true
        tick()
    }

    private fun parsePart(input: String): List<Action> {
        val result = mutableListOf<Action>()
        var cursor = 0
        val tokenRegex = Regex("\\{([^{}]+)\\}")
        for (match in tokenRegex.findAll(input)) {
            appendText(result, input.substring(cursor, match.range.first))
            val token = match.groupValues[1].trim()
            when {
                token.equals("ENTER", ignoreCase = true) -> result += Action.Enter
                token.equals("BACK", ignoreCase = true) -> result += Action.Back
                token.equals("SEND", ignoreCase = true) -> result += Action.Send
                token.matches(Regex("WAIT:\\d+", RegexOption.IGNORE_CASE)) -> {
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

        val node = currentInput()
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
                    postDelayForNext()
                } else {
                    currentText.append('\n')
                    setTextRateLimited(node, base + currentText) { postDelayForNext() }
                }
            }
            Action.Back -> {
                actionIndex++
                if (currentText.isNotEmpty()) {
                    val length = currentText.length
                    val from = currentText.offsetByCodePoints(length, -1)
                    currentText.delete(from, length)
                    setTextRateLimited(node, base + currentText) {
                        lastTypo = false
                        postDelayForNext()
                    }
                } else {
                    lastTypo = false
                    postDelayForNext()
                }
            }
            is Action.Wait -> {
                actionIndex++
                handler.postDelayed({ tick() }, action.ms.coerceIn(0, 60000))
            }
            Action.Send -> {
                actionIndex++
                sendCurrentPart()
                postDelayForNext()
            }
        }
    }

    private fun handleTextAction(node: AccessibilityNodeInfo, actions: List<Action>) {
        val plainCharsInPart = actions.sumOf { action ->
            if (action is Action.Text) action.value.codePointCount(0, action.value.length) else 0
        }
        val step = when {
            TyperState.humanize -> 1
            TyperState.step == 1 && plainCharsInPart > 200 -> 2
            else -> TyperState.step.coerceAtLeast(1)
        }
        val chunk = StringBuilder()
        var next = actionIndex
        while (next < actions.size && chunk.codePointCount(0, chunk.length) < step) {
            val textAction = actions[next] as? Action.Text ?: break
            chunk.append(textAction.value)
            next++
        }
        actionIndex = next
        val value = chunk.toString()
        if (value.isEmpty()) {
            tick()
            return
        }

        val codePoint = value.codePointAt(0)
        val isFirstCharacter = currentText.isEmpty()
        val wrongLetter = if (TyperState.humanize && !isFirstCharacter && !lastTypo &&
            Character.isLetter(codePoint) && Random.nextInt(100) < TyperState.typoPercent.coerceIn(0, 30)
        ) randomWrongLetter(codePoint) else null

        if (wrongLetter != null) {
            currentText.append(wrongLetter)
            setTextRateLimited(node, base + currentText) {
                handler.postDelayed({
                    if (!typing) return@postDelayed
                    val current = cachedInput
                    if (current == null) {
                        tick()
                        return@postDelayed
                    }
                    val length = currentText.length
                    val from = currentText.offsetByCodePoints(length, -1)
                    currentText.delete(from, length)
                    setTextRateLimited(current, base + currentText) {
                        handler.postDelayed({
                            if (!typing) return@postDelayed
                            val refreshedInput = cachedInput
                            if (refreshedInput == null) {
                                tick()
                                return@postDelayed
                            }
                            currentText.append(value)
                            setTextRateLimited(refreshedInput, base + currentText) {
                                lastTypo = true
                                postDelayForNext()
                            }
                        }, Random.nextLong(80, 201))
                    }
                }, Random.nextLong(150, 401))
            }
        } else {
            currentText.append(value)
            setTextRateLimited(node, base + currentText) {
                lastTypo = false
                postDelayForNext()
            }
        }
    }

    private fun randomWrongLetter(codePoint: Int): String? {
        val alphabet = when (codePoint) {
            in 0x0400..0x052F -> "йцукенгшщзхфывапролджэячсмитьбю"
            in 0x0041..0x007A -> "qwertyuiopasdfghjklzxcvbnm"
            else -> return null
        }
        var wrong = alphabet.random().toString()
        if (Character.isUpperCase(codePoint)) wrong = wrong.uppercase(Locale.ROOT)
        return wrong
    }

    private fun postDelayForNext() {
        var delay = TyperState.delayMs
        if (TyperState.humanize && currentText.isNotEmpty()) {
            delay = (delay * when (currentText.last()) {
                ' ' -> 1.6
                ',', '.', '!', '?', ':', ';' -> 2.5
                '\n' -> 3.0
                else -> 1.0
            }).toLong()
        }
        if (TyperState.jitter && delay > 0) delay = (delay * (0.5 + Random.nextDouble())).toLong()
        handler.postDelayed({ tick() }, delay.coerceAtLeast(0))
    }

    private fun setTextRateLimited(node: AccessibilityNodeInfo, value: CharSequence, after: () -> Unit) {
        val remaining = MIN_SET_TEXT_INTERVAL_MS - (SystemClock.elapsedRealtime() - lastSetTextAt)
        if (remaining > 0) {
            handler.postDelayed({
                if (!typing) return@postDelayed
                performSetText(node, value)
                after()
            }, remaining)
        } else {
            performSetText(node, value)
            after()
        }
    }

    private fun performSetText(node: AccessibilityNodeInfo, value: CharSequence) {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        lastSetTextAt = SystemClock.elapsedRealtime()
    }

    private fun moveCursorToEndIfNeeded() {
        val node = cachedInput ?: return
        if (!node.isEditable || !node.isFocused) return
        val end = (base + currentText).length
        if (node.textSelectionStart == end && node.textSelectionEnd == end) return
        val selection = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, end)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection)
    }

    private fun sendCurrentPart() {
        if (sendAttemptedInPart) return
        moveCursorToEndIfNeeded()
        sendAttemptedInPart = true
        clickSend()
    }

    private fun onPartComplete() {
        if (!TyperState.autoSend && !TyperState.splitMode) {
            finish()
            return
        }
        handler.postDelayed({
            if (!typing) return@postDelayed
            sendCurrentPart()
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
                    sendAttemptedInPart = false
                    tick()
                }, TyperState.pauseMs.coerceIn(0, 60000))
            }
        }, 400)
    }

    private fun clickSend(): Boolean {
        val root = rootInActiveWindow
        if (root != null && clickSendInTree(root, 0, intArrayOf(0))) return true
        if (Build.VERSION.SDK_INT >= 30) {
            return cachedInput?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id) == true
        }
        return false
    }

    private fun clickSendInTree(node: AccessibilityNodeInfo, depth: Int, visited: IntArray): Boolean {
        if (depth > MAX_SEND_TREE_DEPTH || visited[0] >= MAX_SEND_TREE_NODES) return false
        visited[0]++
        if (!node.isEditable) {
            val shortText = node.text?.toString()?.takeIf { it.length <= 12 }.orEmpty()
            val label = "${node.contentDescription ?: ""} ${node.viewIdResourceName ?: ""} $shortText"
                .lowercase(Locale.ROOT)
            val keywords = listOf("send", "отправ", "надісл", "надсил", "відправ", "submit")
            if (keywords.any(label::contains)) {
                var candidate: AccessibilityNodeInfo? = node
                var parentDepth = 0
                while (candidate != null && parentDepth <= MAX_SEND_TREE_DEPTH) {
                    if (candidate.isClickable && candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                    candidate = candidate.parent
                    parentDepth++
                }
            }
        }
        for (index in 0 until node.childCount) {
            if (visited[0] >= MAX_SEND_TREE_NODES || depth >= MAX_SEND_TREE_DEPTH) break
            val child = node.getChild(index) ?: continue
            if (clickSendInTree(child, depth + 1, visited)) return true
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
