package com.carplay.android.ui.navigation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.carplay.android.databinding.FragmentMapsBinding
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import timber.log.Timber

/**
 * Maps Fragment - CarPlay Navigation (OpenStreetMap)
 *
 * Uses OSMDroid (OpenStreetMap) — no API key required.
 * - Dark-friendly map tiles
 * - Current location tracking
 * - Search destinations (Nominatim geocoding)
 */
class MapsFragment : Fragment() {

    private var _binding: FragmentMapsBinding? = null
    private val binding get() = _binding!!

    private var locationOverlay: MyLocationNewOverlay? = null

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocation = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseLocation = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false

        if (fineLocation || coarseLocation) {
            enableMyLocation()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // Configure OSMDroid (required before using MapView)
        Configuration.getInstance().load(
            requireContext(),
            requireContext().getSharedPreferences("osmdroid", Context.MODE_PRIVATE)
        )
        Configuration.getInstance().userAgentValue = requireContext().packageName

        _binding = FragmentMapsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupMap()
        setupControls()
    }

    private fun setupMap() {
        val mapView = binding.mapView

        // Use OpenStreetMap tiles (free, no API key)
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(15.0)

        // Default location: Bangkok
        val bangkok = GeoPoint(13.7563, 100.5018)
        mapView.controller.setCenter(bangkok)

        // Enable location overlay
        checkLocationPermission()
    }

    private fun setupControls() {
        // My Location button
        binding.fabMyLocation.setOnClickListener {
            centerOnCurrentLocation()
        }

        // Search
        binding.searchBar.setOnEditorActionListener { _, _, _ ->
            val query = binding.searchBar.text.toString()
            if (query.isNotEmpty()) {
                searchLocation(query)
            }
            true
        }
    }

    private fun checkLocationPermission() {
        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            enableMyLocation()
        } else {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun enableMyLocation() {
        try {
            val mapView = binding.mapView
            locationOverlay = MyLocationNewOverlay(
                GpsMyLocationProvider(requireContext()),
                mapView
            ).apply {
                enableMyLocation()
                enableFollowLocation()
            }
            mapView.overlays.add(locationOverlay)
            Timber.d("MyLocation enabled")
        } catch (e: SecurityException) {
            Timber.e(e, "Location permission not granted")
        }
    }

    private fun centerOnCurrentLocation() {
        val overlay = locationOverlay ?: return
        val myLocation = overlay.myLocation
        if (myLocation != null) {
            binding.mapView.controller.animateTo(myLocation)
            binding.mapView.controller.setZoom(17.0)
            Timber.d("Centered on: ${myLocation.latitude}, ${myLocation.longitude}")
        } else {
            Timber.w("Current location not available yet")
        }
    }

    private fun searchLocation(query: String) {
        // Use Nominatim (OpenStreetMap geocoder) — free, no API key
        Thread {
            try {
                val encoded = java.net.URLEncoder.encode(query, "UTF-8")
                val url = java.net.URL(
                    "https://nominatim.openstreetmap.org/search?q=$encoded&format=json&limit=1"
                )
                val response = url.readText()
                val jsonArray = org.json.JSONArray(response)

                if (jsonArray.length() > 0) {
                    val result = jsonArray.getJSONObject(0)
                    val lat = result.getDouble("lat")
                    val lon = result.getDouble("lon")
                    val displayName = result.getString("display_name")

                    requireActivity().runOnUiThread {
                        val point = GeoPoint(lat, lon)
                        binding.mapView.controller.animateTo(point)
                        binding.mapView.controller.setZoom(16.0)

                        // Add marker
                        val marker = Marker(binding.mapView).apply {
                            position = point
                            title = displayName
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        }
                        binding.mapView.overlays.removeAll { it is Marker && it.id != "myLocation" }
                        binding.mapView.overlays.add(marker)
                        binding.mapView.invalidate()

                        Timber.d("Found: $displayName ($lat, $lon)")
                    }
                } else {
                    Timber.w("No results for: $query")
                }
            } catch (e: Exception) {
                Timber.e(e, "Search error")
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.mapView.onDetach()
        _binding = null
    }
}
