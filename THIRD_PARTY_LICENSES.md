# Third-party software

AlphaMessenger is built on the open-source components listed below. Each is used
under its own license; the license texts are distributed with each component.

## Android app

| Component | License |
|---|---|
| AndroidX / Jetpack (Compose, Material 3, AppCompat, WebKit, Activity, Lifecycle, Navigation, WorkManager, DataStore, Biometric, Security Crypto, CameraX, Media3, Core Splashscreen, Google Fonts) | Apache-2.0 |
| Kotlin standard library, kotlinx.coroutines | Apache-2.0 |
| Dagger / Hilt | Apache-2.0 |
| Coil | Apache-2.0 |
| OkHttp | Apache-2.0 |
| Gson | Apache-2.0 |
| ZXing (core) | Apache-2.0 |
| Android Image Cropper | Apache-2.0 |
| MapLibre Native for Android | BSD-2-Clause |
| Stream WebRTC Android (WebRTC) | Apache-2.0 (WebRTC: BSD-3-Clause) |
| JNA | Apache-2.0 or LGPL-2.1 (dual) |
| Tor (tor-android, Guardian Project; jtorctl) | BSD-3-Clause |
| Bricolage Grotesque font (loaded through Google Fonts) | SIL Open Font License 1.1 |
| Lottie for Android (lottie-compose) | Apache-2.0 |
| Animated Noto Emoji © Google LLC (animated emoji set, delivered through the Alpha feed) | CC BY 4.0 |
| Fluent Emoji Animated © Microsoft Corporation (sticker packs, delivered through the Alpha feed) | MIT |
| Tabler Icons (the app's icon shapes) © Paweł Kuna | MIT |
| Emoji names and order (`emoji_catalog.txt`), from Unicode emoji data | Unicode License v3 |

## Rust core

| Component | License |
|---|---|
| OpenMLS 0.8.3 (vendored in `vendor/`) | MIT |
| nostr, nostr-sdk | MIT |
| UniFFI | MPL-2.0 |
| rusqlite + SQLCipher (bundled) | MIT; SQLCipher: BSD-3-Clause |
| OpenSSL (vendored, used by SQLCipher) | Apache-2.0 |
| tokio, tokio-tungstenite | MIT |
| reqwest | MIT or Apache-2.0 |
| BLAKE3 | CC0-1.0, Apache-2.0 or Apache-2.0 WITH LLVM-exception |
| RustCrypto (chacha20poly1305, aes-gcm, argon2, hkdf, hmac, sha2 and related crates) | MIT or Apache-2.0 |
| serde, serde_json, chrono, uuid, rand, futures, tracing, anyhow, thiserror, hex, base64, image | MIT or Apache-2.0 |
| ciborium | Apache-2.0 |
| tokio-socks | MIT |

The complete list of Rust dependencies, including transitive ones, with their
license texts can be generated from `rust-core/Cargo.lock`, for example with
`cargo about generate`.

## Downloaded on the phone, not shipped

| Component | License |
|---|---|
| EasyList and EasyPrivacy filter lists (the browser's ad and tracker blocking) | GPL-3.0 or CC BY-SA 3.0 |
