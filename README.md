# Suya Phot

Native Android encrypted photo and video privacy vault and gallery application built with Kotlin and Jetpack Compose.
Optimized for the **Samsung Galaxy S23 Ultra** and modern Android devices (API 29+).

---

## Overview

**Suya Phot** is a local-first, zero-knowledge private media vault. It gives users complete privacy over their sensitive photos and videos without relying on cloud backups, third-party trackers, or ads.

- **App Name**: Suya Phot
- **Platform**: Native Android (Kotlin + Jetpack Compose)
- **Visual Design System**: Dark luxury styling inspired by Ember (#131314 / #222222 / #e55f11 / #ffffff), Sora typography, and fluid spring motion.

---

## Key Features

1. **Keystore-Assisted Cryptographic Security**:
   - Master keys (256-bit random) derived using domain-separated HKDF-SHA256 (`mediaSubkey`, `metaSubkey`, `thumbSubkey`).
   - Streaming AES-256-GCM authenticated encryption for photos and videos using the versioned `SUPH` v1 binary container format (authenticated header and bounded 256 KiB I/O buffers; the complete media file is never buffered in memory).
   - Dual-envelope PIN authentication combining PBKDF2-HMAC-SHA256 (a fixed, envelope-recorded work factor) with an Android Keystore-backed HMAC pepper.
   - Biometric unwrap via `BiometricPrompt` with an Android Keystore authenticated `CryptoObject` (AES-256-GCM). Plaintext key material is kept only in the active in-memory session and is never persisted.
   - 128-bit emergency Recovery Kit formatted as a 26-character Base32 code (`XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XX`), normalized and validated for exact 26-character input.

2. **Decoy / Secondary Vault**:
   - Enter an alternate PIN on the lock screen to open an entirely separate vault with independent keys, folders, and media.
   - Coercion-resistant with zero UI leakage of secondary vault existence or storage metrics.

3. **Transactional Import & Restore Pipelines**:
   - Share target: **"Move to Suya Phot"** (`ACTION_SEND` and `ACTION_SEND_MULTIPLE`) plus in-app photo picker.
   - Crash-safe imports: verifies SHA-256 and GCM integrity before requesting deletion of public gallery originals; unfinished jobs are reconciled on startup via `ImportRecoveryManager`.
   - Restores media to public MediaStore with a full SHA-256 recheck before any private vault copy is removed. Source deletion uses standard Android scoped-storage consent APIs.

4. **Arbitrarily Nested Folders & Media Management**:
   - Multi-level folder hierarchy with O(1) item selection and instant moves without re-encryption.
   - Strict cycle prevention algorithm preventing moving a folder into its own descendant.
   - Dynamic breadcrumb navigation, folder renaming, and transactional deletion policies (move contents to parent or trash).

5. **Performance & Memory Controls**:
   - Zero background services, polling loops, or persistent wake locks. Session timeout is evaluated passively using `ProcessLifecycleObserver` and monotonic `SystemClock.elapsedRealtime()`.
   - Large camera images are decoded with a two-pass bounds inspection (`inJustDecodeBounds`) and sampled toward a 2560px maximum dimension using `RGB_565`.
   - Video playback decrypts into an ephemeral `playback_cache` directory, immediately released and deleted upon screen exit or vault lock.
   - Encrypted, downsampled 360px thumbnails for gallery grids.

6. **Intruder Detection & Screen Protection**:
   - Front-camera capture via CameraX at the configured failed-PIN threshold after the user grants camera permission; Android camera privacy indicators still apply. Captures are encrypted with a dedicated non-exportable Keystore key.
   - Screen capture, screen recording, and Android Recents preview protection via `FLAG_SECURE`.

---

## Build & Test Instructions

### Prerequisites
- JDK 17 (recommended: Eclipse Temurin 17)
- Android SDK (API 35 platforms and build-tools)

### Running Tests
```bash
./gradlew test
```

### Building APK
```bash
# Debug APK
./gradlew assembleDebug

# Release APK
./gradlew assembleRelease
```
Output APK locations:
`app/build/outputs/apk/debug/app-debug.apk`
`app/build/outputs/apk/release/app-release-unsigned.apk` (or signed debug)
