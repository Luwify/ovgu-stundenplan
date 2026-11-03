package de.luwify.stundenplanmd.core.compat

import de.luwify.stundenplanmd.core.ical.LsfImport
import de.luwify.stundenplanmd.core.model.Hidden
import de.luwify.stundenplanmd.core.model.PlanZone
import de.luwify.stundenplanmd.core.model.Subjects
import de.luwify.stundenplanmd.core.schedule.PersonalChanges
import de.luwify.stundenplanmd.core.schedule.Schedule
import de.luwify.stundenplanmd.core.schedule.Together
import de.luwify.stundenplanmd.core.share.ShareCodec
import de.luwify.stundenplanmd.core.share.ShareFrames
import de.luwify.stundenplanmd.core.share.ShareResult
import de.luwify.stundenplanmd.core.share.SharedPlan
import de.luwify.stundenplanmd.core.share.Forwarding
import de.luwify.stundenplanmd.core.store.BackupFileResult
import de.luwify.stundenplanmd.core.store.BackupFormat
import de.luwify.stundenplanmd.core.store.DisplayBackup
import de.luwify.stundenplanmd.core.store.MyShare
import de.luwify.stundenplanmd.core.store.StoreOps
import de.luwify.stundenplanmd.core.store.StoredData
import de.luwify.stundenplanmd.core.store.SubjectPalette
import de.luwify.stundenplanmd.core.store.ThemeBackup
import de.luwify.stundenplanmd.core.store.UpdatesBackup
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import kotlin.io.encoding.Base64

/**
 * Compiled into both jvmTest and jsTest: the same steps on the same input give a canonical description (state JSON,
 * schedules, together matching, subjects, share payloads and files, animated frames, backup). The JVM run writes it as
 * golden values (CompatGolden, generated), the JS test computes it again and must match line for line: JS and JVM
 * builds of :core then read and write the same bytes.
 */
object CompatProbe {
    /** 2026-11-03T10:00:00Z. */
    val NOW: Instant = Instant.ofEpochSecond(1793700000)
    const val NOW_MS: Double = 1793700000000.0
    const val MY_ID = "0123456789abcdef"
    const val FRIEND_ID = "00000000000000aa"

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun b64(b: ByteArray) = Base64.encode(b)
    fun unb64(s: String) = Base64.decode(s)

    /** My plan imported from [ics], as the web app does it ([StoreOps.importIcs] on empty data). */
    fun imported(ics: String): StoredData = StoreOps.importIcs(StoredData(), LsfImport.parse(ics), NOW).data

    /** A friend made from the same export (other id, two thirds of the events), sent through a share file. */
    fun friendShare(ics: String): SharedPlan = SharedPlan(
        personId = FRIEND_ID,
        name = "Tom",
        exportedOn = LocalDate.of(2026, 10, 30),
        planDate = LocalDate.of(2026, 10, 20),
        events = LsfImport.parse(ics).events.filterIndexed { i, _ -> i % 3 != 0 },
        settings = emptyMap(),
        complete = true,
    )

    /** Imported plan + share identity + a style + own changes + a hidden date + the friend. */
    fun rich(ics: String): StoredData {
        var d = imported(ics).copy(share = MyShare(MY_ID, "Alex"))
        val subject = Subjects.build(d.myEvents, d.courseSettings).first()
        d = d.copy(courseSettings = Subjects.setStyle(d.courseSettings, subject, "Ana", 0x8E24AA))
        val occ = d.mySchedule().occurrences
        d = d.copy(personalChanges = PersonalChanges.setCancelled(emptyList(), occ[3].ref, occ[3].event.title, true))
        val h = occ[7]
        d = d.copy(hidden = mapOf(StoredData.ME to listOf(Hidden(Hidden.Kind.DATE, courses = setOf(h.event.courseKey), uid = h.ref.uid, date = h.ref.date, title = h.event.title))))
        val friend = (ShareCodec.parse(ShareCodec.file(friendShare(ics))) as ShareResult.Ok).plan
        d = (StoreOps.importFriend(d, friend, NOW) as StoreOps.FriendResult.Imported).data
        return d
    }

    private fun schedule(s: Schedule): String = (
        s.occurrences.map { "O ${it.event.uid} ${it.start} ${it.end} ${it.status} ${it.room} ${it.personal?.cancelled}" } +
            s.cancellations.map { "C ${it.event.uid} ${it.start} ${it.end} ${it.byMe}" }
        ).joinToString("\n")

    /** The java.time subset :core uses, over DST switches, week 53 years, leap days and odd inputs. */
    fun time(): String = buildString {
        val zone = java.time.ZoneId.of("Europe/Berlin")
        val utc = java.time.ZoneId.of("UTC")
        var t = Instant.ofEpochSecond(1_577_836_800) // 2020-01-01
        while (t.epochSecond < 1_924_992_000) { // 2031-01-01
            val z = t.atZone(zone)
            appendLine("$t ${z.toLocalDateTime()} ${z.toEpochSecond()} ${z.toLocalDate().dayOfWeek} ${de.luwify.stundenplanmd.core.model.WeekRhythm.weekOf(z.toLocalDate())}")
            t = t.plusSeconds(86_400L * 3 + 3_601)
        }
        for (y in listOf(2021, 2024, 2026, 2027, 2030)) for ((m, d) in listOf(3 to 28, 3 to 29, 3 to 30, 3 to 31, 10 to 25, 10 to 26, 10 to 27, 10 to 31)) {
            for (h in listOf(0, 1, 2, 3)) {
                val l = java.time.LocalDateTime.of(y, m, d, h, 30)
                val z = l.atZone(zone)
                appendLine("$l -> ${z.toInstant()} ${z.toLocalDateTime()} ${l.atZone(utc).withZoneSameInstant(zone).toLocalDateTime()}")
            }
        }
        for (e in listOf(-800_000L, -1L, 0L, 59L, 10_956L, 20_454L, 2_932_896L)) {
            val d = LocalDate.ofEpochDay(e)
            appendLine("$e $d ${d.toEpochDay()} ${d.dayOfWeek} ${d.plusDays(400)} ${java.time.temporal.ChronoUnit.DAYS.between(d, LocalDate.of(2026, 1, 1))}")
        }
        val f = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
        for (s in listOf("20261103T101500", "20260229T000000", "2026113T101500", "20261103T246000", "20261103T101500Z")) {
            appendLine("$s " + runCatching { java.time.LocalDateTime.parse(s, f).toString() }.getOrElse { "error" })
        }
        for (s in listOf("PT1H30M", "P1D", "PT0S", "-PT15M", "P1W", "PT90M", "PT1.5S", "")) {
            appendLine("$s " + runCatching { java.time.Duration.parse(s).let { "$it ${it.toMinutes()}" } }.getOrElse { "error" })
        }
        for (s in listOf("2026-11-03T10:15:30.123Z", "2026-11-03T10:15:30Z", "2026-11-03T10:15:30.123456789Z", "2026-11-03T10:15:30.120Z")) {
            appendLine("$s " + Instant.parse(s) + " " + Instant.parse(s).toEpochMilli())
        }
        for (s in listOf("2026-11-03T10:15", "2026-11-03T10:15:30", "2026-11-03T10:15:30.5", "2026-11-03T00:00:00")) {
            appendLine("$s " + java.time.LocalDateTime.parse(s))
        }
    }

    /** name -> value, in a fixed order. */
    fun describe(ics: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        out += "time" to time()
        val imported = imported(ics)
        out += "importedState" to json.encodeToString(StoredData.serializer(), imported)
        val d = rich(ics)
        out += "richState" to json.encodeToString(StoredData.serializer(), d)
        val reread = json.decodeFromString(StoredData.serializer(), json.encodeToString(StoredData.serializer(), d))
        out += "richStateRoundTrip" to (reread == d).toString()
        val schedules = mapOf(StoredData.ME to d.mySchedule()) + d.friends.associate { it.id to d.schedule(d.planOf(it.id)!!) }
        for ((id, s) in schedules) {
            out += "schedule:$id" to schedule(s)
            out += "together:$id" to Together.of(s, schedules.filterKeys { it != id }.toList()).toString()
            val events = d.eventsOf(id)
            out += "subjects:$id" to Subjects.resolved(events, d.settingsOf(id, events), SubjectPalette::defaultColor).toString()
            out += "hours:$id" to s.hours.toString()
            out += "week:$id" to s.week(LocalDate.of(2026, 11, 2)).toString()
        }
        val mine = StoreOps.mySharedPlan(d, d.share, LocalDate.of(2026, 11, 3), NOW)!!
        val qr = ShareCodec.qrPayload(mine)
        out += "qr:me" to b64(qr)
        out += "file:me" to b64(ShareCodec.file(mine))
        out += "qrText:me" to ShareCodec.qrText(mine)
        out += "legacyQrText:me" to ShareCodec.payload(mine, complete = false).let(::b64)
        val enc = ShareFrames.Encoder(qr)
        out += "frames:me" to (0 until enc.k + 3).joinToString("\n") { enc.frame(it) }
        val friend = Forwarding.of(d.person(FRIEND_ID)!!, d.planOf(FRIEND_ID)!!, LocalDate.of(2026, 11, 3), PlanZone)!!
        out += "qr:friend" to b64(ShareCodec.qrPayload(friend))
        out += "file:friend" to b64(ShareCodec.file(friend))
        out += "decoded:me" to (ShareCodec.decode(qr) as ShareResult.Ok).plan.toString()
        val backup = BackupFormat.encode(d, ics, ThemeBackup("system", "system", 0x7839D0, false, ""), "system", UpdatesBackup(), DisplayBackup(), NOW)
        out += "backup" to backup
        out += "backupRestored" to ((BackupFormat.decode(backup) as BackupFileResult.Ok).backup.data == d).toString()
        // Every edit the web app makes, and the result shared and backed up.
        val edits = CompatEdits.run(ics)
        for ((name, state) in edits) out += "edit:$name" to json.encodeToString(StoredData.serializer(), state)
        val e = edits.last().second
        out += "edits:schedule:me" to schedule(e.mySchedule())
        out += "edits:week:me" to e.mySchedule().week(LocalDate.of(2026, 10, 12)).toString()
        for (f in e.friends) out += "edits:schedule:${f.id}" to schedule(e.schedule(e.planOf(f.id)!!))
        val editsShare = StoreOps.mySharedPlan(e, e.share, LocalDate.of(2026, 11, 3), NOW)!!
        out += "edits:file:me" to b64(ShareCodec.file(editsShare))
        out += "edits:qr:me" to b64(ShareCodec.qrPayload(editsShare))
        val editsBackup = BackupFormat.encode(e, ics, ThemeBackup("system", "system", 0x7839D0, false, ""), "system", UpdatesBackup(), DisplayBackup(), NOW)
        out += "edits:backup" to editsBackup
        out += "edits:backupRestored" to ((BackupFormat.decode(editsBackup) as BackupFileResult.Ok).backup.data == e).toString()
        return out
    }
}
