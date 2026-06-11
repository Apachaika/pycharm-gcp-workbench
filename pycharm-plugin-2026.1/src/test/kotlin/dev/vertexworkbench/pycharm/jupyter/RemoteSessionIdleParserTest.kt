package dev.vertexworkbench.pycharm.jupyter

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RemoteSessionIdleParserTest {
    private fun fixed(iso: String): Clock =
        Clock.fixed(Instant.parse(iso), ZoneOffset.UTC)

    @Test
    fun returnsNullForBlankOrUnparseable() {
        val now = fixed("2026-06-10T12:00:00Z")
        assertNull(formatIdleFor(null, now))
        assertNull(formatIdleFor("", now))
        assertNull(formatIdleFor("not-a-date", now))
    }

    @Test
    fun lessThanOneMinuteShowsLessThan1m() {
        val now = fixed("2026-06-10T12:00:00Z")
        assertEquals("idle <1m", formatIdleFor("2026-06-10T11:59:30Z", now))
    }

    @Test
    fun negativeDiffStillShowsLessThan1m() {
        val now = fixed("2026-06-10T12:00:00Z")
        assertEquals("idle <1m", formatIdleFor("2026-06-10T12:00:30Z", now))
    }

    @Test
    fun minutes() {
        val now = fixed("2026-06-10T12:00:00Z")
        assertEquals("idle 5m", formatIdleFor("2026-06-10T11:55:00Z", now))
        assertEquals("idle 59m", formatIdleFor("2026-06-10T11:01:00Z", now))
    }

    @Test
    fun hours() {
        val now = fixed("2026-06-10T12:00:00Z")
        assertEquals("idle 1h", formatIdleFor("2026-06-10T11:00:00Z", now))
        assertEquals("idle 23h", formatIdleFor("2026-06-09T13:00:00Z", now))
    }

    @Test
    fun days() {
        val now = fixed("2026-06-10T12:00:00Z")
        assertEquals("idle 1d", formatIdleFor("2026-06-09T12:00:00Z", now))
        assertEquals("idle 7d", formatIdleFor("2026-06-03T12:00:00Z", now))
    }
}
