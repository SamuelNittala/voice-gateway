package dev.nittala.voicegateway

import dev.nittala.voicegateway.core.config.BuildVariant
import org.junit.Assert.assertEquals
import org.junit.Test

/** Spec (A10): the composition root builds the stores with the real build variant (debug allows tailnet http). */
class AppGraphTest {
    @Test
    fun theBuildVariantComesFromTheBuildType() {
        assertEquals(BuildVariant.DEBUG, buildVariantOf(debug = true))
        assertEquals(BuildVariant.RELEASE, buildVariantOf(debug = false))
    }
}
