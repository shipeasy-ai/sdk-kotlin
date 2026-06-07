package ai.shipeasy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TelemetryTest {
    // 1) basic telemetry send works for each entity call, hitting the right URL.
    @Test
    fun firesPerEntity() {
        val sent = mutableListOf<String>()
        val t = Telemetry("https://e.x", "srv", "server", "prod", false) { sent.add(it) }

        t.emit("gate", "g")
        t.emit("config", "c")
        t.emit("experiment", "e")
        t.emit("ks", "k")

        assertEquals(4, sent.size)
        assertTrue(sent[0].endsWith("/gate/g"))
        assertTrue(sent[1].endsWith("/config/c"))
        assertTrue(sent[2].endsWith("/experiment/e"))
        assertTrue(sent[3].endsWith("/ks/k"))
        sent.forEach {
            assertTrue(it.startsWith("https://e.x/t/"))
            assertFalse(it.contains("srv")) // raw key never appears in the URL
        }
    }

    // 2) telemetry is not sent when disabled in settings.
    @Test
    fun disabledSendsNothing() {
        val sent = mutableListOf<String>()
        val t = Telemetry("https://e.x", "srv", "server", "prod", true) { sent.add(it) }

        t.emit("gate", "g")
        t.emit("config", "c")
        t.emit("experiment", "e")

        assertTrue(sent.isEmpty())
    }
}
