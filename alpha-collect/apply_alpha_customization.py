from pathlib import Path
import sys

ROOT = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path.cwd()


def replace_exact(path_rel: str, old: str, new: str):
    path = ROOT / path_rel
    if not path.exists():
        raise FileNotFoundError(f"Missing expected file: {path}")
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise RuntimeError(f"Expected source block not found in {path_rel}. Source may have changed.")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")
    print(f"Updated: {path_rel}")


def replace_all(path_rel: str, old: str, new: str):
    path = ROOT / path_rel
    if not path.exists():
        raise FileNotFoundError(f"Missing expected file: {path}")
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise RuntimeError(f"Expected text not found in {path_rel}. Source may have changed.")
    count = text.count(old)
    path.write_text(text.replace(old, new), encoding="utf-8")
    print(f"Updated: {path_rel} ({count} replacements)")


# 1) Hide the large in-form controller ONLY for XLSForm background-audio.
# Recording, foreground service and media attachment handling are left unchanged.
path = "collect_app/src/main/java/org/odk/collect/android/audio/AudioRecordingControllerFragment.java"
old = """        if (session != null) {
            if (session.getFile() == null) {
                binding.getRoot().setVisibility(VISIBLE);
                renderRecordingInProgress(session, hasBackgroundRecording);
            } else {
                binding.getRoot().setVisibility(GONE);
            }
"""
new = """        if (session != null) {
            if (session.getFile() == null) {
                if (hasBackgroundRecording) {
                    // Alpha customization: background-audio remains active but the
                    // in-form mic/time/volume/help controller is intentionally hidden.
                    binding.getRoot().setVisibility(GONE);
                } else {
                    binding.getRoot().setVisibility(VISIBLE);
                    renderRecordingInProgress(session);
                }
            } else {
                binding.getRoot().setVisibility(GONE);
            }
"""
replace_exact(path, old, new)

old = """    private void renderRecordingInProgress(RecordingSession session, boolean hasBackgroundRecording) {
        binding.timeCode.setText(LengthFormatterKt.formatLength(session.getDuration()));
        binding.volumeBar.addAmplitude(session.getAmplitude());

        if (hasBackgroundRecording) {
            binding.controls.setVisibility(GONE);
            binding.help.setVisibility(VISIBLE);
        } else {
            renderControls(session);
        }
    }
"""
new = """    private void renderRecordingInProgress(RecordingSession session) {
        binding.timeCode.setText(LengthFormatterKt.formatLength(session.getDuration()));
        binding.volumeBar.addAmplitude(session.getAmplitude());
        binding.help.setVisibility(GONE);
        renderControls(session);
    }
"""
replace_exact(path, old, new)

# 2) Update focused tests for Alpha background-audio behavior.
path = "collect_app/src/test/java/org/odk/collect/android/audio/AudioRecordingControllerFragmentTest.java"
old = """    @Test
    public void whenFormHasBackgroundRecording_hidesControls() {
        hasBackgroundRecording.setValue(true);
        audioRecorder.start("session", Output.AAC);

        FragmentScenario<AudioRecordingControllerFragment> scenario = launcherRule.launchInContainer(AudioRecordingControllerFragment.class);
        scenario.onFragment(fragment -> {
            assertThat(fragment.binding.controls.getVisibility(), is(View.GONE));
        });
    }

    @Test
    public void whenFormHasBackgroundRecording_clickingHelpButton_opensHelpDialog() {
        hasBackgroundRecording.setValue(true);
        audioRecorder.start("session", Output.AAC);

        FragmentScenario<AudioRecordingControllerFragment> scenario = launcherRule.launchInContainer(AudioRecordingControllerFragment.class);
        scenario.onFragment(fragment -> {
            assertThat(fragment.binding.help.getVisibility(), is(View.VISIBLE));

            fragment.binding.help.performClick();
            BackgroundAudioHelpDialogFragment dialog = getFragmentByClass(fragment.getParentFragmentManager(), BackgroundAudioHelpDialogFragment.class);
            assertThat(dialog, notNullValue());
        });
    }
"""
new = """    @Test
    public void whenFormHasBackgroundRecording_hidesRecordingControllerWhileRecordingContinues() {
        hasBackgroundRecording.setValue(true);
        audioRecorder.start("session", Output.AAC);

        FragmentScenario<AudioRecordingControllerFragment> scenario = launcherRule.launchInContainer(AudioRecordingControllerFragment.class);
        scenario.onFragment(fragment -> {
            assertThat(fragment.binding.getRoot().getVisibility(), is(View.GONE));
            assertThat(audioRecorder.isRecording(), is(true));
        });
    }

    @Test
    public void whenFormHasBackgroundRecording_doesNotShowHelpOrCompactIndicator() {
        hasBackgroundRecording.setValue(true);
        audioRecorder.start("session", Output.AAC);

        FragmentScenario<AudioRecordingControllerFragment> scenario = launcherRule.launchInContainer(AudioRecordingControllerFragment.class);
        scenario.onFragment(fragment -> {
            assertThat(fragment.binding.getRoot().getVisibility(), is(View.GONE));
            assertThat(fragment.binding.help.getVisibility(), is(View.GONE));
        });
    }
"""
replace_exact(path, old, new)

# 3) Alpha name.
replace_exact(
    "strings/src/main/res/values/untranslated.xml",
    '<string name="collect_app_name" translatable="false">KoboCollect</string>',
    '<string name="collect_app_name" translatable="false">Alpha Collect</string>'
)

# 4) Separate package so Alpha Collect can coexist with official KoboCollect.
replace_exact(
    "collect_app/build.gradle",
    "applicationId('org.koboc.collect.android')",
    "applicationId('com.alphainternational.collect')"
)
replace_exact(
    "collect_app/build.gradle",
    "archivesBaseName = 'ODK-Collect'",
    "archivesBaseName = 'Alpha-Collect'"
)

# 5) Provider authorities must match the new package.
replace_exact(
    "collect_app/src/main/AndroidManifest.xml",
    'android:authorities="org.koboc.collect.android.provider.odk.forms"',
    'android:authorities="com.alphainternational.collect.provider.odk.forms"'
)
replace_exact(
    "collect_app/src/main/AndroidManifest.xml",
    'android:authorities="org.koboc.collect.android.provider.odk.instances"',
    'android:authorities="com.alphainternational.collect.provider.odk.instances"'
)
replace_exact(
    "collect_app/src/main/AndroidManifest.xml",
    'android:label="ODK Form"',
    'android:label="Alpha Form"'
)

replace_exact(
    "collect_app/src/main/java/org/odk/collect/android/external/FormsContract.java",
    'static final String AUTHORITY = "org.koboc.collect.android.provider.odk.forms";',
    'static final String AUTHORITY = "com.alphainternational.collect.provider.odk.forms";'
)
replace_exact(
    "collect_app/src/main/java/org/odk/collect/android/external/InstancesContract.java",
    'public static final String AUTHORITY = "org.koboc.collect.android.provider.odk.instances";',
    'public static final String AUTHORITY = "com.alphainternational.collect.provider.odk.instances";'
)

# 6) Google Services Gradle plugin validates applicationId at build time.
# Firebase analytics/crash reporting are already disabled in Kobo's manifest;
# this change makes the existing config compatible with the Alpha package.
for google_services in [
    "collect_app/google-services.json",
    "collect_app/src/debug/google-services.json",
]:
    replace_all(
        google_services,
        '"package_name": "org.koboc.collect.android"',
        '"package_name": "com.alphainternational.collect"'
    )

print("\nAlpha Collect customization applied successfully.")
print("Application ID: com.alphainternational.collect")
print("Background recording engine and submission attachment saving were not modified.")
print("Android foreground-service recording notification remains enabled.")
