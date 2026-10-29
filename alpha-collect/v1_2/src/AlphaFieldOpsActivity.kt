package org.odk.collect.android.alpha

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import org.odk.collect.android.R
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class AlphaFieldOpsActivity : AppCompatActivity() {

    private val preferences by lazy {
        getSharedPreferences("alpha_field_ops", MODE_PRIVATE)
    }
    private val database by lazy { AlphaFieldDatabase(this) }
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var projectId: EditText
    private lateinit var enumeratorId: EditText
    private lateinit var supervisorId: EditText
    private lateinit var eaId: EditText
    private lateinit var deviceId: TextView
    private lateinit var frameSummary: TextView
    private lateinit var searchInput: EditText
    private lateinit var searchResults: TextView
    private lateinit var selectedCase: TextView
    private lateinit var distanceThreshold: EditText
    private lateinit var locationResult: TextView
    private lateinit var visitStatus: Spinner
    private lateinit var revisitDate: EditText
    private lateinit var visitNote: EditText
    private lateinit var lastVisit: TextView
    private lateinit var progress: ProgressBar

    private var selectedRow: AlphaFrameRow? = null
    private var verifiedLocation: Location? = null
    private var verifiedDistanceMeters: Double? = null
    private var pendingLocationVerification = false
    private var activeLocationListener: LocationListener? = null

    private val statuses = listOf(
        "pending",
        "completed",
        "refused",
        "revisit",
        "vacant",
        "not_found",
        "unavailable"
    )

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true

            if (granted && pendingLocationVerification) {
                pendingLocationVerification = false
                acquireCurrentLocation()
            } else if (!granted) {
                pendingLocationVerification = false
                locationResult.text = getString(R.string.alpha_location_permission_required)
            }
        }

    private val importCsv =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult

            progress.visibility = View.VISIBLE
            frameSummary.text = getString(R.string.alpha_importing_frame)

            Thread {
                try {
                    contentResolver.openInputStream(uri).use { stream ->
                        requireNotNull(stream) { "Unable to open selected CSV file." }
                        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                            val stats = database.replaceMasterFrame(reader)
                            runOnUiThread {
                                progress.visibility = View.GONE
                                updateFrameSummary()
                                clearSelectedCase()
                                Toast.makeText(
                                    this,
                                    getString(
                                        R.string.alpha_import_complete,
                                        stats.importedRows,
                                        stats.duplicateRecordIds,
                                        stats.duplicatePhones
                                    ),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                } catch (error: Exception) {
                    runOnUiThread {
                        progress.visibility = View.GONE
                        frameSummary.text = getString(
                            R.string.alpha_import_failed,
                            error.message ?: "Unknown error"
                        )
                    }
                }
            }.start()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.alpha_field_ops_activity)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.alpha_field_ops)

        projectId = findViewById(R.id.alpha_project_id)
        enumeratorId = findViewById(R.id.alpha_enumerator_id)
        supervisorId = findViewById(R.id.alpha_supervisor_id)
        eaId = findViewById(R.id.alpha_ea_id)
        deviceId = findViewById(R.id.alpha_device_id)
        frameSummary = findViewById(R.id.alpha_frame_summary)
        searchInput = findViewById(R.id.alpha_search_input)
        searchResults = findViewById(R.id.alpha_search_results)
        selectedCase = findViewById(R.id.alpha_selected_case)
        distanceThreshold = findViewById(R.id.alpha_distance_threshold)
        locationResult = findViewById(R.id.alpha_location_result)
        visitStatus = findViewById(R.id.alpha_visit_status)
        revisitDate = findViewById(R.id.alpha_revisit_date)
        visitNote = findViewById(R.id.alpha_visit_note)
        lastVisit = findViewById(R.id.alpha_last_visit)
        progress = findViewById(R.id.alpha_import_progress)

        visitStatus.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            statuses
        ).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        loadAssignment()
        showDeviceId()
        updateFrameSummary()

        findViewById<Button>(R.id.alpha_save_assignment).setOnClickListener {
            saveAssignment()
        }
        findViewById<Button>(R.id.alpha_import_frame).setOnClickListener {
            importCsv.launch(
                arrayOf("text/*", "text/csv", "application/csv", "application/vnd.ms-excel")
            )
        }
        findViewById<Button>(R.id.alpha_search_button).setOnClickListener {
            runLookup()
        }
        findViewById<Button>(R.id.alpha_verify_location).setOnClickListener {
            verifySelectedLocation()
        }
        findViewById<Button>(R.id.alpha_open_expected_location).setOnClickListener {
            openExpectedLocation()
        }
        findViewById<Button>(R.id.alpha_save_visit).setOnClickListener {
            saveVisitStatus()
        }
    }

    override fun onDestroy() {
        stopLocationUpdates()
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun loadAssignment() {
        projectId.setText(preferences.getString("project_id", ""))
        enumeratorId.setText(preferences.getString("enumerator_id", ""))
        supervisorId.setText(preferences.getString("supervisor_id", ""))
        eaId.setText(preferences.getString("ea_id", ""))
        distanceThreshold.setText(
            preferences.getInt("distance_threshold_meters", 100).toString()
        )
    }

    private fun saveAssignment() {
        val enumerator = enumeratorId.text.toString().trim()
        val ea = eaId.text.toString().trim()
        val threshold = parseThreshold()

        if (enumerator.isBlank() || ea.isBlank()) {
            Toast.makeText(this, R.string.alpha_assignment_required, Toast.LENGTH_LONG).show()
            return
        }

        preferences.edit()
            .putString("project_id", projectId.text.toString().trim())
            .putString("enumerator_id", enumerator)
            .putString("supervisor_id", supervisorId.text.toString().trim())
            .putString("ea_id", ea)
            .putInt("distance_threshold_meters", threshold)
            .apply()

        clearSelectedCase()
        updateFrameSummary()
        Toast.makeText(this, R.string.alpha_assignment_saved, Toast.LENGTH_SHORT).show()
    }

    private fun showDeviceId() {
        deviceId.text = getString(R.string.alpha_device_value, getDeviceShortId())
    }

    private fun getDeviceShortId(): String {
        val androidId = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ANDROID_ID
        ).orEmpty()

        return when {
            androidId.isBlank() -> "unavailable"
            androidId.length > 8 -> androidId.takeLast(8)
            else -> androidId
        }
    }

    private fun updateFrameSummary() {
        Thread {
            val ea = eaId.text.toString().trim()
            val total = database.totalCount()
            val assigned = database.countForEa(ea)
            runOnUiThread {
                frameSummary.text = getString(
                    R.string.alpha_frame_summary_value,
                    total,
                    if (ea.isBlank()) "-" else ea,
                    assigned
                )
            }
        }.start()
    }

    private fun runLookup() {
        val query = searchInput.text.toString().trim()
        if (query.isBlank()) {
            searchResults.text = getString(R.string.alpha_enter_lookup)
            clearSelectedCase()
            return
        }

        searchResults.text = getString(R.string.alpha_searching)
        Thread {
            val ea = eaId.text.toString().trim()
            val rows = database.search(query, ea)
            val chosen = AlphaFieldRules.selectRecord(rows, query)

            val rendered = if (rows.isEmpty()) {
                getString(R.string.alpha_no_matches)
            } else {
                rows.joinToString("\n\n") { row ->
                    val recordDuplicates = database.recordIdCount(row.recordId)
                    val phoneDuplicates = database.phoneCount(row.phone)
                    val risk = if (recordDuplicates > 1 || phoneDuplicates > 1) "HIGH" else "LOW"

                    buildString {
                        append("ID: ${row.recordId}")
                        append("\nEA: ${row.eaId}")
                        row.phone?.let { append("\nPhone: $it") }
                        if (row.latitude != null && row.longitude != null) {
                            append("\nGPS: ${row.latitude}, ${row.longitude}")
                        }
                        row.status?.let { append("\nStatus: $it") }
                        append("\nDuplicate risk: $risk")
                        if (recordDuplicates > 1) append(" | ID matches: $recordDuplicates")
                        if (phoneDuplicates > 1) append(" | Phone matches: $phoneDuplicates")
                    }
                }
            }

            runOnUiThread {
                searchResults.text = rendered
                if (chosen != null) {
                    selectCase(chosen)
                } else {
                    clearSelectedCase()
                    if (rows.size > 1) {
                        selectedCase.text = getString(R.string.alpha_multiple_matches)
                    }
                }
            }
        }.start()
    }

    private fun selectCase(row: AlphaFrameRow) {
        selectedRow = row
        verifiedLocation = null
        verifiedDistanceMeters = null
        locationResult.text = getString(R.string.alpha_location_not_verified)

        selectedCase.text = buildString {
            append(getString(R.string.alpha_selected_case_value, row.recordId, row.eaId))
            if (row.latitude != null && row.longitude != null) {
                append("\nExpected GPS: ${row.latitude}, ${row.longitude}")
            } else {
                append("\n")
                append(getString(R.string.alpha_selected_case_no_gps))
            }
        }

        Thread {
            val latest = database.latestVisit(row.recordId, row.eaId)
            runOnUiThread {
                renderLatestVisit(latest)
            }
        }.start()
    }

    private fun clearSelectedCase() {
        selectedRow = null
        verifiedLocation = null
        verifiedDistanceMeters = null
        selectedCase.text = getString(R.string.alpha_no_case_selected)
        locationResult.text = getString(R.string.alpha_location_not_verified)
        lastVisit.text = ""
    }

    private fun verifySelectedLocation() {
        val row = selectedRow
        if (row == null) {
            locationResult.text = getString(R.string.alpha_select_case_first)
            return
        }
        if (row.latitude == null || row.longitude == null) {
            locationResult.text = getString(R.string.alpha_case_has_no_gps)
            return
        }

        val fineGranted = ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!fineGranted && !coarseGranted) {
            pendingLocationVerification = true
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
            return
        }

        acquireCurrentLocation()
    }

    private fun acquireCurrentLocation() {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val enabledProviders = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER
        ).filter { provider ->
            runCatching { locationManager.isProviderEnabled(provider) }.getOrDefault(false)
        }

        if (enabledProviders.isEmpty()) {
            locationResult.text = getString(R.string.alpha_location_provider_disabled)
            return
        }

        val lastKnown = enabledProviders.mapNotNull { provider ->
            try {
                if (
                    ActivityCompat.checkSelfPermission(
                        this,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) != PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(
                        this,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    null
                } else {
                    locationManager.getLastKnownLocation(provider)
                }
            } catch (_: Exception) {
                null
            }
        }.maxByOrNull { it.time }

        val recentEnough = lastKnown != null &&
            System.currentTimeMillis() - lastKnown.time <= 120000

        if (recentEnough) {
            processLocation(lastKnown!!)
            return
        }

        locationResult.text = getString(R.string.alpha_acquiring_location)
        stopLocationUpdates()

        var resolved = false
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (resolved) return
                resolved = true
                stopLocationUpdates()
                processLocation(location)
            }

            @Deprecated("Deprecated in Android framework")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

            override fun onProviderEnabled(provider: String) = Unit

            override fun onProviderDisabled(provider: String) = Unit
        }
        activeLocationListener = listener

        try {
            enabledProviders.forEach { provider ->
                locationManager.requestLocationUpdates(
                    provider,
                    0L,
                    0f,
                    listener,
                    Looper.getMainLooper()
                )
            }
        } catch (_: SecurityException) {
            locationResult.text = getString(R.string.alpha_location_permission_required)
            return
        }

        handler.postDelayed(
            {
                if (!resolved) {
                    resolved = true
                    stopLocationUpdates()
                    if (lastKnown != null) {
                        processLocation(lastKnown)
                    } else {
                        locationResult.text = getString(R.string.alpha_location_timeout)
                    }
                }
            },
            15000
        )
    }

    private fun stopLocationUpdates() {
        val listener = activeLocationListener ?: return
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        runCatching { locationManager.removeUpdates(listener) }
        activeLocationListener = null
    }

    private fun processLocation(location: Location) {
        val row = selectedRow ?: return
        val expectedLat = row.latitude ?: return
        val expectedLon = row.longitude ?: return
        val threshold = parseThreshold().toDouble()

        val distance = AlphaFieldRules.distanceMeters(
            location.latitude,
            location.longitude,
            expectedLat,
            expectedLon
        )
        val result = AlphaFieldRules.classifyDistance(distance, threshold)

        verifiedLocation = location
        verifiedDistanceMeters = distance

        preferences.edit()
            .putInt("distance_threshold_meters", threshold.toInt())
            .apply()

        val resultLabel = when (result) {
            AlphaDistanceResult.VERIFIED -> getString(R.string.alpha_location_verified)
            AlphaDistanceResult.OUTSIDE_THRESHOLD -> getString(R.string.alpha_location_outside)
        }

        locationResult.text = getString(
            R.string.alpha_location_result_value,
            resultLabel,
            distance,
            location.accuracy,
            location.latitude,
            location.longitude,
            threshold
        )
    }

    private fun parseThreshold(): Int {
        return distanceThreshold.text.toString().trim().toIntOrNull()
            ?.coerceIn(10, 5000)
            ?: 100
    }

    private fun openExpectedLocation() {
        val row = selectedRow
        val lat = row?.latitude
        val lon = row?.longitude

        if (row == null || lat == null || lon == null) {
            Toast.makeText(this, R.string.alpha_case_has_no_gps, Toast.LENGTH_LONG).show()
            return
        }

        val label = Uri.encode(row.recordId)
        val uri = Uri.parse("geo:$lat,$lon?q=$lat,$lon($label)")
        val intent = Intent(Intent.ACTION_VIEW, uri)

        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        } else {
            Toast.makeText(this, R.string.alpha_no_map_app, Toast.LENGTH_LONG).show()
        }
    }

    private fun saveVisitStatus() {
        val row = selectedRow
        if (row == null) {
            Toast.makeText(this, R.string.alpha_select_case_first, Toast.LENGTH_LONG).show()
            return
        }

        val status = visitStatus.selectedItem?.toString() ?: "pending"
        val note = visitNote.text.toString().trim()
        val revisit = revisitDate.text.toString().trim()
        val location = verifiedLocation
        val distance = verifiedDistanceMeters
        val enumerator = enumeratorId.text.toString().trim()

        Thread {
            database.saveVisit(
                row = row,
                status = status,
                note = note,
                revisitDate = revisit,
                latitude = location?.latitude,
                longitude = location?.longitude,
                distanceMeters = distance,
                enumeratorId = enumerator,
                deviceId = getDeviceShortId()
            )
            val latest = database.latestVisit(row.recordId, row.eaId)

            runOnUiThread {
                renderLatestVisit(latest)
                Toast.makeText(this, R.string.alpha_visit_saved, Toast.LENGTH_SHORT).show()
                visitNote.setText("")
                if (status != "revisit") {
                    revisitDate.setText("")
                }
            }
        }.start()
    }

    private fun renderLatestVisit(visit: AlphaVisitRecord?) {
        if (visit == null) {
            lastVisit.text = getString(R.string.alpha_no_previous_visit)
            return
        }

        val whenText = DateFormat.getDateTimeInstance(
            DateFormat.MEDIUM,
            DateFormat.SHORT,
            Locale.getDefault()
        ).format(Date(visit.timestamp))

        lastVisit.text = buildString {
            append(getString(R.string.alpha_last_visit_value, visit.status, whenText))
            visit.distanceMeters?.let {
                append("\nDistance: ")
                append(String.format(Locale.US, "%.1f m", it))
            }
            visit.revisitDate?.let {
                append("\nRevisit: ")
                append(it)
            }
            visit.note?.let {
                append("\nNote: ")
                append(it)
            }
        }
    }
}
