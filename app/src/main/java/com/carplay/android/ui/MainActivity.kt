package com.carplay.android.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
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

    // ── Media Projection Launcher ──────────────────────────

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            Timber.d("Screen capture permission granted")

            // Get MediaProjection from the permission result
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager
            val projection = projectionManager.getMediaProjection(
                result.resultCode, result.data!!
            )

            // Pass to CarPlayService
            carPlayService?.setMediaProjection(projection)

            // Start media capture
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

        // Start CarPlay service
        startCarPlayService()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
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
        // Connect button
        binding.btnConnect.setOnClickListener {
            carPlayService?.connect()
        }

        // Disconnect button
        binding.btnDisconnect.setOnClickListener {
            carPlayService?.disconnect()
        }

        // Screen capture button — requests MediaProjection permission
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
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
            as MediaProjectionManager
        val intent = projectionManager.createScreenCaptureIntent()
        screenCaptureLauncher.launch(intent)
    }

    /**
     * Observe CarPlay service state changes
     */
    private fun observeServiceState() {
        // Observe connection state
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

                    // Show/hide buttons based on state
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

        // Observe active features
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
