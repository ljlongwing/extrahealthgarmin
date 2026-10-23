# Security Policy

Extra Health Garmin handles sensitive data: Health Connect records, Garmin sign-in
credentials and OAuth tokens (AES-encrypted via the Android Keystore). Please report
security problems responsibly.

## Supported versions

| Version | Supported |
| ------- | --------- |
| Latest release (`v*` tags) | ✅ |
| Anything older / `main` HEAD | Best effort |

## Reporting a vulnerability

**Do not open a public GitHub issue for security problems.**

Use GitHub's private vulnerability reporting instead:

1. Go to [Security advisories](https://github.com/zamotkin/extrahealthgarmin/security/advisories)
2. Click **Report a vulnerability**

Include: app version, Android version, what an attacker could achieve, and steps to
reproduce. Debug logcats are generally safe to attach — the app redacts credentials and
tokens from its own logs (`RedactingHttpLogger`).

Expect a response within 7 days. Please allow 90 days before public disclosure; you will
be credited in the advisory unless you prefer to stay anonymous.

## Scope

- Garmin authentication, token storage and refresh (`garmin/`)
- Health Connect record handling and local sync state (`health/`, `data/`)
- FIT file generation and upload

Out of scope: vulnerabilities in Garmin Connect or Health Connect themselves, and issues
that require an already-compromised device (rooted phone, unlocked bootloader, malware
with full device access).
