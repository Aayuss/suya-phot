# Suya Phot — Performance & Battery Architecture

## 1. Zero Background Footprint

- **No Always-Running Services**: When the app is not actively performing a user-initiated import or restore, no background services or processes exist.
- **No Wakeful Polling or Timers**: Timeout checks use `SystemClock.elapsedRealtime()` during app lifecycle transitions (`onStart` / `onStop`) rather than running background timer handlers.
- **No Periodic WorkManager Tasks**: Trash expiration is evaluated when the app or Trash screen is opened.

---

## 2. Memory & Image Optimization

- **Bounded Thumbnail Cache**: Thumbnails are generated at exact grid display sizes (~300–400px) and stored encrypted.
- **No Full-Resolution Decode in Grids**: Grids only load cached thumbnails via Coil with strict memory bounds.
- **Streaming Media I/O**: Multi-gigabyte videos and 200MP photos are never loaded via `readBytes()` or full byte arrays; they are stream-encrypted and stream-decrypted through 256KB–1MB buffers.
- **Resource Releasing**: ExoPlayer and CameraX bindings are released immediately upon leaving the viewer screen or when the vault locks.
- **Immediate Lock Eviction**: Locking clears all in-memory keys and wipes image memory caches.

---

## 3. UI & Jetpack Compose Performance

- **Stable Keys**: `LazyVerticalGrid` items are keyed by immutable `MediaItem.id`.
- **Immutable State Models**: UI state models are `@Immutable` to eliminate unnecessary recompositions.
- **Derived State**: Computed values (such as selection counts and filter active states) use `derivedStateOf`.
- **Lambda Modifiers**: Animated graphics layer properties (scale, alpha) use lambda modifiers to avoid recomposing parent composables.
- **120Hz Smoothness**: Optimized for Samsung Galaxy S23 Ultra's 120Hz display with frame budgets <= 8.33ms.
