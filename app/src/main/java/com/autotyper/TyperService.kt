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
        private val SEND_WORDS = listOf("send", "отправ", "надісл", "надсил", "відправ", "submit")
        private val BLACKLIST = listOf(
            "send as", "send_as", "sendas", "as_", "от имени", "от лица", "від імені",
            "написать от", "написати від", "schedule", "запланир", "заплан", "voice",
            "audio", "голос", "attach", "вложен", "вкладен", "emoji", "sticker", "стикер"
        )
        private val EXACT_SEND = setOf("send", "отправить", "надіслати", "відправити", "отправка", "submit")
    }

    private sealed class Action {
        data class Text(val value: String) : Action()
        object Enter : Action()
        object Back : Action()
        data class Wait(val ms: Long) : Action()
        object Send : Action()
    }

    private data class Rule(
        val viewId: String,
        val description: String,
        val text: String,
        val className: String,
        val x: Float,
        val y: Float,
        val manual: Boolean
    )
    private data class HookNode(val node: AccessibilityNodeInfo, val packageName: String, val exact: Boolean = false)
    private data class HeuristicNode(val node: AccessibilityNodeInfo, val packageName: String, val distance: Int)

    private val handler = Handler(Looper.getMainLooper())
    private var overlay: View? = null
    private var learning = false
    private var passiveLearnPackage: String? = null
    private var passiveLearnUntil = 0L
    private val promptedPackages = mutableSetOf<String>()
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
        passiveLearnPackage = null
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
        passiveLearnPackage = null
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
        val eventPackage = event.packageName?.toString().orEmpty()
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED && eventPackage != packageName) {
            if (learning) {
                saveLearnedRule(event, manual = true, includePackage = true)
                return
            }
            if (passiveLearnPackage == eventPackage) {
                if (SystemClock.elapsedRealtime() <= passiveLearnUntil) {
                    if (saveLearnedRule(event, manual = true, includePackage = false)) {
                        passiveLearnPackage = null
                        passiveLearnUntil = 0L
                    }
                    return
                }
                passiveLearnPackage = null
            }
        }
        if (typing || !TyperState.armed || eventPackage == packageName) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED, AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                if (checkPending) return
                checkPending = true
                handler.postDelayed({ checkPending = false; tryStart() }, 150)
            }
        }
    }

    private fun saveLearnedRule(event: AccessibilityEvent, manual: Boolean, includePackage: Boolean): Boolean {
        val source = event.source ?: return false
        val target = firstClickableParent(source) ?: source
        val pkg = event.packageName?.toString()?.takeIf { it.isNotBlank() } ?: return false
        val bounds = Rect()
        target.getBoundsInScreen(bounds)
        val width = resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val height = resources.displayMetrics.heightPixels.coerceAtLeast(1)
        val text = target.text?.toString().orEmpty().takeIf { it.length <= 12 }.orEmpty()
        val json = JSONObject().apply {
            put("viewId", target.viewIdResourceName.orEmpty())
            put("description", target.contentDescription?.toString().orEmpty())
            put("text", text)
            put("className", target.className?.toString().orEmpty())
            put("x", bounds.centerX().toFloat() / width)
            put("y", bounds.centerY().toFloat() / height)
            put("manual", manual)
        }
        getSharedPreferences(RULES_PREFS, MODE_PRIVATE).edit().putString(pkg, json.toString()).apply()
        Toast.makeText(this, if (includePackage) "Запомнено ✓ ($pkg)" else "Запомнено ✓", Toast.LENGTH_SHORT).show()
        if (manual) stopLearn()
        return true
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
        val input = focusedInput() ?: return
        if (input.packageName == packageName) return
        val rawParts = if (TyperState.splitMode) {
            val split = if (TyperState.splitNewline) TyperState.text.split(Regex("[,，\\r\\n]+")) else TyperState.text.split(',', '，')
            split.map { it.trim() }.filter { it.isNotEmpty() }
        } else listOf(TyperState.text)
        if (rawParts.isEmpty()) { disarm(); return }
        parts = rawParts.map(::parsePart)
        partIndex = 0; actionIndex = 0; currentText = StringBuilder(); base = ""
        cachedInput = input; lostFocusTicks = 0; lastTypo = false; sendAttemptedInPart = false
        lastSetTextAt = 0L; typing = true; tick()
    }

    private fun parsePart(input: String): List<Action> {
        val result = mutableListOf<Action>()
        var cursor = 0
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
        appendText(result, input.substring(cursor))
        return result
    }

    private fun appendText(target: MutableList<Action>, text: String) {
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
            Action.Enter -> {
                actionIndex++
                if (Build.VERSION.SDK_INT >= 30) { node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id); postDelayForNext() }
                else { currentText.append('\n'); setTextRateLimited(node, base + currentText) { postDelayForNext() } }
            }
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
        val charCount = actions.sumOf { if (it is Action.Text) it.value.codePointCount(0, it.value.length) else 0 }
        val step = when { TyperState.humanize -> 1; TyperState.step == 1 && charCount > 200 -> 2; else -> TyperState.step.coerceAtLeast(1) }
        val chunk = StringBuilder(); var next = actionIndex
        while (next < actions.size && chunk.codePointCount(0, chunk.length) < step) { val item = actions[next] as? Action.Text ?: break; chunk.append(item.value); next++ }
        actionIndex = next
        val value = chunk.toString(); if (value.isEmpty()) { tick(); return }
        val codePoint = value.codePointAt(0)
        val wrong = if (TyperState.humanize && currentText.isNotEmpty() && !lastTypo && Character.isLetter(codePoint) && Random.nextInt(100) < TyperState.typoPercent.coerceIn(0, 30)) randomWrongLetter(codePoint) else null
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
        var result = alphabet.random().toString(); if (Character.isUpperCase(codePoint)) result = result.uppercase(Locale.ROOT); return result
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
        val roots = windowRootsInActiveFirst()
        val activeRoot = rootInActiveWindow
        val foregroundPackage = activeRoot?.packageName?.toString().orEmpty()
        val tried = mutableSetOf<String>()
        val activeInput = activeRoot?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable } ?: cachedInput
        val learned = loadRule(foregroundPackage)
        if (learned != null && activeRoot != null) {
            val matched = findLearnedNode(activeRoot, learned)
            if (matched != null && clickNodeOnce(matched, activeInput, tried)) return true
            if (dispatchLearnedTap(learned)) return true
        }

        val keywords = mutableListOf<HookNode>()
        val visited = intArrayOf(0)
        for ((root, pkg) in roots) collectKeywordCandidates(root, pkg, 0, visited, keywords)
        val orderedKeywords = keywords.sortedWith(compareByDescending<HookNode> { it.exact }.thenByDescending { nodeRight(it.node) }.thenByDescending { nodeBottom(it.node) })
        for (candidate in orderedKeywords) {
            val target = firstClickableParent(candidate.node) ?: continue
            if (clickNodeOnce(target, inputForRoot(roots, candidate.packageName), tried)) {
                if (candidate.exact) saveAutomaticRule(candidate.packageName, target)
                return true
            }
        }

        val heuristic = mutableListOf<HeuristicNode>()
        val heuristicVisited = intArrayOf(0)
        for ((root, pkg) in roots) {
            val input = inputForRoot(roots, pkg) ?: continue
            collectHeuristic(root, pkg, input, 0, heuristicVisited, heuristic)
        }
        val orderedHeuristic = heuristic.distinctBy { nodeKey(it.node) }.sortedWith(
            compareBy<HeuristicNode> { it.distance }
                .thenByDescending { preferredClass(it.node.className?.toString().orEmpty()) }
        )
        for (candidate in orderedHeuristic) {
            val target = firstClickableParent(candidate.node) ?: continue
            if (clickNodeOnce(target, inputForRoot(roots, candidate.packageName), tried)) {
                saveAutomaticRule(candidate.packageName, target)
                return true
            }
        }

        if (Build.VERSION.SDK_INT >= 30) {
            val input = activeInput ?: roots.asSequence().mapNotNull { (root, _) -> try { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } catch (_: Exception) { null } }.firstOrNull { it.isEditable }
            if (input?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id) == true) return true
        }
        if (foregroundPackage.isNotBlank() && promptedPackages.add(foregroundPackage)) {
            Toast.makeText(this, "Не нашёл кнопку отправки. Нажми её вручную один раз — запомню", Toast.LENGTH_LONG).show()
            passiveLearnPackage = foregroundPackage
            passiveLearnUntil = SystemClock.elapsedRealtime() + 30_000L
            handler.postDelayed({ if (passiveLearnPackage == foregroundPackage && SystemClock.elapsedRealtime() >= passiveLearnUntil) passiveLearnPackage = null }, 30_000L)
        }
        return false
    }

    private fun windowRootsInActiveFirst(): List<Pair<AccessibilityNodeInfo, String>> {
        val result = mutableListOf<Pair<AccessibilityNodeInfo, String>>()
        val seen = mutableSetOf<Int>()
        val sortedWindows = try { windows.sortedByDescending { it.isActive } } catch (_: Exception) { emptyList() }
        for (window in sortedWindows) {
            try {
                val root = window.root ?: continue
                if (seen.add(root.windowId)) result += root to (root.packageName?.toString().orEmpty())
            } catch (_: Exception) { }
        }
        val active = rootInActiveWindow
        if (active != null && seen.add(active.windowId)) result.add(0, active to (active.packageName?.toString().orEmpty()))
        return result
    }

    private fun inputForRoot(roots: List<Pair<AccessibilityNodeInfo, String>>, pkg: String): AccessibilityNodeInfo? {
        val root = roots.firstOrNull { it.second == pkg }?.first ?: return null
        return try { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable } } catch (_: Exception) { null }
    }

    private fun loadRule(pkg: String): Rule? {
        if (pkg.isBlank()) return null
        val raw = getSharedPreferences(RULES_PREFS, MODE_PRIVATE).getString(pkg, null) ?: return null
        return try {
            val json = JSONObject(raw)
            Rule(json.optString("viewId"), json.optString("description"), json.optString("text"), json.optString("className"), json.optDouble("x").toFloat(), json.optDouble("y").toFloat(), json.optBoolean("manual", false))
        } catch (_: Exception) { null }
    }

    private fun findLearnedNode(root: AccessibilityNodeInfo, rule: Rule): AccessibilityNodeInfo? {
        if (rule.viewId.isNotBlank()) {
            val matches = try { root.findAccessibilityNodeInfosByViewId(rule.viewId) } catch (_: Exception) { emptyList<AccessibilityNodeInfo>() }
            matches.firstOrNull { it.isVisibleToUser && it.isEnabled && it.contentDescription?.toString().orEmpty() == rule.description }?.let { return it }
        }
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        collectAll(root, 0, intArrayOf(0), nodes)
        return nodes.firstOrNull { it.isVisibleToUser && it.isEnabled && it.className?.toString() == rule.className && it.contentDescription?.toString().orEmpty() == rule.description }
    }

    private fun dispatchLearnedTap(rule: Rule): Boolean {
        if (Build.VERSION.SDK_INT < 24) return false
        val path = Path().apply { val x = rule.x * resources.displayMetrics.widthPixels; val y = rule.y * resources.displayMetrics.heightPixels; moveTo(x, y); lineTo(x, y) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 50)).build()
        return dispatchGesture(gesture, null, null)
    }

    private fun saveAutomaticRule(pkg: String, node: AccessibilityNodeInfo) {
        if (pkg.isBlank()) return
        val prefs = getSharedPreferences(RULES_PREFS, MODE_PRIVATE)
        val existing = prefs.getString(pkg, null)
        if (existing != null && try { JSONObject(existing).optBoolean("manual", false) } catch (_: Exception) { false }) return
        saveNodeRule(pkg, node, manual = false)
    }

    private fun saveNodeRule(pkg: String, node: AccessibilityNodeInfo, manual: Boolean) {
        val bounds = Rect(); node.getBoundsInScreen(bounds)
        val width = resources.displayMetrics.widthPixels.coerceAtLeast(1); val height = resources.displayMetrics.heightPixels.coerceAtLeast(1)
        val text = node.text?.toString().orEmpty().takeIf { it.length <= 12 }.orEmpty()
        val rule = JSONObject().apply {
            put("viewId", node.viewIdResourceName.orEmpty())
            put("description", node.contentDescription?.toString().orEmpty())
            put("text", text)
            put("className", node.className?.toString().orEmpty())
            put("x", bounds.centerX().toFloat() / width)
            put("y", bounds.centerY().toFloat() / height)
            put("manual", manual)
        }
        getSharedPreferences(RULES_PREFS, MODE_PRIVATE).edit().putString(pkg, rule.toString()).apply()
    }

    private fun clickNodeOnce(node: AccessibilityNodeInfo, input: AccessibilityNodeInfo?, tried: MutableSet<String>): Boolean {
        val target = firstClickableParent(node) ?: return false
        val key = nodeKey(target)
        if (!tried.add(key)) return false
        if (input != null) {
            val inputBounds = Rect(); input.getBoundsInScreen(inputBounds)
            val screenWidth = resources.displayMetrics.widthPixels
            val clickBounds = Rect(); target.getBoundsInScreen(clickBounds)
            if (inputBounds.width() > screenWidth / 2 && clickBounds.centerX() < screenWidth / 2) return false
        }
        return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun collectHeuristic(node: AccessibilityNodeInfo, pkg: String, input: AccessibilityNodeInfo, depth: Int, visited: IntArray, out: MutableList<HeuristicNode>) {
        if (depth > MAX_SEND_TREE_DEPTH || visited[0] >= MAX_SEND_TREE_NODES) return
        visited[0]++
        if (node.isVisibleToUser && node.isEnabled && node.isClickable && !node.isEditable && BLACKLIST.none(candidateLabel(node)::contains)) {
            val bounds = Rect(); node.getBoundsInScreen(bounds)
            val ib = Rect(); input.getBoundsInScreen(ib)
            val overlapsRow = bounds.bottom >= ib.top && bounds.top <= ib.bottom
            if (bounds.left >= ib.right && overlapsRow) out += HeuristicNode(node, pkg, abs(bounds.left - ib.right))
        }
        if (depth >= MAX_SEND_TREE_DEPTH) return
        for (i in 0 until node.childCount) { if (visited[0] >= MAX_SEND_TREE_NODES) break; node.getChild(i)?.let { collectHeuristic(it, pkg, input, depth + 1, visited, out) } }
    }

    private fun collectKeywordCandidates(node: AccessibilityNodeInfo, pkg: String, depth: Int, visited: IntArray, out: MutableList<HookNode>) {
        if (depth > MAX_SEND_TREE_DEPTH || visited[0] >= MAX_SEND_TREE_NODES) return
        visited[0]++
        if (!node.isEditable) {
            val label = candidateLabel(node)
            if (SEND_WORDS.any(label::contains) && BLACKLIST.none(label::contains)) {
                val description = node.contentDescription?.toString().orEmpty(); val text = node.text?.toString().orEmpty()
                val exact = description.trim().equalsAnyIgnoreCase(EXACT_SEND) || (text.length <= 12 && text.trim().equalsAnyIgnoreCase(EXACT_SEND))
                out += HookNode(node, pkg, exact)
            }
        }
        if (depth >= MAX_SEND_TREE_DEPTH) return
        for (i in 0 until node.childCount) { if (visited[0] >= MAX_SEND_TREE_NODES) break; node.getChild(i)?.let { collectKeywordCandidates(it, pkg, depth + 1, visited, out) } }
    }

    private fun collectAll(node: AccessibilityNodeInfo, depth: Int, visited: IntArray, out: MutableList<AccessibilityNodeInfo>) {
        if (depth > MAX_SEND_TREE_DEPTH || visited[0] >= MAX_SEND_TREE_NODES) return
        visited[0]++; out += node
        if (depth >= MAX_SEND_TREE_DEPTH) return
        for (i in 0 until node.childCount) { if (visited[0] >= MAX_SEND_TREE_NODES) break; node.getChild(i)?.let { collectAll(it, depth + 1, visited, out) } }
    }

    private fun candidateLabel(node: AccessibilityNodeInfo): String {
        val text = node.text?.toString().orEmpty().takeIf { it.length <= 12 }.orEmpty()
        return "${node.contentDescription ?: ""} ${node.viewIdResourceName ?: ""} $text".lowercase(Locale.ROOT)
    }
    private fun String.equalsAnyIgnoreCase(values: Set<String>) = values.any { equals(it, ignoreCase = true) }
    private fun nodeKey(node: AccessibilityNodeInfo): String { val b = Rect(); node.getBoundsInScreen(b); return "${node.windowId}:${node.viewIdResourceName}:${node.className}:${b.left},${b.top},${b.right},${b.bottom}" }
    private fun nodeRight(node: AccessibilityNodeInfo): Int { val b = Rect(); node.getBoundsInScreen(b); return b.right }
    private fun nodeBottom(node: AccessibilityNodeInfo): Int { val b = Rect(); node.getBoundsInScreen(b); return b.bottom }
    private fun preferredClass(name: String): Int = when { name.endsWith("ImageButton") -> 3; name.endsWith("ImageView") -> 2; name.endsWith("Button") -> 1; else -> 0 }

    private fun firstClickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node; var depth = 0
        while (current != null && depth <= MAX_SEND_TREE_DEPTH) { if (current.isClickable) return current; current = current.parent; depth++ }
        return null
    }

    private fun finish() { Toast.makeText(this, "Готово ✓", Toast.LENGTH_SHORT).show(); disarm() }
    private fun showLearnOverlay() {
        hideOverlay()
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val hint = Button(this).apply { text = "🎯 Нажми кнопку отправки"; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#D32F2F")); isAllCaps = false }
        val cancel = Button(this).apply { text = "✕"; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#D32F2F")); setOnClickListener { stopLearn() } }
        row.addView(hint); row.addView(cancel); addOverlay(row)
    }
    private fun showOverlay() {
        if (overlay != null) return
        val button = Button(this).apply { text = "■ СТОП"; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#D32F2F")); setOnClickListener { disarm() } }
        addOverlay(button)
    }
    private fun addOverlay(view: View) {
        val manager = getSystemService(WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.END; y = 160; x = 16 }
        manager.addView(view, params); overlay = view
    }
    private fun hideOverlay() {
        val view = overlay ?: return
        try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view) } catch (_: Exception) { }
        overlay = null
    }
}
