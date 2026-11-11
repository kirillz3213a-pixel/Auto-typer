package com.autotyper

/** Shared settings/state between the activity and accessibility service. */
object TyperState {
    @Volatile var text: String = ""
    @Volatile var delayMs: Long = 40
    @Volatile var step: Int = 1
    @Volatile var jitter: Boolean = true
    @Volatile var armed: Boolean = false
}
