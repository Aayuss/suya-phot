# Suya Phot — System Architecture

## 1. High-Level Overview

Suya Phot is a local-first, highly secured Android private photo and video vault designed for Samsung Galaxy S23 Ultra and modern Android devices.

The architecture emphasizes:
- **Local-first encrypted storage**: All media and sensitive metadata are encrypted on-device with AES-256-GCM using hardware-peppered PBKDF2/Keystore envelopes.
- **Fail-safe transactional pipelines**: Import, restore, and backup operations are guarded by state machines that never delete or overwrite existing vaults or source files before durable encryption and hash verification succeed.
- **Biometric & Decoy support**: Hardware-backed biometric unwrap and isolated decoy/secondary vault namespaces.
- **Performance & Battery efficiency**: Continuous keyset pagination, zero background services, on-demand operations, streaming crypto, bounded memory caching, and Jetpack Compose UI matching the Ember design system.

---

## 2. Package Structure

```
com.suyaphot.app
├── app
│   ├── SuyaApp.kt               // Application class (initialization, session lifecycle)
│   ├── AppContainer.kt          // Central lightweight DI container
│   └── MainActivity.kt          // Single Activity host with window security (FLAG_SECURE)
├── core
│   ├── crypto                   // AES-256-GCM, HKDF, PBKDF2, Keystore integration
│   │   ├── Aead.kt
│   │   ├── HkdfSha256.kt
│   │   ├── KeyManager.kt
│   │   ├── VaultCrypto.kt
│   │   ├── SensitiveKeyHandle.kt
│   │   ├── PepperProvider.kt
│   │   └── IntruderKeyProvider.kt
│   ├── database                 // Room Database & DAOs (WAL mode, schema export enabled)
│   │   ├── SuyaDatabase.kt
│   │   ├── entity/              // VaultEntity, FolderEntity, MediaItemEntity, JobEntity, etc.
│   │   └── dao/
│   ├── datastore                // DataStore preferences for non-sensitive settings
│   ├── media                    // Media metadata extraction, EXIF, thumbnails
│   │   ├── MetadataReader.kt
│   │   ├── ThumbnailGenerator.kt
│   │   └── ExifHelper.kt
│   ├── permissions              // Runtime permission handlers
│   └── util                     // SafeLog, VaultFileStore, Digest
├── domain
│   ├── auth                     // Authentication, PIN verification, Biometric, Recovery
│   │   ├── SessionManager.kt
│   │   └── PinAuthenticator.kt
│   ├── importmedia              // Transactional import pipeline & crash-safe recovery
│   │   ├── ImportCoordinator.kt
│   │   ├── ImportRecoveryManager.kt
│   │   └── SourceDeletionCoordinator.kt
│   ├── restore                  // MediaStore restoration pipeline
│   │   └── RestoreCoordinator.kt
│   ├── backup                   // Portable encrypted backup/restore (.suyavault v2)
│   │   ├── VaultBackupExporter.kt
│   │   ├── VaultBackupImporter.kt
│   │   ├── BackupVerifier.kt
│   │   ├── BackupArchiveFormat.kt
│   │   └── BackupManifest.kt
│   └── folders                  // Folder tree, cycle prevention, transactional moves/deletes
│       └── FolderManager.kt
├── feature
│   ├── onboarding               // First-run setup, PIN creation, Recovery Kit
│   ├── lock                     // PIN keypad, biometric prompt, rate-limiting
│   ├── photos                   // All media grid, filter chips, selection
│   ├── folders                  // Nested folder browser, breadcrumbs
│   ├── viewer                   // Photo pan/zoom & Media3 video playback
│   ├── security                 // Security diagnostics, intruder logs, secondary PIN
│   ├── trash                    // Vault trash, auto-purge, restoration
│   ├── intruder                 // CameraX silent selfie capture
│   └── settings                 // App preferences, storage stats, backup & restore
├── navigation                   // Navigation Compose destinations & transitions
└── ui
    ├── theme                    // SuyaColors, SuyaTypography (Sora), Shapes
    ├── components               // Buttons, Cards, Inputs, Dialogs, Keypad, Grids
    └── motion                   // MotionTokens, Enter/Exit transitions
```

---

## 3. Session & Lifecycle Management

- **ProcessLifecycleObserver**: Monitors foreground/background transitions.
- **ElapsedRealtime**: Computes timeout durations without wakeful background timers.
- **Memory Security**: Wipes sensitive byte arrays (`SensitiveKeyHandle`, master keys, intermediate byte arrays) upon lock or background timeout.
- **Window Protection**: Toggles `FLAG_SECURE` to prevent screen capture and recent app thumbnail leakage.

---

## 4. Media Streaming & Memory Architecture

- **Continuous Keyset Paging**: Large galleries and viewers operate on index-backed keyset pagination (`fetchNextViewerBatch` / `fetchPreviousViewerBatch`), allowing fluid navigation across 50,000+ items without heap pressure.
- **Bounded Image Decoding**: 200MP images are sampled using two-pass `BitmapFactory` bounds calculation, clamped to 2560px max dimension and loaded into `Bitmap.Config.RGB_565`. Full files are never read into byte arrays via `readBytes()`.
- **Ephemeral Video Playback**: Videos stream decrypted into an isolated `playback_cache` directory in private app cache. When the viewer is disposed or the vault locks, `DisposableEffect` releases ExoPlayer and unlinks the temporary file immediately.
- **Encrypted Thumbnail Pipeline**: Fast grid tiles load from pre-generated AES-GCM encrypted thumbnails with dedicated disk and memory budgets.

---

## 5. Scope & Roadmap

- **Shizuku Integration**: Deferred to post-v1 roadmap to maintain zero IPC attack surface in v1. V1 exclusively uses standard Android `MediaStore.createDeleteRequest`.
- **Zero Cloud / Zero Telemetry**: Strict local-only operation with no network permissions or external SDKs.
