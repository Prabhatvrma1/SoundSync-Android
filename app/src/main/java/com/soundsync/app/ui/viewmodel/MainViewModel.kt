package com.soundsync.app.ui.viewmodel

import android.app.Application
import android.media.projection.MediaProjection
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.soundsync.app.audio.capture.AudioCaptureManager
import com.soundsync.app.data.model.DeviceRole
import com.soundsync.app.data.model.SlaveDevice
import com.soundsync.app.data.model.StreamQualityPreset
import com.soundsync.app.data.model.SyncMetrics
import com.soundsync.app.network.ControlClient
import com.soundsync.app.network.DiscoveredMaster
import com.soundsync.app.service.AudioStreamService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _deviceName = MutableStateFlow(Build.MODEL ?: "Android Device")
    val deviceName: StateFlow<String> = _deviceName.asStateFlow()

    private val _masterPin = MutableStateFlow("7294")
    val masterPin: StateFlow<String> = _masterPin.asStateFlow()

    private val _selectedPreset = MutableStateFlow(StreamQualityPreset.BALANCED)
    val selectedPreset: StateFlow<StreamQualityPreset> = _selectedPreset.asStateFlow()

    val currentRole: StateFlow<DeviceRole>
        get() = AudioStreamService.instance?.currentRole ?: MutableStateFlow(DeviceRole.IDLE)

    val isStreaming: StateFlow<Boolean>
        get() = AudioStreamService.instance?.isStreaming ?: MutableStateFlow(false)

    val captureState: StateFlow<AudioCaptureManager.CaptureState>
        get() = AudioStreamService.instance?.captureManager?.captureState
            ?: MutableStateFlow(AudioCaptureManager.CaptureState.IDLE)

    val captureMessage: StateFlow<String>
        get() = AudioStreamService.instance?.captureManager?.statusMessage
            ?: MutableStateFlow("Idle")

    val amplitude: StateFlow<Float>
        get() = AudioStreamService.instance?.captureManager?.currentRms
            ?: MutableStateFlow(0f)

    val connectedSlaves: StateFlow<List<SlaveDevice>>
        get() = AudioStreamService.instance?.controlServer?.connectedSlaves
            ?: MutableStateFlow(emptyList())

    val discoveredMasters: StateFlow<List<DiscoveredMaster>>
        get() = AudioStreamService.instance?.discoveryManager?.discoveredMasters
            ?: MutableStateFlow(emptyList())

    val slaveConnectionState: StateFlow<ControlClient.ConnectionState>
        get() = AudioStreamService.instance?.controlClient?.connectionState
            ?: MutableStateFlow(ControlClient.ConnectionState.DISCONNECTED)

    val slaveMetrics: StateFlow<SyncMetrics>
        get() = AudioStreamService.instance?.syncEngine?.syncMetrics
            ?: MutableStateFlow(SyncMetrics())

    val connectedMasterName: StateFlow<String?>
        get() = AudioStreamService.instance?.controlClient?.masterName
            ?: MutableStateFlow("Master")

    private val _isTestToneActive = MutableStateFlow(false)
    val isTestToneActive: StateFlow<Boolean> = _isTestToneActive.asStateFlow()

    fun setDeviceName(name: String) {
        _deviceName.value = name
    }

    fun setQualityPreset(preset: StreamQualityPreset) {
        _selectedPreset.value = preset
    }

    fun toggleTestTone() {
        val newActive = !_isTestToneActive.value
        _isTestToneActive.value = newActive
        AudioStreamService.instance?.captureManager?.isTestToneEnabled = newActive
    }

    fun startMaster(mediaProjection: MediaProjection? = null) {
        val svc = AudioStreamService.instance ?: return
        svc.activeMediaProjection = mediaProjection
        svc.startAsMaster(_deviceName.value, _masterPin.value, _selectedPreset.value)
    }

    fun startMasterStreaming() {
        val svc = AudioStreamService.instance ?: return
        svc.startMasterStreaming(_selectedPreset.value)
    }

    fun pauseMasterStreaming() {
        AudioStreamService.instance?.pausePlayback()
    }

    fun startDiscoveringMasters() {
        AudioStreamService.instance?.discoveryManager?.startDiscovery(viewModelScope)
    }

    fun joinMaster(master: DiscoveredMaster, pin: String? = null) {
        val svc = AudioStreamService.instance ?: return
        svc.startAsSlave(_deviceName.value, master.ipAddress, pin)
    }

    fun setSlaveVolume(slaveId: String, volume: Float) {
        AudioStreamService.instance?.controlServer?.setSlaveVolume(slaveId, volume)
    }

    fun setSlaveMute(slaveId: String, isMuted: Boolean) {
        AudioStreamService.instance?.controlServer?.setSlaveMute(slaveId, isMuted)
    }

    fun muteAllSlaves(isMuted: Boolean) {
        AudioStreamService.instance?.controlServer?.muteAll(isMuted)
    }

    fun disconnectSlave(slaveId: String) {
        AudioStreamService.instance?.controlServer?.disconnectSlave(slaveId)
    }

    fun setLocalReceiverVolume(volume: Float) {
        AudioStreamService.instance?.player?.volume = volume
    }

    fun setLocalReceiverMute(isMuted: Boolean) {
        AudioStreamService.instance?.player?.isMuted = isMuted
    }

    fun stopEverything() {
        AudioStreamService.instance?.stopAll()
        _isTestToneActive.value = false
    }
}
