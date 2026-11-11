package com.autotyper

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var etText: EditText
    private lateinit var etDelay: EditText
    private lateinit var etStep: EditText
    private lateinit var cbJitter: CheckBox

    private val prefs by lazy { getSharedPreferences("typer", Context.MODE_PRIVATE) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(16))
        }
        root.addView(TextView(this).apply {
            text = "AutoTyper"
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
        })
        status = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(status)
        root.addView(Button(this).apply {
            text = "1. Включить службу (Специальные возможности)"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        etText = EditText(this).apply {
            hint = "Текст для ввода (любой язык, код, эмодзи)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 6
            maxLines = 12
            gravity = Gravity.TOP or Gravity.START
            setText(prefs.getString("text", ""))
        }
        root.addView(etText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        etDelay = EditText(this).apply {
            hint = "Задержка, мс"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.getLong("delay", 40).toString())
        }
        etStep = EditText(this).apply {
            hint = "Символов за шаг"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.getInt("step", 1).toString())
        }
        row.addView(etDelay, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(etStep, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row)

        cbJitter = CheckBox(this).apply {
            text = "Случайная вариация скорости"
            isChecked = prefs.getBoolean("jitter", true)
        }
        root.addView(cbJitter)
        root.addView(Button(this).apply {
            text = "2. ▶ ГОТОВО — выбрать поле"
            setOnClickListener { arm() }
        })
        root.addView(Button(this).apply {
            text = "■ Стоп"
            setOnClickListener {
                TyperService.instance?.disarm()
                refreshStatus()
            }
        })
        root.addView(TextView(this).apply {
            text = "Как пользоваться: введи текст → «ГОТОВО» → приложение свернётся → " +
                "открой любое приложение, тапни по полю ввода — текст начнёт печататься. " +
                "Красная кнопка «СТОП» висит поверх экрана."
            textSize = 13f
            setPadding(0, dp(12), 0, 0)
        })
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onPause() {
        super.onPause()
        save()
    }

    private fun save() {
        prefs.edit()
            .putString("text", etText.text.toString())
            .putLong("delay", etDelay.text.toString().toLongOrNull() ?: 40)
            .putInt("step", etStep.text.toString().toIntOrNull() ?: 1)
            .putBoolean("jitter", cbJitter.isChecked)
            .apply()
    }

    private fun refreshStatus() {
        val on = TyperService.instance != null
        status.text = when {
            !on -> "Служба выключена — включи AutoTyper в Специальных возможностях"
            TyperState.armed -> "Служба включена · ожидаю тап по полю ввода…"
            else -> "Служба включена · готов к работе"
        }
    }

    private fun arm() {
        val service = TyperService.instance
        if (service == null) {
            Toast.makeText(this, "Сначала включи службу (шаг 1)", Toast.LENGTH_LONG).show()
            return
        }
        val text = etText.text.toString()
        if (text.isEmpty()) {
            Toast.makeText(this, "Введи текст", Toast.LENGTH_SHORT).show()
            return
        }
        save()
        TyperState.text = text
        TyperState.delayMs = (etDelay.text.toString().toLongOrNull() ?: 40).coerceIn(0, 5000)
        TyperState.step = (etStep.text.toString().toIntOrNull() ?: 1).coerceIn(1, 500)
        TyperState.jitter = cbJitter.isChecked
        service.arm()
        Toast.makeText(this, "Открой приложение и тапни по полю ввода", Toast.LENGTH_LONG).show()
        moveTaskToBack(true)
    }
}
