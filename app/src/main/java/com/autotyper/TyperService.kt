package com.autotyper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
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
import android.widget.LinearLayout
import android.widget.Toast
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.random.Random

class TyperService : AccessibilityService() {
    companion object {
        @Volatile var instance: TyperService? = null
        private const val MIN_SET_TEXT_INTERVAL_MS = 30L
        private const val MAX_SEND_TREE_DEPTH = 40
        private const val MAX_SEND_TREE_NODES = 1500
        private const val RULES_PREFS = "send_rules"
    }

    private sealed class Action {
        data class Text(val value: String) : Action()
        object Enter : Action()
        object Back : Action()
        data class Wait(val ms: Long) : Action()
        object Send : Action()
    }

    private data class SendCandidate(val node: AccessibilityNodeInfo, val exactLabel: Boolean, val right: Int, val bottom: Int)
    private data class LearnedRule(
        val viewId: String, val description: String, val text: String,
        val className: String, val xRatio: Float, val yRatio: Float
    )

    private val handler = Handler(Looper.getMainLooper())
    private var overlay: View? = null
    private var learning = false
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

    override fun onServiceConnected() { super.onServiceConnected(); instance = this }
    override fun onUnbind(intent: android.content.Intent?): Boolean { disarm(); instance = null; return super.onUnbind(intent) }
    override fun onInterrupt() {}

    fun startLearn() {
        typing = false
        learning = true
        TyperState.armed = false
        showLearnOverlay()
    }

    fun clearLearnedRules() { getSharedPreferences(RULES_PREFS, MODE_PRIVATE).edit().clear().apply() }

    private fun stopLearn() { learning = false; hideOverlay() }

    fun arm() {
        learning = false
        typing = false
        parts = emptyList()
        cachedInput = null
        TyperState.armed = true
        showOverlay()
    }

    fun disarm() {
        learning = false
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
        if (learning && event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED && event.packageName != packageName) {
            saveLearnedRule(event)
            return
        }
        if (typing || !TyperState.armed || event.packageName == packageName) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED, AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                if (checkPending) return
                checkPending = true
                handler.postDelayed({ checkPending = false; tryStart() }, 150)
            }
        }
    }

    private fun saveLearnedRule(event: AccessibilityEvent) {
        val source = event.source ?: return
        val target = firstClickableParent(source) ?: source
        val packageName = event.packageName?.toString()?.takeIf { it.isNotBlank() } ?: return
        val bounds = Rect()
        target.getBoundsInScreen(bounds)
        val width = resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val height = resources.displayMetrics.heightPixels.coerceAtLeast(1)
        val text = target.text?.toString().orEmpty().takeIf { it.length <= 12 }.orEmpty()
        val rule = JSONObject().apply {
            put("viewId", target.viewIdResourceName.orEmpty())
            put("description", target.contentDescription?.toString().orEmpty())
            put("text", text)
            put("className", target.className?.toString().orEmpty())
            put("xRatio", bounds.centerX().toFloat() / width)
            put("yRatio", bounds.centerY().toFloat() / height)
        }
        getSharedPreferences(RULES_PREFS, MODE_PRIVATE).edit().putString(packageName, rule.toString()).apply()
        Toast.makeText(this, "Запомнено ✓ ($packageName)", Toast.LENGTH_SHORT).show()
        stopLearn()
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
            val split = if (TyperState.splitNewline) TyperState.text.split(Regex("[,，\\r\\n]+")) else TyperState.text.split(',', '，')
            split.map { it.trim() }.filter { it.isNotEmpty() }
        } else listOf(TyperState.text)
        if (rawParts.isEmpty()) { disarm(); return }
        parts = rawParts.map(::parsePart)
        partIndex = 0; actionIndex = 0; currentText = StringBuilder(); base = ""
        cachedInput = node; lostFocusTicks = 0; lastTypo = false; sendAttemptedInPart = false
        lastSetTextAt = 0L; typing = true; tick()
    }

    private fun parsePart(input: String): List<Action> {
        val result = mutableListOf<Action>(); var cursor = 0
        for (match in Regex("\\{([^{}]+)\\}").findAll(input)) {
            appendText(result, input.substring(cursor, match.range.first))
            val token = match.groupValues[1].trim()
            when {
                token.equals("ENTER", true) -> result += Action.Enter
                token.equals("BACK", true) -> result += Action.Back
                token.equals("SEND", true) -> result += Action.Send
                token.matches(Regex("WAIT:\\d+", RegexOption.IGNORE_CASE)) -> result += Action.Wait(token.substringAfter(':').toLongOrNull()?.coerceIn(0, 60000) ?: 0L)
                else -> result += Action.Text(match.value)
            }
            cursor = match.range.last + 1
        }
        appendText(result, input.substring(cursor)); return result
    }
n    private fun appendText(target: MutableList<Action>, text: String) {
        var index = 0
        while (index < text.length) { val next = text.offsetByCodePoints(index, 1); target += Action.Text(text.substring(index, next)); index = next }
    }

    private fun tick() {
        if (!typing) return
        if (partIndex >= parts.size) { finish(); return }
        val actions = parts[partIndex]
        if (actionIndex >= actions.size) { onPartComplete(); return }
        val node = currentInput()
        if (node == null) {
            if (++lostFocusTicks > 40) { Toast.makeText(this, "Поле потеряно — остановлено", Toast.LENGTH_SHORT).show(); disarm(); return }
            handler.postDelayed({ tick() }, 250); return
        }
        lostFocusTicks = 0
        when (val action = actions[actionIndex]) {
            is Action.Text -> handleTextAction(node, actions)
            Action.Enter -> { actionIndex++; if (Build.VERSION.SDK_INT >= 30) { node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id); postDelayForNext() } else { currentText.append('\n'); setTextRateLimited(node, base + currentText) { postDelayForNext() } } }
            Action.Back -> {
                actionIndex++
                if (currentText.isNotEmpty()) { val length = currentText.length; val from = currentText.offsetByCodePoints(length, -1); currentText.delete(from, length); setTextRateLimited(node, base + currentText) { lastTypo = false; postDelayForNext() } }
                else { lastTypo = false; postDelayForNext() }
            }
            is Action.Wait -> { actionIndex++; handler.postDelayed({ tick() }, action.ms.coerceIn(0, 60000)) }
            Action.Send -> { actionIndex++; sendCurrentPart(); postDelayForNext() }
        }
    }

    private fun handleTextAction(node: AccessibilityNodeInfo, actions: List<Action>) {
        val plainCharsInPart = actions.sumOf { if (it is Action.Text) it.value.codePointCount(0, it.value.length) else 0 }
        val step = when { TyperState.humanize -> 1; TyperState.step == 1 && plainCharsInPart > 200 -> 2; else -> TyperState.step.coerceAtLeast(1) }
        val chunk = StringBuilder(); var next = actionIndex
        while (next < actions.size && chunk.codePointCount(0, chunk.length) < step) { val a = actions[next] as? Action.Text ?: break; chunk.append(a.value); next++ }
        actionIndex = next
        val value = chunk.toString(); if (value.isEmpty()) { tick(); return }
        val codePoint = value.codePointAt(0); val first = currentText.isEmpty()
        val wrong = if (TyperState.humanize && !first && !lastTypo && Character.isLetter(codePoint) && Random.nextInt(100) < TyperState.typoPercent.coerceIn(0, 30)) randomWrongLetter(codePoint) else null
        if (wrong == null) { currentText.append(value); setTextRateLimited(node, base + currentText) { lastTypo = false; postDelayForNext() }; return }
        currentText.append(wrong)
        setTextRateLimited(node, base + currentText) {
            handler.postDelayed({
                if (!typing) return@postDelayed
                val current = cachedInput ?: run { tick(); return@postDelayed }
                val length = currentText.length; val from = currentText.offsetByCodePoints(length, -1); currentText.delete(from, length)
                setTextRateLimited(current, base + currentText) {
                    handler.postDelayed({
                        if (!typing) return@postDelayed
                        val refreshed = cachedInput ?: run { tick(); return@postDelayed }
                        currentText.append(value); setTextRateLimited(refreshed, base + currentText) { lastTypo = true; postDelayForNext() }
                    }, Random.nextLong(80, 201))
                }
            }, Random.nextLong(150, 401))
        }
    }

    private fun randomWrongLetter(codePoint: Int): String? {
        val alphabet = when (codePoint) { in 0x0400..0x052F -> "йцукенгшщзхфывапролджэячсмитьбю"; in 0x0041..0x007A -> "qwertyuiopasdfghjklzxcvbnm"; else -> return null }
        var wrong = alphabet.random().toString(); if (Character.isUpperCase(codePoint)) wrong = wrong.uppercase(Locale.ROOT); return wrong
    }

    private fun postDelayForNext() {
        var delay = TyperState.delayMs
        if (TyperState.humanize && currentText.isNotEmpty()) delay = (delay * when (currentText.last()) { ' ' -> 1.6; ',', '.', '!', '?', ':', ';' -> 2.5; '\n' -> 3.0; else -> 1.0 }).toLong()
        if (TyperState.jitter && delay > 0) delay = (delay * (0.5 + Random.nextDouble())).toLong()
        handler.postDelayed({ tick() }, delay.coerceAtLeast(0))
    }

    private fun setTextRateLimited(node: AccessibilityNodeInfo, value: CharSequence, after: () -> Unit) {
        val remaining = MIN_SET_TEXT_INTERVAL_MS - (SystemClock.elapsedRealtime() - lastSetTextAt)
        if (remaining > 0) handler.postDelayed({ if (!typing) return@postDelayed; performSetText(node, value); after() }, remaining)
        else { performSetText(node, value); after() }
    }
    private fun performSetText(node: AccessibilityNodeInfo, value: CharSequence) {
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args); lastSetTextAt = SystemClock.elapsedRealtime()
    }
    private fun moveCursorToEndIfNeeded() {
        val node = cachedInput ?: return
        if (!node.isEditable || !node.isFocused) return
        val end = (base + currentText).length
        if (node.textSelectionStart == end && node.textSelectionEnd == end) return
        val args = Bundle().apply { putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, end); putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end) }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
    }
    private fun sendCurrentPart() { if (sendAttemptedInPart) return; moveCursorToEndIfNeeded(); sendAttemptedInPart = true; clickSend() }

    private fun onPartComplete() {
        if (!TyperState.autoSend && !TyperState.splitMode) { finish(); return }
        handler.postDelayed({
            if (!typing) return@postDelayed
            sendCurrentPart()
            if (partIndex == parts.lastIndex) finish() else handler.postDelayed({
                if (!typing) return@postDelayed
                partIndex++; actionIndex = 0; currentText = StringBuilder(); base = ""; lostFocusTicks = 0; lastTypo = false; sendAttemptedInPart = false; tick()
            }, TyperState.pauseMs.coerceIn(0, 60000))
        }, 400)
    }

    private fun clickSend(): Boolean {
        val root = rootInActiveWindow
        if (root != null) {
            val input = cachedInput ?: focusedInput()
            val packageName = root.packageName?.toString().orEmpty()
            val tried = mutableSetOf<String>()
            val rule = loadRule(packageName)
            if (rule != null) {
                val learnedNode = findLearnedNode(root, rule)
                if (learnedNode != null && clickNodeOnce(learnedNode, input, tried)) return true
                if (dispatchLearnedTap(rule)) return true
            }
            if (input != null) {
                val heuristic = mutableListOf<AccessibilityNodeInfo>()
                collectHeuristicCandidates(root, input, 0, intArrayOf(0), heuristic)
                val inputBounds = Rect(); input.getBoundsInScreen(inputBounds)
                val best = heuristic.distinctBy(::nodeKey).sortedWith(
                    compareBy<AccessibilityNodeInfo> { node -> val b = Rect(); node.getBoundsInScreen(b); abs(b.left - inputBounds.right) }
                        .thenByDescending { node -> preferredClass(node.className?.toString().orEmpty()) }
                )
                for (candidate in best) if (clickNodeOnce(candidate, input, tried)) return true
            }
            val keywords = mutableListOf<SendCandidate>()
            collectSendCandidates(root, 0, intArrayOf(0), keywords)
            val rootBounds = Rect(); root.getBoundsInScreen(rootBounds)
            val screenWidth = maxOf(rootBounds.right, resources.displayMetrics.widthPixels)
            for (candidate in keywords.sortedWith(compareByDescending<SendCandidate> { it.exactLabel }.thenByDescending { it.right }.thenByDescending { it.bottom })) {
                if (clickNodeOnce(candidate.node, input, tried, screenWidth)) return true
            }
        }
        if (Build.VERSION.SDK_INT >= 30) return focusedInput()?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id) == true
        return false
    }

    private fun loadRule(pkg: String): LearnedRule? {
        if (pkg.isBlank()) return null
        val raw = getSharedPreferences(RULES_PREFS, MODE_PRIVATE).getString(pkg, null) ?: return null
        return try { val j = JSONObject(raw); LearnedRule(j.optString("viewId"), j.optString("description"), j.optString("text"), j.optString("className"), j.optDouble("xRatio").toFloat(), j.optDouble("yRatio").toFloat()) } catch (_: Exception) { null }
    }

    private fun findLearnedNode(root: AccessibilityNodeInfo, rule: LearnedRule): AccessibilityNodeInfo? {
        if (rule.viewId.isNotBlank()) {
            val matches = try { root.findAccessibilityNodeInfosByViewId(rule.viewId) } catch (_: Exception) { emptyList<AccessibilityNodeInfo>() }
            val valid = matches.filter { it.isVisibleToUser && it.isEnabled }
            val best = valid.firstOrNull { it.contentDescription?.toString().orEmpty() == rule.description } ?: valid.firstOrNull()
            if (best != null) return best
        }
        val nodes = mutableListOf<AccessibilityNodeInfo>(); collectAll(root, 0, intArrayOf(0), nodes)
        return nodes.firstOrNull { it.isVisibleToUser && it.isEnabled && it.className?.toString() == rule.className && it.contentDescription?.toString().orEmpty() == rule.description }
    }

    private fun dispatchLearnedTap(rule: LearnedRule): Boolean {
        val x = rule.xRatio * resources.displayMetrics.widthPixels
        val y = rule.yRatio * resources.displayMetrics.heightPixels
        val path = Path().apply { moveTo(x, y); lineTo(x, y) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 50)).build()
        return dispatchGesture(gesture, null, null)
    }

    private fun clickNodeOnce(node: AccessibilityNodeInfo, input: AccessibilityNodeInfo?, tried: MutableSet<String>, screenWidth: Int = 0): Boolean {
        val target = firstClickableParent(node) ?: return false
        val key = nodeKey(target)
        if (!tried.add(key)) return false
        if (input != null && screenWidth > 0) {
            val ib = Rect(); input.getBoundsInScreen(ib); val cb = Rect(); target.getBoundsInScreen(cb)
            if (ib.width() > screenWidth / 2 && cb.centerX() < screenWidth / 2) return false
        }
        return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun nodeKey(node: AccessibilityNodeInfo): String {
        val b = Rect(); node.getBoundsInScreen(b)
        return "${node.windowId}:${node.viewIdResourceName}:${node.className}:${b.flattenToString()}"
    }

    private fun preferredClass(name: String): Int = when {
        name.endsWith("ImageButton") -> 3
        name.endsWith("ImageView") -> 2
        name.endsWith("Button") -> 1
        else -> 0
    }

    private fun collectHeuristicCandidates(node: AccessibilityNodeInfo, input: AccessibilityNodeInfo, depth: Int, visited: IntArray, out: MutableList<AccessibilityNodeInfo>) {
        if (depth > MAX_SEND_TREE_DEPTH || visited[0] >= MAX_SEND_TREE_NODES) return
        visited[0]++
        if (node.isVisibleToUser && node.isEnabled && node.isClickable && !node.isEditable) {
            val bounds = Rect(); node.getBoundsInScreen(bounds); val inputBounds = Rect(); input.getBoundsInScreen(inputBounds)
            val right = bounds.left >= inputBounds.right
            val rowOverlap = bounds.bottom >= inputBounds.top && bounds.top <= inputBounds.bottom
            val belowRight = bounds.top >= inputBounds.bottom && bounds.left >= inputBounds.right - inputBounds.width() / 4
            if ((right && rowOverlap) || belowRight) {
                val label = candidateLabel(node)
                if (BLACKLIST.none(label::contains)) out += node
            }
        }
        if (depth >= MAX_SEND_TREE_DEPTH) return
        for (i in 0 until node.childCount) { if (visited[0] >= MAX_SEND_TREE_NODES) break; node.getChild(i)?.let { collectHeuristicCandidates(it, input, depth + 1, visited, out) } }
    }

    private fun collectAll(node: AccessibilityNodeInfo, depth: Int, visited: IntArray, out: MutableList<AccessibilityNodeInfo>) {
        if (depth > MAX_SEND_TREE_DEPTH || visited[0] >= MAX_SEND_TREE_NODES) return
        visited[0]++; out += node
        if (depth >= MAX_SEND_TREE_DEPTH) return
        for (i in 0 until node.childCount) { if (visited[0] >= MAX_SEND_TREE_NODES) break; node.getChild(i)?.let { collectAll(it, depth + 1, visited, out) } }
    }

    private fun collectSendCandidates(node: AccessibilityNodeInfo, depth: Int, visited: IntArray, candidates: MutableList<SendCandidate>) {
        if (depth > MAX_SEND_TREE_DEPTH || visited[0] >= MAX_SEND_TREE_NODES) return
        visited[0]++
        if (!node.isEditable) {
            val label = candidateLabel(node)
            if (KEYWORDS.any(label::contains) && BLACKLIST.none(label::contains)) {
                val exactLabels = setOf("send", "отправить", "надіслати", "відправити", "отправка", "submit")
                val desc = node.contentDescription?.toString().orEmpty(); val text = node.text?.toString().orEmpty()
                val exact = desc.trim().equalsAnyIgnoreCase(exactLabels) || (text.length <= 12 && text.trim().equalsAnyIgnoreCase(exactLabels))
                val bounds = Rect(); node.getBoundsInScreen(bounds); candidates += SendCandidate(node, exact, bounds.right, bounds.bottom)
            }
        }
        if (depth >= MAX_SEND_TREE_DEPTH) return
        for (i in 0 until node.childCount) { if (visited[0] >= MAX_SEND_TREE_NODES) break; node.getChild(i)?.let { collectSendCandidates(it, depth + 1, visited, candidates) } }
    }

    private fun candidateLabel(node: AccessibilityNodeInfo): String {
        val text = node.text?.toString().orEmpty().takeIf { it.length <= 12 }.orEmpty()
        return "${node.contentDescription ?: ""} ${node.viewIdResourceName ?: ""} $text".lowercase(Locale.ROOT)
    }
    private fun String.equalsAnyIgnoreCase(values: Set<String>) = values.any { equals(it, ignoreCase = true) }

    private fun firstClickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node; var depth = 0
        while (current != null && depth <= MAX_SEND_TREE_DEPTH) { if (current.isClickable) return current; current = current.parent; depth++ }
        return null
    }

    private fun finish() { Toast.makeText(this, "Готово ✓", Toast.LENGTH_SHORT).show(); disarm() }

    private fun showLearnOverlay() {
        hideOverlay()
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val hint = Button(this).apply { text = "🎯 Нажми кнопку отправки"; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#D32F2F")); isAllCaps = false; setOnClickListener { } }
        val cancel = Button(this).apply { text = "✕"; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#D32F2F")); setOnClickListener { stopLearn() } }
        row.addView(hint); row.addView(cancel)
        addOverlay(row)
    }
    private fun showOverlay() {
        if (overlay != null) return
        val btn = Button(this).apply { text = "■ СТОП"; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#D32F2F")); setOnClickListener { disarm() } }
        addOverlay(btn)
    }
    private fun addOverlay(view: View) {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.END; y = 160; x = 16 }
        wm.addView(view, lp); overlay = view
    }
    private fun hideOverlay() {
        val view = overlay ?: return
        try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view) } catch (_: Exception) { }
        overlay = null
    }

    private val KEYWORDS = listOf("send", "отправ", "надісл", "надсил", "відправ", "submit")
    private val BLACKLIST = listOf("send as", "send_as", "sendas", "as_", "от имени", "от лица", "від імені", "написать от", "написати від", "schedule", "запланир", "заплан", "voice", "audio", "голос", "attach", "вложен", "вкладен", "emoji", "sticker", "стикер")
}
