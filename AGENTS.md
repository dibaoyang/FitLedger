# FitLedger update contract

- On 2026-10-17 the user selected icon concept 05 and authorized applying it to this existing app and packaging an update. Release target: 1.8.1 / versionCode 10. This is an icon-only release: preserve Room v6, backup v9, all migrations and all application data behavior. Preserve the original signing key, verify package/certificate compatibility with delivered APKs, and keep old release artifacts. The chosen icon source belongs under `artwork/launcher-icon-source.png`.

- On 2026-10-16 the user explicitly authorized packaging 1.8 / versionCode 9 and then adding offline nutrition-label photo recognition. Include all pending introduction, optional muscle, direct intake, first setup and food-code sharing changes. Use bundled Chinese OCR, camera/photo input, editable review before saving, no automatic food writes. This authorization supersedes the historical packaging/OCR holds below. Preserve the original signing key and the complete Room 1→6 migration chain.

- On 2026-10-15 the user requested a one-time introduction (including existing users), optional daily muscle entry, and direct macro intake. This batch is SOURCE/CHECKS ONLY: do not create APKs or source archives until the user explicitly asks again. Application version remains 1.7 / 8 until packaging. Room v6 and backup v9 are pending changes, not a released APK. Preserve nullable muscle as unrecorded, never zero or yesterday's value. Keep intro acknowledgement sticky through upgrades and restores. Direct intakes must have no synthetic food and must participate in totals, trends, backups and individual deletion. Collapsing keeps the direct-intake draft; clearing or leaving Today discards it. OCR was discussed as a feasibility question only and has not been implemented.

- On 2026-10-14 the user explicitly authorized implementing all 37 enumerated plan combinations and packaging them together with pending food deletion. Release target: 1.7 / versionCode 8. Show a feature table before packaging; this has been provided in the conversation. Preserve legacy plan calculations unless the user enables the new advanced configuration. No OCR or platform port is authorized.

- The user explicitly authorized packaging the completed features on 2026-10-13 after the earlier packaging hold. Release 1.6 / versionCode 7 includes weight-unit input, the food-quantity shortcut, and moving exercise to the bottom. The user declined photo/OCR recognition; do not add it. For later development batches, wait for an explicit packaging request before generating new APKs or source archives.
- Historical hold: food deletion was initially requested with NO packaging, and is now authorized for release 1.7 as stated above. Food removal must retain historical intake/target snapshots and complete backup references; preserve deletion state through backup/restore and prevent stale edits or recommendations from reusing deleted foods.

- Continue this application; keep `applicationId = com.fitledger`, database name `ledger.db`, and DataStore name `settings`.
- Never use destructive migration, delete/recreate the production database, or clear preferences on startup/update.
- Every Room schema change increments its version, adds an explicit migration, preserves exported old schemas, and adds migration tests seeded with real old records. Keep the full migration chain.
- All persisted energy and algorithms use kcal. kJ is a presentation/input conversion, exactly 4.184 kJ per kcal.
- Normal plan save starts the next local day. Immediate apply requires confirmation and only updates today's energy snapshot and target using stored measurements and the explicitly confirmed profile; never alter measured body fields, intakes or other days.
- Backups require versioned metadata, complete validation before writing, confirmation before replacement, transactional Room replacement and durable settings completion. Preserve the recovery journal until DataStore and reminder scheduling are complete.
- Build updates with the ORIGINAL private signing key, stored outside the source tree. Increase versionCode. Verify old/new APK signing certificates before delivery. If the original key is absent, stop signing; never silently generate a new key or advise uninstalling to work around a signature mismatch.
- Run domain, backup and real SQLite migration tests plus Android Lint and compile instrumentation tests. Report device tests as unexecuted unless actually run.
- Do not include private signing material, real user backups or build caches in source archives.

- Legacy energy snapshots remain null. Never backfill historical personal or energy values using the current profile. Privacy mode must mask sensitive body values and their curves across screens.

- Body visibility is independent per field. Keep the legacy body_privacy fallback and transactional missing-key migration; never let visibility flags affect stored measurements or calculations.
- Exercise records persist net extra kcal snapshots. Do not recalculate historical exercise from changed profile/plan; exclude records marked included=false from totals. Hide home body rows at the bottom without exposing their values.
- Explicit expenditure budgets opt in per plan; legacy JSON defaults off. Apply carb schedules before the remaining macro only for the new budget path. Initial-plan repair requires no body or target for today and uses the existing durable settings outbox.
