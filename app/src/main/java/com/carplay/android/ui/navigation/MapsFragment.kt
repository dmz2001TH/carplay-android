package com.carplay.android.ui.navigation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.carplay.android.databinding.FragmentMapsBinding
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
import timber.log.Timber

/**
 * Maps Fragment - CarPlay Navigation
 *
 * Google Maps integration with:
 * - Dark mode map style (matches CarPlay)
 * - Turn-by-turn navigation
 * - Search destinations
 * - Current location tracking
 */
class MapsFragment : Fragment(), OnMapReadyCallback {

    private var _binding: FragmentMapsBinding? = null
    private val binding get() = _binding!!

    private var googleMap: GoogleMap? = null

    // Location permission launcher
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
        _binding = FragmentMapsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize map
        val mapFragment = childFragmentManager.findFragmentById(com.carplay.android.R.id.mapFragment)
                as? SupportMapFragment
        mapFragment?.getMapAsync(this)

        // Search bar
        binding.searchBar.setOnEditorActionListener { _, _, _ ->
            val query = binding.searchBar.text.toString()
            if (query.isNotEmpty()) {
                searchLocation(query)
            }
            true
        }
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map

        // Apply dark map style (CarPlay look)
        try {
            val darkStyle = MapStyleOptions("""
                [
                    {"elementType": "geometry", "stylers": [{"color": "#1d2c4d"}]},
                    {"elementType": "labels.text.fill", "stylers": [{"color": "#8ec3b9"}]},
                    {"elementType": "labels.text.stroke", "stylers": [{"color": "#1a3646"}]},
                    {"featureType": "administrative.country", "elementType": "geometry.stroke", "stylers": [{"color": "#4b6878"}]},
                    {"featureType": "road", "elementType": "geometry", "stylers": [{"color": "#304a7d"}]},
                    {"featureType": "road", "elementType": "geometry.stroke", "stylers": [{"color": "#255763"}]},
                    {"featureType": "water", "elementType": "geometry", "stylers": [{"color": "#0e1626"}]}
                ]
            """)
            map.setMapStyle(darkStyle)
        } catch (e: Exception) {
            Timber.e(e, "Error setting map style")
        }

        // Map settings
        map.uiSettings.isZoomControlsEnabled = true
        map.uiSettings.isCompassEnabled = true
        map.uiSettings.isMyLocationButtonEnabled = true

        // Enable location if permission granted
        checkLocationPermission()

        // Default location (Bangkok)
        val bangkok = LatLng(13.7563, 100.5018)
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(bangkok, 12f))
    }

    private fun checkLocationPermission() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            enableMyLocation()
        } else {
            locationPermissionLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        }
    }

    private fun enableMyLocation() {
        try {
            googleMap?.isMyLocationEnabled = true
        } catch (e: SecurityException) {
            Timber.e(e, "Location permission not granted")
        }
    }

    private fun searchLocation(query: String) {
        // TODO: Implement Places API search
        Timber.d("Searching for: $query")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
