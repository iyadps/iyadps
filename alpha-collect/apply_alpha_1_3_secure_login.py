"""Apply Alpha Collect 1.3 secure first-run login on top of 1.2.1.

Requires ALPHA_COLLECT_PUBLIC_URL to be an actual Alpha-controlled HTTPS origin
for network-capable test builds. Never embeds Kobo API credentials.
"""
from pathlib import Path
import os
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET

root = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path.cwd()
source = Path(__file__).resolve().parent
subprocess.run([sys.executable,str(source/"apply_alpha_audio_ui_test.py"),str(root)],check=True)

url=os.environ.get("ALPHA_COLLECT_PUBLIC_URL","").strip().rstrip("/")
if not url.startswith("https://") or len(url)<12 or any(char in url for char in ('"',"<",">","'"," ")):
    raise SystemExit("ALPHA_COLLECT_PUBLIC_URL must be a valid Alpha HTTPS origin")
from urllib.parse import urlsplit
parsed=urlsplit(url)
if parsed.path or parsed.query or parsed.fragment or not parsed.hostname or parsed.username or parsed.password:
    raise SystemExit("ALPHA_COLLECT_PUBLIC_URL must be an HTTPS origin without path/credentials")

def replace(rel,old,new):
    path=root/rel
    body=path.read_text(encoding="utf-8")
    if body.count(old)!=1:
        raise RuntimeError(f"Expected a unique 1.2.1 integration point in {rel}: {old[:75]}")
    path.write_text(body.replace(old,new,1),encoding="utf-8")

replace("collect_app/build.gradle","versionCode 4155","versionCode 4156")
replace("collect_app/build.gradle","versionName '1.2.1-test1'","versionName '1.3.0-test1'")

first="collect_app/src/main/java/org/odk/collect/android/activities/FirstLaunchActivity.kt"
replace(first,"import org.odk.collect.android.databinding.FirstLaunchLayoutBinding",
        "import org.odk.collect.android.databinding.FirstLaunchLayoutBinding\nimport org.odk.collect.android.configure.qr.AppConfigurationGenerator\nimport org.odk.collect.projects.ProjectCreator")
replace(first,"    @Inject\n    lateinit var projectsRepository: ProjectsRepository",
        "    @Inject\n    lateinit var projectCreator: ProjectCreator\n\n    @Inject\n    lateinit var alphaConfig: AppConfigurationGenerator\n\n    @Inject\n    lateinit var projectsRepository: ProjectsRepository")
replace(first,"        lifecycle.addObserver(mdmConfigObserver)",
        "        lifecycle.addObserver(mdmConfigObserver)\n        if (savedInstanceState == null) {\n            AlphaFirstRunEnrollment(this, projectCreator, alphaConfig).show()\n        }")

destination=root/"collect_app/src/main/java/org/odk/collect/android/activities/AlphaFirstRunEnrollment.kt"
shutil.copyfile(source/"v1_3_secure_login"/"android"/"AlphaFirstRunEnrollment.kt",destination)

strings=root/"collect_app/src/main/res/values/alpha_server_config.xml"
xml_safe=(url.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;"))
strings.write_text('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
                   f'    <string name="alpha_server_url" translatable="false">{xml_safe}</string>\n'
                   '</resources>\n',encoding="utf-8")
print(f"Alpha Collect 1.3.0-test1 Android first-launch login applied to {parsed.hostname}")
print("Kobo token and API credentials are NOT included in the APK.")
