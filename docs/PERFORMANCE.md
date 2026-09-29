# Suya Phot — Performance & Battery Architecture

## 1. Zero Background Footprint

- **No Always-Running Services**: When the app is not actively performing a user-initiated import or restore, no background services or processes exist.
- **No Wakeful Background Polling**: Vault timeout checks use `SystemClock.elapsedRealtime()` during app lifecycle transitions. Protected foreground screens periodically re-check short-lived access grants so expired content closes while visible.
- **No Periodic WorkManager Tasks**: Trash expiration is evaluated when the app or Trash screen is opened.

---

## 2. Memory & Image Optimization

- **Downsampled Encrypted Thumbnails**: Thumbnails are generated at a 360px target and stored encrypted.
- **No Full-Resolution Decode in Grids**: Grids only load cached thumbnails via Coil with strict memory bounds.
- **Streaming Media I/O**: Multi-gigabyte videos and 200MP photos are never loaded via `readBytes()` or full byte arrays; they are stream-encrypted and stream-decrypted through 256KB–1MB buffers.
- **Resource Releasing**: ExoPlayer is released when the viewer leaves composition, and CameraX capture bindings are removed after completion or failure.
- **Immediate Lock Eviction**: Locking clears session key arrays and ephemeral plaintext cache files. Operation-owned key copies are independently wiped when their active import or restore finishes.

---

## 3. UI & Jetpack Compose Performance

- **Stable Keys**: `LazyVerticalGrid` items are keyed by immutable `MediaItem.id`.
- **Derived State**: Selection-mode state uses `derivedStateOf`, while Room/Paging flows drive gallery updates.
- **Off-Main Sensitive I/O**: Metadata search-index decryption, intruder-log decryption, full-media authentication, and bitmap decode run on `Dispatchers.IO`.
- **Paging**: Normal gallery browsing uses Paging 3 rather than materializing the full media table in Compose.

No device-specific frame-time claim is made until a release build is profiled on physical hardware.
