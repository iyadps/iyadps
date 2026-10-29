# Alpha Collect 1.1 Development MVP

This branch extends the verified Alpha Collect 1.0.0 baseline without changing the 1.0.0 production release.

## Implemented in dev1

### Alpha Field Operations
- Local project ID, enumerator ID, supervisor ID and assigned EA ID.
- Short device identifier derived from Android's device-scoped ANDROID_ID.
- Assignment data stored locally in app-private preferences.

### Offline master frame
- Import a CSV from Android's document picker.
- Store the frame in an app-private SQLite database.
- Optimized indexes for EA, record ID and normalized phone.
- Designed for large frames without loading the whole file into memory.

### Lookup and duplicate-risk foundation
- Search by record/building/household/establishment ID or phone.
- Restrict search to the currently assigned EA.
- Flag HIGH duplicate risk when the same record ID or normalized phone appears more than once.
- Show GPS and case status when those fields exist in the frame.

## CSV contract

Required columns:
- record_id
- ea_id

Accepted aliases include:
- record_id: recordid, building_id, household_id, establishment_id, id
- ea_id: eaid, enumeration_area, enumeration_area_id, ea

Optional columns:
- latitude (aliases: lat, y)
- longitude (aliases: lon, lng, long, x)
- phone (aliases: phone_number, mobile, telephone)
- status (aliases: visit_status, case_status)

See `sample_master_frame.csv`.

## Version

- Package: `com.alphainternational.collect`
- Version name: `1.1.0-dev1`
- Version code: `4152`

## Deliberately not yet implemented

This dev1 build does not yet:
- block forms outside the assigned EA;
- inject assignment fields automatically into an XLSForm;
- compare current live GPS against the master-frame coordinates;
- synchronize assignment/frame data from an Alpha server;
- enforce supervisor/admin RBAC;
- encrypt the master-frame database with a separate application-level database key;
- perform server-side cross-device duplicate detection.

Those are later 1.1 milestones after dev1 validates the offline data model and enumerator workflow.

## Safety and privacy

Background audio behavior remains unchanged from Alpha Collect 1.0.0: the recording controller is hidden for background-audio forms, but recording continues through Android's foreground microphone service and Android's system privacy indicators remain intact.

The master frame is stored in app-private Android storage. Production hardening should add explicit encrypted-database key management before storing high-risk PII at scale.
