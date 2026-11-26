package com.mqx.dyshuaping

object MessageProvider {
    private var sequentialIndex = 0

    @Synchronized
    fun peek(settings: Settings): String? {
        val messages = settings.messages
        if (messages.isEmpty()) return null
        if (settings.randomMode) return messages.random()
        if (sequentialIndex !in messages.indices) sequentialIndex = 0
        return messages[sequentialIndex]
    }

    @Synchronized
    fun advanceAfterSuccess(settings: Settings) {
        if (settings.randomMode) return
        val size = settings.messages.size
        if (size == 0) {
            sequentialIndex = 0
        } else {
            sequentialIndex = (sequentialIndex + 1) % size
        }
    }

    @Synchronized
    fun reset() {
        sequentialIndex = 0
    }
}
