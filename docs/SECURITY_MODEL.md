# Suya Phot — Security Model & Cryptographic Architecture

## 1. Threat Model & Guarantees

### Protected Against:
- Casual device examination or unauthorized users unlocking the phone.
- Malicious apps on the device scanning public MediaStore or storage.
- Extracted private application data without Android Keystore access.
- Accidental screenshots, screen recordings, and Recents snapshots (`FLAG_SECURE`).
- Coercion via secondary (decoy) PIN without leaking the existence of the real vault.

### Out of Scope / Fundamental Limits:
- Compromised/rooted operating systems with kernel-level memory inspection while the vault is actively unlocked.
- Hardware-level physical tampering beyond Android Keystore hardware protection.
- Wear-leveling forensic overwrite guarantees on flash memory (plain flash overwrites do not guarantee physical erasure).

---

## 2. Key Derivation & Envelopes

Every vault has an independent 256-bit `VaultMasterKey` generated from `SecureRandom`.

### 2.1 Subkey Derivation (HKDF-SHA256)
Using domain separation info strings:
- `suya-phot-media-key`: Encrypting and decrypting full media files.
- `suya-phot-meta-key`: Encrypting sensitive metadata strings and DB payloads.
- `suya-phot-thumb-key`: Encrypting cached thumbnail images.

### 2.2 PIN Wrapping (PBKDF2-HMAC-SHA256 + Keystore Pepper)
1. User enters numeric PIN (minimum 4 digits, recommended 6+).
2. Generate 16-byte random salt.
3. PBKDF2-HMAC-SHA256 with 100,000+ iterations derives an intermediate key.
4. Intermediate key is combined with an Android Keystore HMAC pepper (hardware-backed).
5. Resulting KEK wraps the `VaultMasterKey` using AES-256-GCM.
6. Stored envelope: `{version, salt, iterations, nonce, wrappedMasterKey}`.
7. Verification: Successful GCM tag authentication indicates correct PIN without storing any plaintext hash.

### 2.3 Biometric Wrapping (Android Keystore + BiometricPrompt)
- An AES-256-GCM key is generated inside Android Keystore requiring `setUserAuthenticationRequired(true)`.
- `BiometricPrompt` uses `CryptoObject` to unwrap the `VaultMasterKey`.
- Unlocking does not persist any plaintext key to disk.

### 2.4 Recovery Envelope
- A high-entropy 128-bit secret is generated at setup and displayed to the user as formatted chunks.
- The secret is hashed/HKDF-derived into a recovery KEK to wrap the `VaultMasterKey`.
- Plaintext recovery code is never persisted.

---

## 3. Decoy / Secondary Vault

- The secondary PIN unlocks an entirely distinct `VaultMasterKey`.
- Storage directories and Room database records for the secondary vault are segregated.
- The UI in secondary mode provides identical features without displaying words such as "Decoy" or "Fake".
- Real vault storage usage and folder counts are completely hidden when in secondary mode.

---

## 4. Encrypted File Format (SUPH v1)

```
0..3   : Magic bytes "SUPH" (4 bytes)
4      : Version = 1 (1 byte)
5      : Media type flags (1 byte, 0 = image, 1 = video)
6..7   : Reserved (2 bytes)
8..23  : Item Salt (16 bytes)
24..35 : AES-GCM Nonce (12 bytes)
36..43 : Plaintext length (8 bytes, big-endian)
44..N  : AES-256-GCM Ciphertext
N..N+16: 128-bit GCM Authentication Tag
```
Header bytes (0..43) are provided as Additional Authenticated Data (AAD) to ensure complete integrity.
