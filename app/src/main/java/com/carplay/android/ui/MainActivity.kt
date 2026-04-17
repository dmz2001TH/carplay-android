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
import androidx.navigation.ui.setupWithNavController
import com.carplay.android.R
import com.carplay.android.databinding.ActivityMainBinding
import com.carplay.android.service.CarPlayService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Main Activity - CarPlay Dashboard
 *
 * Displays the CarPlay-like interface with:
 * - Dashboard (Home screen)
 * - Navigation (Maps)
 * - Music Player
 * - Phone/Calls
 *
 * Handles all runtime permission requests on startup.
 * Landscape-only layout optimized for car use.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var carPlayService: CarPlayService? = null
    private var serviceBound = false

    // ── Service Connection ─────────────────────────────────

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as CarPlayService.LocalBinder
            carPlayService = localBinder.getService()
            serviceBound = true
            Timber.d("CarPlay service connected")

            // Observe service state
            observeServiceState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            carPlayService = null
            serviceBound = false
            Timber.d("CarPlay service disconnected")
        }
    }

    // ── Runtime Permission Launcher ────────────────────────

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.filter { it.value }.keys
        val denied = permissions.filter { !it.value }.keys

        Timber.d("Permissions granted: $granted")
        if (denied.isNotEmpty()) {
            Timber.w("Permissions denied: $denied")
            Toast.makeText(this,
                "Some features may not work without permissions",
                Toast.LENGTH_LONG).show()
        }

        // Proceed to start service after permission result
        startCarPlayService()
    }

    // ── Media Projection Launcher ──────────────────────────

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            Timber.d("Screen capture permission granted")

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

        // Full screen landscape mode
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        )

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupNavigation()
        setupConnectionUI()

        // Request permissions first, then start service
        requestRequiredPermissions()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }

    // ── Permissions ────────────────────────────────────────

    /**
     * Build list of permissions needed at runtime
     */
    private fun getRequiredPermissions(): Array<String> {
        val perms = mutableListOf(
            // Core functionality
            Manifest.permission.RECORD_AUDIO,        // Audio capture for CarPlay
            Manifest.permission.ACCESS_FINE_LOCATION, // Maps
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )

        // Android 12+ (API 31) — Bluetooth runtime permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
        }

        // Android 13+ (API 33) — Notification permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
            perms.add(Manifest.permission.READ_MEDIA_AUDIO)
        }

        // Android 14+ (API 34) — Foreground service permissions are manifest-only
        // but we need to ensure the types are declared (already done in manifest)

        return perms.toTypedArray()
    }

    /**
     * Check which permissions still need to be requested
     */
    private fun getMissingPermissions(): Array<String> {
        return getRequiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
    }

    /**
     * Request all required runtime permissions
     */
    private fun requestRequiredPermissions() {
        val missing = getMissingPermissions()

        if (missing.isEmpty()) {
            Timber.d("All permissions already granted")
            startCarPlayService()
        } else {
            Timber.d("Requesting permissions: ${missing.toList()}")
            permissionLauncher.launch(missing)
        }
    }

    // ── Setup ──────────────────────────────────────────────

    private fun setupNavigation() {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.navigation_dashboard -> {
                    navController.navigate(R.id.dashboardFragment)
                    true
                }
                R.id.navigation_maps -> {
                    navController.navigate(R.id.mapsFragment)
                    true
                }
                R.id.navigation_music -> {
                    navController.navigate(R.id.musicFragment)
                    true
                }
                R.id.navigation_phone -> {
                    navController.navigate(R.id.phoneFragment)
                    true
                }
                else -> false
            }
        }
    }

    private fun setupConnectionUI() {
        binding.btnConnect.setOnClickListener {
            carPlayService?.connect()
        }

        binding.btnDisconnect.setOnClickListener {
            carPlayService?.disconnect()
        }

        binding.btnScreenCapture.setOnClickListener {
            requestScreenCapture()
        }
    }

    /**
     * Start and bind to CarPlay service
     */
    private fun startCarPlayService() {
        val serviceIntent = Intent(this, CarPlayService::class.java)
        startForegroundService(serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    /**
     * Request screen capture permission via MediaProjection API
     */
    private fun requestScreenCapture() {
        // Check RECORD_AUDIO permission first (needed for audio encoder)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Microphone permission needed for audio", Toast.LENGTH_SHORT).show()
            permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            return
        }

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
            as MediaProjectionManager
        val intent = projectionManager.createScreenCaptureIntent()
        screenCaptureLauncher.launch(intent)
    }

    /**
     * Observe CarPlay service state changes
     */
    private fun observeServiceState() {
        lifecycleScope.launch {
            carPlayService?.connectionState?.collectLatest { state ->
                runOnUiThread {
                    binding.connectionStatus.text = when (state) {
                        CarPlayService.ConnectionState.DISCONNECTED -> "Disconnected"
                        CarPlayService.ConnectionState.CONNECTING -> "Connecting..."
                        CarPlayService.ConnectionState.AUTHENTICATING -> "Authenticating..."
                        CarPlayService.ConnectionState.NEGOTIATING -> "Starting sessions..."
                        CarPlayService.ConnectionState.ACTIVE -> "● Connected"
                        CarPlayService.ConnectionState.ERROR -> "✗ Error"
                    }

                    binding.connectionStatus.setTextColor(
                        when (state) {
                            CarPlayService.ConnectionState.ACTIVE ->
                                getColor(android.R.color.holo_green_light)
                            CarPlayService.ConnectionState.ERROR ->
                                getColor(android.R.color.holo_red_light)
                            else ->
                                getColor(android.R.color.white)
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

        lifecycleScope.launch {
            carPlayService?.activeFeatures?.collectLatest { features ->
                runOnUiThread {
                    binding.featureStatus.text = if (features.isEmpty()) {
                        "No features active"
                    } else {
                        features.joinToString(" · ") { feature ->
                            when (feature) {
                                "screen" -> "📺 Screen"
                                "media" -> "🎵 Media"
                                "mic" -> "🎤 Mic"
                                "audio" -> "🔊 Audio"
                                "touch" -> "👆 Touch"
                                "phone" -> "📱 Phone"
                                else -> feature
                            }
                        }
                    }
                }
            }
        }
    }
}
