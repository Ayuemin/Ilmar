package com.ayuemin.ilmar

import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectSanityTest {
    @Test fun trimsChatTitle() {
        assertEquals("Привет", ChatTitle.fromFirstMessage("   Привет   "))
    }
}
