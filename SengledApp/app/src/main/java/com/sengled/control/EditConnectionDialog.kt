package com.sengled.control

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.widget.EditText
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sengled.control.databinding.DialogEditConnectionBinding

/**
 * Two-field (IP + MAC) connection editor for a paired bulb.
 *
 * The IP field inserts a '.' after every three digits, the MAC field a ':'
 * after every two hex digits. Both fields rebuild their masked text from a raw
 * model, so paste, prefix and oversize input are normalized the same way.
 * Validation runs at save time only: an invalid value shows a field error and
 * the dialog stays open with nothing written. An empty MAC is valid and clears
 * the stored `mac_<id>`.
 */
object EditConnectionDialog {

    fun show(context: Context, bulb: Bulb, onSaved: (newIp: String, newMac: String) -> Unit) {
        val binding = DialogEditConnectionBinding.inflate(LayoutInflater.from(context))

        // Prefill before attaching the mask watchers so programmatic text needs
        // no re-entry guard. IP is shown as stored; the MAC is normalized by
        // construction for display only (legacy values stay untouched on disk).
        binding.inputIp.setText(bulb.ip)
        binding.inputMac.setText(
            InputMasks.formatMac(InputMasks.macRaw(BulbRegistry.getMac(context, bulb.id)))
        )

        attachMask(binding.inputIp, '.', InputMasks::ipRaw, InputMasks::formatIp)
        attachMask(binding.inputMac, ':', InputMasks::macRaw, InputMasks::formatMac)
        // Any edit clears a previously shown field error.
        binding.inputIp.addTextChangedListener(clearErrorWatcher { binding.tilIp.error = null })
        binding.inputMac.addTextChangedListener(clearErrorWatcher { binding.tilMac.error = null })

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.edit_connection_title)
            .setView(binding.root)
            .setPositiveButton(R.string.rename_save, null)
            .setNegativeButton(R.string.rename_cancel, null)
            .show()

        // Override the positive button so validation can veto the dismiss.
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val ip = binding.inputIp.text?.toString()?.trim().orEmpty()
            val mac = binding.inputMac.text?.toString()?.trim().orEmpty()

            var valid = true
            if (!InputMasks.isValidIpv4(ip)) {
                binding.tilIp.error = context.getString(R.string.edit_connection_error_ip)
                valid = false
            } else {
                binding.tilIp.error = null
            }
            // An empty MAC is allowed (it clears mac_<id>); a non-empty one must
            // be exactly six hex byte pairs.
            if (mac.isNotEmpty() && InputMasks.normalizeMacToCanonical(mac) == null) {
                binding.tilMac.error = context.getString(R.string.edit_connection_error_mac)
                valid = false
            } else {
                binding.tilMac.error = null
            }

            if (valid) {
                dialog.dismiss()
                onSaved(ip, mac)
            }
        }
    }

    /**
     * Re-entry-guarded mask: rebuilds the displayed text from a raw model and
     * restores the caret. Delegates to the shared [InputMasks.attachMask] so the
     * add-manual dialog and this editor mask identically.
     */
    private fun attachMask(
        field: EditText,
        separator: Char,
        filterRaw: (String) -> String,
        format: (String) -> String
    ) = InputMasks.attachMask(field, separator, filterRaw, format)

    private fun clearErrorWatcher(clear: () -> Unit): TextWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = clear()
        override fun afterTextChanged(s: Editable?) = Unit
    }
}
