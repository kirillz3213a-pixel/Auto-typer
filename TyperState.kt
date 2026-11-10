package com.autotyper

/** Общие настройки/состояние между Activity и службой. */
object TyperState {
    @Volatile var text: String = ""
    @Volatile var delayMs: Long = 40      // пауза между шагами
    @Volatile var step: Int = 1           // сколько символов за один шаг
    @Volatile var jitter: Boolean = true  // случайная вариация задержки
    @Volatile var armed: Boolean = false  // ждём, пока пользователь тапнет по полю
}
