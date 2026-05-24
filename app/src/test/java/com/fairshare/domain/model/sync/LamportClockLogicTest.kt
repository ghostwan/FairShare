package com.fairshare.domain.model.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class LamportClockLogicTest {

    @Test
    fun `tickLocal increments by one`() {
        assertEquals(1L, LamportClockLogic.tickLocal(0L))
        assertEquals(43L, LamportClockLogic.tickLocal(42L))
    }

    @Test
    fun `merge returns max when remote is ahead`() {
        assertEquals(10L, LamportClockLogic.merge(local = 3L, remote = 10L))
    }

    @Test
    fun `merge returns local when local is ahead or equal`() {
        assertEquals(10L, LamportClockLogic.merge(local = 10L, remote = 4L))
        assertEquals(10L, LamportClockLogic.merge(local = 10L, remote = 10L))
    }

    @Test
    fun `emit-after-receive composes to strictly greater than both`() {
        val local = 5L
        val remote = 8L
        val next = LamportClockLogic.tickLocal(LamportClockLogic.merge(local, remote))
        assertEquals(9L, next)
        // strictly greater than both inputs
        assert(next > local && next > remote)
    }
}
