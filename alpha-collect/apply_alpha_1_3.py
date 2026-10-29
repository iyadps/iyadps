from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path.cwd()
HERE = Path(__file__).resolve().parent
SOURCES = HERE / "v1_3"

subprocess.run([sys.executable, str(HERE / "apply_alpha_1_2.py"), str(ROOT)], check=True)


def replace_exact(path_rel, old, new):
    path = ROOT / path_rel
    contents = path.read_text(encoding="utf-8")
    if old not in contents:
        raise RuntimeError(f"Alpha 1.3 patch target missing: {path_rel}")
    path.write_text(contents.replace(old, new, 1), encoding="utf-8")
    print(f"Updated: {path_rel}")


def copy(source, target):
    path = ROOT / target
    path.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(SOURCES / source, path)
    print(f"Copied: {target}")


replace_exact("collect_app/build.gradle", "versionCode 4153", "versionCode 4154")
replace_exact(
    "collect_app/build.gradle",
    "versionName '1.2.0-test1'",
    "versionName '1.3.0-test1'",
)

base = "collect_app/src/main/java/org/odk/collect/android/alpha/"
copy("src/AlphaMapGeometry.kt", base + "AlphaMapGeometry.kt")
copy("src/AlphaOfflineMapRepository.kt", base + "AlphaOfflineMapRepository.kt")
copy("src/AlphaOfflineMapView.kt", base + "AlphaOfflineMapView.kt")
copy("src/AlphaOfflineMapActivity.kt", base + "AlphaOfflineMapActivity.kt")
copy("res/layout/alpha_offline_map_activity.xml",
     "collect_app/src/main/res/layout/alpha_offline_map_activity.xml")
copy("res/values/alpha_map_strings.xml",
     "collect_app/src/main/res/values/alpha_map_strings.xml")
copy("test/AlphaMapGeometryTest.kt",
     "collect_app/src/test/java/org/odk/collect/android/alpha/AlphaMapGeometryTest.kt")

replace_exact(
    "collect_app/src/main/AndroidManifest.xml",
    '        <activity\n            android:name=".alpha.AlphaFieldOpsActivity"\n            android:exported="false" />',
    '        <activity\n            android:name=".alpha.AlphaFieldOpsActivity"\n            android:exported="false" />\n'
    '        <activity\n            android:name=".alpha.AlphaOfflineMapActivity"\n            android:exported="false" />',
)

replace_exact(
    "collect_app/src/main/res/layout/alpha_field_ops_activity.xml",
    '            android:text="@string/alpha_search" />',
    '            android:text="@string/alpha_search" />\n'
    '        <Button\n'
    '            android:id="@+id/alpha_open_ea_map"\n'
    '            android:layout_width="match_parent"\n'
    '            android:layout_height="wrap_content"\n'
    '            android:text="@string/alpha_map_open" />',
)

activity = "collect_app/src/main/java/org/odk/collect/android/alpha/AlphaFieldOpsActivity.kt"
replace_exact(
    activity,
    "    private val permissionLauncher =",
    '''    private val mapLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val recordId = result.data?.getStringExtra("record_id").orEmpty()
                if (recordId.isNotBlank()) {
                    searchInput.setText(recordId)
                    runLookup()
                }
            }
        }

    private val permissionLauncher =''',
)

replace_exact(
    activity,
    '''        findViewById<Button>(R.id.alpha_verify_location).setOnClickListener {''',
    '''        findViewById<Button>(R.id.alpha_open_ea_map).setOnClickListener {
            val ea = eaId.text.toString().trim()
            if (ea.isBlank()) {
                Toast.makeText(this, R.string.alpha_map_missing_ea, Toast.LENGTH_LONG).show()
            } else {
                val intent = Intent(this, AlphaOfflineMapActivity::class.java)
                    .putExtra("ea_id", ea)
                verifiedLocation?.let {
                    intent.putExtra("user_lat", it.latitude)
                    intent.putExtra("user_lon", it.longitude)
                }
                mapLauncher.launch(intent)
            }
        }
        findViewById<Button>(R.id.alpha_verify_location).setOnClickListener {''',
)

print("Alpha Collect Test 1.3.0-test1 (4154) map customization applied successfully.")
print("Production Alpha Collect 1.0.0 is unaffected.")
