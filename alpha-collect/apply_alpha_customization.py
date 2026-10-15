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


def write_text(path_rel: str, content: str):
    path = ROOT / path_rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")
    print(f"Wrote: {path_rel}")


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


# 7) Alpha International visual branding derived from the supplied official logo.
# Brand colors sampled from the supplied mark: dark #01559D, bright #017CC3.

write_text(
    "collect_app/src/main/res/drawable/alpha_logo_mark.xml",
    """<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path android:fillColor="#01559D" android:pathData="M54,20 L8,84 L32,84 L54,54 Z"/>
    <path android:fillColor="#01559D" android:pathData="M54,20 L100,84 L76,84 L54,54 Z"/>

    <path android:fillColor="#017CC3" android:pathData="M54,20 L54,38 L43,32 Z"/>
    <path android:fillColor="#0C84C4" android:pathData="M43,32 L54,38 L33,45 Z"/>
    <path android:fillColor="#124879" android:pathData="M33,45 L54,38 L54,54 L22,60 Z"/>
    <path android:fillColor="#017CC3" android:pathData="M22,60 L32,84 L8,84 Z"/>
    <path android:fillColor="#01559D" android:pathData="M54,54 L32,84 L22,60 Z"/>

    <path android:fillColor="#0C84C4" android:pathData="M54,20 L66,36 L54,38 Z"/>
    <path android:fillColor="#01559D" android:pathData="M66,36 L79,54 L54,38 Z"/>
    <path android:fillColor="#017CC3" android:pathData="M54,38 L79,54 L54,54 Z"/>
    <path android:fillColor="#0C84C4" android:pathData="M54,54 L79,54 L88,69 L63,66 Z"/>
    <path android:fillColor="#01559D" android:pathData="M63,66 L88,69 L100,84 L76,84 Z"/>
</vector>
"""
)

write_text(
    "collect_app/src/main/res/drawable/alpha_launcher_icon.xml",
    """<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path android:fillColor="#FFFFFF"
        android:pathData="M54,2 A52,52 0,1 1 53.99,2 Z"/>
    <path android:fillColor="#01559D" android:pathData="M54,22 L14,80 L35,80 L54,54 Z"/>
    <path android:fillColor="#01559D" android:pathData="M54,22 L94,80 L73,80 L54,54 Z"/>
    <path android:fillColor="#017CC3" android:pathData="M54,22 L54,38 L44,33 Z"/>
    <path android:fillColor="#0C84C4" android:pathData="M44,33 L54,38 L35,45 Z"/>
    <path android:fillColor="#124879" android:pathData="M35,45 L54,38 L54,54 L25,59 Z"/>
    <path android:fillColor="#017CC3" android:pathData="M25,59 L35,80 L14,80 Z"/>
    <path android:fillColor="#0C84C4" android:pathData="M54,22 L65,36 L54,38 Z"/>
    <path android:fillColor="#01559D" android:pathData="M65,36 L76,52 L54,38 Z"/>
    <path android:fillColor="#017CC3" android:pathData="M54,38 L76,52 L54,54 Z"/>
    <path android:fillColor="#0C84C4" android:pathData="M54,54 L76,52 L85,66 L63,64 Z"/>
    <path android:fillColor="#01559D" android:pathData="M63,64 L85,66 L94,80 L73,80 Z"/>
</vector>
"""
)

write_text(
    "collect_app/src/main/res/values/alpha_branding.xml",
    """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="alpha_brand_name" translatable="false">ALPHA INTERNATIONAL</string>
    <string name="alpha_brand_tagline" translatable="false">Research · Polling · Informatics</string>
</resources>
"""
)

# Use Alpha mark as the launcher icon for all supported Android versions.
replace_exact(
    "collect_app/src/main/AndroidManifest.xml",
    'android:icon="@mipmap/ic_launcher"',
    'android:icon="@drawable/alpha_launcher_icon"'
)
replace_exact(
    "collect_app/src/main/AndroidManifest.xml",
    'android:roundIcon="@mipmap/ic_launcher_round"',
    'android:roundIcon="@drawable/alpha_launcher_icon"'
)

# Android 12+ splash screen.
replace_exact(
    "collect_app/src/main/res/values/theme.xml",
    '<item name="windowSplashScreenAnimatedIcon">@drawable/ic_splash_screen_icon</item>',
    '<item name="windowSplashScreenAnimatedIcon">@drawable/alpha_launcher_icon</item>'
)

# First-launch screen: replace Kobo symbol with Alpha mark and official Alpha wording.
replace_exact(
    "collect_app/src/main/res/layout/first_launch_layout.xml",
    '''            <ImageView
                android:id="@+id/logo"
                android:layout_width="100dp"
                android:layout_height="wrap_content"
                android:adjustViewBounds="true"
                android:contentDescription="@string/collect_app_name"
                android:src="@drawable/kobologo_symbol_cropped"
                app:layout_constraintStart_toStartOf="parent"
                app:layout_constraintTop_toTopOf="parent" />''',
    '''            <ImageView
                android:id="@+id/logo"
                android:layout_width="140dp"
                android:layout_height="108dp"
                android:adjustViewBounds="true"
                android:contentDescription="@string/collect_app_name"
                android:src="@drawable/alpha_logo_mark"
                app:layout_constraintEnd_toEndOf="parent"
                app:layout_constraintStart_toStartOf="parent"
                app:layout_constraintTop_toTopOf="parent" />'''
)

replace_exact(
    "collect_app/src/main/res/layout/first_launch_layout.xml",
    'android:text="@string/tagline"',
    'android:text="@string/alpha_brand_name"'
)
replace_exact(
    "collect_app/src/main/res/layout/first_launch_layout.xml",
    'app:layout_constraintStart_toStartOf="parent"\n                app:layout_constraintTop_toBottomOf="@id/logo" />',
    'app:layout_constraintEnd_toEndOf="parent"\n                app:layout_constraintStart_toStartOf="parent"\n                app:layout_constraintTop_toBottomOf="@id/logo" />'
)

# Main menu branding above fieldwork actions.
replace_exact(
    "collect_app/src/main/res/layout/main_menu.xml",
    '''            <org.odk.collect.android.mainmenu.StartNewFormButton
                android:id="@+id/enter_data"''',
    '''            <ImageView
                android:id="@+id/alpha_brand_logo"
                android:layout_width="92dp"
                android:layout_height="72dp"
                android:layout_marginTop="@dimen/margin_standard"
                android:contentDescription="@string/alpha_brand_name"
                android:src="@drawable/alpha_logo_mark"
                app:layout_constraintEnd_toEndOf="parent"
                app:layout_constraintStart_toStartOf="parent"
                app:layout_constraintTop_toBottomOf="@+id/google_drive_deprecation_banner" />

            <TextView
                android:id="@+id/alpha_brand_name"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/alpha_brand_name"
                android:textAppearance="?textAppearanceSubtitle1"
                android:textColor="#01559D"
                app:layout_constraintEnd_toEndOf="parent"
                app:layout_constraintStart_toStartOf="parent"
                app:layout_constraintTop_toBottomOf="@id/alpha_brand_logo" />

            <TextView
                android:id="@+id/alpha_brand_tagline"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginBottom="@dimen/margin_extra_small"
                android:text="@string/alpha_brand_tagline"
                android:textAppearance="?textAppearanceCaption"
                android:textColor="#017CC3"
                app:layout_constraintEnd_toEndOf="parent"
                app:layout_constraintStart_toStartOf="parent"
                app:layout_constraintTop_toBottomOf="@id/alpha_brand_name" />

            <org.odk.collect.android.mainmenu.StartNewFormButton
                android:id="@+id/enter_data"'''
)

replace_exact(
    "collect_app/src/main/res/layout/main_menu.xml",
    'app:layout_constraintTop_toBottomOf="@id/google_drive_deprecation_banner" />',
    'app:layout_constraintTop_toBottomOf="@id/alpha_brand_tagline" />'
)

# Shift the main app palette from Kobo cyan to Alpha International blue.
replace_exact(
    "collect_app/src/main/res/values/colors.xml",
    '<color name="colorPrimaryLight">#3e9fcc</color>',
    '<color name="colorPrimaryLight">#01559D</color>'
)
replace_exact(
    "collect_app/src/main/res/values/colors.xml",
    '<color name="colorPrimaryContainerLight">#bfe9ff</color>',
    '<color name="colorPrimaryContainerLight">#D9EEFA</color>'
)
replace_exact(
    "collect_app/src/main/res/values/colors.xml",
    '<color name="colorPrimaryDark">#4cbcf0</color>',
    '<color name="colorPrimaryDark">#017CC3</color>'
)
replace_exact(
    "collect_app/src/main/res/values/colors.xml",
    '<color name="colorPrimaryContainerDark">#004d65</color>',
    '<color name="colorPrimaryContainerDark">#013B6D</color>'
)


print("\nAlpha Collect customization applied successfully.")
print("Application ID: com.alphainternational.collect")
print("Background recording engine and submission attachment saving were not modified.")
print("Android foreground-service recording notification remains enabled.")
print("Alpha International launcher, splash, first-launch and main-menu branding applied.")
