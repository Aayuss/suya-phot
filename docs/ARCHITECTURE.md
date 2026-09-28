# Suya Phot — System Architecture

## 1. High-Level Overview

Suya Phot is a local-first, highly secured Android private photo and video vault designed for Samsung Galaxy S23 Ultra and modern Android devices.

The architecture emphasizes:
- **Zero-knowledge local storage**: All media and sensitive metadata are encrypted with AES-256-GCM.
- **Fail-safe transactional pipelines**: Import and restore operations are guarded by state machines that never delete source files before durable encryption and hash verification succeed.
- **Biometric & Decoy support**: Hardware-backed biometric unwrap and isolated decoy/secondary vault namespaces.
- **Performance & Battery efficiency**: Zero background services, on-demand operations, streaming crypto, bounded memory caching, and Jetpack Compose UI matching the Ember design system.

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
│   │   └── SensitiveKeyHandle.kt
│   ├── database                 // Room Database & DAOs
│   │   ├── SuyaDatabase.kt
│   │   ├── entity/              // VaultEntity, FolderEntity, MediaItemEntity, JobEntity, etc.
│   │   └── dao/
│   ├── datastore                // DataStore preferences for non-sensitive settings
│   ├── media                    // Media metadata extraction, EXIF, thumbnails
│   │   ├── MetadataReader.kt
│   │   ├── ThumbnailGenerator.kt
│   │   └── ExifHelper.kt
│   ├── permissions              // Runtime permission handlers
│   ├── shizuku                  // Optional Shizuku privilege connector
│   └── util                     // SafeLog, FileStore, Digest
├── domain
│   ├── auth                     // Authentication, PIN verification, Biometric, Recovery
│   │   ├── SessionManager.kt
│   │   ├── PinAuthenticator.kt
│   │   └── RecoveryManager.kt
│   ├── importmedia              // Transactional import pipeline & job manager
│   │   ├── ImportCoordinator.kt
│   │   └── SourceDeletionCoordinator.kt
│   ├── restore                  // MediaStore restoration pipeline
│   │   └── RestoreCoordinator.kt
│   └── folders                  // Folder tree, cycle prevention, moves
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
│   └── settings                 // App preferences, storage stats, backup export
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
- **Memory Security**: Clears sensitive byte arrays in memory upon lock or background timeout.
- **Window Protection**: Toggles `FLAG_SECURE` to prevent screen capture and recent app thumbnail leakage.
