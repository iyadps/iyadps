from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path.cwd()
SCRIPT_DIR = Path(__file__).resolve().parent
V11_DIR = SCRIPT_DIR / "v1_1"

subprocess.run(
    [sys.executable, str(SCRIPT_DIR / "apply_alpha_production.py"), str(ROOT)],
    check=True,
)


def replace_exact(path_rel: str, old: str, new: str):
    path = ROOT / path_rel
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise RuntimeError(f"Expected source block not found in {path_rel}. Source may have changed.")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")
    print(f"Updated: {path_rel}")


def copy_file(source_rel: str, target_rel: str):
    source = V11_DIR / source_rel
    target = ROOT / target_rel
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, target)
    print(f"Copied: {target_rel}")


# 1) Advance the development release while preserving the 1.0.0 production baseline.
replace_exact("collect_app/build.gradle", "versionCode 4151", "versionCode 4152")
replace_exact("collect_app/build.gradle", "versionName '1.0.0'", "versionName '1.1.0-dev1'")

# 2) Add Alpha Field Operations source, resources and focused parser tests.
copy_file(
    "src/AlphaCsvParser.kt",
    "collect_app/src/main/java/org/odk/collect/android/alpha/AlphaCsvParser.kt",
)
copy_file(
    "src/AlphaFieldDatabase.kt",
    "collect_app/src/main/java/org/odk/collect/android/alpha/AlphaFieldDatabase.kt",
)
copy_file(
    "src/AlphaFieldOpsActivity.kt",
    "collect_app/src/main/java/org/odk/collect/android/alpha/AlphaFieldOpsActivity.kt",
)
copy_file(
    "res/layout/alpha_field_ops_activity.xml",
    "collect_app/src/main/res/layout/alpha_field_ops_activity.xml",
)
copy_file(
    "res/values/alpha_field_ops_strings.xml",
    "collect_app/src/main/res/values/alpha_field_ops_strings.xml",
)
copy_file(
    "test/AlphaCsvParserTest.kt",
    "collect_app/src/test/java/org/odk/collect/android/alpha/AlphaCsvParserTest.kt",
)

# 3) Register the Field Operations activity.
replace_exact(
    "collect_app/src/main/AndroidManifest.xml",
    '        <activity android:name="org.odk.collect.android.activities.CrashHandlerActivity" />',
    '''        <activity
            android:name=".alpha.AlphaFieldOpsActivity"
            android:exported="false" />

        <activity android:name="org.odk.collect.android.activities.CrashHandlerActivity" />'''
)

# 4) Expose Alpha Field Operations on the main menu.
replace_exact(
    "collect_app/src/main/res/layout/main_menu.xml",
    '''            <org.odk.collect.android.mainmenu.StartNewFormButton
                android:id="@+id/enter_data"''',
    '''            <org.odk.collect.android.mainmenu.MainMenuButton
                android:id="@+id/alpha_field_ops"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_marginTop="@dimen/margin_extra_small"
                app:icon="@drawable/ic_edit_24"
                app:layout_constraintEnd_toEndOf="parent"
                app:layout_constraintStart_toStartOf="parent"
                app:layout_constraintTop_toBottomOf="@id/alpha_brand_tagline"
                app:layout_constraintWidth_max="@dimen/max_content_width"
                app:name="@string/alpha_field_ops" />

            <org.odk.collect.android.mainmenu.StartNewFormButton
                android:id="@+id/enter_data"'''
)
replace_exact(
    "collect_app/src/main/res/layout/main_menu.xml",
    'app:layout_constraintTop_toBottomOf="@id/alpha_brand_tagline" />',
    'app:layout_constraintTop_toBottomOf="@id/alpha_field_ops" />'
)

# 5) Launch the new activity from the generated view binding.
replace_exact(
    "collect_app/src/main/java/org/odk/collect/android/mainmenu/MainMenuFragment.kt",
    "import org.odk.collect.android.activities.DeleteFormsActivity",
    "import org.odk.collect.android.alpha.AlphaFieldOpsActivity\nimport org.odk.collect.android.activities.DeleteFormsActivity"
)
replace_exact(
    "collect_app/src/main/java/org/odk/collect/android/mainmenu/MainMenuFragment.kt",
    '''    private fun initButtons(binding: MainMenuBinding) {
        binding.enterData.setOnClickListener {''',
    '''    private fun initButtons(binding: MainMenuBinding) {
        binding.alphaFieldOps.setOnClickListener {
            startActivity(Intent(requireContext(), AlphaFieldOpsActivity::class.java))
        }

        binding.enterData.setOnClickListener {'''
)

print("\nAlpha Collect 1.1 development customization applied.")
print("Version: 1.1.0-dev1 (4152)")
print("Features: field assignment, device identity, offline SQLite master frame, local lookup and duplicate-risk checks.")
print("The 1.0.0 production branch and APK are unchanged.")
