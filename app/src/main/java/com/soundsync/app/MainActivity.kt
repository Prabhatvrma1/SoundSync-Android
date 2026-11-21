package com.soundsync.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.soundsync.app.data.model.DeviceRole
import com.soundsync.app.service.AudioStreamService
import com.soundsync.app.ui.screens.HomeScreen
import com.soundsync.app.ui.screens.MasterScreen
import com.soundsync.app.ui.screens.SlaveScreen
import com.soundsync.app.ui.theme.DarkBackground
import com.soundsync.app.ui.theme.SoundSyncTheme
import com.soundsync.app.ui.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Permissions granted callback
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, AudioStreamService::class.java).apply {
                action = AudioStreamService.ACTION_START_PROJECTION
                putExtra(AudioStreamService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(AudioStreamService.EXTRA_RESULT_DATA, result.data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        checkAndRequestPermissions()

        setContent {
            SoundSyncTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DarkBackground
                ) {
                    AppNavigation(
                        viewModel = viewModel,
                        onRequestMediaProjection = { requestMediaProjection() }
                    )
                }
            }
        }
    }

    private fun startAudioService() {
        val intent = Intent(this, AudioStreamService::class.java)
        try {
            startService(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun requestMediaProjection() {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val captureIntent = projectionManager.createScreenCaptureIntent()
        projectionLauncher.launch(captureIntent)
    }
}

@Composable
fun AppNavigation(
    viewModel: MainViewModel,
    onRequestMediaProjection: () -> Unit
) {
    val navController = rememberNavController()
    val currentRole by viewModel.currentRole.collectAsState()

    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                viewModel = viewModel,
                onCreateGroupClick = {
                    viewModel.startMaster()
                    navController.navigate("master")
                },
                onJoinGroupClick = {
                    navController.navigate("slave")
                }
            )
        }
        composable("master") {
            MasterScreen(
                viewModel = viewModel,
                onRequestMediaProjection = onRequestMediaProjection,
                onBackClick = {
                    viewModel.stopEverything()
                    navController.popBackStack("home", inclusive = false)
                }
            )
        }
        composable("slave") {
            SlaveScreen(
                viewModel = viewModel,
                onBackClick = {
                    viewModel.stopEverything()
                    navController.popBackStack("home", inclusive = false)
                }
            )
        }
    }
}
