# Metrics acceptance validation — Health Connect -> Garmin Connect

Validated 2026-10-15 on device (39261FDJG004CJ), app v2.0.0 (versionCode 2), real Garmin
account, real Health Connect data (30-day backfill, one FIT upload). Method: the app uploaded
one `weight_scale` FIT file carrying all candidate fields (scales verified against
com.garmin:fit:21.176.0 in task 2a); the account owner then inspected the latest weigh-in
detail in the Garmin Connect mobile app and reported which metrics are displayed.

## Matrix

| HC record -> DTO field | FIT WeightScaleMesg field (scale) | Uploaded | Visible in Garmin Connect | Evidence |
|---|---|---|---|---|
| WeightRecord -> weightKg | weight (100) | yes | **YES** | owner-confirmed in Connect, 2026-10-15 |
| BodyFatRecord -> percentFat | percentFat (100) | yes | **YES** | owner-confirmed |
| computed from HeightRecord -> bmi | bmi (10) | yes | **YES** | owner-confirmed |
| BoneMassRecord -> boneMassKg | boneMass (100, field 4) | yes | **YES** | owner-confirmed |
| BodyWaterMassRecord -> bodyWaterPercent | percentHydration (100, field 2) | yes | **YES** | owner-confirmed ("вода в организме") |
| LeanBodyMassRecord -> leanBodyMassKg | muscleMass (100, field 5) | yes | **no** | owner: not displayed |
| BasalMetabolicRateRecord -> basalMetabolicRateKcal | basalMet (4, field 7) | yes | **no** | owner: not displayed |

## Conclusions

- 5 of 7 candidate metrics are confirmed accepted AND displayed: weight, body fat, BMI,
  bone mass, body water percent. The extended-metric bet of the PRD paid off for bone mass
  and hydration in particular.
- muscle mass and BMR ride the same FIT file (upload-service accepts the file; per-record
  import reported success) but Garmin Connect does not surface them on the weigh-in screen.
  Not provably "rejected" — possibly stored without UI, or ignored at field level.

## Follow-up hypotheses (not in this epic's scope)

- Muscle mass: FIT also has `muscle_mass_skeletal` (WeightScaleMesg skeletal-muscle field);
  scales usually report skeletal muscle. Switching leanBodyMass mapping from `muscleMass`
  to `muscleMassSkeletal` may make it visible. Cheap experiment: change mapping, re-weigh or
  re-sync one record, check Connect.
- BMR: Garmin derives metabolism itself; a per-weigh-in BMR display may simply not exist in
  Connect. Keeping `basalMet` in the file is harmless either way.

Decision for now: keep sending all seven fields (same FIT file, no extra cost, no observed
rejections); revisit only if Garmin ever signals per-field failures in detailedImportResult.

## Run evidence

- Backfill upload 12:06:54: `POST connectapi.garmin.com/upload-service/upload` ->
  `201`, body_composition.fit (627 bytes), `"successes":[{"messages":[{"code":200,
  "content":"File processed."}]}],"failures":[]`; "Загружено измерений в Garmin Connect: 38".
- Dedup (AC-3): 5 sync taps + 2 user-driven syncs -> **0 upload POSTs**; UI "Все измерения
  уже в Garmin Connect — загружать нечего"; journal fixed at 38 keys.
- The 39-processed vs 38-uploaded delta: two HC anchors shared timestamp+weight;
  `UploadJournal.filterNew` batch-dedup collapsed them — both marked processed, by design.
- Offline cycle (AC-2): airplane on -> sync no-op clean (journal covered all; no crash,
  PID stable); airplane off -> recovery sync clean. Sync state via run-as:

| Point | token | lastSync | processed |
|---|---|---|---|
| after upload (12:06) | 8423 | 12:06:54 | 39 |
| after dedup (12:08) | 8423 | 12:08:55 | 39 |
| offline attempt (12:34) | 8423 | 12:34:02 | 39 |
| recovery (12:34) | 8423 | 12:34:56 | 39 |

  Token never advanced on any attempt without new data. The retryable-error branch (new
  data + network down) remains covered by unit tests (SyncEngineTest) — on-device it cannot
  fire once the journal covers everything.
- Journal span: 38 keys `epoch:weight`, 2026-09-15 20:50 -> 2026-10-13 08:42 local,
  weights 83.75-87.95 kg (real scale history, 30-day window).
- Owner Garmin Connect inspection answered the visibility column above.
