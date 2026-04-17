package com.carplay.android.ui.navigation

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.carplay.android.databinding.FragmentMapsBinding
import timber.log.Timber

/**
 * Maps Fragment — Google Maps via WebView
 *
 * Embeds Google Maps in a WebView for in-app navigation.
 * Includes search bar and quick action buttons.
 * Falls back to opening Google Maps app externally.
 */
class MapsFragment : Fragment() {

    private var _binding: FragmentMapsBinding? = null
    private val binding get() = _binding!!

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocation = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        if (fineLocation) {
            binding.mapWebView.reload()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMapsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        checkLocationPermission()
        setupWebView()
        setupControls()
    }

    // ── WebView Setup ──────────────────────────────────────

    private fun setupWebView() {
        val webView = binding.mapWebView

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setGeolocationEnabled(true)
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
        }

        // Enable geolocation
        webView.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                callback?.invoke(origin, true, false)
                Timber.d("Geolocation permission granted for: $origin")
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                binding.mapLoading.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                binding.mapLoading.visibility = View.GONE
                Timber.d("Map page loaded: $url")
            }

            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                binding.mapLoading.visibility = View.GONE
                Timber.e("WebView error: $errorCode - $description")
            }
        }

        // Load Google Maps embed (no API key needed for basic embed)
        loadMap(null)
    }

    private fun loadMap(searchQuery: String?) {
        val url = if (searchQuery != null) {
            "https://www.google.com/maps/search/${Uri.encode(searchQuery)}"
        } else {
            // Try to get current location for initial view
            val location = getLastKnownLocation()
            if (location != null) {
                "https://www.google.com/maps/@${location.latitude},${location.longitude},15z"
            } else {
                "https://www.google.com/maps/@13.7563,100.5018,13z" // Default: Bangkok
            }
        }

        binding.mapWebView.loadUrl(url)
        Timber.d("Loading map: $url")
    }

    // ── Controls ───────────────────────────────────────────

    private fun setupControls() {
        // Search
        binding.searchBar.setOnEditorActionListener { textView, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val query = textView.text.toString().trim()
                if (query.isNotEmpty()) {
                    loadMap(query)
                    binding.btnClearSearch.visibility = View.VISIBLE
                    // Hide keyboard
                    val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE)
                        as android.view.inputmethod.InputMethodManager
                    imm.hideSoftInputFromWindow(textView.windowToken, 0)
                }
                true
            } else {
                false
            }
        }

        // Clear search
        binding.btnClearSearch.setOnClickListener {
            binding.searchBar.text?.clear()
            binding.btnClearSearch.visibility = View.GONE
            loadMap(null)
        }

        // My Location FAB
        binding.fabMyLocation.setOnClickListener {
            val location = getLastKnownLocation()
            if (location != null) {
                binding.mapWebView.loadUrl(
                    "https://www.google.com/maps/@${location.latitude},${location.longitude},17z"
                )
            } else {
                Toast.makeText(requireContext(), "Location not available", Toast.LENGTH_SHORT).show()
                // Reload with geolocation request
                binding.mapWebView.reload()
            }
        }

        // Open in Google Maps app
        binding.fabOpenExternal.setOnClickListener {
            openInGoogleMapsApp()
        }
    }

    private fun openInGoogleMapsApp() {
        try {
            val location = getLastKnownLocation()
            val uri = if (location != null) {
                Uri.parse("geo:${location.latitude},${location.longitude}?z=15")
            } else {
                Uri.parse("geo:13.7563,100.5018?z=13")
            }

            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage("com.google.android.apps.maps")
            }

            // Check if Google Maps is installed
            if (intent.resolveActivity(requireContext().packageManager) != null) {
                startActivity(intent)
            } else {
                // Try without package restriction
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            }
        } catch (e: Exception) {
            Timber.e(e, "Cannot open Google Maps")
            Toast.makeText(requireContext(), "Google Maps not installed", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Location Helpers ───────────────────────────────────

    private fun checkLocationPermission() {
        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun getLastKnownLocation(): Location? {
        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }

        val locationManager = requireContext().getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // Try GPS first, then network
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                val location = locationManager.getLastKnownLocation(provider)
                if (location != null) return location
            } catch (e: Exception) {
                Timber.w("Location provider $provider not available")
            }
        }
        return null
    }

    // ── Lifecycle ──────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        binding.mapWebView.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.mapWebView.onPause()
    }

    override fun onDestroyView() {
        binding.mapWebView.apply {
            stopLoading()
            destroy()
        }
        _binding = null
        super.onDestroyView()
    }
}
