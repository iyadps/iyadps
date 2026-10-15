# Alpha Collect pilot APK build

This branch builds an installable **Alpha Collect** Android APK from the pinned KoboToolbox Collect source.

## Alpha changes

- App name: Alpha Collect
- Application ID: `com.alphainternational.collect`
- Can coexist with official KoboCollect
- XLSForm background-audio continues recording
- Large in-form microphone/time/volume/help controller is hidden during background-audio
- No compact replacement recording indicator is added
- Audio attachment saving remains unchanged
- Android foreground-service recording notification remains enabled

## Base source

KoboToolbox Collect commit:

`b8682768b4592f7105a7b7b278328726db4a282b`

## Signing

The pilot APK uses Kobo source's self-signed release configuration. It is suitable for controlled testing on Alpha tablets.

Before long-term fleet deployment, use an Alpha-owned private signing key and retain it securely for all future APK updates.
