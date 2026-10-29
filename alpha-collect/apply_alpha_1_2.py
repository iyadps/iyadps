from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path.cwd()
SCRIPT_DIR = Path(__file__).resolve().parent
V12_DIR = SCRIPT_DIR / "v1_2"

subprocess.run(
    [sys.executable, str(SCRIPT_DIR / "apply_alpha_1_1.py"), str(ROOT)],
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
    source = V12_DIR / source_rel
    target = ROOT / target_rel
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, target)
    print(f"Copied: {target_rel}")


# Advance the isolated test application. This keeps the same test package/signing
# identity so 1.2 can update 1.1 Test without touching Alpha Collect 1.0.0.
replace_exact("collect_app/build.gradle", "versionCode 4152", "versionCode 4153")
replace_exact(
    "collect_app/build.gradle",
    "versionName '1.1.0-test1'",
    "versionName '1.2.0-test1'",
)

# Field-control layer: distance rules, upgraded local visit database,
# live GPS verification, visit status and revisit tracking.
copy_file(
    "src/AlphaFieldRules.kt",
    "collect_app/src/main/java/org/odk/collect/android/alpha/AlphaFieldRules.kt",
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
    "test/AlphaFieldRulesTest.kt",
    "collect_app/src/test/java/org/odk/collect/android/alpha/AlphaFieldRulesTest.kt",
)

print("\nAlpha Collect 1.2 test customization applied.")
print("Package: com.alphainternational.collect.test")
print("Version: 1.2.0-test1 (4153)")
print("Features: live GPS-to-case distance verification, configurable threshold, visit status and revisit log.")
print("GPS capture is event-based; no continuous enumerator tracking is introduced.")
