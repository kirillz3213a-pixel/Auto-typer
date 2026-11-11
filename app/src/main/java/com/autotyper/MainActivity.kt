package com.autotyper

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

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
    private lateinit var modeHint: TextView
    private lateinit var textInput: EditText
    private lateinit var counter: TextView
    private lateinit var normalButton: Button
    private lateinit var messageButton: Button
    private lateinit var pauseInput: EditText
    private lateinit var typoInput: EditText
    private lateinit var pauseRow: View
    private lateinit var sendCheck: CheckBox
    private lateinit var splitCheck: CheckBox
    private lateinit var jitterCheck: CheckBox
    private lateinit var humanizeCheck: CheckBox

    private val prefs by lazy { getSharedPreferences("typer", Context.MODE_PRIVATE) }
    private var messageMode = false
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.decorView.systemUiVisibility = 0

        val scroll = ScrollView(this).apply { setBackgroundColor(bg) }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(24))
        }
        scroll.addView(content)

        content.addView(TextView(this).apply {
            text = "AutoTyper"
            textSize = 28f
            setTextColor(light)
            setTypeface(typeface, Typeface.BOLD)
        })
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(16))
        }
        statusDot = View(this).apply { background = oval(muted) }
        statusRow.addView(statusDot, LinearLayout.LayoutParams(dp(9), dp(9)))
        statusText = TextView(this).apply {
            textSize = 14f
            setTextColor(muted)
            setPadding(dp(8), 0, 0, 0)
        }
        statusRow.addView(statusText)
        content.addView(statusRow)

        serviceButton = button("Включить службу (Спец. возможности)", secondary = true)
        serviceButton.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        content.addView(serviceButton, fullWidthParams(bottom = 12))

        val modeCard = card(content, "РЕЖИМ")
        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(inputColor, 14)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        normalButton = button("Обычный")
        messageButton = button("Сообщения")
        modeRow.addView(normalButton, LinearLayout.LayoutParams(0, dp(46), 1f))
        modeRow.addView(messageButton, LinearLayout.LayoutParams(0, dp(46), 1f))
        modeCard.addView(modeRow, fullWidthParams(bottom = 10))
        modeHint = TextView(this).apply { textSize = 13f; setTextColor(muted) }
        modeCard.addView(modeHint, fullWidthParams())
        normalButton.setOnClickListener { setMode(false) }
        messageButton.setOnClickListener { setMode(true) }

        val textCard = card(content, "ТЕКСТ")
        textInput = EditText(this).apply {
            hint = "Например: привет как дела, я сегодня пошел в парк"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 5
            maxLines = 10
            gravity = Gravity.TOP or Gravity.START
            setTextColor(light)
            setHintTextColor(muted)
            textSize = 16f
            background = rounded(inputColor, 12)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setText(prefs.getString("text", ""))
        }
        textCard.addView(textInput, fullWidthParams(bottom = 8))
        counter = TextView(this).apply { textSize = 12f; setTextColor(muted) }
        textCard.addView(counter, fullWidthParams(bottom = 5))
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

        val speedCard = card(content, "СКОРОСТЬ")
        val speedRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val delayInput = numericInput("Задержка, мс", prefs.getLong("delay", 40).toString())
        val stepInput = numericInput("Символов за шаг", prefs.getInt("step", 1).toString())
        speedRow.addView(fieldColumn("Задержка, мс", delayInput), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val gap = View(this)
        speedRow.addView(gap, LinearLayout.LayoutParams(dp(10), 1))
        speedRow.addView(fieldColumn("Символов за шаг", stepInput), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        speedCard.addView(speedRow, fullWidthParams(bottom = 8))

        pauseRow = fieldColumn("Пауза между сообщениями, мс", numericInput("", prefs.getLong("pause", 800).toString()))
        pauseInput = (pauseRow as LinearLayout).getChildAt(1) as EditText
        speedCard.addView(pauseRow, fullWidthParams(bottom = 8))
        jitterCheck = checkbox("Случайная вариация скорости", prefs.getBoolean("jitter", true))
        speedCard.addView(jitterCheck, fullWidthParams())
        sendCheck = checkbox("Автоотправка после ввода", prefs.getBoolean("send", false))
        speedCard.addView(sendCheck, fullWidthParams())
        splitCheck = checkbox("Режим сообщений", prefs.getBoolean("split", false))
        humanizeCheck = checkbox("Имитация клавиатуры (паузы и опечатки)", prefs.getBoolean("humanize", false))
        // Message mode is selected through the segmented control; this checkbox is only used as persisted state.
        splitCheck.visibility = View.GONE
        typoInput = numericInput("", prefs.getInt("typo", 3).toString())
        val typoColumn = fieldColumn("Шанс опечатки, %", typoInput)
        speedCard.addView(humanizeCheck, fullWidthParams())
        speedCard.addView(typoColumn, fullWidthParams(bottom = 4))
        humanizeCheck.setOnCheckedChangeListener { _, checked -> typoColumn.visibility = if (checked) View.VISIBLE else View.GONE }

        val ready = button("▶  Готово — выбрать поле", primary = true)
        ready.setOnClickListener { arm(delayInput, stepInput) }
        content.addView(ready, fullWidthParams(bottom = 8))
        val stop = button("■  Стоп", danger = true)
        stop.setOnClickListener {
            TyperService.instance?.disarm()
            refreshStatus()
        }
        content.addView(stop, fullWidthParams(bottom = 10))
        content.addView(TextView(this).apply {
            text = "Введи текст, нажми «Готово», затем выбери поле ввода в другом приложении. Красная кнопка поверх экрана остановит набор."
            textSize = 12f
            setTextColor(muted)
            setPadding(dp(2), dp(4), dp(2), 0)
        }, fullWidthParams())

        val restoredMode = prefs.getBoolean("split", false)
        setMode(restoredMode)
        typoColumn.visibility = if (humanizeCheck.isChecked) View.VISIBLE else View.GONE
        updateCounter()
        setContentView(scroll)
        refreshStatus()
    }

    private fun card(parent: LinearLayout, title: String): LinearLayout {
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(cardColor, 16)
            setPadding(dp(14), dp(13), dp(14), dp(14))
        }
        wrapper.addView(TextView(this).apply {
            text = title
            textSize = 12f
            setTextColor(muted)
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.08f
            setPadding(0, 0, 0, dp(10))
        })
        parent.addView(wrapper, fullWidthParams(bottom = 12))
        return wrapper
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

    private fun button(label: String, primary: Boolean = false, secondary: Boolean = false, danger: Boolean = false): Button = Button(this).apply {
        text = label
        textSize = if (primary) 16f else 14f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(if (danger) red else if (primary) Color.WHITE else if (secondary) accent else muted)
        transformationMethod = null
        stateListAnimator = null
        isAllCaps = false
        background = when {
            primary -> rounded(accent, 14)
            danger -> rounded(bg, 14, red)
            secondary -> rounded(cardColor, 12)
            else -> rounded(inputColor, 12)
        }
        minHeight = dp(if (primary) 54 else 44)
    }

    private fun checkbox(label: String, checked: Boolean) = CheckBox(this).apply {
        text = label
        textSize = 14f
        setTextColor(light)
        isChecked = checked
        buttonTintList = ColorStateList.valueOf(accent)
        setPadding(0, dp(2), 0, dp(2))
    }

    private fun numericInput(hintText: String, value: String): EditText = EditText(this).apply {
        hint = hintText
        inputType = InputType.TYPE_CLASS_NUMBER
        setText(value)
        setTextColor(light)
        setHintTextColor(muted)
        textSize = 14f
        background = rounded(inputColor, 12)
        setPadding(dp(11), dp(9), dp(11), dp(9))
        filters = arrayOf(InputFilter.LengthFilter(6))
        minHeight = dp(46)
    }

    private fun fieldColumn(label: String, input: EditText): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(this@MainActivity).apply {
            text = label
            textSize = 12f
            setTextColor(muted)
            setPadding(dp(2), 0, 0, dp(5))
            visibility = if (label.isEmpty()) View.GONE else View.VISIBLE
        })
        addView(input, fullWidthParams())
    }

    private fun setMode(messages: Boolean) {
        messageMode = messages
        if (!::normalButton.isInitialized) return
        normalButton.background = rounded(if (!messages) accent else inputColor, 11)
        normalButton.setTextColor(if (!messages) Color.WHITE else muted)
        messageButton.background = rounded(if (messages) accent else inputColor, 11)
        messageButton.setTextColor(if (messages) Color.WHITE else muted)
        normalButton.setTypeface(normalButton.typeface, if (!messages) Typeface.BOLD else Typeface.NORMAL)
        messageButton.setTypeface(messageButton.typeface, if (messages) Typeface.BOLD else Typeface.NORMAL)
        modeHint.text = if (messages) {
            "Запятая = конец сообщения. Каждая часть набирается и отправляется отдельно."
        } else {
            "Весь текст набирается в поле как есть."
        }
        if (::pauseRow.isInitialized) pauseRow.visibility = if (messages) View.VISIBLE else View.GONE
        if (::sendCheck.isInitialized) sendCheck.visibility = if (messages) View.GONE else View.VISIBLE
        updateCounter()
    }

    private fun updateCounter() {
        if (!::counter.isInitialized || !::textInput.isInitialized) return
        val text = textInput.text.toString()
        counter.text = if (messageMode) {
            val count = text.split(',', '，').count { it.trim().isNotEmpty() }
            "Сообщений: $count"
        } else {
            val tokenPattern = Regex("\\{(?:ENTER|BACK|SEND|WAIT:\\d+)\\}", RegexOption.IGNORE_CASE)
            "Символов: ${text.replace(tokenPattern, "").codePointCount(0, text.replace(tokenPattern, "").length)}"
        }
    }

    private fun refreshStatus() {
        val serviceOn = TyperService.instance != null
        val (label, color) = when {
            !serviceOn -> "Служба выключена" to muted
            TyperState.armed -> "ожидаю тап по полю ввода…" to accent
            else -> "Служба включена · готов к работе" to green
        }
        statusText.text = label
        statusText.setTextColor(color)
        statusDot.background = oval(color)
        serviceButton.visibility = if (serviceOn) View.GONE else View.VISIBLE
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onPause() {
        save()
        super.onPause()
    }

    private fun save() {
        if (!::textInput.isInitialized) return
        prefs.edit()
            .putString("text", textInput.text.toString())
            .putLong("delay", (findNumber("Задержка, мс", 40)).coerceIn(0, 5000))
            .putInt("step", findNumber("Символов за шаг", 1).coerceIn(1, 500))
            .putBoolean("jitter", jitterCheck.isChecked)
            .putBoolean("send", sendCheck.isChecked)
            .putBoolean("split", messageMode)
            .putLong("pause", pauseInput.text.toString().toLongOrNull()?.coerceIn(0, 60000) ?: 800)
            .putBoolean("humanize", humanizeCheck.isChecked)
            .putInt("typo", typoInput.text.toString().toIntOrNull()?.coerceIn(0, 30) ?: 3)
            .apply()
    }

    private fun findNumber(label: String, default: Int): Int {
        val root = window.decorView.findViewById<ViewGroup>(android.R.id.content) ?: return default
        fun visit(group: ViewGroup): Int? {
            for (i in 0 until group.childCount) {
                val view = group.getChildAt(i)
                if (view is LinearLayout) {
                    val labelView = if (view.childCount > 0) view.getChildAt(0) as? TextView else null
                    if (labelView?.text == label && view.childCount > 1) {
                        return (view.getChildAt(1) as? EditText)?.text?.toString()?.toIntOrNull()
                    }
                    val nested = visit(view)
                    if (nested != null) return nested
                } else if (view is ViewGroup) {
                    val nested = visit(view)
                    if (nested != null) return nested
                }
            }
            return null
        }
        return visit(root) ?: default
    }

    private fun arm(delayInput: EditText, stepInput: EditText) {
        val service = TyperService.instance
        if (service == null) {
            Toast.makeText(this, "Сначала включи службу", Toast.LENGTH_LONG).show()
            return
        }
        val text = textInput.text.toString()
        if (text.isBlank()) {
            Toast.makeText(this, "Введи текст", Toast.LENGTH_SHORT).show()
            return
        }
        val parts = text.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }
        if (messageMode && parts.isEmpty()) {
            Toast.makeText(this, "Добавь хотя бы одно сообщение", Toast.LENGTH_SHORT).show()
            return
        }
        save()
        TyperState.text = text
        TyperState.delayMs = delayInput.text.toString().toLongOrNull()?.coerceIn(0, 5000) ?: 40
        TyperState.step = stepInput.text.toString().toIntOrNull()?.coerceIn(1, 500) ?: 1
        TyperState.jitter = jitterCheck.isChecked
        TyperState.splitMode = messageMode
        TyperState.autoSend = sendCheck.isChecked || messageMode
        TyperState.pauseMs = pauseInput.text.toString().toLongOrNull()?.coerceIn(0, 60000) ?: 800
        TyperState.humanize = humanizeCheck.isChecked
        TyperState.typoPercent = typoInput.text.toString().toIntOrNull()?.coerceIn(0, 30) ?: 3
        service.arm()
        Toast.makeText(this, "Открой приложение и тапни по полю ввода", Toast.LENGTH_LONG).show()
        moveTaskToBack(true)
    }
}
