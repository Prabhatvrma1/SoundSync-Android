package com.soundsync.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.soundsync.app.MainActivity
import com.soundsync.app.R
import com.soundsync.app.audio.capture.AudioCaptureManager
import com.soundsync.app.audio.playback.AudioTrackPlayer
import com.soundsync.app.audio.sync.ClockSynchronizer
import com.soundsync.app.audio.sync.SyncEngine
import com.soundsync.app.data.model.DeviceRole
import com.soundsync.app.data.model.StreamQualityPreset
import com.soundsync.app.network.AudioUdpReceiver
import com.soundsync.app.network.AudioUdpSender
import com.soundsync.app.network.ControlClient
import com.soundsync.app.network.ControlServer
import com.soundsync.app.network.DiscoveryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Foreground Service that handles continuous audio capture, network streaming,
 * and synchronized playback in background and with screen off.
 */
class AudioStreamService : Service() {

    companion object {
        private const val TAG = "AudioStreamService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "soundsync_stream_channel"

        const val ACTION_STOP = "com.soundsync.app.ACTION_STOP"
        const val ACTION_PLAY = "com.soundsync.app.ACTION_PLAY"
        const val ACTION_PAUSE = "com.soundsync.app.ACTION_PAUSE"

        var instance: AudioStreamService? = null
            private set
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    // Subsystems
    val captureManager = AudioCaptureManager()
    val player = AudioTrackPlayer()
    val clockSynchronizer = ClockSynchronizer()
    lateinit var syncEngine: SyncEngine
    lateinit var discoveryManager: DiscoveryManager

    var controlServer: ControlServer? = null
    var controlClient: ControlClient? = null
    val audioSender = AudioUdpSender()
    var audioReceiver: AudioUdpReceiver? = null

    private val _currentRole = MutableStateFlow(DeviceRole.IDLE)
    val currentRole: StateFlow<DeviceRole> = _currentRole.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    @Volatile
    var activeMediaProjection: MediaProjection? = null

    inner class LocalBinder : Binder() {
        fun getService(): AudioStreamService = this@AudioStreamService
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        syncEngine = SyncEngine(clockSynchronizer, player)
        discoveryManager = DiscoveryManager(applicationContext)
        createNotificationChannel()
        acquireLocks()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopAll()
            ACTION_PLAY -> resumePlayback()
            ACTION_PAUSE -> pausePlayback()
        }
        return START_NOT_STICKY
    }

    fun startAsMaster(
        deviceName: String,
        pin: String?,
        preset: StreamQualityPreset = StreamQualityPreset.BALANCED
    ) {
        _currentRole.value = DeviceRole.MASTER
        startForegroundServiceNotification("SoundSync Master - Ready", "Broadcasting presence...")

        audioSender.start()

        val server = ControlServer(masterName = deviceName, requiredPin = pin)
        controlServer = server
        server.start(
            scope = serviceScope,
            onSlaveConnected = { slave ->
                audioSender.addReceiver(slave.ipAddress, slave.audioPort)
                updateNotification()
            },
            onSlaveDisconnected = { slaveId ->
                // Keep streaming
                updateNotification()
            }
        )

        discoveryManager.startAdvertising(
            deviceName = deviceName,
            receiverCountSupplier = { audioSender.getReceiverCount() },
            isStreamingSupplier = { _isStreaming.value },
            scope = serviceScope
        )

        // Forward captured audio packets to UDP sender
        serviceScope.launch {
            captureManager.audioPackets.collect { packet ->
                if (_isStreaming.value) {
                    audioSender.sendPacket(packet)
                }
            }
        }
    }

    fun startMasterStreaming(preset: StreamQualityPreset = StreamQualityPreset.BALANCED) {
        val proj = activeMediaProjection
        if (proj != null) {
            captureManager.startSystemAudioCapture(proj, preset, serviceScope)
        } else {
            captureManager.startTestPulseOnly(preset, serviceScope)
        }

        controlServer?.broadcastStartStream(leadTimeMs = preset.bufferTargetMs, preset = preset)
        _isStreaming.value = true
        updateNotification()
    }

    fun pausePlayback() {
        if (_currentRole.value == DeviceRole.MASTER) {
            controlServer?.broadcastPauseStream()
            captureManager.stopCapture()
            _isStreaming.value = false
            updateNotification()
        }
    }

    fun resumePlayback() {
        if (_currentRole.value == DeviceRole.MASTER) {
            startMasterStreaming()
        }
    }

    fun startAsSlave(slaveName: String, masterIp: String, pin: String?) {
        _currentRole.value = DeviceRole.SLAVE
        startForegroundServiceNotification("SoundSync Receiver", "Connecting to master...")

        audioReceiver = AudioUdpReceiver { packet ->
            syncEngine.onPacketReceived(packet)
        }
        audioReceiver?.start(serviceScope)

        val slaveId = "slave_" + System.currentTimeMillis() % 10000
        val client = ControlClient(
            slaveId = slaveId,
            slaveName = slaveName,
            clockSynchronizer = clockSynchronizer,
            syncEngine = syncEngine,
            player = player
        )
        controlClient = client
        client.connect(masterIp = masterIp, pin = pin, scope = serviceScope)
        _isStreaming.value = true
    }

    private fun startForegroundServiceNotification(title: String, text: String) {
        val notification = buildNotification(title, text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            try {
                startForeground(NOTIFICATION_ID, notification, type)
            } catch (e: Exception) {
                Log.e(TAG, "Error starting foreground with type", e)
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, AudioStreamService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification() {
        val title = if (_currentRole.value == DeviceRole.MASTER) "SoundSync Master" else "SoundSync Receiver"
        val count = audioSender.getReceiverCount()
        val text = if (_isStreaming.value) "Streaming synchronized audio ($count receivers)" else "Playback paused"
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, buildNotification(title, text))
    }

    fun stopAll() {
        _isStreaming.value = false
        _currentRole.value = DeviceRole.IDLE
        captureManager.stopCapture()
        controlServer?.broadcastStopStream()
        controlServer?.stop()
        controlServer = null

        controlClient?.disconnect()
        controlClient = null

        audioSender.close()
        audioReceiver?.stop()
        audioReceiver = null

        syncEngine.stop()
        discoveryManager.stopAdvertising()
        discoveryManager.stopDiscovery()

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireLocks() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SoundSync:WakeLock")
            wakeLock?.acquire(12 * 60 * 60 * 1000L) // 12 hours max

            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiLock = wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "SoundSync:WifiLock")
            wifiLock?.acquire()
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring Wake/Wifi locks", e)
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing locks", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        instance = null
        stopAll()
        releaseLocks()
        serviceScope.cancel()
        super.onDestroy()
    }
}
