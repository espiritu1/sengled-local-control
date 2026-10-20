package com.sengled.control

import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText

/**
 * Pure, Android-free input masks and validators for the IP/MAC editors.
 *
 * Keeping these functions free of framework types lets the dialog and
 * [BulbRegistry] share the exact same canonicalization rules. The raw caps
 * (12 digits / 12 hex chars) absorb paste, prefix and oversize input.
 */
object InputMasks {

    private const val MAX_IP_DIGITS = 12 // 4 octets x 3 digits
    private const val MAX_MAC_HEX = 12   // 6 byte pairs

    /** Digits only, capped at 12 (four octets max). */
    fun ipRaw(text: String): String = text.filter { it.isDigit() }.take(MAX_IP_DIGITS)

    /** Hex digits only, separators stripped, capped at 12 and uppercased. */
    fun macRaw(text: String): String =
        text.filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
            .take(MAX_MAC_HEX)
            .uppercase()

    /** Groups the raw digits in octets of up to three digits, joined by '.'. */
    fun formatIp(raw: String): String = raw.chunked(3).joinToString(".")

    /** Groups the raw hex in byte pairs joined by ':'. */
    fun formatMac(raw: String): String = raw.chunked(2).joinToString(":")

    /**
     * Maps a raw-character index to a display index in [masked], skipping over
     * the separators that precede the indexed character so the caret never
     * lands in the middle of an inserted '.' or ':'.
     */
    fun displayCursor(rawIndex: Int, masked: String, separator: Char): Int {
        if (rawIndex <= 0) return 0
        var rawSeen = 0
        for (i in masked.indices) {
            if (masked[i] == separator) continue
            rawSeen++
            if (rawSeen == rawIndex) {
                var j = i + 1
                while (j < masked.length && masked[j] == separator) j++
                return j
            }
        }
        return masked.length
    }

    /** True when [s] is exactly four dot-separated octets, each in 0..255. */
    fun isValidIpv4(s: String): Boolean {
        val parts = s.trim().split(".")
        if (parts.size != 4) return false
        return parts.all { part ->
            part.isNotEmpty() && part.length <= 3 && part.all { it.isDigit() } &&
                (part.toIntOrNull()?.let { it in 0..255 } == true)
        }
    }

    /**
     * Uppercase `XX:XX:XX:XX:XX:XX` form, or null when the input is not
     * exactly 12 hex digits (blank, short and oversize input all return null).
     */
    fun normalizeMacToCanonical(s: String): String? {
        val raw = s.filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }.uppercase()
        if (raw.length != MAX_MAC_HEX) return null
        return raw.chunked(2).joinToString(":")
    }

    /**
     * Attaches a re-entry-guarded mask to [field]: the displayed text is rebuilt
     * from a raw model (via [filterRaw] + [format]) and the caret is restored.
     * Deleting a separator also drops the digit before it, so the separator is
     * not re-inserted and the caret never strands after it.
     *
     * The raw caps ([ipRaw]/[macRaw]) enforce the maximum length, so callers do
     * not need a separate [android.text.InputFilter.LengthFilter].
     */
    fun attachMask(
        field: EditText,
        separator: Char,
        filterRaw: (String) -> String,
        format: (String) -> String
    ) {
        field.addTextChangedListener(object : TextWatcher {
            private var guard = false
            private var previous = field.text?.toString().orEmpty()

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (guard) return

                val newText = s?.toString().orEmpty()
                var raw = filterRaw(newText)

                // Coordinated backspace: a separator deleted on its own takes
                // the digit before it along.
                if (before == 1 && count == 0 && start in previous.indices && previous[start] == separator) {
                    val digitsBefore = filterRaw(
                        newText.substring(0, start.coerceIn(0, newText.length))
                    ).length
                    if (digitsBefore in 1..raw.length) {
                        raw = raw.removeRange(digitsBefore - 1, digitsBefore)
                    }
                }

                val masked = format(raw)
                val cursorRaw = filterRaw(
                    newText.substring(0, (start + count).coerceIn(0, newText.length))
                ).length

                guard = true
                if (masked != newText) {
                    field.setText(masked)
                    field.setSelection(
                        InputMasks.displayCursor(cursorRaw, masked, separator)
                            .coerceIn(0, masked.length)
                    )
                }
                guard = false

                previous = masked
            }

            override fun afterTextChanged(s: Editable?) = Unit
        })
    }
}
