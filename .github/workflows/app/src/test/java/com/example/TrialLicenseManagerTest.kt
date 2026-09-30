package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.licensing.TrialLicenseManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrialLicenseManagerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("sys_sec_license_store", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun testInitializationSets7DaysRemaining() {
        TrialLicenseManager.ensureInitialized(context)
        val remaining = TrialLicenseManager.getRemainingDays(context)
        assertEquals(7, remaining)
        assertFalse(TrialLicenseManager.isTrialExpired(context))
        assertTrue(TrialLicenseManager.isAppUsable(context))
    }

    @Test
    fun testValidActivationCodeUnlocksApp() {
        TrialLicenseManager.ensureInitialized(context)
        assertFalse(TrialLicenseManager.isLifetimeActivated(context))

        // Clé maîtresse autorisée
        val success = TrialLicenseManager.activateWithCode(context, "PASTILLE-2026-VIP")
        assertTrue(success)
        assertTrue(TrialLicenseManager.isLifetimeActivated(context))
        assertTrue(TrialLicenseManager.isAppUsable(context))
        assertEquals(999, TrialLicenseManager.getRemainingDays(context))
    }

    @Test
    fun testInvalidActivationCodeFails() {
        TrialLicenseManager.ensureInitialized(context)
        val wrongCodeSuccess = TrialLicenseManager.activateWithCode(context, "PIRATE-CODE-1234")
        assertFalse(wrongCodeSuccess)
        assertFalse(TrialLicenseManager.isLifetimeActivated(context))
    }

    @Test
    fun testSha256IsDeterministicAndNonEmpty() {
        val hash1 = TrialLicenseManager.computeSha256("TEST_CODE", "SALT_123")
        val hash2 = TrialLicenseManager.computeSha256("TEST_CODE", "SALT_123")
        assertEquals(hash1, hash2)
        assertEquals(64, hash1.length) // 256 bits = 64 caractères hexadécimaux
    }
}
