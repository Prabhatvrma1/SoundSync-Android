# SoundSync — Multi-Phone Synchronized Audio System (Android)

**SoundSync** is a native Android application that turns multiple Android phones into synchronized wireless speakers. One device acts as the **Master (Leader)**, capturing its whole-phone system audio output (or generating precision calibration audio), and synchronizes transmission to multiple connected **Slave (Receiver)** phones over local Wi-Fi with sub-millisecond precision clock alignment.

---

## 📱 Generated APK Deliverables

The application has been built, tested, and verified on D: drive:

- **Debug APK (Ready to install):** `D:\ai\release_apk\SoundSync-debug.apk` (53.6 MB)
- **Release APK (Signed):** `D:\ai\release_apk\SoundSync-release.apk` (40.0 MB)
- **Project Source Root:** `D:\ai\SoundSync`
- **SDK & Build Toolchain:** `D:\ai\tools`
- **Gradle & Cache:** `D:\ai\.gradle`

---

## 🛠️ Technology Stack & Architecture

- **Language:** Kotlin 1.9.22
- **UI Framework:** Jetpack Compose with Material 3 Dark theme
- **Architecture:** MVVM with Coroutines & StateFlow
- **Minimum Android Version:** Android 10 (API 29) — required for `AudioPlaybackCapture`
- **Target SDK:** Android 14 (API 34)
- **Audio Capture:** Android `AudioPlaybackCaptureConfiguration` via `MediaProjection`
- **Audio Playback:** Low-latency `AudioTrack` (`PERFORMANCE_MODE_LOW_LATENCY`)
- **Audio Pipeline:** 48 kHz stereo, 16-bit PCM with IMA-ADPCM zero-delay 4:1 compression option
- **Clock Synchronization:** Microsecond-precision NTP (Cristian's algorithm) with RTT filtering and exponential moving average
- **Jitter Buffer:** Ordered ring buffer with out-of-order reassembly and packet loss concealment
- **Continuous Drift Compensation:** Micro-resampling drift detector adjusting sample frames without audio clicks or pitch distortion
- **Network Transport:** Low-overhead binary UDP datagrams on port 8890
- **Control Channel:** TCP bidirectional socket on port 8889 with PIN authentication
- **Device Discovery:** Android NSD (mDNS `_soundsync._tcp`) + fallback UDP broadcast beacon on port 8888
- **Background Streaming:** Foreground Service with `WakeLock`, `WifiLock` (`WIFI_MODE_FULL_HIGH_PERF`), and media controls notification

---

## 🎧 Whole-Phone Audio Capture

1. **System Audio Mirroring:**
   - Captures eligible audio from third-party apps (YouTube, Spotify, music players, games, podcast players, browsers).
   - Built on `AudioPlaybackCaptureConfiguration.Builder(mediaProjection)`:
     - `addMatchingUsage(AudioAttributes.USAGE_MEDIA)`
     - `addMatchingUsage(AudioAttributes.USAGE_GAME)`
     - `addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)`

2. **Android Policy & Restrictions Handling:**
   - Android restricts capture for applications that explicitly set `setAllowedCapturePolicy(ALLOW_CAPTURE_BY_NONE)` (e.g. DRM protected playback or voice calls).
   - SoundSync catches security restrictions and displays clear status messages in the UI.

3. **Built-in Precision Pulse Calibration Mode:**
   - SoundSync includes an internal **1kHz click pulse generator** (emitting 10ms clicks every 500ms).
   - Allows users and testers to audibly test and calibrate multi-speaker synchronization with sharp acoustic transients without needing external media apps running.

---

## ⏱️ Synchronization & Drift Engine

- **Clock Offset Estimation:**
  - Slaves send periodic pings to Master.
  - Round-Trip Time $RTT = (t_3 - t_0) - (t_2 - t_1)$.
  - Clock Offset $\theta = \frac{(t_1 - t_0) + (t_2 - t_3)}{2}$.
  - Outlier rejection discards high-latency Wi-Fi spikes.
- **Scheduled Playback Start:**
  - Master sends `START_STREAM` with coordinated timestamp $T_{\text{start}} = \text{now} + \text{leadTime}$.
  - Slaves buffer audio in `JitterBuffer` and trigger `AudioTrack.play()` at their localized timestamp.
- **Dynamic Drift Compensation:**
  - `DriftDetector` checks elapsed `AudioTrack` playback frames against elapsed master time.
  - When drift is between 3ms and 50ms, SoundSync drops or duplicates individual audio frames across buffers to correct drift smoothly with zero clicks.

---

## 🧪 Unit Testing Results

All unit tests compiled and passed (`BUILD SUCCESSFUL`):
1. **`ClockSynchronizerTest`:** Verified NTP offset calculation, time conversions, and RTT spike rejection.
2. **`JitterBufferTest`:** Verified sequential polling, out-of-order packet reordering, and buffer depth calculation.
3. **`AudioCompressorTest`:** Verified 4:1 compression ratio (3840 bytes -> 968 bytes) and PCM roundtrip decoding.
4. **`AudioPacketSerializationTest`:** Verified binary serialization, 48-byte header structure, and corruption rejection.
5. **`DriftDetectorTest`:** Verified zero-drift stability, lagging detection (-1 frame), leading detection (+1 frame), and hard resync thresholds.

---

## 🚀 How to Install and Run

### 1. Install via ADB to Connected Phones:
```powershell
# Master device
d:\ai\tools\android-sdk\platform-tools\adb.exe -s <DEVICE_1_ID> install -r d:\ai\release_apk\SoundSync-debug.apk

# Receiver device(s)
d:\ai\tools\android-sdk\platform-tools\adb.exe -s <DEVICE_2_ID> install -r d:\ai\release_apk\SoundSync-debug.apk
```

### 2. Connect & Stream:
1. Connect all phones to the same Wi-Fi network or enable a mobile hotspot on Phone 1.
2. On Phone 1 (Master):
   - Tap **Create Speaker Group**.
   - Note the 4-digit PIN (default `7294`).
   - Tap **Start Sync Stream** and accept the system Audio Capture dialog.
3. On Phone 2 (Receiver):
   - Tap **Join Speaker Group**.
   - Select Phone 1 from the list of discovered speakers.
   - Enter the PIN and tap **Connect**.
4. Both devices will lock their clocks and play synchronized audio in real time.
