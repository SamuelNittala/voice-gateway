package dev.nittala.voicegateway

import org.junit.Assert.assertEquals
import org.junit.Test

/** Spec (A3): the app's entry point is `MainActivity` in the `dev.nittala.voicegateway` package. */
class SkeletonTest {
    @Test
    fun mainActivityLivesInTheAppPackage() {
        assertEquals("dev.nittala.voicegateway", MainActivity::class.java.packageName)
    }
}
