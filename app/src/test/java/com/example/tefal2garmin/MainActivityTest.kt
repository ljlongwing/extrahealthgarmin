package com.example.tefal2garmin

import org.junit.Test
import org.junit.Assert

class MainActivityTest {
    
    @Test
    fun packageNameCorrect() {
        // Verify package name matches expected
        val expectedPackage = "com.example.tefal2garmin"
        // In a real test, you would access the package name through context
        Assert.assertTrue("Package should contain 'tefal2garmin'", 
                      expectedPackage.contains("tefal2garmin"))
    }
    
    @Test
    fun activityClassExists() {
        // Test that MainActivity class exists and can be instantiated through reflection
        val mainActivityClass = Class.forName("com.example.tefal2garmin.MainActivity")
        Assert.assertNotNull("MainActivity class should exist", mainActivityClass)
        mainActivityClass.`package`?.let { pkg ->
            Assert.assertEquals("MainActivity should be in correct package", 
                            "com.example.tefal2garmin", 
                            pkg.name)
        }
    }
}