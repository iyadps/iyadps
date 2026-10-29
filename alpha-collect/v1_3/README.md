# Alpha Collect Test 1.3.0-test1 — Offline EA Map

**Build identity**
- Application label: Alpha Collect Test
- Package: `com.alphainternational.collect.test`
- Version: `1.3.0-test1`
- Version code: `4154`
- Signing: separate test signing identity; installs beside Alpha Collect 1.0.0 production.

## What this release adds

The new **View assigned EA map** button in Alpha Field Operations opens a native, interactive, entirely **offline vector map** of the selected EA's imported boundary and mapped households/buildings.

- Colored building markers based on locally stored status: blue/pending, green/completed, orange/revisit, red/refused, gray/other.
- Tap a marker to view case ID, status and coordinates; **Select building** returns to field operations and searches that exact case.
- Pinch to zoom, drag to pan, tap **Fit** to reset.
- **My GPS** acquires a location on demand (15-second timeout), showing the device's position and reported accuracy. No continuous tracking is performed.
- **Import EA CSV** imports EA polygon vertices to SQLite for offline display.
- Existing Kobo forms, audio recording behavior and Alpha 1.2 visit/GPS verification remain unchanged.

**Important scope:** This is a georeferenced, offline vector boundary/points view, **not an offline street/satellite basemap**. The first iteration has no imagery/road tiles. GPS verification and recording of visit status remain separate functions on the Field Operations screen, not on the map screen.

## Files needed

1. Master-frame CSV — load first under **Offline Master Frame**. Required columns: `record_id,ea_id`; for mapping each row also needs `latitude,longitude`. Optional: `phone,status`.
2. EA boundary vertices CSV — import from the map screen. Required columns: `ea_id,point_order,latitude,longitude`. One row per polygon vertex. Each EA must have at least 3 vertices, ordered sequentially. Polygon is automatically closed when drawn. Multiple EAs may be included.

Files must use decimal WGS84 latitude/longitude, and EA IDs must match exactly in assignment, master frame and boundary file. No arbitrary coordinate reprojection occurs in-app. Export real GIS polygons to WGS84 and ordered vertices first.

## Suggested tablet test

1. Install the APK beside Alpha Collect 1.0.0; keep the production copy.
2. In **Alpha Field Operations**, enter `EN001` and `EA-HB-TEST`, then save.
3. Import `sample_master_frame.csv` using the existing master-frame import.
4. Open **View assigned EA map** and import `sample_ea_boundaries.csv`.
5. Confirm the EA polygon and four case markers render without an internet connection.
6. Pinch/drag; use **Fit**. Tap a marker; select building; confirm the exact case appears in lookup.
7. Use **My GPS** (with location permission) and verify that the reported GPS and accuracy are plausible.
8. Change a visit to `completed`, return to map and press **Refresh**; verify the marker changes from blue to green.
9. Complete a normal Kobo test form, submit with background-audio enabled, and verify the audio attachment is playable in Kobo.

## Limits and risk controls

- Mapped cases are capped to the first **5,000 geocoded rows per EA** in the initial test build for performance. The summary shows mapped count and total EA count. Un-geocoded rows are not displayed.
- A CSV import replaces *all previous boundary vertices* inside the test app (transactionally). Retain the original CSV in Alpha's secured workspace.
- The test frame and boundaries are local to this test package and are not server-synchronized.
- No EA exclusion policy is enforced against Kobo forms yet. Assignment/lookup is advisory in this build; true RBAC, server assignment and hard geofencing are future milestones.
- The database uses app-private SQLite and must not be treated as a separately encrypted store; test with non-sensitive synthetic records until encryption/key management is implemented.
- Device GPS accuracy depends on Android/provider conditions and cannot be guaranteed by CI.

Source: `alpha-collect/apply_alpha_1_3.py`; CI: `.github/workflows/build-alpha-collect-1-3.yml`.
