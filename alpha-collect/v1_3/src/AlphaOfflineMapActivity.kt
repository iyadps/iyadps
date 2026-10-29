package org.odk.collect.android.alpha

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import org.odk.collect.android.R
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale

class AlphaOfflineMapActivity : AppCompatActivity() {

    private val repository by lazy { AlphaOfflineMapRepository(this) }
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var eaId: String
    private lateinit var map: AlphaOfflineMapView
    private lateinit var summary: TextView
    private lateinit var selection: TextView
    private lateinit var gpsInfo: TextView
    private lateinit var useCase: Button
    private var selectedRow: AlphaFrameRow? = null
    private var activeListener: LocationListener? = null
    private var locationResolved = false

    private val boundaryPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            summary.text = getString(R.string.alpha_map_importing)
            Thread {
                try {
                    val count = contentResolver.openInputStream(uri).use { stream ->
                        requireNotNull(stream) { "Could not read the selected boundary CSV." }
                        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use {
                            repository.importBoundaryCsv(it)
                        }
                    }
                    runOnUiThread {
                        Toast.makeText(this, getString(R.string.alpha_map_imported, count), Toast.LENGTH_LONG).show()
                        refreshMap()
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        summary.text = getString(
                            R.string.alpha_map_import_failed,
                            e.message ?: "Unknown error"
                        )
                    }
                }
            }.start()
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (
                results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                results[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            ) {
                refreshGps()
            } else {
                gpsInfo.text = getString(R.string.alpha_location_permission_required)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.alpha_offline_map_activity)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.alpha_map_title)

        eaId = intent.getStringExtra("ea_id").orEmpty().trim()
        map = findViewById(R.id.alpha_ea_map_view)
        summary = findViewById(R.id.alpha_map_summary)
        selection = findViewById(R.id.alpha_map_selection)
        gpsInfo = findViewById(R.id.alpha_map_gps_info)
        useCase = findViewById(R.id.alpha_map_use_case)
        useCase.isEnabled = false

        selection.text = getString(R.string.alpha_map_no_selection)
        gpsInfo.text = getString(R.string.alpha_location_not_verified)
        map.onCaseSelected = { row ->
            selectedRow = row
            selection.text = getString(
                R.string.alpha_map_selected_record,
                row.recordId,
                row.status ?: "pending",
                row.latitude ?: 0.0,
                row.longitude ?: 0.0
            )
            useCase.isEnabled = true
        }

        findViewById<Button>(R.id.alpha_map_import_boundary).setOnClickListener {
            boundaryPicker.launch(arrayOf("text/*", "text/csv", "application/csv"))
        }
        findViewById<Button>(R.id.alpha_map_refresh).setOnClickListener { refreshMap() }
        findViewById<Button>(R.id.alpha_map_my_location).setOnClickListener {
            requestCurrentLocation()
        }
        findViewById<Button>(R.id.alpha_map_reset).setOnClickListener { map.resetView() }
        useCase.setOnClickListener {
            val row = selectedRow ?: return@setOnClickListener
            setResult(RESULT_OK, Intent().putExtra("record_id", row.recordId))
            finish()
        }

        val initialLatitude = intent.getDoubleExtra("user_lat", Double.NaN)
        val initialLongitude = intent.getDoubleExtra("user_lon", Double.NaN)
        if (AlphaMapGeometry.validCoordinate(initialLatitude, initialLongitude)) {
            map.setMyLocation(initialLatitude, initialLongitude)
        }
        refreshMap()
    }

    private fun refreshMap() {
        if (eaId.isBlank()) {
            summary.text = getString(R.string.alpha_map_missing_ea)
            return
        }
        summary.text = getString(R.string.alpha_map_loading)
        Thread {
            try {
                val data = repository.loadEa(eaId)
                runOnUiThread {
                    map.setData(data.records, data.boundary)
                    selectedRow = null
                    useCase.isEnabled = false
                    selection.text = getString(R.string.alpha_map_no_selection)
                    summary.text = getString(
                        R.string.alpha_map_loaded,
                        eaId,
                        data.records.size,
                        data.totalPoints,
                        data.boundary.size
                    )
                }
            } catch (e: Exception) {
                runOnUiThread {
                    summary.text = getString(R.string.alpha_map_import_failed, e.message ?: "Unknown error")
                }
            }
        }.start()
    }

    private fun requestCurrentLocation() {
        val fine = ActivityCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ActivityCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
            return
        }
        refreshGps()
    }

    private fun refreshGps() {
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) {
            gpsInfo.text = getString(R.string.alpha_location_provider_disabled)
            return
        }
        gpsInfo.text = getString(R.string.alpha_acquiring_location)
        stopGps()
        locationResolved = false

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (!locationResolved) {
                    locationResolved = true
                    stopGps()
                    updateLocation(location)
                }
            }
            @Deprecated("Deprecated in Android framework")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }
        activeListener = listener

        try {
            for (provider in providers) {
                manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
            }
        } catch (_: SecurityException) {
            stopGps()
            gpsInfo.text = getString(R.string.alpha_location_permission_required)
            return
        }
        handler.postDelayed({
            if (!locationResolved) {
                locationResolved = true
                stopGps()
                gpsInfo.text = getString(R.string.alpha_location_timeout)
            }
        }, 15000)
    }

    private fun updateLocation(location: Location) {
        map.setMyLocation(location.latitude, location.longitude)
        gpsInfo.text = getString(
            R.string.alpha_map_gps_value,
            location.latitude,
            location.longitude,
            location.accuracy
        )
    }

    private fun stopGps() {
        val listener = activeListener ?: return
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        runCatching { manager.removeUpdates(listener) }
        activeListener = null
    }

    override fun onDestroy() {
        stopGps()
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
