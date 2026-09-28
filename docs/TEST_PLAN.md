# Suya Phot — Test Plan & Quality Assurance Matrix

## 1. Test Categories

### 1.1 Implemented Unit Tests (`src/test`)
- **Crypto & Key Derivation**: HKDF RFC test vectors, AES-GCM encryption/decryption, tamper detection (modified ciphertext or tag), PBKDF2 iterations.
- **Folder Algorithms**: Ancestor validation, cycle detection (moving folder into itself or descendant).
- **Stream Verification**: SUPH round-trip, tamper rejection, and SHA-256 verification.
- **Auth**: Rate-limiter calculation and secondary-PIN rewrap preserving the existing master key and encrypted fixture.

### 1.2 Implemented Instrumented Tests (`src/androidTest`)
- **Room Migration**: Opens a version-1 database through the explicit 1-to-2 migration and validates the schema.
- **Android Keystore**: Encrypt/decrypt round-trip with the production intruder-key provider and verifies the key is non-exportable.

---

## 2. Manual QA Scenario Matrix (not claimed as executed by the automated suite)

- **D001–D012**: JPEG photo lifecycle (Import, Picker, Root, Nested 5, Cancel, Crash recovery, Deny delete, Restore Copy, Restore Move, Vault Move, Trash/Restore, Permanent Delete).
- **D013–D024**: PNG screenshot lifecycle.
- **D025–D036**: HEIC photo lifecycle.
- **D037–D048**: Animated GIF lifecycle.
- **D049–D060**: 4K MP4 video lifecycle.
- **D061–D072**: Large >1GB MP4 video lifecycle.
- **D073–D084**: High-resolution 200MP photo lifecycle.
- **D085–D096**: Unicode-filename photo lifecycle.
- **L001–L119**: Extended authentication, folder, metadata, lifecycle, import/restore, UI/motion, performance, and security edge cases.
