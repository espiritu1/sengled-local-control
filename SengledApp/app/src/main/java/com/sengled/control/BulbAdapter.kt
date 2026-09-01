package com.sengled.control

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.EditText
import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import com.sengled.control.databinding.ItemBulbBinding

class BulbAdapter(
    initialBulbs: List<Bulb>,
    private val listener: Listener
) : RecyclerView.Adapter<BulbAdapter.BulbHolder>() {

    interface Listener {
        fun onSwitchChanged(bulb: Bulb, isOn: Boolean)
        fun onBrightnessChanged(bulb: Bulb, value: Int)
        fun onRename(bulb: Bulb, newName: String)
        fun onEditIp(bulb: Bulb, newIp: String)
        fun onEditSchedule(bulb: Bulb)
        fun onDeleteBulb(bulb: Bulb)
    }

    private companion object {
        const val MAX_NAME_LENGTH = 40
        const val BRIGHTNESS_DEBOUNCE_MS = 250L
    }

    private val bulbs = initialBulbs.toMutableList()
    private val handler = Handler(Looper.getMainLooper())
    private val pendingBrightness = HashMap<String, Runnable>()
    private var updating = false

    /** Set by the host to start a drag for a given holder (long-press). */
    var onStartDrag: ((RecyclerView.ViewHolder) -> Unit)? = null

    inner class BulbHolder(val binding: ItemBulbBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BulbHolder {
        val binding = ItemBulbBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return BulbHolder(binding)
    }

    override fun getItemCount(): Int = bulbs.size

    override fun onBindViewHolder(holder: BulbHolder, position: Int) {
        val bulb = bulbs[position]
        val context = holder.binding.root.context
        val b = holder.binding

        b.txtName.text = bulb.name
        b.txtBrightnessValue.text = context.getString(R.string.brightness_percent, bulb.brightness)
        b.btnSchedule.contentDescription = context.getString(R.string.content_desc_schedule)
        b.btnRename.contentDescription = context.getString(R.string.content_desc_rename)
        b.btnDelete.contentDescription = context.getString(R.string.content_desc_delete)

        when (bulb.connected) {
            null -> {
                b.txtSwitchLabel.setText(R.string.status_checking)
                b.txtSwitchLabel.setTextColor(context.getColor(R.color.text_secondary))
            }
            true -> if (bulb.isOn) {
                b.txtSwitchLabel.setText(R.string.status_on)
                b.txtSwitchLabel.setTextColor(context.getColor(R.color.status_online))
            } else {
                b.txtSwitchLabel.setText(R.string.status_off)
                b.txtSwitchLabel.setTextColor(context.getColor(R.color.text_secondary))
            }
            false -> {
                b.txtSwitchLabel.setText(R.string.status_offline)
                b.txtSwitchLabel.setTextColor(context.getColor(R.color.status_offline))
            }
        }

        updating = true
        b.switchOn.isChecked = bulb.isOn
        b.switchOn.isEnabled = bulb.connected != false
        // SeekBar range 0..99 maps to brightness 1..100 (1 = lowest light, never off)
        b.seekBrightness.progress = (bulb.brightness - 1).coerceIn(0, b.seekBrightness.max)
        updating = false

        b.switchOn.setOnCheckedChangeListener { _, isChecked ->
            if (updating) return@setOnCheckedChangeListener
            listener.onSwitchChanged(bulb, isChecked)
        }

        b.seekBrightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (updating || !fromUser) return
                val brightness = progress + 1
                b.txtBrightnessValue.text = context.getString(R.string.brightness_percent, brightness)

                pendingBrightness.remove(bulb.id)?.let(handler::removeCallbacks)
                val runnable = Runnable {
                    pendingBrightness.remove(bulb.id)
                    listener.onBrightnessChanged(bulb, brightness)
                }
                pendingBrightness[bulb.id] = runnable
                handler.postDelayed(runnable, BRIGHTNESS_DEBOUNCE_MS)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })

        b.btnSchedule.setOnClickListener { listener.onEditSchedule(bulb) }
        b.btnRename.setOnClickListener { showRenameDialog(context, bulb) }
        b.btnEditIp.setOnClickListener { showEditIpDialog(context, bulb) }
        b.btnDelete.setOnClickListener { listener.onDeleteBulb(bulb) }

        // Long-press the card body to drag & reorder
        holder.binding.root.setOnLongClickListener {
            onStartDrag?.invoke(holder)
            true
        }
    }

    private fun showRenameDialog(context: Context, bulb: Bulb) {
        val input = EditText(context).apply {
            setText(bulb.name)
            filters = arrayOf(InputFilter.LengthFilter(MAX_NAME_LENGTH))
            setSelection(text.length)
        }

        AlertDialog.Builder(context)
            .setTitle(R.string.rename_title)
            .setView(input)
            .setPositiveButton(R.string.rename_save) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    listener.onRename(bulb, name)
                }
            }
            .setNegativeButton(R.string.rename_cancel, null)
            .show()
    }

    private fun showEditIpDialog(context: Context, bulb: Bulb) {
        val input = EditText(context).apply {
            setText(bulb.ip)
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            setSelection(text.length)
        }

        AlertDialog.Builder(context)
            .setTitle(R.string.edit_ip_title)
            .setMessage(context.getString(R.string.edit_ip_hint, bulb.name, bulb.ip))
            .setView(input)
            .setPositiveButton(R.string.edit_ip_save) { _, _ ->
                val ip = input.text.toString().trim()
                if (ip.isNotEmpty()) {
                    listener.onEditIp(bulb, ip)
                }
            }
            .setNegativeButton(R.string.rename_cancel, null)
            .show()
    }

    fun updateBulb(updated: Bulb) {
        val index = bulbs.indexOfFirst { it.id == updated.id }
        if (index < 0) return
        bulbs[index] = updated
        notifyItemChanged(index)
    }

    fun addBulb(bulb: Bulb) {
        if (bulbs.any { it.id == bulb.id }) return
        bulbs.add(bulb)
        notifyItemInserted(bulbs.size - 1)
    }

    fun removeBulb(bulbId: String) {
        val index = bulbs.indexOfFirst { it.id == bulbId }
        if (index < 0) return
        bulbs.removeAt(index)
        notifyItemRemoved(index)
    }

    /** Move an item within the list (for drag & drop). */
    fun onItemMove(fromPosition: Int, toPosition: Int) {
        if (fromPosition in bulbs.indices && toPosition in bulbs.indices) {
            val moved = bulbs.removeAt(fromPosition)
            bulbs.add(toPosition, moved)
            notifyItemMoved(fromPosition, toPosition)
        }
    }

    /** Current order of bulb ids (used to persist after a drag ends). */
    fun orderIds(): List<String> = bulbs.map { it.id }
}
