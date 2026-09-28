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

1. **Hardware-Backed Cryptographic Security**:
   - Master keys (256-bit random) derived using domain-separated HKDF-SHA256.
   - Streaming AES-256-GCM authenticated encryption for photos and 4K videos using the versioned `SUPH` v1 binary container format.
   - Dual-envelope PIN authentication (PBKDF2-HMAC-SHA256 + Android Keystore hardware-backed pepper).
   - Biometric fingerprint unwrap using AndroidX Biometric and Android Keystore.
   - 128-bit emergency Recovery Kit for forgotten PIN recovery.

2. **Decoy / Secondary Vault**:
   - Enter an alternate PIN on the lock screen to open an entirely separate vault with independent keys, folders, and media.
   - Coercion-resistant with zero UI leak that a secondary vault exists.

3. **Transactional Import & Restore Pipelines**:
   - Share target: **"Move to Suya Phot"** (`ACTION_SEND` and `ACTION_SEND_MULTIPLE`).
   - Crash-safe imports: verifies SHA-256 and GCM integrity before requesting deletion of public gallery originals.
   - Restores media to public Samsung Gallery with byte-for-byte fidelity and original EXIF metadata.

4. **Arbitrarily Nested Folders**:
   - Instant folder-to-folder media moves via database transactions without re-encrypting files.
   - Cycle prevention algorithm preventing moving a folder into its own descendant.
   - Dynamic breadcrumb navigation.

5. **Performance & Battery Efficiency**:
   - Zero background services or persistent wake locks.
   - Bounded, encrypted thumbnail cache; never decodes full-resolution 200MP images into lazy grids.
   - Video playback via Media3 ExoPlayer with immediate resource cleanup on exit or vault lock.

6. **Intruder Detection & Screen Protection**:
   - Silent front-camera capture via CameraX upon configurable failed PIN thresholds.
   - Window screenshot and Android Recents preview protection (`FLAG_SECURE`).

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
