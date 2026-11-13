# Security policy

## Reporting a vulnerability

Report it privately through GitHub: on this repository's **Security** tab, choose **Report a vulnerability**. The app leads there too: Settings → About → Report a bug, then "Security vulnerability".

Please include:

- what an attacker can do, and what they need to do it (network position, a contact, physical access to the phone);
- steps to reproduce it, or a proof of concept;
- the app version from Settings → About.

You'll get an answer within a week. Please keep the details private until a fix has shipped.

## Scope

In scope:

- the Rust core: MLS, transport, storage, media encryption;
- the Android app;
- anything that reveals message content, contacts, identity keys or metadata beyond what [the design](README.md) says relays and media servers can see.

Out of scope:

- attacks that require an unlocked, compromised phone;
- relay or media server operators seeing what the design already exposes to them: when a message passed through, its size, and the sender's IP address when Tor is off.
