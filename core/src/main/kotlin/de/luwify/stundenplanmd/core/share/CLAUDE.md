# Share codec

- **Share codec** (`:core`): binary payload, raw deflate (level 9). QR text = `SPMD:` + Base45(payload)
  (alphanumeric mode, ECC L). File: extension `.stundenplan`, MIME `application/vnd.luwify.stundenplan`,
  content = magic `SPMD` + payload. Payload: format version, flags, 8-byte random person id, export day,
  planDate (my last .ics import = "Stand"), display name, base day, string table (titles, categories,
  lecturers, rooms as `room|alias|capacity` when the raw LOCATION rebuilds exactly, else `=raw`, notes),
  course ids sorted + delta, course settings (course ref, subject key, short name, color), then events column-wise:
  course, appointment id (delta in course), title, category, lecturers, location, note, day offset,
  start/length in quarter hours (escape for other minutes), slot count (0 = single), interval, bitmask of
  cancelled slots. Escapes for non-LSF UIDs and other RRULEs. Format 2 appends own changes (UID, date, series/until,
  s.t., cancelled, room, time; no notes); a plan without shareable changes is still written as 1, so older apps import
  it (they reject 2 as "newer version"). Format 3 appends hidden dates (UID, day) and type parts of short names (index
  into the settings, text); written only when there are any (`ShareCodec.version` = the lowest that holds everything).
  Dropped: off-rhythm EXDATEs, PRODID. Flag bit 4 (since 1.2.2, ignored by older
  readers): plain INTERVAL=2 series are in the calendar-week parity of their first date. Without it (apps before 1.2.2)
  they were counted every 14 days: `share/LegacyShares` re-expands them by parity (end = first parity date on or after
  the old last one, cancelled slots kept by date, `manual-` ones stay 14-day) and, where a series changed (it crossed
  a week 53, so LSF's EXDATEs after it were dropped), sets `SharedPlan.legacy` -> `Plan.legacyShare` -> a hint banner
  in that friend's plan only ("neu teilen lassen"). Tests write old shares with the internal `payload(..., legacy)`.
  **Format 4 (the QR since 1.5, `ShareCodec.qrPayload`):** the file profile's full body (all sections, every note,
  raw SUMMARY; the privacy rules stay: no notes of own changes or manual courses, fully hidden courses left out),
  then version byte 4 + CRC32 of the body + raw deflate 9 with the preset dictionary `ShareDictionary` (generic LSF
  wording; **frozen: part of the format, `QrFormat4Test` pins size and CRC**; another dictionary = new version). Still
  `SPMD:` + Base45 in alphanumeric mode (not byte mode) so older apps read the version byte and say "newer version".
  Files stay at the lowest version 1–3 (complete already, readable by older apps). Old QR codes (1–3, apps before
  1.5: "QR profile" = notes only of single events, capped at 80 chars, series notes dropped, SUMMARY = title) stay
  readable with their `Event.notesShortened` flags (`Event.noteHint`, `NoteHintTest`); tests write them with the
  internal `ShareCodec.legacyQrText`. A file holding the QR text (`SPMD:`) is accepted too (checked before the binary
  magic `SPMD`, which it also starts with). Real plan: format 4 ~2,630 chars (v4 payload ~1.75 KB), file ~1.9 KB.
  Decoder rejects newer versions and caps sizes (256 KB inflated, event and string limits).
- **Animated QR** (`share/ShareFrames`): random linear fountain code over GF(2) on the format-4 payload: frame
  `seq < K` = block seq, later ones the XOR of a pseudo-random half (seed: payload CRC + seq); decoder = incremental
  Gaussian elimination, done at rank K (~K + 2 random frames), CRC check, a foreign payload's frame starts over.
  Frame text `SPMD:` + Base45 of tag 5 (`ShareCodec.FRAME_TAG`, older apps: "newer version"), varint seq, K, length,
  payload CRC32, block of `BLOCK` = 330 B (version 12-L). Experiment branch `experiment/qr` measured the options.
