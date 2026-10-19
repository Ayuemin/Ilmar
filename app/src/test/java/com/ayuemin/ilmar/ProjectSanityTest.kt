package com.ayuemin.ilmar

import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectSanityTest {
    @Test fun packageNameIsStable() {
        assertEquals("com.ayuemin.ilmar", BuildConfig.APPLICATION_ID)
    }
}
