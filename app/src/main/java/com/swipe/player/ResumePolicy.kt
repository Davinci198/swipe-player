package com.swipe.player

/**
 * Poziția (ms) de la care se reia un videoclip salvat în istoric; 0 = de la început.
 * Nu depinde de player.duration (care e TIME_UNSET înainte de prepare()), ci de
 * durata/progresul salvate: un videoclip terminat (progres >= 95%) sau aflat la mai
 * puțin de 3s de final pornește de la început, altfel ar ajunge direct în STATE_ENDED.
 */
internal fun pozitieDeReluare(pozitieMs: Long, durataMs: Long, progres: Int): Long {
    if (pozitieMs <= 0L) return 0L
    if (progres >= 95) return 0L
    if (durataMs > 0L && durataMs - pozitieMs < 3_000L) return 0L
    return pozitieMs
}
