package com.rox.sleeptimer

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.rox.sleeptimer.data.PresetStorage
import com.rox.sleeptimer.model.TimerPreset
import com.rox.sleeptimer.model.TransitionMode
import com.rox.sleeptimer.model.VolumeScheduleMath
import com.rox.sleeptimer.model.VolumeStep
import com.rox.sleeptimer.service.VolumeTimerService
import com.rox.sleeptimer.ui.theme.RoxAmber
import com.rox.sleeptimer.ui.theme.RoxBackground
import com.rox.sleeptimer.ui.theme.RoxDanger
import com.rox.sleeptimer.ui.theme.RoxOutline
import com.rox.sleeptimer.ui.theme.RoxSurface
import com.rox.sleeptimer.ui.theme.RoxSurfaceVariant
import com.rox.sleeptimer.ui.theme.RoxTextMuted
import com.rox.sleeptimer.ui.theme.RoxTextPrimary
import com.rox.sleeptimer.ui.theme.SleepTimerRoxTheme
import java.util.UUID
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private lateinit var audioManager: AudioManager
    private lateinit var storage: PresetStorage

    private val deviceVolumePercentState = mutableIntStateOf(60)
    private val batteryExemptState = mutableStateOf(true)

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            deviceVolumePercentState.intValue = readCurrentMediaVolumePercent()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        storage = PresetStorage(this)

        deviceVolumePercentState.intValue = readCurrentMediaVolumePercent()
        batteryExemptState.value = isIgnoringBatteryOptimizations()
        requestNotificationPermissionIfNeeded()

        setContent {
            SleepTimerRoxTheme {
                SleepTimerScreen(
                    deviceVolumePercent = deviceVolumePercentState.intValue,
                    isBatteryExempt = batteryExemptState.value,
                    storage = storage,
                    onRefreshDeviceVolume = { readCurrentMediaVolumePercent() },
                    onStartTimer = { startVol, steps, mode, pauseAtEnd ->
                        startTimerService(startVol, steps, mode, pauseAtEnd)
                    },
                    onStopTimer = { stopTimerService() },
                    onRequestBatteryExemption = { openBatteryExemptionPrompt() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        deviceVolumePercentState.intValue = readCurrentMediaVolumePercent()
        batteryExemptState.value = isIgnoringBatteryOptimizations()
        val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
        ContextCompat.registerReceiver(this, volumeReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    override fun onPause() {
        runCatching { unregisterReceiver(volumeReceiver) }
        super.onPause()
    }

    private fun readCurrentMediaVolumePercent(): Int {
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return ((current.toDouble() / max.toDouble()) * 100.0).roundToInt().coerceIn(0, 100)
    }

    private fun startTimerService(
        startVolumePercent: Int,
        steps: List<VolumeStep>,
        mode: TransitionMode,
        pauseAtEnd: Boolean
    ) {
        val intent = Intent(this, VolumeTimerService::class.java).apply {
            action = VolumeTimerService.ACTION_START
            putExtra(VolumeTimerService.EXTRA_START_VOLUME, startVolumePercent)
            putExtra(VolumeTimerService.EXTRA_STEPS_JSON, VolumeTimerService.encodeStepsForIntent(steps))
            putExtra(VolumeTimerService.EXTRA_MODE, mode.name)
            putExtra(VolumeTimerService.EXTRA_PAUSE_AT_END, pauseAtEnd)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopTimerService() {
        val intent = Intent(this, VolumeTimerService::class.java).apply {
            action = VolumeTimerService.ACTION_STOP
        }
        startService(intent)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun openBatteryExemptionPrompt() {
        runCatching {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }
}

@Composable
private fun SleepTimerScreen(
    deviceVolumePercent: Int,
    isBatteryExempt: Boolean,
    storage: PresetStorage,
    onRefreshDeviceVolume: () -> Int,
    onStartTimer: (Int, List<VolumeStep>, TransitionMode, Boolean) -> Unit,
    onStopTimer: () -> Unit,
    onRequestBatteryExemption: () -> Unit
) {
    val timerStatus by VolumeTimerService.status.collectAsState()

    var startVolumePercent by remember { mutableIntStateOf(deviceVolumePercent) }
    var steps by remember { mutableStateOf(storage.loadActiveSteps()) }
    var transitionMode by remember { mutableStateOf(storage.loadTransitionMode()) }
    var pauseAtEnd by remember { mutableStateOf(storage.loadPauseAtEnd()) }
    var presets by remember { mutableStateOf(storage.loadPresets()) }

    var showSaveDialog by remember { mutableStateOf(false) }
    var presetNameInput by remember { mutableStateOf("") }
    var feedbackMessage by remember { mutableStateOf<String?>(null) }

    fun persistSteps(updated: List<VolumeStep>) {
        steps = updated
        storage.saveActiveSteps(updated)
    }

    Scaffold(
        containerColor = RoxBackground,
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(RoxBackground)
                    .navigationBarsPadding() // Respeta la barra de flechas de Android
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                if (timerStatus.isRunning) {
                    Button(
                        onClick = onStopTimer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = RoxDanger,
                            contentColor = RoxBackground
                        )
                    ) {
                        Icon(Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Detener temporizador", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                } else {
                    Button(
                        onClick = {
                            val sorted = steps.filter { it.minute > 0 }.sortedBy { it.minute }
                            if (sorted.isEmpty()) {
                                feedbackMessage = "Añade al menos un tramo válido."
                            } else {
                                persistSteps(sorted)
                                feedbackMessage = null
                                onStartTimer(startVolumePercent, sorted, transitionMode, pauseAtEnd)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = RoxAmber,
                            contentColor = RoxBackground
                        )
                    ) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Iniciar bajada de volumen", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding(),
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Encabezado
            item {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "Sleep Timer Rox",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = RoxTextPrimary
                    )
                    Text(
                        text = "Sin publicidad · 100% sin conexión · Audio multimedia",
                        style = MaterialTheme.typography.bodySmall,
                        color = RoxTextMuted
                    )
                }
            }

            // Atajos rápidos de tiempo (15m, 30m, 45m, 60m)
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val quickPresets = listOf(
                        "15 min" to listOf(VolumeStep(1L, 8, 35), VolumeStep(2L, 15, 0)),
                        "30 min" to listOf(VolumeStep(1L, 15, 40), VolumeStep(2L, 30, 0)),
                        "45 min" to listOf(VolumeStep(1L, 20, 50), VolumeStep(2L, 35, 20), VolumeStep(3L, 45, 0)),
                        "60 min" to listOf(VolumeStep(1L, 20, 60), VolumeStep(2L, 40, 30), VolumeStep(3L, 55, 10), VolumeStep(4L, 60, 0))
                    )
                    quickPresets.forEach { (label, presetSteps) ->
                        OutlinedButton(
                            onClick = {
                                persistSteps(presetSteps)
                                feedbackMessage = "Configuración rápida: $label"
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp),
                            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, RoxOutline)
                        ) {
                            Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = RoxAmber, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }

            // Panel activo si está en marcha
            if (timerStatus.isRunning) {
                item {
                    val remaining = (timerStatus.totalSeconds - timerStatus.elapsedSeconds).coerceAtLeast(0)
                    Card(
                        colors = CardDefaults.cardColors(containerColor = RoxSurfaceVariant),
                        border = BorderStroke(1.dp, RoxAmber.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "Temporizador activo (pantalla apagada OK)",
                                color = RoxAmber,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.Bottom
                            ) {
                                Column {
                                    Text("Tiempo restante", color = RoxTextMuted, fontSize = 11.sp)
                                    Text(
                                        text = VolumeScheduleMath.formatDuration(remaining),
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 26.sp,
                                        color = RoxTextPrimary
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("Volumen actual", color = RoxTextMuted, fontSize = 11.sp)
                                    Text(
                                        text = "${timerStatus.currentVolumePercent}%",
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 26.sp,
                                        color = RoxAmber
                                    )
                                }
                            }
                            if (timerStatus.nextStepMinute != null && timerStatus.nextStepVolume != null) {
                                Text(
                                    text = "Próximo: min ${timerStatus.nextStepMinute} ➜ ${timerStatus.nextStepVolume}%",
                                    color = RoxTextMuted,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }

            // Tarjeta de volumen inicial + botón "Usar volumen actual"
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = RoxSurface),
                    border = BorderStroke(1.dp, RoxOutline),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Volumen inicial (min 0)", color = RoxTextMuted, fontSize = 12.sp)
                                Text(
                                    text = "$startVolumePercent%",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = RoxTextPrimary
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("Teléfono ahora", color = RoxTextMuted, fontSize = 11.sp)
                                Text(
                                    text = "$deviceVolumePercent%",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = RoxAmber
                                )
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                val current = onRefreshDeviceVolume()
                                startVolumePercent = current
                                feedbackMessage = "Fijado en $current% (volumen actual del teléfono)."
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(38.dp),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            border = BorderStroke(1.dp, RoxAmber.copy(alpha = 0.6f))
                        ) {
                            Icon(Icons.Rounded.VolumeUp, contentDescription = null, tint = RoxAmber, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Usar volumen actual", color = RoxTextPrimary, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                        }

                        feedbackMessage?.let { msg ->
                            Text(text = msg, color = RoxAmber, fontSize = 11.sp)
                        }
                    }
                }
            }

            // Encabezado lista de tramos
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Tramos de volumen",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = RoxTextPrimary,
                        maxLines = 1
                    )
                    Button(
                        onClick = {
                            val nextId = (steps.maxOfOrNull { it.id } ?: 0L) + 1L
                            val lastMinute = steps.maxOfOrNull { it.minute } ?: 0
                            val lastVolume = steps.lastOrNull()?.volumePercent ?: startVolumePercent
                            val suggestedVolume = (lastVolume - 20).coerceAtLeast(0)
                            persistSteps(steps + VolumeStep(nextId, lastMinute + 15, suggestedVolume))
                        },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = RoxSurfaceVariant,
                            contentColor = RoxAmber
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Añadir", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                    }
                }
            }

            // Lista editable de [minuto, volumen] (Compacta)
            itemsIndexed(steps, key = { _, step -> step.id }) { index, step ->
                StepRowEditor(
                    step = step,
                    index = index,
                    canDelete = steps.size > 1,
                    onUpdate = { newMin, newVol ->
                        val updated = steps.map {
                            if (it.id == step.id) it.copy(minute = newMin, volumePercent = newVol) else it
                        }
                        persistSteps(updated)
                    },
                    onDelete = {
                        persistSteps(steps.filterNot { it.id == step.id })
                    }
                )
            }

            // Opciones de comportamiento
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = RoxSurface),
                    border = BorderStroke(1.dp, RoxOutline),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Bajada gradual suave", color = RoxTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                Text(
                                    text = if (transitionMode == TransitionMode.GRADUAL) {
                                        "Reduce progresivamente entre cada tramo"
                                    } else {
                                        "Cambia en escalón exacto al llegar al minuto"
                                    },
                                    color = RoxTextMuted,
                                    fontSize = 11.sp
                                )
                            }
                            Switch(
                                checked = transitionMode == TransitionMode.GRADUAL,
                                onCheckedChange = { isGradual ->
                                    transitionMode = if (isGradual) TransitionMode.GRADUAL else TransitionMode.STEP
                                    storage.saveTransitionMode(transitionMode)
                                }
                            )
                        }

                        HorizontalDivider(color = RoxOutline)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Pausar reproducción al final", color = RoxTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                Text(
                                    text = "Detiene la música o audio al llegar al minuto final",
                                    color = RoxTextMuted,
                                    fontSize = 11.sp
                                )
                            }
                            Switch(
                                checked = pauseAtEnd,
                                onCheckedChange = { enabled ->
                                    pauseAtEnd = enabled
                                    storage.savePauseAtEnd(enabled)
                                }
                            )
                        }

                        if (!isBatteryExempt) {
                            HorizontalDivider(color = RoxOutline)
                            OutlinedButton(
                                onClick = onRequestBatteryExemption,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(38.dp),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                border = BorderStroke(1.dp, RoxOutline)
                            ) {
                                Text(
                                    "Permitir en segundo plano (sin ahorro batería)",
                                    fontSize = 11.sp,
                                    color = RoxTextPrimary,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                }
            }

            // Configuraciones guardadas
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Configuraciones guardadas",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = RoxTextPrimary,
                        maxLines = 1
                    )
                    Button(
                        onClick = { showSaveDialog = true },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = RoxSurfaceVariant,
                            contentColor = RoxAmber
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Icon(Icons.Rounded.BookmarkAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Guardar", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                    }
                }
            }

            items(presets, key = { it.id }) { preset ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = RoxSurface),
                    border = BorderStroke(1.dp, RoxOutline),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(preset.name, color = RoxTextPrimary, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                            val summary = preset.steps.joinToString(" · ") { "${it.minute}m ➜ ${it.volumePercent}%" }
                            Text(summary, color = RoxTextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = {
                                    persistSteps(preset.steps)
                                    transitionMode = preset.transitionMode
                                    pauseAtEnd = preset.pauseAtEnd
                                    storage.saveTransitionMode(preset.transitionMode)
                                    storage.savePauseAtEnd(preset.pauseAtEnd)
                                    feedbackMessage = "Cargado: ${preset.name}"
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) {
                                Text("Cargar", color = RoxAmber, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                            IconButton(
                                onClick = {
                                    val updated = presets.filterNot { it.id == preset.id }
                                    presets = updated
                                    storage.savePresets(updated)
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Rounded.Delete, contentDescription = "Borrar", tint = RoxTextMuted, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            containerColor = RoxSurface,
            title = { Text("Guardar configuración", color = RoxTextPrimary, fontSize = 16.sp) },
            text = {
                OutlinedTextField(
                    value = presetNameInput,
                    onValueChange = { presetNameInput = it },
                    label = { Text("Nombre (ej. Mi descanso 40 min)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = presetNameInput.trim()
                        if (trimmed.isNotEmpty()) {
                            val newPreset = TimerPreset(
                                id = UUID.randomUUID().toString(),
                                name = trimmed,
                                transitionMode = transitionMode,
                                pauseAtEnd = pauseAtEnd,
                                steps = steps.sortedBy { it.minute }
                            )
                            val updated = listOf(newPreset) + presets
                            presets = updated
                            storage.savePresets(updated)
                            presetNameInput = ""
                            showSaveDialog = false
                            feedbackMessage = "Guardado: $trimmed"
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RoxAmber, contentColor = RoxBackground)
                ) {
                    Text("Guardar", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) {
                    Text("Cancelar", color = RoxTextMuted)
                }
            }
        )
    }
}

@Composable
private fun StepRowEditor(
    step: VolumeStep,
    index: Int,
    canDelete: Boolean,
    onUpdate: (Int, Int) -> Unit,
    onDelete: () -> Unit
) {
    var minuteText by remember(step.id, step.minute) { mutableStateOf(step.minute.toString()) }
    var volumeText by remember(step.id, step.volumePercent) { mutableStateOf(step.volumePercent.toString()) }

    Card(
        colors = CardDefaults.cardColors(containerColor = RoxSurface),
        border = BorderStroke(1.dp, RoxOutline),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "T${index + 1}",
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = RoxAmber,
                modifier = Modifier.width(22.dp)
            )

            // Campo Minuto compacto
            CompactNumberBox(
                value = minuteText,
                suffix = "min",
                onValueChange = { raw ->
                    val digits = raw.filter { it.isDigit() }.take(3)
                    minuteText = digits
                    digits.toIntOrNull()?.let { parsed ->
                        if (parsed >= 1) onUpdate(parsed, step.volumePercent)
                    }
                },
                modifier = Modifier.weight(1f)
            )

            Text("➜", color = RoxTextMuted, fontWeight = FontWeight.Bold, fontSize = 12.sp)

            // Campo Volumen compacto
            CompactNumberBox(
                value = volumeText,
                suffix = "%",
                onValueChange = { raw ->
                    val digits = raw.filter { it.isDigit() }.take(3)
                    volumeText = digits
                    digits.toIntOrNull()?.let { parsed ->
                        onUpdate(step.minute, parsed.coerceIn(0, 100))
                    }
                },
                modifier = Modifier.weight(1f)
            )

            IconButton(
                onClick = onDelete,
                enabled = canDelete,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Delete,
                    contentDescription = "Borrar tramo",
                    tint = if (canDelete) RoxDanger else RoxOutline.copy(alpha = 0.4f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun CompactNumberBox(
    value: String,
    suffix: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .height(38.dp)
            .background(RoxSurfaceVariant, RoundedCornerShape(8.dp))
            .border(1.dp, RoxOutline, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(
                color = RoxTextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                fontFamily = FontFamily.Monospace
            ),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = suffix,
            color = RoxTextMuted,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
