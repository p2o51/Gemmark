package com.gemmark.app.core

/**
 * Token counting abstraction.
 *
 * The v1 spec requires all token counts to be recomputed with the Gemma 4
 * tokenizer after generation — platform callbacks (AICore chunks are not
 * single tokens) must not be trusted.
 *
 * [ApproxTokenCounter] is a stand-in so the pipeline works end-to-end before
 * the real tokenizer is integrated on device. Swap in a SentencePiece-backed
 * implementation (Gemma vocabulary) during the device-integration session and
 * record its id in `config.token_counter` of every report.
 */
interface TokenCounter {
    /** Stable identifier recorded into reports, e.g. "approx-v1" or "gemma4-spm". */
    val id: String

    fun count(text: String): Int
}

/**
 * Heuristic counter: CJK characters ≈ 1 token each, other scripts ≈ 1.3 tokens
 * per whitespace-separated word. Good enough to exercise the pipeline; NOT
 * valid for published numbers.
 */
class ApproxTokenCounter : TokenCounter {
    override val id: String = "approx-v1"

    override fun count(text: String): Int {
        if (text.isBlank()) return 0
        var cjk = 0
        val rest = StringBuilder()
        for (ch in text) {
            if (isCjk(ch)) cjk++ else rest.append(ch)
        }
        val words = rest.split(Regex("\\s+")).count { it.isNotBlank() }
        return cjk + Math.round(words * 1.3).toInt()
    }

    private fun isCjk(ch: Char): Boolean {
        val block = Character.UnicodeBlock.of(ch) ?: return false
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
            block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
            block == Character.UnicodeBlock.HIRAGANA ||
            block == Character.UnicodeBlock.KATAKANA ||
            block == Character.UnicodeBlock.HANGUL_SYLLABLES
    }
}
