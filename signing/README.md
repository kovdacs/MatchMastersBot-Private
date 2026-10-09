# STABLE DEBUG KEY — NEVER USE FOR RELEASE

`match3-stable-debug.keystore` is a **debug-only** signing key for this private repository.

- It has no production security value.
- Password and key password are the standard debug password `android`.
- Alias: `androiddebugkey`.
- Debug builds (local and GitHub Actions `analyzer-ci`) sign with this file.
- The release build type does **not** use this key.
- Do not upload this keystore to a release track or reuse it as a production certificate.

Certificate subject includes `OU=STABLE DEBUG KEY - NEVER USE FOR RELEASE`.
