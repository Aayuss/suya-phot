# Suya Phot — Storage, Import & Recovery Pipeline

## 1. Storage Layout

Suya Phot stores all encrypted media and derivatives inside the application's private internal directory:
`context.noBackupFilesDir`

```
vault/
  <opaqueVaultId>/
    media/
      <2-char-prefix>/
        <uuid>.sph       // AES-256-GCM encrypted media
    thumbs/
      <2-char-prefix>/
        <uuid>.sth       // AES-256-GCM encrypted thumbnail
    security/
      <uuid>.sph         // Intruder photos (encrypted)
    partial/             // Temporary partial files during active streaming encryption
    quarantine/          // Damaged/corrupted files identified during audits
```

- Plaintext original filenames never appear in the filesystem.
- `android:allowBackup="false"` in AndroidManifest prevents cloud leakage.

---

## 2. Transactional Import Pipeline

Importing an item (e.g. from Samsung Gallery share or in-app picker) follows this strict state progression:

1. **QUEUED**: Job record created in Room.
2. **READING_SOURCE**: Open source URI stream; compute SHA-256 hash as bytes flow.
3. **ENCRYPTING**: Stream through `CipherOutputStream` into a `.partial` file in private storage.
4. **DURABILITY SYNC**: Flush buffer, call `fileDescriptor.sync()` / `fsync`.
5. **ATOMIC RENAME**: Move `.partial` file to final `<uuid>.sph`.
6. **VERIFICATION**: Decrypt stream to an authentication sink:
   - Validate AES-GCM tag.
   - Verify byte count matches plaintext size.
   - Verify computed SHA-256 matches source hash.
7. **DATABASE COMMIT**: Insert item record into Room transactionally; mark job `AWAITING_SOURCE_DELETE`.
8. **SOURCE DELETION**:
   - Request system deletion via `MediaStore.createDeleteRequest` (Standard Mode) or privileged delete (Shizuku Mode).
   - If user denies deletion, encrypted vault item is safely retained, and UI reports original still exists.
   - Source is NEVER deleted if any previous step fails.

---

## 3. Transactional Restore Pipeline

1. Read encrypted item metadata and decrypt original attributes (filename, relative path, MIME type).
2. Create pending entry in MediaStore with `IS_PENDING = 1`.
3. Stream-decrypt item into destination `FileDescriptor`.
4. Flush and `sync()`.
5. Verify restored byte count and SHA-256.
6. Publish to MediaStore by updating `IS_PENDING = 0`.
7. If operation was "Move to Original Gallery", delete the vault copy only after verified publish.
8. If operation was "Export/Copy to Gallery", retain the vault copy.

---

## 4. Recovery System

- **Path A (Biometric Reset)**: If fingerprint is configured, user authenticates with biometric to unwrap the `VaultMasterKey` and re-wrap with a new PIN without re-encrypting media.
- **Path B (Recovery Kit)**: 128-bit random secret shown during setup can be entered to unwrap the recovery envelope and configure a new PIN.
- Recovery code rotation creates a fresh envelope and invalidates the previous code.
