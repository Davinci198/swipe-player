package com.swipe.player

import org.junit.Assert.assertEquals
import org.junit.Test

class ResumePolicyTest {
    @Test
    fun pozitieLaMijloc_seReia() {
        assertEquals(30_000L, pozitieDeReluare(30_000L, 120_000L, 25))
    }

    @Test
    fun videoTerminat_pornesteDeLaInceput() {
        assertEquals(0L, pozitieDeReluare(120_000L, 120_000L, 100))
        assertEquals(0L, pozitieDeReluare(10_000L, 0L, 96))
    }

    @Test
    fun subTreiSecundeDeFinal_pornesteDeLaInceput() {
        assertEquals(0L, pozitieDeReluare(118_000L, 120_000L, 90))
    }

    @Test
    fun durataNecunoscuta_seReiaDupaProgres() {
        assertEquals(15_000L, pozitieDeReluare(15_000L, 0L, 40))
    }

    @Test
    fun pozitieZero_pornesteDeLaInceput() {
        assertEquals(0L, pozitieDeReluare(0L, 120_000L, 0))
    }
}
