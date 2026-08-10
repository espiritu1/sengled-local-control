package com.sengled.control

import android.app.TimePickerDialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sengled.control.databinding.DialogRoutineBinding
import java.util.Locale

/**
 * Per-bulb routine editor: pick a bulb, choose ON/OFF times (AM/PM TimePicker
 * with an always-visible AM/PM toggle), toggle the whole routine, then save.
 * The dialog loads each bulb's saved config when it is selected, so it behaves
 * as a general editor over all bulbs.
 */
object RoutineDialog {

    fun show(
        context: Context,
        bulbs: List<Bulb>,
        initialBulbId: String,
        onSaved: (bulbId: String, enabled: Boolean, onMinutes: Int, offMinutes: Int, brightness: Int) -> Unit
    ) {
        if (bulbs.isEmpty()) return

        val binding = DialogRoutineBinding.inflate(LayoutInflater.from(context))
        var selectedBulbId = bulbs.first().id
        var onMinutes = 19 * 60 + 30
        var offMinutes = 0
        var routineBrightness = 1

        fun formatTime(minutes: Int): String {
            val hour = minutes / 60
            val minute = minutes % 60
            val period = if (hour < 12) "AM" else "PM"
            val hour12 = when (hour % 12) { 0 -> 12; else -> hour % 12 }
            return String.format(Locale.US, "%d:%02d %s", hour12, minute, period)
        }

        /**
         * Converts minutes-since-midnight to the same wall time in the other
         * period, preserving the displayed 12-hour value and the minutes.
         * 12 AM = midnight (0), 12 PM = noon (720).
         */
        fun applyPeriod(minutes: Int, isAm: Boolean): Int {
            val hour24 = minutes / 60
            val minute = minutes % 60
            val hour12 = when (hour24 % 12) { 0 -> 12; else -> hour24 % 12 }
            val newHour24 = when {
                isAm && hour12 == 12 -> 0
                isAm -> hour12
                hour12 == 12 -> 12
                else -> hour12 + 12
            }
            return (newHour24 * 60 + minute).coerceIn(0, 1439)
        }

        fun syncPeriodToggles() {
            binding.toggleRoutineOnPeriod.check(
                if (onMinutes / 60 < 12) R.id.btnRoutineOnAm else R.id.btnRoutineOnPm
            )
            binding.toggleRoutineOffPeriod.check(
                if (offMinutes / 60 < 12) R.id.btnRoutineOffAm else R.id.btnRoutineOffPm
            )
        }

        fun renderTimes() {
            binding.btnRoutineOn.text = context.getString(R.string.routine_on_at, formatTime(onMinutes))
            binding.btnRoutineOff.text = context.getString(R.string.routine_off_at, formatTime(offMinutes))
            syncPeriodToggles()
        }

        fun loadRoutine(bulbId: String) {
            val config = ScheduleManager.getRoutine(context, bulbId)
            onMinutes = config.onMinutes
            offMinutes = config.offMinutes
            routineBrightness = config.brightness.coerceIn(1, 100)
            binding.switchRoutineEnabled.isChecked = config.enabled
            binding.seekRoutineBrightness.progress = routineBrightness - 1
            binding.lblRoutineBrightness.text = context.getString(
                R.string.routine_brightness_label, routineBrightness
            )
            renderTimes()
        }

        binding.seekRoutineBrightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                routineBrightness = progress + 1
                binding.lblRoutineBrightness.text = context.getString(
                    R.string.routine_brightness_label, routineBrightness
                )
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })

        binding.toggleRoutineOnPeriod.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val isAm = checkedId == R.id.btnRoutineOnAm
            if (isAm != (onMinutes / 60 < 12)) {
                onMinutes = applyPeriod(onMinutes, isAm)
                renderTimes()
            }
        }
        binding.toggleRoutineOffPeriod.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val isAm = checkedId == R.id.btnRoutineOffAm
            if (isAm != (offMinutes / 60 < 12)) {
                offMinutes = applyPeriod(offMinutes, isAm)
                renderTimes()
            }
        }

        val labels = bulbs.map { "${it.name} - ${it.ip}" }
        binding.spinnerBulb.adapter = ArrayAdapter(
            context,
            R.layout.item_spinner,
            labels
        ).apply {
            setDropDownViewResource(R.layout.item_spinner)
        }

        binding.spinnerBulb.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedBulbId = bulbs[position].id
                loadRoutine(selectedBulbId)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        val startIndex = bulbs.indexOfFirst { it.id == initialBulbId }.coerceAtLeast(0)
        binding.spinnerBulb.setSelection(startIndex)
        loadRoutine(selectedBulbId)

        binding.btnRoutineOn.setOnClickListener {
            TimePickerDialog(context, { _, hour, minute ->
                onMinutes = hour * 60 + minute
                renderTimes()
            }, onMinutes / 60, onMinutes % 60, false).show()
        }
        binding.btnRoutineOff.setOnClickListener {
            TimePickerDialog(context, { _, hour, minute ->
                offMinutes = hour * 60 + minute
                renderTimes()
            }, offMinutes / 60, offMinutes % 60, false).show()
        }

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.routine_title)
            .setView(binding.root)
            .setPositiveButton(R.string.routine_save) { _, _ ->
                onSaved(selectedBulbId, binding.switchRoutineEnabled.isChecked, onMinutes, offMinutes, routineBrightness)
            }
            .setNegativeButton(R.string.routine_cancel, null)
            .show()
    }
}
