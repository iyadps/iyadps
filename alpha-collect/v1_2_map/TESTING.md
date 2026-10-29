# Alpha Collect 1.2.0-test2 — tablet acceptance

Identity: Alpha Collect Test; com.alphainternational.collect.test; versionCode 4154.
Signed with the pinned Kobo development certificate, not a production certificate.
Installs alongside Alpha Collect 1.0.0. It updates earlier Alpha Collect Test builds
only if their certificate matches. Back up test data before replacing an installation.
Never uninstall the production app to install this test.

## Offline map test

1. Record production version and confirm its existing forms are present.
2. Install the test APK. Confirm both app icons remain and production forms remain intact.
3. In Test > Alpha Field Operations, save project DEMO, enumerator EN1, EA EA-DEMO.
4. Import sample_master_frame.csv (synthetic data; replaces the TEST master frame).
5. Open offline EA map. Expect 8 points, one case without GPS, and no OTHER-EA case.
6. Import sample_ea.geojson. Expect a green EA polygon and larger purple dashed bounds.
7. Enable airplane mode, close and reopen the app. The polygon, bounds and markers must remain.
8. Drag, pinch, use +/− and Fit. Tap markers and check IDs/status/GPS. HH-004 and HH-008
   overlap: a chooser must appear. Choose case from list also exposes the missing-GPS case.
9. Use selected case returns to Field Operations. Record a visit status, reopen map, verify color changes.
10. Select a case in Field Operations before opening the map; verify its black selection ring.
11. Locate outdoors with device location enabled. Verify blue point and reported accuracy.
    This is an explicit snapshot, not continuous tracking. GPS can work in airplane mode
    when device location remains enabled. Deny permission and disable location separately;
    confirm useful messages. Leave the screen during acquisition; updates must stop.
12. Import invalid JSON, wrong-EA file, invalid coordinates and an unclosed ring. Expect an
    error and the previously imported map retained. Change saved EA: previous EA overlays
    and cases must disappear. Restore EA-DEMO: its imported overlay must return.
13. Rotate the tablet; map reloads and selected ID persists. Capture GPS again after rotation.
14. Confirm production app and data are still intact.

## Interview/audio regression — required on a real tablet

Configure a test Kobo project. Download a form with background audio, start it, grant
microphone permission, answer questions while speaking, save/resume, finalize and submit.
Confirm existing controller behavior, Android microphone indicator, and a playable audio
attachment in Kobo. Test offline save then later submission. Automated audio tests do not
replace this physical device and server check.

## Import contract and limits

Map: WGS84 GeoJSON Feature or FeatureCollection. Each applicable feature must have
properties.ea_id exactly equal to the saved EA. Geometry may be Polygon, MultiPolygon
(including holes), or null when bbox is supplied. Feature bbox is [west,south,east,north].
Multiple feature bboxes can represent map-layer extents. Collection-level bbox is ignored
because it may cover other EAs. No coordinates are inferred from a household point cloud.
GeoJSON positions are [longitude,latitude]; CSV uses named latitude/longitude columns.
Split antimeridian polygons/bounds before import. Files <= 1 MB and <= 20,000 matching
vertices. Imports are stored locally by saved project and EA. Missing/invalid household
coordinates are counted and omitted from the map, but remain selectable in the list.
Duplicate record IDs in an EA can be inspected but cannot be returned as a visit target.

The map is an offline vector overlay viewer with a neutral background. It does not
download streets, satellite imagery, MBTiles, routes, or server assignments. Bounds show
coverage extents, not map imagery. Existing Kobo map/question behavior is unchanged.
Assignment and master-frame storage retain the existing 1.2 model; only one master frame
is active at a time. A large EA can make the all-case list and marker rendering slower.

Implementation APIs: Android Canvas, ScaleGestureDetector and LocationManager; GeoJSON
RFC 7946. Sources: https://developer.android.com/reference/android/view/ScaleGestureDetector
https://developer.android.com/reference/android/location/LocationManager
https://datatracker.ietf.org/doc/html/rfc7946

Automated CI gates: existing CSV, field rules and audio tests, map parser/projection/hit
tests and database tests, signed APK build, package/version/permissions verification,
signature verification, zip alignment and SHA-256. See CI artifacts for actual results.
Real-tablet acceptance remains pending until the steps above are performed.
