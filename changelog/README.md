One file per release and language: `<version>.de.md` and `<version>.en.md`. Short and written for users (what
changed for them, not how). tools/release.sh puts both into updates.json and the German one plus the English one
into the release text; it refuses to release without them.
