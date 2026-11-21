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
        val svc = AudioStreamService.instance
        if (svc != null) {
            svc.captureManager.isTestToneEnabled = newActive
            if (newActive && !svc.isStreaming.value) {
                svc.startMasterStreaming(_selectedPreset.value)
            }
        }
    }

    fun startMaster(mediaProjection: MediaProjection? = null) {
        val app = getApplication<Application>()
        val intent = android.content.Intent(app, AudioStreamService::class.java).apply {
            action = AudioStreamService.ACTION_START_MASTER
            putExtra("DEVICE_NAME", _deviceName.value)
            putExtra("PIN", _masterPin.value)
        }
        try {
            app.startService(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        AudioStreamService.instance?.let { svc ->
            svc.activeMediaProjection = mediaProjection
        }
    }

    fun startMasterStreaming() {
        val svc = AudioStreamService.instance ?: return
        svc.startMasterStreaming(_selectedPreset.value)
    }

    fun pauseMasterStreaming() {
        AudioStreamService.instance?.pausePlayback()
    }

    fun startDiscoveringMasters() {
        val app = getApplication<Application>()
        if (AudioStreamService.instance == null) {
            try {
                val intent = android.content.Intent(app, AudioStreamService::class.java)
                app.startService(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        AudioStreamService.instance?.discoveryManager?.startDiscovery(viewModelScope)
    }

    fun joinMaster(master: DiscoveredMaster, pin: String? = null) {
        joinMasterDirect(master.ipAddress, master.controlPort, pin, master.deviceName)
    }

    fun joinMasterDirect(ip: String, port: Int = 8889, pin: String? = null, masterName: String = "Master") {
        val app = getApplication<Application>()
        val intent = android.content.Intent(app, AudioStreamService::class.java).apply {
            action = AudioStreamService.ACTION_START_SLAVE
            putExtra("DEVICE_NAME", _deviceName.value)
            putExtra("MASTER_IP", ip)
            putExtra("PIN", pin)
        }
        try {
            app.startService(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
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
        val app = getApplication<Application>()
        val intent = android.content.Intent(app, AudioStreamService::class.java).apply {
            action = AudioStreamService.ACTION_STOP
        }
        try {
            app.startService(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        AudioStreamService.instance?.stopAll()
        _isTestToneActive.value = false
    }
}
