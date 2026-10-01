package io.github.imostrovskiy.untether

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdaterTest {
    @Test
    fun newer() {
        for ((v, current, want) in listOf(
            Triple("0.2.4", "0.2.3", true),
            Triple("0.10.0", "0.9.9", true),
            Triple("1.0.0", "0.99.99", true),
            Triple("0.2.3", "0.2.3", false),
            Triple("0.2.2", "0.2.3", false),
            Triple("0.2.4", "0.2.3-dev", false), // development builds are left alone
            Triple("0.2.4", "0.0.0-dev.12", false),
        )) assertEquals("$v vs $current", want, Updater.newer(v, current))
    }
}
