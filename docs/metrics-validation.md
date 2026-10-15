# Metrics acceptance validation matrix (task 4, stage C)

Device E2E proof of the Health Connect to Garmin Connect weigh-in pipeline on a
real device with a real Garmin account. Produced by task
`tefal2garmin-device-e2e-metrics-matrix-epp` (stage C); validation only, no code
changes in this task.

## Test setup

| Item | Value |
|---|---|
| Date | 2026-10-15 |
| Device | Pixel 8 Pro (husky), serial 39261FDJG004CJ, Android beta |
| App | com.zamotkin.extrahealthgarmin 2.0.0 (versionCode 2, debug build) |
| Garmin auth | Logged in (UI: "Garmin подключен"); token pre-existing from stage B |
| HC permissions | All 7 read permissions granted (UI: "Health Connect подключен") |
| HC data origin | User's real scale-app history, 30-day backfill 2026-09-15 to 2026-10-13 |

## Upload evidence (stage C step 1)

First "Синхронизировать" tap at 12:06:52 local. UI after ~30 s:

- `Загружено измерений в Garmin Connect: 38`
- `Последняя синхронизация: 15.10.2026 12:06`

Logcat (verbatim, IP and account owner id redacted):

```
10-15 12:06:54.451 D/GarminHttp(18074): --> POST https://connectapi.garmin.com/upload-service/upload
10-15 12:06:54.452 D/GarminHttp(18074): Content-Disposition: form-data; name="file"; filename="body_composition.fit"
10-15 12:06:54.452 D/GarminHttp(18074): Content-Type: application/octet-stream
10-15 12:06:54.452 D/GarminHttp(18074): Content-Length: 627
10-15 12:06:55.671 D/GarminHttp(18074): <-- 201 {"detailedImportResult":{"uploadId":493988484706,
  "uploadUuid":{"uuid":"2f8be4c4-342e-453b-bb77-fa953dece622"},"owner":<redacted>,"fileSize":627,
  "processingTime":482,"creationDate":"2026-10-15 09:06:54.176 GMT","ipAddress":"<redacted>",
  "fileName":"body_composition.fit","report":null,
  "successes":[{"messages":[{"code":200,"content":"File processed."}]}],"failures":[]}}
10-15 12:06:55.678 D/GarminRepository(18074): Upload: 38 records confirmed by Garmin
```

Garmin accepted the single FIT file (HTTP 201, zero failures) containing all 38
weigh-ins of the 30-day backfill. Note: the upload client logs under tags
`GarminHttp` / `GarminRepository` / `GarminUpload` / `GarminJournal`; there is no
`GarminUploadClient` tag.

## Metrics matrix

All metrics travel in the SAME `body_composition.fit` upload (one
`WeightScaleMesg` per weigh-in), so "Uploaded?" is proven once by the 201
response above for every row. "Visible in Garmin Connect?" is pending the user's
check of the latest weigh-in detail in the Garmin Connect app.

| # | Metric | HC record field (Health Connect) | DTO field (`WeighInMeasurement` / `GarminWeighInRecord`) | FIT `WeightScaleMesg` field (scale) | Uploaded? | Visible in Garmin Connect? | Evidence |
|---|---|---|---|---|---|---|---|
| 1 | Measurement time | `WeightRecord.time` (anchor) | `timestamp` / `epochSecondsUtc` | `timestamp` (field 253, s) | Yes: 38 records, 201 "File processed.", 0 failures | USER_CONFIRM_PENDING | GarminHttp 12:06:55.671; GarminRepository 12:06:55.678 |
| 2 | Weight | `WeightRecord.weight.inKilograms` (anchor) | `weightKg` | `weight` (field 0, scale 100, kg) | Yes (same FIT file) | USER_CONFIRM_PENDING | same as row 1 |
| 3 | Body fat % | `BodyFatRecord.percentage` (companion, +/-60 s) | `percentFat` | `percent_fat` (field 1, scale 100, %) | Yes (same FIT file) | USER_CONFIRM_PENDING | same as row 1 |
| 4 | Water % | `BodyWaterMassRecord.mass.inKilograms` converted `waterKg / weightKg * 100` | `bodyWaterPercent` | `percent_hydration` (field 2, scale 100, %) | Yes (same FIT file) | USER_CONFIRM_PENDING | same as row 1 |
| 5 | Bone mass | `BoneMassRecord.mass.inKilograms` (companion) | `boneMassKg` | `bone_mass` (field 4, scale 100, kg) | Yes (same FIT file) | USER_CONFIRM_PENDING | same as row 1 |
| 6 | Muscle mass | `LeanBodyMassRecord.mass.inKilograms` (companion); naming bridge: HC lean mass maps to FIT `muscle_mass` | `leanBodyMassKg` | `muscle_mass` (field 5, scale 100, kg) | Yes (same FIT file) | USER_CONFIRM_PENDING | same as row 1 |
| 7 | BMR | `BasalMetabolicRateRecord` kcal/day (companion) | `basalMetabolicRateKcal` | `basal_met` (field 7, scale 4, kcal/day) | Yes (same FIT file) | USER_CONFIRM_PENDING | same as row 1 |
| 8 | BMI | computed: latest `HeightRecord` cached by engine, `weightKg / heightM^2` | `bmi` | `bmi` (field 13, scale 10) | Yes (same FIT file), when a height record exists | USER_CONFIRM_PENDING | same as row 1 |

Null fields are never encoded as zero (`FitWeightFileBuilder` sets a FIT field
only when the DTO value is non-null); ranges are validated before encoding
(`GarminWeighInRecord.validateRanges`).

USER_CONFIRM_PENDING: the agent cannot read the Garmin Connect app; the user was
asked to open Garmin Connect -> Daily/Вес -> latest weigh-in detail and report
which of Вес / Жир % / ИМТ / Костная масса / Мышечная масса / Вода % / BMR are
shown. Fill this column from the answer; a metric not shown there is a follow-up
decision (may mean dropping the field), not a code change in this task.

## Sync state file (app-private, `files/hc-sync-state.json`)

| Point in test | changesToken | lastSync (local) | processedRecordIds |
|---|---|---|---|
| Before first sync | file absent | "Синхронизация еще не выполнялась" | 0 |
| After first sync (12:06) | 8423 | 12:06:54 | 39 |
| After dedup taps (12:08-12:09) | 8423 | 12:08:55 | 39 |
| After user's own syncs (12:16, 12:22) | 8423 | 12:22:47 | 39 |
| Offline sync attempt (12:34:01, airplane ON) | 8423 | 12:34:02 | 39 |
| Recovery sync (12:34:54, airplane OFF) | 8423 | 12:34:56 | 39 |

The token never advanced on any no-op attempt; `lastSync` advances only on
terminal sync outcomes. Upload journal (`files/upload_journal.json`) holds 38
keys `epochSeconds:weightKg`, range 2026-09-15 20:50 to 2026-10-13 08:42 local,
weights 83.75-87.95 kg.

39 processed IDs vs 38 uploads explained: two HC weight anchors shared the same
timestamp+weight, so `UploadJournal.filterNew` (journal key `epoch:weight`)
collapsed them into one uploaded record while both anchor IDs are marked
processed, which matches the design.

## AC-3 dedup evidence (5x sync)

Five "Синхронизировать" taps, 5 s apart, 12:08:32-12:08:53:

- Upload POST count during the phase: 0
- UI after: `Все измерения уже в Garmin Connect — загружать нечего`
- Journal entry count stable at 38; processedRecordIds stable at 39
- Two extra user-driven syncs (12:16:04, 12:22:47) also produced 0 uploads

## AC-2 offline cycle evidence

1. `adb shell cmd connectivity airplane-mode enable` (12:33), confirmed
   `airplane_mode_on=1`; sync tap at 12:34:01.
2. Result: no crash (app PID 18074 unchanged), sync completed as a no-op
   (`Все измерения уже в Garmin Connect — загружать нечего`, lastSync 12:34:02).
   Because the journal already covers every record, the engine never contacts
   Garmin, so an offline upload failure is structurally impossible here; the
   spec anticipates this ("if no new data, dedup covers"). The
   retryable-failure UI path is therefore covered by unit tests only, not
   on-device.
3. changesToken unchanged (8423) after the offline attempt.
4. Airplane OFF; Wi-Fi revalidated within 3 s; sync tap at 12:34:54 gave a clean
   no-op success (0 upload POSTs), token still 8423, 39 records.

## Open items

- Visible-in-Garmin-Connect column: awaiting user answer (USER_CONFIRM_PENDING).
- New-data ingestion path: intentionally not exercised on-device (user confirmed
  no new weigh-ins will be recorded); the 30-day backfill already proves the
  full ingest-aggregate-upload chain, and the deferred-young-anchor logic is
  unit-tested.
