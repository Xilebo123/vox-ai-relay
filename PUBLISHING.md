# GitHub publishing checklist

## Repository

1. Create a GitHub repository named `vox-ai-relay` or similar.
2. Upload the contents of the source tree, not the outer release bundle.
3. Confirm that `local.properties`, keystores, `.gradle`, and build directories are not committed.
4. Choose and add a license before describing the project as open source. Without a license, normal copyright restrictions remain in effect.
5. Push the `main` branch and confirm that the Android CI workflow succeeds.

## Release 0.8.0

1. Create tag `v0.8.0`.
2. Create a GitHub Release for that tag.
3. Copy the text from `RELEASE_NOTES_0.8.0.md` into the release description.
4. Upload:
   - `Vox-AI-Relay-0.8.0-debug.apk`;
   - `Vox-AI-Relay-0.8.0-source.zip`;
   - `SHA256SUMS.txt`.
5. Mark it as a pre-release while the distributed APK uses debug signing.

## Signing warning

The prepared APK is debug-signed and suitable for direct testing or a GitHub pre-release. A long-lived public release should use a dedicated release keystore stored outside the repository and backed up securely.

Android upgrades require the same application ID and signing key. The application ID is intentionally retained as `pl.marcin.openclaw`; changing it would create a separate installation. Never publish a private signing key or passwords in GitHub Actions logs or repository files.

