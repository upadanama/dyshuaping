package com.mqx.dyshuaping

import java.util.ArrayDeque
import java.util.Random

/**
 * Builds the anti-duplicate suffix appended to each outgoing comment.
 * The deque keeps the last 20 full suffix strings that were actually sent.
 */
object SuffixGenerator {
    private const val RECENT_LIMIT = 20
    private const val MAX_GENERATE_ATTEMPTS = 80

    private enum class Kind { SYMBOL, NUMBER, EMOJI }

    private val random = Random()
    private val recent = ArrayDeque<String>()

    private val symbols = listOf(
        "~", "～", "…", "!!", "？？", "。。", "☀", "🌙", "✨", "🍀", "👍"
    )
    private val emojiCodes = listOf(
        "[比心]", "[爱心]", "[鼓掌]", "[玫瑰]", "[666]", "[一起加油]",
        "[坏笑]", "[礼物]", "[黄脸祈祷]", "[害羞]", "[微笑]"
    )

    @Synchronized
    fun build(settings: Settings): String {
        if (!settings.suffixEnabled) return ""
        val kinds = ArrayList<Kind>(3)
        if (settings.suffixSymbols) kinds.add(Kind.SYMBOL)
        if (settings.suffixNumbers) kinds.add(Kind.NUMBER)
        if (settings.suffixEmoji) kinds.add(Kind.EMOJI)
        if (kinds.isEmpty()) return ""

        val count = settings.suffixCount.coerceIn(1, 3)
        var candidate = ""
        var attempt = 0
        while (attempt < MAX_GENERATE_ATTEMPTS) {
            candidate = generate(kinds, count)
            if (!recent.contains(candidate)) break
            attempt++
        }
        return candidate
    }

    @Synchronized
    fun markUsed(suffix: String) {
        if (suffix.isEmpty()) return
        recent.remove(suffix)
        if (recent.size >= RECENT_LIMIT) recent.removeFirst()
        recent.addLast(suffix)
    }

    @Synchronized
    fun clearRecent() {
        recent.clear()
    }

    private fun generate(kinds: List<Kind>, count: Int): String {
        val builder = StringBuilder()
        repeat(count) {
            when (kinds[random.nextInt(kinds.size)]) {
                Kind.SYMBOL -> builder.append(symbols[random.nextInt(symbols.size)])
                Kind.NUMBER -> builder.append(randomNumberToken())
                Kind.EMOJI -> builder.append(emojiCodes[random.nextInt(emojiCodes.size)])
            }
        }
        return builder.toString()
    }

    private fun randomNumberToken(): String {
        val length = random.nextInt(3) + 1
        val builder = StringBuilder(length)
        repeat(length) {
            builder.append(('0' + random.nextInt(10)))
        }
        return builder.toString()
    }
}
