# Forgejo bridge

Older installs (up to 1.0.2) read `updates.json` from the old self-hosted Forgejo release repo and download APKs
only from there (no redirects). The bridge release, the first version that reads GitHub, is therefore published
twice with the **same APK**:

1. `tools/release.sh <version>`: builds, verifies and publishes it on GitHub (its updates.json points to GitHub).
2. `tools/forgejo/release-forgejo.sh <version> [--dry-run]`: takes `build/release/<version>/` from step 1 (APK and
   source archive, not rebuilt), checks that it is the APK of the GitHub release, uploads it to the Forgejo release
   repo and adds it to Forgejo's updates.json (with the Forgejo download URL). After installing it, the app reads
   GitHub.

`tools/forgejo/withdraw-forgejo.sh <version>` withdraws a version in Forgejo's updates.json.

Settings: `release/forgejo.conf` (git-ignored), sourced after `release/app.conf`:

```bash
FORGEJO_HOST="git.example.org"
# API calls go to this address directly (curl --resolve), e.g. when the API is only open from the local network.
NAS_IP="192.0.2.10"
RELEASE_OWNER="owner"
RELEASE_REPO="app-releases"
RELEASE_BRANCH="main"
TOKEN_FILE="$HOME/.config/<name>/token"
```
