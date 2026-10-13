One file per release and language: `<version>.de.md` and `<version>.en.md`. Until a version is chosen, finished features
collect in `unreleased.de.md` / `unreleased.en.md` (renamed to the version before a release). Short and written for users (what
changed for them, not how). tools/release.sh puts both into updates.json and the German one plus the English one
into the release text; it refuses to release without them.
