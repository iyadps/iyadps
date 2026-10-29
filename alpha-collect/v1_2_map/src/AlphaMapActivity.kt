package org.odk.collect.android.alpha

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.security.MessageDigest
import java.util.concurrent.Executors

class AlphaMapActivity : AppCompatActivity() {
    private lateinit var map: AlphaMapView
    private lateinit var message: TextView
    private lateinit var caseInfo: TextView
    private lateinit var useCase: Button
    private lateinit var importButton: Button
    private val database by lazy { AlphaFieldDatabase(this) }
    private val preferences by lazy { getSharedPreferences("alpha_field_ops", MODE_PRIVATE) }
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var listener: LocationListener? = null
    private var ea = ""
    private var project = ""
    private var rows: List<AlphaFrameRow> = emptyList()
    private var ready = false
    private var selectedId: String? = null
    private var locationText = "GPS not captured. Tap Locate."
    private var overlayWarning: String? = null
    private var invalidPoints = 0
    private val storageKey: String get() = "map_" + MessageDigest.getInstance("SHA-256")
        .digest((project + "\u0000" + ea).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private val permission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasLocationPermission()) locate() else { locationText = "Location permission denied. Offline map remains available."; summary() }
    }
    private val importMap = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importButton.isEnabled = false
            message.text = "Importing map…"
            executor.execute {
                val result = runCatching {
                    val text = contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input)
                        val bytes = input.readBytesLimited(1_000_000)
                        String(bytes, Charsets.UTF_8)
                    }
                    val overlay = AlphaMapData.parse(text, ea)
                    check(preferences.edit().putString(storageKey, text).commit()) { "Unable to save map" }
                    overlay
                }
                runOnUiThread {
                    if (!isDestroyed) {
                        importButton.isEnabled = ready
                        result.onSuccess { map.overlay = it; overlayWarning = null; map.fit(); summary() }
                            .onFailure { message.text = "Map import failed: ${it.message}. Previous map retained." }
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ea = preferences.getString("ea_id", "").orEmpty().trim()
        project = preferences.getString("project_id", "").orEmpty()
        if (ea.isBlank()) { finish(); return }
        selectedId = savedInstanceState?.getString("selected_id") ?: intent.getStringExtra("record_id")
        supportActionBar?.title = "Offline map • $ea"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        message = TextView(this).apply { setPadding(16, 8, 16, 8); text = "Loading assigned EA…" }
        root.addView(message)
        val controls = LinearLayout(this)
        fun control(label: String, action: () -> Unit) {
            controls.addView(Button(this).apply { text = label; setOnClickListener { action() } },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        control("Fit") { map.fit() }; control("+") { map.zoom(1.8) }; control("−") { map.zoom(1 / 1.8) }; control("Locate") { locate() }
        root.addView(controls)
        map = AlphaMapView(this)
        root.addView(map, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        caseInfo = TextView(this).apply { setPadding(16, 8, 16, 8); text = "Tap a marker or choose a case." }
        details.addView(caseInfo)
        details.addView(Button(this).apply { text = "Choose case from list"; setOnClickListener { choose(rows) } })
        useCase = Button(this).apply {
            text = "Use selected case in Field Operations"; isEnabled = false
            setOnClickListener {
                val row = map.selected ?: return@setOnClickListener
                setResult(RESULT_OK, Intent().putExtra("record_id", row.recordId).putExtra("ea_id", ea))
                finish()
            }
        }
        details.addView(useCase)
        importButton = Button(this).apply {
            text = "Import EA GeoJSON / layer bounds"; isEnabled = false
            setOnClickListener { importMap.launch(arrayOf("*/*")) }
        }
        details.addView(importButton)
        AlphaMapData.colors.forEach { (status, color) ->
            details.addView(TextView(this).apply { text = "● ${status.replace('_', ' ')}"; setTextColor(color); setPadding(16, 0, 16, 0) })
        }
        details.addView(TextView(this).apply { text = "Green outline: assigned EA • Purple dashed: layer bounds\nBlack ring: selected case • Blue: captured enumerator GPS"; setPadding(16, 8, 16, 8) })
        root.addView(ScrollView(this).apply { addView(details) }, LinearLayout.LayoutParams(-1, (210 * resources.displayMetrics.density).toInt()))
        setContentView(root)
        map.onCases = { choose(it) }
        executor.execute {
            val result = runCatching { database.mapRows(ea) }
            val saved = preferences.getString(storageKey, null)
            val overlay = saved?.let { runCatching { AlphaMapData.parse(it, ea) } }
            runOnUiThread {
                if (!isDestroyed) result.onSuccess { loaded ->
                    rows = loaded
                    map.rows = loaded.filter { AlphaMapData.point(it) != null }
                    invalidPoints = loaded.size - map.rows.size
                    map.overlay = overlay?.getOrNull() ?: AlphaMapOverlay()
                    overlayWarning = overlay?.exceptionOrNull()?.let { "Saved overlay could not be read; reimport it." }
                    ready = true; importButton.isEnabled = true
                    rows.singleOrNull { it.recordId == selectedId }?.let { select(it) }
                    map.post { map.fit() }; summary()
                }.onFailure { message.text = "Unable to load master frame: ${it.message}" }
            }
        }
    }

    private fun summary() {
        message.text = "EA $ea • ${map.rows.size} points • $invalidPoints without valid GPS\n" +
            (overlayWarning ?: if (map.overlay.polygons.isEmpty()) "No EA polygon imported. " else "EA polygon available offline. ") + locationText
    }

    private fun choose(candidates: List<AlphaFrameRow>) {
        if (candidates.isEmpty()) return
        if (candidates.size == 1) { select(candidates.first()); return }
        AlertDialog.Builder(this).setTitle("Choose case (${candidates.size})")
            .setItems(candidates.map { "${it.recordId} • ${AlphaMapData.status(it.status)} • ${it.latitude}, ${it.longitude}" }.toTypedArray()) { _, i -> select(candidates[i]) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun select(row: AlphaFrameRow) {
        map.selected = row; selectedId = row.recordId
        val unique = rows.count { it.recordId == row.recordId } == 1
        useCase.isEnabled = unique
        caseInfo.text = "${row.recordId} • ${AlphaMapData.status(row.status)}\nGPS: ${row.latitude}, ${row.longitude}" +
            if (unique) "" else "\nDuplicate ID in assigned EA. Resolve duplicates before recording a visit."
        AlphaMapData.point(row)?.let { map.center(it) }
        map.invalidate()
    }

    private fun hasLocationPermission() = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        .any { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun locate() {
        if (!hasLocationPermission()) { permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)); return }
        stopLocation()
        map.location = null; map.invalidate()
        val manager = getSystemService(LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) { locationText = "GPS providers disabled. Enable device location."; summary(); return }
        locationText = "Acquiring GPS (up to 30 seconds)…"; summary()
        val callback = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val age = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000
                if (age !in 0..120000) return
                val point = runCatching { AlphaMapPoint(location.latitude, location.longitude) }.getOrNull() ?: return
                stopLocation()
                map.location = point; map.center(point)
                locationText = "Captured GPS: ${location.latitude}, ${location.longitude}; accuracy ±${location.accuracy.toInt()} m. Tap Locate to refresh."
                summary()
            }
            @Deprecated("Deprecated in Android framework")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }
        listener = callback
        var registered = false
        providers.forEach { provider ->
            try { manager.requestLocationUpdates(provider, 0L, 0f, callback, Looper.getMainLooper()); registered = true } catch (_: SecurityException) { }
        }
        if (!registered) { stopLocation(); locationText = "Location permission unavailable."; summary(); return }
        handler.postDelayed({ stopLocation(); locationText = "No recent GPS fix. Move outdoors and retry."; summary() }, 30000)
    }
    private fun stopLocation() {
        handler.removeCallbacksAndMessages(null)
        listener?.let { runCatching { (getSystemService(LOCATION_SERVICE) as LocationManager).removeUpdates(it) } }
        listener = null
    }
    override fun onStop() {
        if (listener != null) { stopLocation(); locationText = "GPS request stopped. Tap Locate to retry."; summary() }
        super.onStop()
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("selected_id", selectedId); super.onSaveInstanceState(outState) }
    override fun onDestroy() { stopLocation(); executor.execute { database.close() }; executor.shutdown(); super.onDestroy() }
    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}

private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        require(output.size() + count <= limit) { "Map file exceeds 1 MB" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
