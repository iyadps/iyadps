"""Apply the map test release on top of the unchanged 1.2-test1 customization."""
from pathlib import Path
import shutil
import subprocess
import sys

root = Path(sys.argv[1]).resolve()
source = Path(__file__).resolve().parent
subprocess.run([sys.executable, str(source / "apply_alpha_1_2.py"), str(root)], check=True)

def replace(path, old, new):
    target = root / path
    text = target.read_text(encoding="utf-8")
    assert text.count(old) == 1, f"Expected exactly one integration point in {path}: {old}"
    target.write_text(text.replace(old, new), encoding="utf-8")

replace("collect_app/build.gradle", "versionCode 4153", "versionCode 4154")
replace("collect_app/build.gradle", "versionName '1.2.0-test1'", "versionName '1.2.0-test2'")
for folder, target in [("src", "main"), ("test", "test")]:
    for file in (source / "v1_2_map" / folder).glob("*.kt"):
        shutil.copyfile(file, root / f"collect_app/src/{target}/java/org/odk/collect/android/alpha" / file.name)

replace("collect_app/src/main/AndroidManifest.xml",
        '        <activity android:name="org.odk.collect.android.activities.CrashHandlerActivity" />',
        '''        <activity android:name=".alpha.AlphaMapActivity" android:exported="false" />
        <activity android:name="org.odk.collect.android.activities.CrashHandlerActivity" />''')

db = "collect_app/src/main/java/org/odk/collect/android/alpha/AlphaFieldDatabase.kt"
replace(db, "    fun recordIdCount(recordId: String): Int {", '''    // A map needs the entire assigned EA, not the 50-row lookup window.
    fun mapRows(eaId: String): List<AlphaFrameRow> {
        if (eaId.isBlank()) return emptyList()
        val rows = mutableListOf<AlphaFrameRow>()
        readableDatabase.query("master_frame",
            arrayOf("record_id", "ea_id", "latitude", "longitude", "phone", "phone_norm", "status"),
            "ea_id = ?", arrayOf(eaId.trim()), null, null, "record_id ASC, row_id ASC"
        ).use { c ->
            while (c.moveToNext()) rows.add(AlphaFrameRow(c.getString(0), c.getString(1),
                if (c.isNull(2)) null else c.getDouble(2), if (c.isNull(3)) null else c.getDouble(3),
                c.getString(4), c.getString(5), c.getString(6)))
        }
        return rows
    }

    fun recordIdCount(recordId: String): Int {''')

activity = "collect_app/src/main/java/org/odk/collect/android/alpha/AlphaFieldOpsActivity.kt"
replace(activity, "    override fun onCreate(savedInstanceState: Bundle?) {", '''    private val openMap = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val id = result.data?.getStringExtra("record_id")
            val ea = result.data?.getStringExtra("ea_id")
            if (ea == preferences.getString("ea_id", "") && id != null) {
                Thread {
                    val row = database.mapRows(ea.orEmpty()).singleOrNull { it.recordId == id }
                    runOnUiThread { if (!isDestroyed && row != null) { searchInput.setText(row.recordId); selectCase(row) } }
                }.start()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {''')
replace(activity, "        loadAssignment()", '''        findViewById<Button>(R.id.alpha_offline_map).setOnClickListener {
            val savedEa = preferences.getString("ea_id", "").orEmpty()
            if (savedEa.isBlank() || savedEa != eaId.text.toString().trim() ||
                preferences.getString("project_id", "") != projectId.text.toString().trim()) {
                Toast.makeText(this, "Save assignment before opening the map", Toast.LENGTH_LONG).show()
            } else {
                openMap.launch(Intent(this, AlphaMapActivity::class.java).putExtra("record_id", selectedRow?.recordId))
            }
        }
        loadAssignment()''')
replace("collect_app/src/main/res/layout/alpha_field_ops_activity.xml",
        '        <TextView\n            android:id="@+id/alpha_frame_summary"',
        '''        <Button
            android:id="@+id/alpha_offline_map"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="Open offline EA map" />

        <TextView
            android:id="@+id/alpha_frame_summary"''')
print("Alpha Collect 1.2.0-test2 (4154), isolated test package; offline map applied.")
