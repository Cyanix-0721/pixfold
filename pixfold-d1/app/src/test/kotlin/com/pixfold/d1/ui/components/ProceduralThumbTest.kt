package com.pixfold.d1.ui.components

import androidx.compose.ui.graphics.Color
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ProceduralThumbTest {

    @Test
    fun `same seed yields identical color`() {
        assertEquals(seedBaseColor(42), seedBaseColor(42))
        assertEquals(seedAccentColor(42), seedAccentColor(42))
    }

    @Test
    fun `different seeds yield different colors`() {
        assertNotEquals(seedBaseColor(1), seedBaseColor(2))
    }

    @Test
    fun `color is deterministic across many seeds`() {
        for (s in 0..200) {
            assertEquals(seedBaseColor(s), seedBaseColor(s), "seed=$s 应稳定")
        }
    }

    @Test
    fun `accent is darker than base`() {
        for (s in 0..20) {
            val b = seedBaseColor(s)
            val a = seedAccentColor(s)
            val lumB = 0.299 * b.red + 0.587 * b.green + 0.114 * b.blue
            val lumA = 0.299 * a.red + 0.587 * a.green + 0.114 * a.blue
            assertTrue(lumA < lumB, "seed=$s 的 accent 应比 base 暗")
        }
    }

    @Test
    fun `colors are fully opaque`() {
        for (s in 0..20) {
            assertEquals(1f, seedBaseColor(s).alpha)
            assertEquals(1f, seedAccentColor(s).alpha)
        }
    }

    @Test
    fun `display number and line count are bounded and deterministic`() {
        for (s in -50..200) {
            val n = seedDisplayNumber(s)
            assertTrue(n in 1..99, "seed=$s 序号应在 1..99,实际 $n")
            val lines = seedLineCount(s)
            assertTrue(lines in 2..4, "seed=$s 纹样线数应在 2..4,实际 $lines")
        }
        assertEquals(seedDisplayNumber(7), seedDisplayNumber(7))
    }

    @Test
    fun `negative seeds do not crash and stay valid`() {
        // Collections.hashCode 等可能产生负数 seed;不能出现负色相/越界
        val c: Color = seedBaseColor(-12345)
        assertTrue(c.red in 0f..1f && c.green in 0f..1f && c.blue in 0f..1f)
    }
}
