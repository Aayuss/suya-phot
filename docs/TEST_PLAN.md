# Suya Phot — Test Plan & Quality Assurance Matrix

## 1. Test Categories

### 1.1 Unit Tests (`src/test`)
- **Crypto & Key Derivation**: HKDF RFC test vectors, AES-GCM encryption/decryption, tamper detection (modified ciphertext or tag), PBKDF2 iterations.
- **Folder Algorithms**: Ancestor validation, cycle detection (moving folder into itself or descendant).
- **Import/Restore Verification**: SHA-256 computation, stream verification, filename conflict resolution.
- **Auth & Session Management**: Rate limiting, elapsed realtime lock calculation, biometric unwrap, recovery code validation.
- **Decoy Isolation**: Verification that decoy and real master keys and namespaces have zero cross-leakage.

### 1.2 Instrumented Room & Android Tests (`src/androidTest`)
- **Database CRUD**: Transactional folder moves, cascaded soft-deletes, job status updates.
- **FileStore Operations**: Atomic partial write, rename, sync, and safe cleanup.
- **Share Receiver Activity**: Verification of intent filter handling for `ACTION_SEND` and `ACTION_SEND_MULTIPLE`.

---

## 2. QA Scenario Execution Matrix (from Master Spec)

- **D001–D012**: JPEG photo lifecycle (Import, Picker, Root, Nested 5, Cancel, Crash recovery, Deny delete, Restore Copy, Restore Move, Vault Move, Trash/Restore, Permanent Delete).
- **D013–D024**: PNG screenshot lifecycle.
- **D025–D036**: HEIC photo lifecycle.
- **D037–D048**: Animated GIF lifecycle.
- **D049–D060**: 4K MP4 video lifecycle.
- **D061–D072**: Large >1GB MP4 video lifecycle.
- **D073–D084**: High-resolution 200MP photo lifecycle.
- **D085–D096**: Unicode-filename photo lifecycle.
- **L001–L119**: Extended authentication, folder, metadata, lifecycle, import/restore, UI/motion, performance, and security edge cases.
