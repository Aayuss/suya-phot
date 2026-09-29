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

---

## 6. Portable Encrypted Backup & Fail-Safe Restore (.suyavault v2)

Suya Phot provides a fully self-contained, portable encrypted backup and migration system using the `.suyavault` archive specification (V2).

### 6.1 Archive Format & Authenticated Descriptors (V2)
- **Magic Header & Trailer**: `SYPB` (0x53595042) magic header, `SYED` (0x53594544) end marker.
- **Archive Version**: Version 2 (`CURRENT_VERSION = 2`), supporting backward compatibility with version 1 readers/archives.
- **Portable Master Key Unwrapping**: Encrypted using a KEK derived via HKDF-SHA256 from the user's 26-character Recovery Code (`info = "suya-vault-backup-kek:v1"`).
- **Zero Hardware Pepper Dependency**: The backup is completely decoupled from the originating device's Android Keystore and hardware pepper, allowing seamless restore onto completely fresh devices, new Android versions, or replacement hardware without risk of permanent lock-out.
- **Authenticated Descriptors**: The encrypted manifest authenticates each descriptor: entry type (`ENTRY_TYPE_MEDIA`, `ENTRY_TYPE_THUMB`, `ENTRY_TYPE_PREVIEW`), item ID, ciphertext length, and ciphertext SHA-256.
- **Body Authentication**: Body files remain in their existing vault-encrypted format. Original media has its own SUPH AES-256-GCM integrity tied to item identity and plaintext SHA-256. Private metadata has separate item-bound AEAD. Thumbnails and previews have their own item-bound derivative encryption. Corrupted or unauthenticated optional derivatives (thumbnails/previews) are safely omitted during export and dropped during restore without jeopardizing original media.

### 6.2 Preflight & Post-Write Verification
- **SHA-256 Preflight**: Before writing any bytes to the output stream, the exporter verifies the presence and integrity of all media files on disk via `vaultCrypto.verifyAndHash`, strictly comparing verified plaintext SHA-256 and ciphertext size against database records.
- **Protected Folder Portability Check**: Every protected folder lock is verified to possess a valid portable recovery envelope decryptable with the vault's metadata subkey. If any lock lacks portable recovery or is corrupt, export fails safely with `FOLDER_LOCK_RECOVERY_NOT_READY` or `FOLDER_LOCK_RECOVERY_CORRUPT`.
- **Post-Write SAF Verification**: After writing and syncing the file descriptor, the app re-opens the output URI from the Storage Access Framework and executes `backupVerifier.verifyFullArchive` to verify the written file before confirming success to the user.

### 6.3 Fail-Safe Restore Invariant
- **Never Destroys Existing Vaults**: To prevent accidental data loss, `VaultBackupImporter` strictly enforces `BackupError.RESTORE_REQUIRES_EMPTY_VAULT`. If a vault of the same kind already exists on the device, the restore operation is rejected before staging files or modifying the database.
- **Strict Path Containment**: No string originating inside a backup archive ever determines a local filesystem path. Entries are staged under `restore_staging/<uuid>/` using local sequence filenames (`%08d_%s.bin`). Stale staging directories are automatically cleaned on app cold start.
- **Free-Space Precheck**: Before extracting backup bodies, device storage is verified against declared total body bytes plus safety margin via `StatFs`.
- **Atomic Database Commit**: All Room database entries are committed inside a single transaction after all media items have been verified and staged. Synchronous failure or crash before commit is reconciled cleanly.

### 6.4 Folder Lock Portability & Source Disposition (Schema Version 6)
- **Credential Reset Flag**: `FolderLockEntity` tracks `requiresCredentialReset`. Restored folders have `requiresCredentialReset = true` and `biometric = null`. Users are prompted to set a new PIN or Pattern using their Recovery Kit before access is granted.
- **Unresolved Source Move Disposition**: Database version 6 introduces `sourceDispositionCode` on `VaultJobEntity` (`SourceDisposition.RETAINED_AFTER_INTERRUPTION`, `DELETE_FAILED`, `RETAINED_BY_USER`, `DELETED`) allowing interrupted public Gallery move deletions to be surfaced clearly to users via an attention banner with "Finish Moving" and "Keep Original" options.

### 6.5 Deterministic Keyset Paging
- **Continuous Keyset Paging**: The gallery and media viewer utilize index-backed keyset pagination (`fetchNextViewerBatch` and `fetchPreviousViewerBatch`) with identical deterministic total order tie-breakers across all sort modes (`id ASC` / `id DESC`), preventing duplicated or skipped items across window boundaries.

