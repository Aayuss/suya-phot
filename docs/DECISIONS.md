# Suya Phot — Architectural & Design Decisions (ADR)

## ADR 001: Pure Native Android with Jetpack Compose
- **Decision**: Use 100% native Kotlin and Jetpack Compose.
- **Rationale**: Highest UI responsiveness, zero cross-platform bridge overhead, native access to Android Keystore, BiometricPrompt, MediaStore, and CameraX.

## ADR 002: Visual Design System Translation
- **Decision**: Adopt all visual tokens, colors (`#131314`, `#222222`, `#e55f11`, `#ffffff`), Sora typography, spacing, surface hierarchy, and motion language from the Ember design specification.
- **Rationale**: Strictly comply with the user's design guide while discarding all irrelevant crypto-wallet domain concepts in favor of a private gallery.

## ADR 003: Single-Item Encrypted Blobs (.sph) with Streaming AES-GCM
- **Decision**: Store each media item as an independent encrypted binary file with a custom versioned header (`SUPH` v1) containing its random nonce and item salt.
- **Rationale**: Isolates file corruption, enables atomic moves across logical folders in milliseconds via DB transactions without re-encrypting, and avoids loading multi-GB media into RAM.

## ADR 004: Two-Tier Sensitive Data Protection
- **Decision**: Room database stores structural IDs, timestamps, and encrypted blobs for sensitive metadata (original filenames, original paths, EXIF details).
- **Rationale**: Prevents sensitive metadata from leaking in plaintext database records while avoiding heavy external dependencies.

## ADR 005: Decoy Vault Isolation
- **Decision**: Real and decoy vaults have independent 256-bit master keys, separate envelopes, and distinct directory shards.
- **Rationale**: Coercion protection with zero leakage; secondary UI looks and feels identical to a genuine fresh vault.

## ADR 006: Shizuku as an Optional Enhancement
- **Decision**: Implement standard Android MediaStore APIs by default (`createDeleteRequest`); provide Shizuku support strictly as an optional toggle in advanced settings.
- **Rationale**: Ensures the app runs reliably on any stock S23 Ultra without requiring root or external daemons, while offering seamless operations for power users who have Shizuku running.
