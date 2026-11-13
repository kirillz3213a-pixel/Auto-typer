package com.autotyper

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.database.Cursor
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

class MainActivity : Activity() {
    private val bg = Color.rgb(15, 17, 21)
    private val cardColor = Color.rgb(26, 29, 36)
    private val inputColor = Color.rgb(36, 40, 51)
    private val muted = Color.rgb(138, 144, 160)
    private val accent = Color.rgb(79, 140, 255)
    private val light = Color.rgb(232, 234, 240)
    private val red = Color.rgb(229, 72, 77)
    private val green = Color.rgb(61, 214, 140)

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var serviceButton: Button
    private lateinit var normalButton: Button
    private lateinit var messageButton: Button
    private lateinit var modeHint: TextView
    private lateinit var textInput: EditText
    private lateinit var fileInfo: TextView
    private lateinit var counter: TextView
    private lateinit var learnedCount: TextView
    private lateinit var delayInput: EditText
    private lateinit var stepInput: EditText
    private lateinit var pauseInput: EditText
    private lateinit var jitterCheck: CheckBox
    private lateinit var sendCheck: CheckBox
    private lateinit var newlineCheck: CheckBox
    private lateinit var humanizeCheck: CheckBox
    private lateinit var typoInput: EditText
    private lateinit var pauseRow: View

    private val prefs by lazy { getSharedPreferences("typer", Context.MODE_PRIVATE) }
    private var messageMode = false

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg

        val scroll = ScrollView(this).apply { setBackgroundColor(bg) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(24))
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = "AutoTyper"
            textSize = 28f
            setTextColor(light)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        })

        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(14))
        }
        statusDot = View(this).apply { background = oval(muted) }
        statusRow.addView(statusDot, LinearLayout.LayoutParams(dp(10), dp(10)))
        statusText = TextView(this).apply {
            textSize = 14f
            setTextColor(muted)
            setPadding(dp(8), 0, 0, 0)
        }
        statusRow.addView(statusText)
        root.addView(statusRow)

        serviceButton = button("Включить службу (Спец. возможности)", outlined = true)
        serviceButton.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        root.addView(serviceButton, fullWidthParams(bottom = 12))

        val modeCard = card(root, "РЕЖИМ")
        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(inputColor, 12)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        normalButton = buttonSegment("Обычный", true)
        messageButton = buttonSegment("Сообщения", false)
        normalButton.setOnClickListener { setMode(false) }
        messageButton.setOnClickListener { setMode(true) }
        modeRow.addView(normalButton, LinearLayout.LayoutParams(0, dp(44), 1f))
        modeRow.addView(messageButton, LinearLayout.LayoutParams(0, dp(44), 1f))
        modeCard.addView(modeRow, fullWidthParams(bottom = 10))
        modeHint = TextView(this).apply {
            textSize = 13f
            setTextColor(muted)
        }
        modeCard.addView(modeHint)

        val textCard = card(root, "ТЕКСТ")
        textInput = EditText(this).apply {
            hint = "Например: привет как дела, я сегодня пошел в парк"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 5
            maxLines = 10
            gravity = Gravity.TOP or Gravity.START
            setTextColor(light)
            setHintTextColor(muted)
            textSize = 16f
            setBackgroundDrawable(rounded(inputColor, 12))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setText(prefs.getString("text", ""))
        }
        textCard.addView(textInput, fullWidthParams(bottom = 8))

        val learnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val learnButton = button("🎯 Обучить кнопку отправки", outlined = true)
        learnButton.setOnClickListener {
            val service = TyperService.instance
            if (service == null) {
                Toast.makeText(this, "Сначала включи службу", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            service.startLearn()
            Toast.makeText(this, "Подождите…", Toast.LENGTH_SHORT).show()
            moveTaskToBack(true)
        }
        learnButton.setTextSize(12f)
        learnButton.minHeight = dp(42)
        val resetButton = button("Сбросить", outlined = true)
        resetButton.setOnClickListener {
            val rules = getSharedPreferences("send_rules", MODE_PRIVATE)
            rules.edit().clear().apply()
            refreshLearnedCount()
            Toast.makeText(this, "Правила сброшены", Toast.LENGTH_SHORT).show()
        }
        resetButton.setTextSize(12f)
        resetButton.minHeight = dp(42)
        learnRow.addView(learnButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        learnRow.addView(resetButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        textCard.addView(learnRow, fullWidthParams(bottom = 7))

        val loadButton = button("📄 Загрузить .txt", outlined = true)
        loadButton.setOnClickListener {
            val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/plain"
            }
            startActivityForResult(picker, REQUEST_OPEN_TEXT)
        }
        textCard.addView(loadButton, fullWidthParams(bottom = 7))

        fileInfo = TextView(this).apply {
            textSize = 12f
            setTextColor(muted)
            visibility = View.GONE
        }
        textCard.addView(fileInfo, fullWidthParams(bottom = 7))
        counter = TextView(this).apply {
            textSize = 12f
            setTextColor(muted)
        }
        textCard.addView(counter, fullWidthParams(bottom = 5))
        learnedCount = TextView(this).apply {
            textSize = 12f
            setTextColor(muted)
        }
        textCard.addView(learnedCount, fullWidthParams(bottom = 5))
        textCard.addView(TextView(this).apply {
            text = "Спец. клавиши: {ENTER} {BACK} {WAIT:500} {SEND}"
            textSize = 12f
            setTextColor(muted)
        }, fullWidthParams())
        textInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = updateCounter()
            override fun afterTextChanged(s: Editable?) = Unit
        })

        val speedCard = card(root, "СКОРОСТЬ")
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        delayInput = numericInput(prefs.getLong("delay", 40).toString())
        stepInput = numericInput(prefs.getInt("step", 1).toString())
        row.addView(fieldLabel("Задержка, мс", delayInput), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(10), 1) }, LinearLayout.LayoutParams(dp(10), 1))
        row.addView(fieldLabel("Символов за шаг", stepInput), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        speedCard.addView(row, fullWidthParams(bottom = 8))

        pauseInput = numericInput(prefs.getLong("pause", 800).toString())
        pauseRow = fieldLabel("Пауза между сообщениями, мс", pauseInput)
        speedCard.addView(pauseRow, fullWidthParams(bottom = 8))
        newlineCheck = checkbox("Новая строка = новое сообщение", prefs.getBoolean("newline", false))
        speedCard.addView(newlineCheck, fullWidthParams(bottom = 6))

        jitterCheck = checkbox("Случайная вариация скорости", prefs.getBoolean("jitter", true))
        speedCard.addView(jitterCheck, fullWidthParams(bottom = 6))
        sendCheck = checkbox("Автоотправка после ввода", prefs.getBoolean("send", false))
        speedCard.addView(sendCheck, fullWidthParams(bottom = 6))
        humanizeCheck = checkbox("Имитация клавиатуры (паузы и опечатки)", prefs.getBoolean("humanize", false))
        speedCard.addView(humanizeCheck, fullWidthParams(bottom = 6))
        typoInput = numericInput(prefs.getInt("typo", 3).toString())
        val typoRow = fieldLabel("Шанс опечатки, %", typoInput)
        speedCard.addView(typoRow, fullWidthParams(bottom = 8))
        humanizeCheck.setOnCheckedChangeListener { _, checked -> typoRow.visibility = if (checked) View.VISIBLE else View.GONE }
        typoRow.visibility = if (humanizeCheck.isChecked) View.VISIBLE else View.GONE

        val ready = button("▶  Готово — выбрать поле", primary = true)
        ready.setOnClickListener { arm() }
        root.addView(ready, fullWidthParams(bottom = 8))
        val stop = button("■  Стоп", danger = true)
        stop.setOnClickListener {
            TyperService.instance?.disarm()
            refreshStatus()
        }
        root.addView(stop, fullWidthParams(bottom = 10))
        root.addView(TextView(this).apply {
            text = "Введи текст, нажми «Готово», затем выбери поле ввода в другом приложении. Красная кнопка поверх …"
            textSize = 12f
            setTextColor(muted)
            setPadding(dp(2), dp(4), dp(2), 0)
        }, fullWidthParams())

        setContentView(scroll)
        setMode(prefs.getBoolean("split", false))
        updateCounter()
        refreshStatus()
        refreshLearnedCount()
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    @Deprecated("Deprecated in Android API, required for document picker compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_OPEN_TEXT && resultCode == RESULT_OK) {
            data?.data?.let(::loadTextUri)
        }
    }

    private fun handleIncomingIntent(incoming: Intent?) {
        if (incoming == null || !::textInput.isInitialized) return
        when (incoming.action) {
            Intent.ACTION_SEND -> {
                val uri = incoming.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                    ?: incoming.clipData?.getItemAt(0)?.uri
                if (uri != null) {
                    loadTextUri(uri)
                } else {
                    val shared = incoming.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() ?: return
                    loadSharedText(shared)
                }
            }
            Intent.ACTION_VIEW -> incoming.data?.let(::loadTextUri)
        }
    }

    private fun loadSharedText(shared: String) {
        Thread {
            try {
                val bytes = shared.toByteArray(StandardCharsets.UTF_8)
                if (bytes.size > MAX_TEXT_BYTES) {
                    runOnUiThread { Toast.makeText(this, "Файл слишком большой", Toast.LENGTH_LONG).show() }
                    return@Thread
                }
                val decoded = decodeText(bytes)
                runOnUiThread {
                    textInput.setText(decoded)
                    fileInfo.text = "Текст · ${formatCount(decoded.codePointCount(0, decoded.length))} символов"
                    fileInfo.visibility = View.VISIBLE
                }
            } catch (_: Exception) {
                runOnUiThread { Toast.makeText(this, "Не удалось прочитать файл", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun loadTextUri(uri: Uri) {
        Thread {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_TEXT_BYTES) {
                            runOnUiThread { Toast.makeText(this, "Файл слишком большой", Toast.LENGTH_LONG).show() }
                            return@Thread
                        }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                } ?: throw IllegalStateException("Unable to open selected file")
                val decoded = decodeText(bytes)
                val name = queryDisplayName(uri)
                runOnUiThread {
                    textInput.setText(decoded)
                    fileInfo.text = "$name · ${formatCount(decoded.codePointCount(0, decoded.length))} символов"
                    fileInfo.visibility = View.VISIBLE
                }
            } catch (_: Exception) {
                runOnUiThread { Toast.makeText(this, "Не удалось прочитать файл", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun decodeText(source: ByteArray): String {
        val bytes = if (source.size >= 3 &&
            source[0] == 0xEF.toByte() && source[1] == 0xBB.toByte() && source[2] == 0xBF.toByte()
        ) source.copyOfRange(3, source.size) else source
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            String(bytes, Charset.forName("windows-1251"))
        }
    }

    private fun queryDisplayName(uri: Uri): String {
        var name: String? = null
        val cursor: Cursor? = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) name = it.getString(index)
            }
        }
        return name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Текстовый файл"
    }

    private fun formatCount(count: Int): String = String.format(java.util.Locale.getDefault(), "%d", count).replace(',', ' ')

    private fun card(parent: LinearLayout, title: String): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(cardColor, 16)
            setPadding(dp(14), dp(13), dp(14), dp(14))
        }
        card.addView(TextView(this).apply {
            text = title
            textSize = 12f
            setTextColor(muted)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.08f
            setPadding(0, 0, 0, dp(10))
        })
        parent.addView(card, fullWidthParams(bottom = 12))
        return card
    }

    private fun buttonSegment(label: String, selected: Boolean): Button = Button(this).apply {
        text = label
        textSize = 14f
        setTextColor(if (selected) Color.WHITE else muted)
        setTypeface(Typeface.DEFAULT, if (selected) Typeface.BOLD else Typeface.NORMAL)
        transformationMethod = null
        stateListAnimator = null
        background = rounded(if (selected) accent else inputColor, 10)
        minHeight = dp(40)
    }

    private fun button(label: String, primary: Boolean = false, outlined: Boolean = false, danger: Boolean = false): Button = Button(this).apply {
        text = label
        textSize = if (primary) 16f else 14f
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        transformationMethod = null
        stateListAnimator = null
        isAllCaps = false
        minHeight = dp(if (primary) 54 else 44)
        setTextColor(when {
            primary -> Color.WHITE
            danger -> red
            outlined -> accent
            else -> light
        })
        background = when {
            primary -> rounded(accent, 14)
            danger -> rounded(bg, 14, red)
            outlined -> rounded(cardColor, 12, accent)
            else -> rounded(inputColor, 12)
        }
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }

    private fun checkbox(label: String, checked: Boolean): CheckBox = CheckBox(this).apply {
        text = label
        textSize = 14f
        setTextColor(light)
        isChecked = checked
        buttonTintList = ColorStateList.valueOf(accent)
        setPadding(0, dp(2), 0, dp(2))
    }

    private fun numericInput(value: String): EditText = EditText(this).apply {
        setText(value)
        inputType = android.text.InputType.TYPE_CLASS_NUMBER
        setTextColor(light)
        setHintTextColor(muted)
        setBackgroundDrawable(rounded(inputColor, 12))
        setPadding(dp(11), dp(9), dp(11), dp(9))
        minHeight = dp(46)
        textSize = 14f
    }

    private fun fieldLabel(label: String, input: EditText): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(this@MainActivity).apply {
            text = label
            textSize = 12f
            setTextColor(muted)
            setPadding(dp(2), 0, 0, dp(5))
        })
        addView(input)
    }

    private fun rounded(color: Int, radiusDp: Int, stroke: Int? = null): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun oval(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun fullWidthParams(bottom: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(bottom) }

    private fun setMode(enabledMessages: Boolean) {
        messageMode = enabledMessages
        normalButton.background = rounded(if (!enabledMessages) accent else inputColor, 10)
        normalButton.setTextColor(if (!enabledMessages) Color.WHITE else muted)
        normalButton.setTypeface(Typeface.DEFAULT, if (!enabledMessages) Typeface.BOLD else Typeface.NORMAL)
        messageButton.background = rounded(if (enabledMessages) accent else inputColor, 10)
        messageButton.setTextColor(if (enabledMessages) Color.WHITE else muted)
        messageButton.setTypeface(Typeface.DEFAULT, if (enabledMessages) Typeface.BOLD else Typeface.NORMAL)
        modeHint.text = if (enabledMessages) {
            "Запятая = конец сообщения. Каждая часть набирается и отправляется отдельно."
        } else {
            "Весь текст набирается в поле как есть."
        }
        pauseRow.visibility = if (enabledMessages) View.VISIBLE else View.GONE
        sendCheck.visibility = if (enabledMessages) View.GONE else View.VISIBLE
        newlineCheck.visibility = if (enabledMessages) View.VISIBLE else View.GONE
        updateCounter()
    }

    private fun messageParts(text: String): List<String> {
        val parts = if (newlineCheck.isChecked) {
            text.split(Regex("[,，\\r\\n]+"))
        } else {
            text.split(',', '，')
        }
        return parts.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun updateCounter() {
        val text = textInput.text?.toString() ?: ""
        if (messageMode) {
            counter.text = "Сообщений: ${messageParts(text).size}"
        } else {
            val cleaned = text.replace(Regex("\\{(?:ENTER|BACK|SEND|WAIT:\\d+)\\}", RegexOption.IGNORE_CASE), "")
            counter.text = "Символов: ${cleaned.length}"
        }
    }

    private fun refreshLearnedCount() {
        val count = getSharedPreferences("send_rules", MODE_PRIVATE).all.size
        learnedCount.text = "Обучено приложений: $count"
    }

    private fun refreshStatus() {
        val running = TyperService.instance != null
        val (label, color) = when {
            !running -> "Служба выключена" to muted
            TyperState.armed -> "ожидаю тап по полю ввода…" to accent
            else -> "Служба включена · готова к работе" to green
        }
        statusText.text = label
        statusText.setTextColor(color)
        statusDot.background = oval(color)
        serviceButton.visibility = if (running) View.GONE else View.VISIBLE
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        refreshLearnedCount()
    }

    override fun onPause() {
        save()
        super.onPause()
    }

    private fun save() {
        prefs.edit()
            .putString("text", textInput.text.toString())
            .putLong("delay", delayInput.text.toString().toLongOrNull()?.coerceIn(0, 5000) ?: 40)
            .putInt("step", stepInput.text.toString().toIntOrNull()?.coerceIn(1, 500) ?: 1)
            .putBoolean("jitter", jitterCheck.isChecked)
            .putBoolean("send", sendCheck.isChecked)
            .putBoolean("split", messageMode)
            .putBoolean("newline", newlineCheck.isChecked)
            .putLong("pause", pauseInput.text.toString().toLongOrNull()?.coerceIn(0, 60000) ?: 800)
            .putBoolean("humanize", humanizeCheck.isChecked)
            .putInt("typo", typoInput.text.toString().toIntOrNull()?.coerceIn(0, 30) ?: 3)
            .apply()
    }

    private fun arm() {
        val service = TyperService.instance
        if (service == null) {
            Toast.makeText(this, "Сначала включи службу", Toast.LENGTH_LONG).show()
            return
        }
        val value = textInput.text.toString()
        if (value.isBlank()) {
            Toast.makeText(this, "Введи текст", Toast.LENGTH_SHORT).show()
            return
        }
        if (messageMode && messageParts(value).isEmpty()) {
            Toast.makeText(this, "Добавь хотя бы одно сообщение", Toast.LENGTH_SHORT).show()
            return
        }

        save()
        TyperState.text = value
        TyperState.delayMs = delayInput.text.toString().toLongOrNull()?.coerceIn(0, 5000) ?: 40
        TyperState.step = stepInput.text.toString().toIntOrNull()?.coerceIn(1, 500) ?: 1
        TyperState.jitter = jitterCheck.isChecked
        TyperState.splitMode = messageMode
        TyperState.splitNewline = newlineCheck.isChecked
        TyperState.autoSend = sendCheck.isChecked || messageMode
        TyperState.pauseMs = pauseInput.text.toString().toLongOrNull()?.coerceIn(0, 60000) ?: 800
        TyperState.humanize = humanizeCheck.isChecked
        TyperState.typoPercent = typoInput.text.toString().toIntOrNull()?.coerceIn(0, 30) ?: 3
        service.arm()
        Toast.makeText(this, "Открой приложение и тапни по полю ввода", Toast.LENGTH_LONG).show()
        moveTaskToBack(true)
    }

    companion object {
        private const val REQUEST_OPEN_TEXT = 42
        private const val MAX_TEXT_BYTES = 1024 * 1024
    }
}
