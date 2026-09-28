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
   - Request user deletion via standard Android `MediaStore.createDeleteRequest`.
   - (Note: Shizuku privileged deletion is intentionally removed for v1 to guarantee zero IPC exposure, deferred to post-v1 roadmap).
   - If user denies deletion, encrypted vault item is safely retained, and UI reports original still exists.
   - Source is NEVER deleted if any previous step fails.
9. **CRASH RECOVERY**:
   - On every unlocked session start, `ImportRecoveryManager` reconciles unfinished import jobs. Partial files are pruned; ciphertext that reached its final path without a matching database row is removed because the public source has not yet been deleted. Jobs awaiting source deletion remain explicit rather than being reported as deleted.

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

- **Recovery Kit**:
  - 128-bit high-entropy secret (16 bytes random) generated at setup.
  - Formatted into 26 Base32 characters grouped as `XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XX`.
  - Normalization strips hyphens and spaces, converting to uppercase and enforcing exact 26 characters.
  - Derives recovery KEK using HKDF-SHA256 (`suya-phot-recovery-kek`), unwraps the master key, and allows resetting the PIN.
  - A valid recovery code re-wraps the same master key under a new PIN, without re-encrypting media.

Biometric PIN reset and recovery-code rotation are not exposed as completed product flows in this revision.

---

## 5. Ephemeral Playback Cache

- For video streaming playback with Media3 ExoPlayer, files are decrypted on-demand into `context.cacheDir/playback_cache/`.
- No unencrypted media files remain permanently on disk:
  - Bound to the Compose lifecycle (`DisposableEffect`), the temporary file is unlinked immediately when the user navigates away or closes the media viewer.
  - Any background transition or session lock automatically destroys the active player and deletes all files in `playback_cache/`.
