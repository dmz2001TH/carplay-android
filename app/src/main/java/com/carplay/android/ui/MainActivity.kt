package com.carplay.android.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import com.carplay.android.R
import com.carplay.android.databinding.ActivityMainBinding
import com.carplay.android.service.CarPlayService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var carPlayService: CarPlayService? = null
    private var serviceBound = false
    private var serviceStarted = false

    // ── Service Connection ─────────────────────────────────

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as CarPlayService.LocalBinder
            carPlayService = localBinder.getService()
            serviceBound = true
            Timber.d("CarPlay service connected")
            observeServiceState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            carPlayService = null
            serviceBound = false
        }
    }

    // ── Permission Launcher ────────────────────────────────

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val denied = permissions.filter { !it.value }.keys
        if (denied.isNotEmpty()) {
            Timber.w("Permissions denied: $denied")
            Toast.makeText(this,
                "Some features may need permissions",
                Toast.LENGTH_LONG).show()
        }
    }

    // ── Media Projection Launcher ──────────────────────────

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager
            val projection = projectionManager.getMediaProjection(
                result.resultCode, result.data!!
            )
            carPlayService?.setMediaProjection(projection)
            carPlayService?.startMedia()
            Timber.d("Screen capture started")
        } else {
            Timber.w("Screen capture permission denied")
        }
    }

    // ── Lifecycle ──────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupConnectionUI()
        requestPermissionsIfNeeded()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }

    // ── Permissions ────────────────────────────────────────

    private fun requestPermissionsIfNeeded() {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
            perms.add(Manifest.permission.READ_MEDIA_AUDIO)
        }

        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing)
        }
    }

    // ── Setup ──────────────────────────────────────────────

    private fun setupConnectionUI() {
        binding.btnConnect.setOnClickListener {
            ensureServiceStarted()
            carPlayService?.connect()
        }
        binding.btnDisconnect.setOnClickListener {
            carPlayService?.disconnect()
        }
        binding.btnScreenCapture.setOnClickListener {
            requestScreenCapture()
        }
    }

    private fun ensureServiceStarted() {
        if (serviceStarted) return
        serviceStarted = true

        val intent = Intent(this, CarPlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        Timber.d("CarPlay service started + bound")
    }

    private fun requestScreenCapture() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Microphone permission needed for audio", Toast.LENGTH_SHORT).show()
            return
        }
        ensureServiceStarted()
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
            as MediaProjectionManager
        screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    // ── Observe State ──────────────────────────────────────

    private fun observeServiceState() {
        lifecycleScope.launch {
            carPlayService?.connectionState?.collectLatest { state ->
                runOnUiThread {
                    binding.connectionStatus.text = when (state) {
                        CarPlayService.ConnectionState.DISCONNECTED -> getString(R.string.status_disconnected)
                        CarPlayService.ConnectionState.CONNECTING -> getString(R.string.status_connecting)
                        CarPlayService.ConnectionState.AUTHENTICATING -> getString(R.string.status_authenticating)
                        CarPlayService.ConnectionState.NEGOTIATING -> getString(R.string.status_negotiating)
                        CarPlayService.ConnectionState.ACTIVE -> getString(R.string.status_active)
                        CarPlayService.ConnectionState.ERROR -> getString(R.string.status_error)
                    }

                    // Status dot color
                    binding.statusDot.background = when (state) {
                        CarPlayService.ConnectionState.ACTIVE ->
                            ContextCompat.getDrawable(this@MainActivity, R.drawable.status_dot_connected)
                        else ->
                            ContextCompat.getDrawable(this@MainActivity, R.drawable.status_dot)
                    }

                    binding.connectionStatus.setTextColor(
                        when (state) {
                            CarPlayService.ConnectionState.ACTIVE -> getColor(R.color.carplay_green)
                            CarPlayService.ConnectionState.ERROR -> getColor(R.color.carplay_red)
                            else -> getColor(R.color.text_tertiary)
                        }
                    )

                    binding.btnConnect.visibility = when (state) {
                        CarPlayService.ConnectionState.DISCONNECTED,
                        CarPlayService.ConnectionState.ERROR -> View.VISIBLE
                        else -> View.GONE
                    }
                    binding.btnDisconnect.visibility = when (state) {
                        CarPlayService.ConnectionState.ACTIVE,
                        CarPlayService.ConnectionState.NEGOTIATING,
                        CarPlayService.ConnectionState.AUTHENTICATING -> View.VISIBLE
                        else -> View.GONE
                    }
                }
            }
        }
    }
}
