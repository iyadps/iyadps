from pathlib import Path
import subprocess
import sys

ROOT = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path.cwd()
SCRIPT_DIR = Path(__file__).resolve().parent

subprocess.run(
    [sys.executable, str(SCRIPT_DIR / "apply_alpha_1_2.py"), str(ROOT)],
    check=True,
)

def replace_exact(path_rel: str, before: str, after: str):
    path = ROOT / path_rel
    content = path.read_text(encoding="utf-8")
    if before not in content:
        raise RuntimeError(f"Cannot find expected source block in {path_rel}")
    path.write_text(content.replace(before, after, 1), encoding="utf-8")
    print(f"Patched {path_rel}")

# Keep the controller invisible from the very first inflation/frame. When not
# in background-audio mode, the normal UI is shown only once an active manual
# recording session is observed.
replace_exact(
    "collect_app/src/main/res/layout/audio_recording_controller_fragment.xml",
    '''    android:orientation="horizontal"
    android:paddingVertical="@dimen/margin_extra_small"''',
    '''    android:orientation="horizontal"
    android:visibility="gone"
    android:paddingVertical="@dimen/margin_extra_small"'''
)

# All background-audio states (pending startup, recording, paused/reopened,
# disabled, and failed start) must keep the controller invisible. The required
# Android system privacy indicators and foreground recording service remain.
# An actual start failure still shows Kobo's AudioRecordingErrorDialogFragment.
controller_path = "collect_app/src/main/java/org/odk/collect/android/audio/AudioRecordingControllerFragment.java"
replace_exact(
    controller_path,
    '''        if (session != null) {
            if (session.getFile() == null) {''',
    '''        // Render no in-form UI for an XLSForm background-audio session, ever.
        // Record action/session state still runs normally; this is presentation only.
        // The extra session-ID check protects against the form metadata arriving
        // after the recorder session when opening/resuming a form.
        if (hasBackgroundRecording || (session != null && session.getId() instanceof java.util.Set)) {
            binding.getRoot().setVisibility(GONE);
            binding.controls.setVisibility(GONE);
            binding.help.setVisibility(GONE);
            return;
        }

        if (session != null) {
            if (session.getFile() == null) {'''
)

tests = "collect_app/src/test/java/org/odk/collect/android/audio/AudioRecordingControllerFragmentTest.java"
replace_exact(
    tests,
    '''    public void whenFormHasBackgroundRecording_andBackgroundRecordingIsDisabled_showsThatRecordingIsDisabled() {''',
    '''    public void whenFormHasBackgroundRecording_andBackgroundRecordingIsDisabled_neverDisplaysController() {'''
)
replace_exact(
    tests,
    '''        hasBackgroundRecording.setValue(true);
        isBackgroundRecordingEnabled.setValue(false);

        FragmentScenario<AudioRecordingControllerFragment> scenario = launcherRule.launchInContainer(AudioRecordingControllerFragment.class);
        scenario.onFragment(fragment -> {
            assertThat(fragment.binding.getRoot().getVisibility(), is(View.VISIBLE));
            assertThat(fragment.binding.timeCode.getText(), is(fragment.getString(org.odk.collect.strings.R.string.recording_disabled, "⋮")));''',
    '''        hasBackgroundRecording.setValue(true);
        isBackgroundRecordingEnabled.setValue(false);

        FragmentScenario<AudioRecordingControllerFragment> scenario = launcherRule.launchInContainer(AudioRecordingControllerFragment.class);
        scenario.onFragment(fragment -> {
            assertThat(fragment.binding.getRoot().getVisibility(), is(View.GONE));'''
)
replace_exact(
    tests,
    '''    public void whenFormHasBackgroundRecording_andThereIsAnError_andSessionIsOver_showsThatThereIsAnError() {''',
    '''    public void whenFormHasBackgroundRecording_andThereIsAnError_andSessionIsOver_hidesController() {'''
)
replace_exact(
    tests,
    '''        audioRecorder.start("blah", Output.AAC_LOW);
        audioRecorder.cleanUp();

        scenario.onFragment(fragment -> {
            assertThat(fragment.binding.getRoot().getVisibility(), is(View.VISIBLE));
            assertThat(fragment.binding.timeCode.getText(), is(fragment.getString(org.odk.collect.strings.R.string.start_recording_failed)));''',
    '''        audioRecorder.start("blah", Output.AAC_LOW);
        audioRecorder.cleanUp();

        scenario.onFragment(fragment -> {
            assertThat(fragment.binding.getRoot().getVisibility(), is(View.GONE));'''
)


# In background-audio mode only the container visibility is contractual;
# child-level waveform state is irrelevant when the root never renders.
replace_exact(
    tests,
    '''            assertThat(fragment.binding.getRoot().getVisibility(), is(View.GONE));
            assertThat(fragment.binding.volumeBar.getVisibility(), is(View.GONE));
            assertThat(fragment.binding.controls.getVisibility(), is(View.GONE));
            assertThat(fragment.binding.help.getVisibility(), is(View.GONE));
        });
    }

    @Test
    public void whenFormDoesNotHaveBackgroundRecording_andBackgroundRecordingIsDisabled_doesNotShowRecordingIsDisabled() {''',
    '''            assertThat(fragment.binding.getRoot().getVisibility(), is(View.GONE));
            assertThat(fragment.binding.controls.getVisibility(), is(View.GONE));
            assertThat(fragment.binding.help.getVisibility(), is(View.GONE));
        });
    }

    @Test
    public void whenFormDoesNotHaveBackgroundRecording_andBackgroundRecordingIsDisabled_doesNotShowRecordingIsDisabled() {'''
)
replace_exact(
    tests,
    '''            assertThat(fragment.binding.getRoot().getVisibility(), is(View.GONE));
            assertThat(fragment.binding.volumeBar.getVisibility(), is(View.GONE));
            assertThat(fragment.binding.controls.getVisibility(), is(View.GONE));
            assertThat(fragment.binding.help.getVisibility(), is(View.GONE));
        });
    }

    @Test
    public void whenFormDoesNotHaveBackgroundRecording_andThereIsAnError_andSessionIsOver_doesNotThatThereIsAnError() {''',
    '''            assertThat(fragment.binding.getRoot().getVisibility(), is(View.GONE));
            assertThat(fragment.binding.controls.getVisibility(), is(View.GONE));
            assertThat(fragment.binding.help.getVisibility(), is(View.GONE));
        });
    }

    @Test
    public void whenFormDoesNotHaveBackgroundRecording_andThereIsAnError_andSessionIsOver_doesNotThatThereIsAnError() {'''
)

# Prove initial visibility, startup transition, pause/resume and fragment recreation.
insert_before = '''    @Test
    public void whenFormDoesNotHaveBackgroundRecording_hidesHelpButton() {'''
new_tests = '''    @Test
    public void backgroundAudioController_isHiddenBeforeDuringAndAfterStart() {
        hasBackgroundRecording.setValue(true);
        isBackgroundRecordingEnabled.setValue(true);

        FragmentScenario<AudioRecordingControllerFragment> scenario = launcherRule.launchInContainer(AudioRecordingControllerFragment.class);
        scenario.onFragment(fragment -> {
            assertThat(fragment.binding.getRoot().getVisibility(), is(View.GONE));
            assertThat(fragment.binding.controls.getVisibility(), is(View.GONE));
        });

        audioRecorder.start("session", Output.AAC);
        scenario.onFragment(fragment -> {
            assertThat(fragment.binding.getRoot().getVisibility(), is(View.GONE));
            assertThat(audioRecorder.isRecording(), is(true));
        });

        audioRecorder.pause();
        scenario.onFragment(fragment -> {
            assertThat(fragment.binding.getRoot().getVisibility(), is(View.GONE));
        });

        audioRecorder.resume();
        scenario.onFragment(fragment -> {
            assertThat(fragment.binding.getRoot().getVisibility(), is(View.GONE));
            assertThat(audioRecorder.isRecording(), is(true));
        });

        scenario.recreate();
        scenario.onFragment(fragment -> {
            assertThat(fragment.binding.getRoot().getVisibility(), is(View.GONE));
            assertThat(audioRecorder.isRecording(), is(true));
        });
    }

    @Test
    public void backgroundAudioSessionBeforeFormFlag_isStillHidden() {
        // Background sessions are identified by the action TreeReference set.
        java.util.HashSet<Object> backgroundSession = new java.util.HashSet<>();
        backgroundSession.add("background");

        audioRecorder.start(backgroundSession, Output.AAC);
        FragmentScenario<AudioRecordingControllerFragment> scenario = launcherRule.launchInContainer(AudioRecordingControllerFragment.class);

        scenario.onFragment(fragment -> {
            assertThat(fragment.binding.getRoot().getVisibility(), is(View.GONE));
            assertThat(audioRecorder.isRecording(), is(true));
        });
    }

'''
replace_exact(tests, insert_before, new_tests + insert_before)

replace_exact("collect_app/build.gradle", "versionCode 4153", "versionCode 4154")
replace_exact(
    "collect_app/build.gradle",
    "versionName '1.2.0-test1'",
    "versionName '1.2.1-test1'"
)

print("Alpha Collect audio UI hardening applied (test 1.2.1 / versionCode 4154).")
print("Consent/permission prompts and OS microphone indicators remain untouched.")
