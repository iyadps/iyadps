package org.odk.collect.android.alpha

import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.odk.collect.android.R
import java.io.BufferedReader
import java.io.InputStreamReader

class AlphaFieldOpsActivity : AppCompatActivity() {

    private val preferences by lazy {
        getSharedPreferences("alpha_field_ops", MODE_PRIVATE)
    }
    private val database by lazy { AlphaFieldDatabase(this) }

    private lateinit var projectId: EditText
    private lateinit var enumeratorId: EditText
    private lateinit var supervisorId: EditText
    private lateinit var eaId: EditText
    private lateinit var deviceId: TextView
    private lateinit var frameSummary: TextView
    private lateinit var searchInput: EditText
    private lateinit var searchResults: TextView
    private lateinit var progress: ProgressBar

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
        progress = findViewById(R.id.alpha_import_progress)

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
    }

    private fun saveAssignment() {
        val enumerator = enumeratorId.text.toString().trim()
        val ea = eaId.text.toString().trim()

        if (enumerator.isBlank() || ea.isBlank()) {
            Toast.makeText(this, R.string.alpha_assignment_required, Toast.LENGTH_LONG).show()
            return
        }

        preferences.edit()
            .putString("project_id", projectId.text.toString().trim())
            .putString("enumerator_id", enumerator)
            .putString("supervisor_id", supervisorId.text.toString().trim())
            .putString("ea_id", ea)
            .apply()

        updateFrameSummary()
        Toast.makeText(this, R.string.alpha_assignment_saved, Toast.LENGTH_SHORT).show()
    }

    private fun showDeviceId() {
        val androidId = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ANDROID_ID
        ).orEmpty()
        val shortId = when {
            androidId.isBlank() -> "unavailable"
            androidId.length > 8 -> androidId.takeLast(8)
            else -> androidId
        }
        deviceId.text = getString(R.string.alpha_device_value, shortId)
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
            return
        }

        searchResults.text = getString(R.string.alpha_searching)
        Thread {
            val ea = eaId.text.toString().trim()
            val rows = database.search(query, ea)
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
            }
        }.start()
    }
}
