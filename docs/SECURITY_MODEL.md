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
- Hardware-level physical tampering. Keystore hardware backing is device-dependent and must be checked at runtime; it is not assumed as a universal guarantee.
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
3. PBKDF2-HMAC-SHA256 with a fixed, envelope-recorded work factor derives an intermediate key.
4. Intermediate key is combined with an Android Keystore HMAC pepper (hardware-backed key alias `suya_phot_pepper_key`).
5. Resulting KEK wraps the `VaultMasterKey` using AES-256-GCM.
6. Stored envelope: `{version, salt, iterations, nonce, wrappedMasterKey}` (Version 1).
7. Verification: Successful GCM tag authentication indicates correct PIN without storing any plaintext hash or password equivalent.

### 2.3 Biometric Wrapping (Android Keystore + BiometricPrompt)
- An AES-256-GCM key is generated inside Android Keystore with `KeyGenParameterSpec` requiring `setUserAuthenticationRequired(true)` and alias `suya_phot_bio_<vaultId>`.
- Unlocking initializes an authenticated cipher passed to `BiometricPrompt.CryptoObject(cipher)`.
- Upon biometric confirmation by the OS, the cipher decrypts the biometric envelope and unwraps the `VaultMasterKey` directly into memory.
- Plaintext keys are never persisted to disk or flash storage.

### 2.4 Recovery Envelope (128-Bit Base32 Code)
- A high-entropy 128-bit (16-byte) cryptographically secure random secret is generated at setup.
- The secret is encoded into a 26-character Base32 Crockford/RFC4648 format, displayed as 7 groups: `XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XX`.
- Normalization strips hyphens and whitespace, uppercasing the input and strictly enforcing the exact 26-character length.
- HKDF-SHA256 derives a 256-bit recovery KEK with domain separation info `suya-phot-recovery-kek` and a random 16-byte salt.
- The master key is wrapped via AES-256-GCM and stored in Room as `RecoveryEnvelope` `{version, salt, nonce, wrappedMasterKey}`.
- Plaintext recovery codes are never stored on device. Entering the valid recovery code allows setting a new PIN without re-encrypting existing media files.

---

## 3. Decoy / Secondary Vault

- The secondary PIN unlocks an entirely distinct `VaultMasterKey`.
- Storage directories and Room database records for the secondary vault are segregated.
- The UI in secondary mode provides identical features without displaying words such as "Decoy" or "Fake".
- Real vault storage usage and folder counts are completely hidden when in secondary mode.

### Hidden and locked folders

Room schema v3 stores direct and inherited hidden/protected folder flags and a materialized media `concealed` flag. Ordinary gallery, search, favorites, and Trash queries exclude concealed media. Entering Hidden folders requires a short-lived vault re-authentication grant; folder PIN/pattern locks require separate, short-lived grants for every locked ancestor. Grants are in memory and cleared on vault lock or app background. Folder locks do not create an independent media-encryption domain: media remains encrypted by its vault key, and the extra lock is an application access gate. Deleted protected media stays classified as private; if its original folder is gone, restore uses a hidden recovery folder rather than visible root.

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
