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
   - Master keys (256-bit random) derived using domain-separated HKDF-SHA256 (`mediaSubkey`, `metaSubkey`, `thumbSubkey`).
   - Streaming AES-256-GCM authenticated encryption for photos and 4K videos using the versioned `SUPH` v1 binary container format (authenticated header, 64KB bounded chunks or atomic payload, never reads raw bytes into memory).
   - Dual-envelope PIN authentication combining PBKDF2-HMAC-SHA256 (100,000+ calibrated iterations) with an Android Keystore hardware-backed HMAC pepper.
   - Real Biometric fingerprint unwrap via `BiometricPrompt` with an Android Keystore authenticated `CryptoObject` (AES-256-GCM) without storing plaintext keys in RAM or disk.
   - 128-bit emergency Recovery Kit formatted as a 26-character Base32 code (`XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XX`), normalized and validated for exact 26-character input.

2. **Decoy / Secondary Vault**:
   - Enter an alternate PIN on the lock screen to open an entirely separate vault with independent keys, folders, and media.
   - Coercion-resistant with zero UI leakage of secondary vault existence or storage metrics.

3. **Transactional Import & Restore Pipelines**:
   - Share target: **"Move to Suya Phot"** (`ACTION_SEND` and `ACTION_SEND_MULTIPLE`) plus in-app photo picker.
   - Crash-safe imports: verifies SHA-256 and GCM integrity before requesting deletion of public gallery originals; unfinished jobs are reconciled on startup via `ImportRecoveryManager`.
   - Restores media to public Samsung Gallery with byte-for-byte fidelity and original EXIF metadata. Source deletion uses standard `MediaStore.createDeleteRequest` (Shizuku privileged mode is reserved for post-v1).

4. **Arbitrarily Nested Folders & Media Management**:
   - Multi-level folder hierarchy with O(1) item selection and instant moves without re-encryption.
   - Strict cycle prevention algorithm preventing moving a folder into its own descendant.
   - Dynamic breadcrumb navigation, folder renaming, and transactional deletion policies (move contents to parent or trash).

5. **Performance & Memory Guarantees (Optimized for S23 Ultra)**:
   - Zero background services, polling loops, or persistent wake locks. Session timeout is evaluated passively using `ProcessLifecycleObserver` and monotonic `SystemClock.elapsedRealtime()`.
   - Full 200MP camera images are decoded safely with a two-pass bounds inspection (`inJustDecodeBounds`), sampled to bounded 2K/2560px resolution using `RGB_565` to strictly limit memory to <15MB per view.
   - Video playback decrypts into an ephemeral `playback_cache` directory, immediately released and deleted upon screen exit or vault lock.
   - Encrypted thumbnail cache with bounded disk and memory limits.

6. **Intruder Detection & Screen Protection**:
   - Silent front-camera capture via CameraX upon configurable failed PIN thresholds, encrypted and stored in the security vault partition.
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
