package de.luwify.stundenplanmd.core.model

import de.luwify.stundenplanmd.core.Fixture
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class SerializationTest {
    @Test
    fun planRoundTrip() {
        val plan = Plan("me", Instant.parse("2026-10-11T18:48:48Z"), "QIS-LSF HIS eG", Fixture.events)
        val json = Json.encodeToString(Plan.serializer(), plan)
        assertEquals(plan, Json.decodeFromString(Plan.serializer(), json))
    }
}
