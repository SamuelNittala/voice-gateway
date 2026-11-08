package dev.nittala.voicegateway.core

import org.junit.Assert.assertThrows
import org.junit.Test

/** Spec (A3): `:core` runs as plain JVM code, with no Android classes on its classpath. */
class SkeletonTest {
    @Test
    fun androidClassesAreNotOnTheCoreClasspath() {
        assertThrows(ClassNotFoundException::class.java) {
            Class.forName("android.os.Build")
        }
    }
}
