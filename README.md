<div align="center">

<img src="android/app/src/main/res/mipmap-xxxhdpi/ic_launcher_art.png" width="96" alt="Alpha" />

# Alpha

**A private messenger with no phone number, no central server and no one in the middle.**

End-to-end encrypted with MLS, carried over Nostr, optionally through Tor.
The core is written in Rust; the app is native Android.

[![Build](https://github.com/Vincentdiligent/AlphaMessenger/actions/workflows/build.yml/badge.svg)](https://github.com/Vincentdiligent/AlphaMessenger/actions/workflows/build.yml)
[![License: AGPL v3](https://img.shields.io/badge/license-AGPL--3.0-blue.svg)](LICENSE)
![Android 8+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
![Rust](https://img.shields.io/badge/core-Rust-B7410E?logo=rust&logoColor=white)
![MLS](https://img.shields.io/badge/E2EE-MLS%20RFC%209420-6E56CF)
![Nostr](https://img.shields.io/badge/network-Nostr-8E44AD)
![Tor](https://img.shields.io/badge/Tor-built--in-7D4698?logo=torproject&logoColor=white)

[Download](https://github.com/Vincentdiligent/AlphaMessenger/releases) · [Under the hood](#under-the-hood) · [What others can see](#what-others-can-see) · [Build](#build-it-yourself) · [Security](SECURITY.md)

</div>

> Screenshots are coming soon.

## Why Alpha

Most messengers know who you are (your phone number), who you talk to (their servers route every message) and when. Alpha is built so that there is nobody in a position to know:

- **No phone number, no email, no account server.** Your account is a key pair created on your phone, backed up as a recovery phrase.
- **No servers of our own.** Messages travel through public [Nostr](https://github.com/nostr-protocol/nips) relays you choose, media through Blossom servers. They only ever see ciphertext.
- **Encryption by default, everywhere.** One-to-one chats, groups and channels all use [MLS (RFC 9420)](https://www.rfc-editor.org/rfc/rfc9420), the IETF standard for group end-to-end encryption, with forward secrecy and post-compromise security.
- **Tor built in.** One switch routes all traffic through Tor. Secret chats always use it.
- **No ads, no analytics, no trackers, no Google push.** The app reports nothing about you to anyone.
- **Open source** under the AGPL, so anyone can check all of the above.

## Features

| | |
|---|---|
| 💬 **Chats** | One-to-one, groups, channels with comments, replies, reactions, edits, polls, link previews, stickers, GIFs |
| 🎙️ **Voice & video** | Voice notes, round video messages, voice and video calls |
| 🖼️ **Media** | Photos, videos and albums of any size, files, streaming playback, in-chat gallery |
| 🕶️ **Privacy tools** | Secret chats (Tor only), view-once media, disappearing messages, anonymous channel admins, chat requests from strangers |
| 🔒 **Lock** | PIN or fingerprint, a panic PIN that wipes the phone's data, screenshot protection for sensitive views |
| 📱 **Linked devices** | Your account on several phones, each with its own key |
| 💾 **Backup & export** | Encrypted backups, export of a chat, a channel or your profile |
| 🗂️ **Organize** | Folders, archive, pinned chats, saved messages with tags, search across all chats |
| 🤖 **Assistant** | An optional AI assistant that talks only to the service you set up, never through us |
| 🎨 **Looks** | Themes, chat wallpapers, day and night modes |

## Under the hood

| Layer | What it uses |
|---|---|
| **Identity** | A secp256k1 key pair (Nostr). No identifiers tied to you in the real world. |
| **Messages** | MLS, ciphersuite `MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519`, via [OpenMLS](https://github.com/openmls/openmls) |
| **Transport** | Nostr relays. One-to-one invitations go as gift-wrapped events (NIP-59), which hide the sender. Group traffic goes as sealed events under rotating per-group keys. |
| **Anonymity** | Embedded Tor (runs in its own process), with stream isolation |
| **Media** | Encrypted on the phone with XChaCha20-Poly1305, streamed in chunks, stored on Blossom servers as opaque blobs |
| **On the phone** | SQLCipher database. The key is derived from your recovery password with Argon2id and kept in the Android Keystore, so the app can start receiving after a reboot without asking you. |
| **Backups** | Argon2id + XChaCha20-Poly1305 |
| **Push** | No Google services. An [ntfy](https://ntfy.sh) wake-up that carries no content, only "check for new messages". |
| **Core** | Rust (`rust-core/`), shared with the app through [UniFFI](https://github.com/mozilla/uniffi-rs) |
| **App** | Kotlin and Jetpack Compose (`android/`) |

## What others can see

Being honest about metadata matters more than slogans.

| Who | Sees | Never sees |
|---|---|---|
| **Relays** | That encrypted events passed through them, their size and time; your IP address if Tor is off | Who you are, who you talk to, any content |
| **Media servers** | That an encrypted blob was stored or fetched; your IP if Tor is off | What the file is, who sent it or to whom |
| **ntfy** | That a random topic got a wake-up | Anything about the message |
| **Your contact during a call** | Your IP address (calls are peer-to-peer WebRTC; STUN servers see it too) | — |
| **Someone holding your unlocked phone** | What the app shows, unless the app lock is on | — |

Turn on private mode (Tor) to hide your IP from relays and media servers. Secret chats always go through Tor and have no calls.

## Download

Signed builds will be published on the [Releases](https://github.com/Vincentdiligent/AlphaMessenger/releases) page together with their SHA-256 checksums. Every push is also built by [GitHub Actions](https://github.com/Vincentdiligent/AlphaMessenger/actions) from this source.

## Build it yourself

Requirements:

- Linux, macOS or WSL (the vendored OpenSSL needs Perl and make);
- JDK 17, and the Android SDK with NDK r27 or newer;
- Rust (the version is pinned in `rust-toolchain.toml`) and [`cargo-ndk`](https://github.com/bbqsrc/cargo-ndk).

```sh
# 1. The core, for arm64. The script also checks the library against the Kotlin bindings.
export ANDROID_NDK_HOME=/path/to/android-ndk
./build_so.sh

# 2. The app
cd android
./gradlew :app:assembleRelease                 # signed with your key in android/keystore.properties
./gradlew :app:assembleRelease -PallowUnsigned  # or unsigned, to check that it builds
```

If you change the FFI (`rust-core/alpha-ffi/src/alphamessenger.udl`), regenerate the bindings from an unstripped host build:

```sh
cd rust-core
cargo build -p alpha-ffi --lib
cargo run --bin uniffi-bindgen -- generate --library target/debug/libalpha_ffi.so \
    --language kotlin --out-dir ../android/app/src/main/java
```

A build signed with a different key cannot update an installed official build.

## Project layout

```
rust-core/   the core: MLS, Nostr transport, storage, media, sync
android/     the Android app
vendor/      vendored OpenMLS
scripts/     check.sh: format, lint and test the core
```

## Contributing

Issues and pull requests are welcome. Please open an issue to discuss a larger change first. Any change has to keep the promises above: no new identifiers, no new third parties, no plaintext leaving the phone.

## Security

Found a vulnerability? Please report it privately, as described in [SECURITY.md](SECURITY.md). Do not open a public issue for it.

## License

Alpha is free software, released under the [GNU Affero General Public License v3.0](LICENSE).
Third-party components keep their own licenses, listed in [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).
