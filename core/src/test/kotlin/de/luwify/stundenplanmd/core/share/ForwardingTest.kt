package de.luwify.stundenplanmd.core.share

import de.luwify.stundenplanmd.core.Fixture
import de.luwify.stundenplanmd.core.model.CourseSetting
import de.luwify.stundenplanmd.core.model.Person
import de.luwify.stundenplanmd.core.model.PersonalChange
import de.luwify.stundenplanmd.core.model.Plan
import de.luwify.stundenplanmd.core.schedule.Schedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class ForwardingTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val today = LocalDate.of(2026, 10, 16)

    private fun ok(result: ShareResult) = (result as? ShareResult.Ok ?: error("not ok: $result")).plan

    /** The friend's plan as the repository stores it after an import. */
    private fun stored(shared: SharedPlan) = Plan(
        personId = shared.personId,
        importedAt = Instant.parse("2026-10-13T10:00:00Z"),
        source = "share",
        events = shared.events,
        exportedOn = shared.exportedOn,
        planDate = shared.planDate,
        sharedSettings = shared.settings,
        sharedChanges = shared.changes,
    )

    @Test
    fun aSharedFriendIsPassedOnAsTheyShared() {
        val series = Fixture.events.first { it.recurrence != null }
        val original = SharedPlan(
            personId = "0123456789abcdef",
            name = "Max Müller",
            exportedOn = LocalDate.of(2026, 10, 12),
            planDate = LocalDate.of(2026, 10, 11),
            events = Fixture.events,
            settings = mapOf(series.courseKey to CourseSetting(series.courseKey, series.title, shortName = "N4", color = 0x3366CC)),
            complete = true,
            changes = listOf(PersonalChange(series.uid, series.start.toLocalDate().plusWeeks(1), cancelled = true)),
        )
        val received = ok(ShareCodec.parse(ShareCodec.file(original)))
        // My local name for them stays mine.
        val person = Person(received.personId, received.name, isMe = false, localName = "Maxi", favorite = true)

        val forwarded = Forwarding.of(person, stored(received), today, zone)!!
        val back = ok(ShareCodec.parse(ShareCodec.file(forwarded)))
        assertEquals(received, back)
        assertEquals("Max Müller", back.name)
        assertEquals(LocalDate.of(2026, 10, 12), back.exportedOn)
        assertEquals(Schedule(received.events, received.changes).occurrences, Schedule(back.events, back.changes).occurrences)
        // A QR still fits.
        assertTrue(ShareCodec.qrText(forwarded).length < ShareCodec.QR_MAX_CHARS)
    }

    @Test
    fun anIcsFriendGetsAStableIdFromTheLocalOne() {
        val person = Person("ics-00112233445566ff", "Lena", isMe = false)
        val plan = Plan(person.id, Instant.parse("2026-10-13T22:30:00Z"), "QIS-LSF HIS eG", Fixture.events, planDate = LocalDate.of(2026, 10, 14))
        val forwarded = Forwarding.of(person, plan, today, zone)!!
        assertEquals("00112233445566ff", forwarded.personId)
        assertEquals(LocalDate.of(2026, 10, 14), forwarded.planDate)
        assertEquals(LocalDate.of(2026, 10, 14), forwarded.exportedOn)
        val back = ok(ShareCodec.parse(ShareCodec.file(forwarded)))
        assertEquals("00112233445566ff", back.personId)
        assertEquals(Schedule(Fixture.events).occurrences.size, Schedule(back.events).occurrences.size)
        // Without a plan date: the import day (local time).
        assertEquals(LocalDate.of(2026, 10, 14), Forwarding.of(person, plan.copy(planDate = null), today, zone)!!.planDate)
    }

    @Test
    fun meAndOddIdsAreNotForwarded() {
        val plan = Plan("me", Instant.EPOCH, null, Fixture.events)
        assertNull(Forwarding.of(Person("me", "Ich", isMe = true), plan, today, zone))
        assertNull(Forwarding.of(Person("xyz", "X", isMe = false), plan.copy(personId = "xyz"), today, zone))
        val friend = Person("0123456789abcdef", "Max", isMe = false)
        assertNull(Forwarding.of(friend, plan.copy(personId = friend.id, events = emptyList()), today, zone))
        assertNull(Forwarding.of(friend, plan, today, zone))
    }
}
