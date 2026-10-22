from pathlib import Path
import subprocess
import sys

ROOT = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path.cwd()
SCRIPT_DIR = Path(__file__).resolve().parent

# Apply the already-tested Alpha branding, package, and background-audio changes.
subprocess.run(
    [sys.executable, str(SCRIPT_DIR / "apply_alpha_customization.py"), str(ROOT)],
    check=True,
)


def replace_all(path_rel: str, old: str, new: str):
    path = ROOT / path_rel
    if not path.exists():
        raise FileNotFoundError(f"Missing expected file: {path}")
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise RuntimeError(
            f"Expected text not found in {path_rel}. Source may have changed."
        )
    count = text.count(old)
    path.write_text(text.replace(old, new), encoding="utf-8")
    print(f"Updated: {path_rel} ({count} replacements)")


def replace_exact(path_rel: str, old: str, new: str):
    path = ROOT / path_rel
    if not path.exists():
        raise FileNotFoundError(f"Missing expected file: {path}")
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise RuntimeError(
            f"Expected source text not found in {path_rel}. Source may have changed."
        )
    path.write_text(text.replace(old, new, 1), encoding="utf-8")
    print(f"Updated: {path_rel}")


# The release source set has its own Firebase configuration and must match
# Alpha's production application ID.
replace_all(
    "collect_app/src/release/google-services.json",
    '"package_name": "org.odk.collect.android"',
    '"package_name": "com.alphainternational.collect"',
)

# Alpha production version. Increment versionCode for every future production
# release that is signed with Alpha's permanent signing certificate.
replace_exact(
    "collect_app/build.gradle",
    "versionCode 4150",
    "versionCode 4151",
)
replace_exact(
    "collect_app/build.gradle",
    "versionName getVersionName()",
    "versionName '1.0.0'",
)

print("\nAlpha Collect production customization applied successfully.")
print("Application ID: com.alphainternational.collect")
print("Version name: 1.0.0")
print("Version code: 4151")
print("Production signing key is intentionally NOT stored in this repository.")
