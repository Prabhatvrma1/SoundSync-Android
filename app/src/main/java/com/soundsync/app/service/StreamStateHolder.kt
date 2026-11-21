package com.soundsync.app.service

import com.soundsync.app.audio.capture.AudioCaptureManager
import com.soundsync.app.data.model.DeviceRole
import com.soundsync.app.data.model.SlaveDevice
import com.soundsync.app.data.model.SyncMetrics
import com.soundsync.app.network.ControlClient
import com.soundsync.app.network.DiscoveredMaster
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Global persistent reactive state container for SoundSync.
 * Ensures ViewModel and Compose always observe permanent, persistent StateFlows
 * regardless of background service recreation or connection lifecycle.
 */
object StreamStateHolder {
    val currentRole = MutableStateFlow(DeviceRole.IDLE)
    val isStreaming = MutableStateFlow(false)
    val slaveConnectionState = MutableStateFlow(ControlClient.ConnectionState.DISCONNECTED)
    val connectedMasterName = MutableStateFlow<String?>("Master Phone")
    val connectedSlaves = MutableStateFlow<List<SlaveDevice>>(emptyList())
    val syncMetrics = MutableStateFlow(SyncMetrics())
    val captureState = MutableStateFlow(AudioCaptureManager.CaptureState.IDLE)
    val captureMessage = MutableStateFlow("Idle")
    val amplitude = MutableStateFlow(0f)
    val discoveredMasters = MutableStateFlow<List<DiscoveredMaster>>(emptyList())
    val syncCalibrationOffsetMs = MutableStateFlow(0f)

    fun reset() {
        currentRole.value = DeviceRole.IDLE
        isStreaming.value = false
        slaveConnectionState.value = ControlClient.ConnectionState.DISCONNECTED
        connectedMasterName.value = "Master Phone"
        connectedSlaves.value = emptyList()
        syncMetrics.value = SyncMetrics()
        captureState.value = AudioCaptureManager.CaptureState.IDLE
        captureMessage.value = "Idle"
        amplitude.value = 0f
    }
}
