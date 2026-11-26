package com.rox.sleeptimer.data

import android.content.Context
import com.rox.sleeptimer.model.TimerPreset
import com.rox.sleeptimer.model.TransitionMode
import com.rox.sleeptimer.model.VolumeStep
import org.json.JSONArray
import org.json.JSONObject

class PresetStorage(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadActiveSteps(): List<VolumeStep> {
        val raw = prefs.getString(KEY_ACTIVE_STEPS, null) ?: return defaultActiveSteps()
        return runCatching { decodeSteps(JSONArray(raw)) }.getOrElse { defaultActiveSteps() }
    }

    fun saveActiveSteps(steps: List<VolumeStep>) {
        prefs.edit().putString(KEY_ACTIVE_STEPS, encodeSteps(steps).toString()).apply()
    }

    fun loadTransitionMode(): TransitionMode {
        val name = prefs.getString(KEY_MODE, TransitionMode.GRADUAL.name)
        return runCatching { TransitionMode.valueOf(name ?: TransitionMode.GRADUAL.name) }
            .getOrDefault(TransitionMode.GRADUAL)
    }

    fun saveTransitionMode(mode: TransitionMode) {
        prefs.edit().putString(KEY_MODE, mode.name).apply()
    }

    fun loadPauseAtEnd(): Boolean {
        return prefs.getBoolean(KEY_PAUSE_AT_END, true)
    }

    fun savePauseAtEnd(pauseAtEnd: Boolean) {
        prefs.edit().putBoolean(KEY_PAUSE_AT_END, pauseAtEnd).apply()
    }

    fun loadPresets(): List<TimerPreset> {
        val raw = prefs.getString(KEY_PRESETS, null) ?: return defaultPresets()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val mode = runCatching {
                        TransitionMode.valueOf(obj.optString("mode", TransitionMode.GRADUAL.name))
                    }.getOrDefault(TransitionMode.GRADUAL)
                    add(
                        TimerPreset(
                            id = obj.getString("id"),
                            name = obj.getString("name"),
                            transitionMode = mode,
                            pauseAtEnd = obj.optBoolean("pauseAtEnd", true),
                            steps = decodeSteps(obj.getJSONArray("steps"))
                        )
                    )
                }
            }
        }.getOrElse { defaultPresets() }
    }

    fun savePresets(presets: List<TimerPreset>) {
        val array = JSONArray()
        presets.forEach { preset ->
            val obj = JSONObject().apply {
                put("id", preset.id)
                put("name", preset.name)
                put("mode", preset.transitionMode.name)
                put("pauseAtEnd", preset.pauseAtEnd)
                put("steps", encodeSteps(preset.steps))
            }
            array.put(obj)
        }
        prefs.edit().putString(KEY_PRESETS, array.toString()).apply()
    }

    private fun encodeSteps(steps: List<VolumeStep>): JSONArray {
        val array = JSONArray()
        steps.forEach { step ->
            array.put(
                JSONObject().apply {
                    put("id", step.id)
                    put("minute", step.minute)
                    put("volume", step.volumePercent)
                }
            )
        }
        return array
    }

    private fun decodeSteps(array: JSONArray): List<VolumeStep> {
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                add(
                    VolumeStep(
                        id = item.optLong("id", (i + 1).toLong()),
                        minute = item.optInt("minute", 10).coerceAtLeast(1),
                        volumePercent = item.optInt("volume", 20).coerceIn(0, 100)
                    )
                )
            }
        }
    }

    private fun defaultActiveSteps(): List<VolumeStep> = listOf(
        VolumeStep(1L, 15, 45),
        VolumeStep(2L, 30, 20),
        VolumeStep(3L, 45, 0)
    )

    private fun defaultPresets(): List<TimerPreset> = listOf(
        TimerPreset(
            id = "preset-30",
            name = "Temporizador 30 min",
            transitionMode = TransitionMode.GRADUAL,
            pauseAtEnd = true,
            steps = listOf(
                VolumeStep(1L, 15, 40),
                VolumeStep(2L, 30, 0)
            )
        ),
        TimerPreset(
            id = "preset-45",
            name = "Temporizador 45 min",
            transitionMode = TransitionMode.GRADUAL,
            pauseAtEnd = true,
            steps = listOf(
                VolumeStep(1L, 20, 50),
                VolumeStep(2L, 35, 20),
                VolumeStep(3L, 45, 0)
            )
        ),
        TimerPreset(
            id = "preset-60",
            name = "Temporizador 60 min",
            transitionMode = TransitionMode.GRADUAL,
            pauseAtEnd = true,
            steps = listOf(
                VolumeStep(1L, 20, 60),
                VolumeStep(2L, 40, 30),
                VolumeStep(3L, 55, 10),
                VolumeStep(4L, 60, 0)
            )
        )
    )

    companion object {
        private const val PREFS_NAME = "sleep_timer_rox_v1_prefs"
        private const val KEY_ACTIVE_STEPS = "active_steps_json"
        private const val KEY_PRESETS = "saved_presets_json"
        private const val KEY_MODE = "transition_mode"
        private const val KEY_PAUSE_AT_END = "pause_at_end"
    }
}
