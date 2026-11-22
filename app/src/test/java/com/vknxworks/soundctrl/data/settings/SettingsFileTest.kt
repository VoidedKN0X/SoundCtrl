package com.vknxworks.soundctrl.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** An imported settings file never makes SoundCtrl less private. */
class SettingsFileTest {
    @Test fun hideSwitchesAreNeverTurnedOff() {
        val current = AppSettings(hideScreenshots = true, hideRecents = true)
        val after = SettingsFile.applied(SettingsFileContent(hideScreenshots = false, hideRecents = false), current)
        assertTrue(after.hideScreenshots)
        assertTrue(after.hideRecents)
    }

    @Test fun lockTimeoutOnlyGetsShorter() {
        val current = AppSettings(lockTimeout = LockTimeout.MINUTES_5)
        assertEquals(LockTimeout.MINUTES_5, SettingsFile.applied(SettingsFileContent(lockTimeout = "RESTART"), current).lockTimeout)
        assertEquals(LockTimeout.MINUTES_5, SettingsFile.applied(SettingsFileContent(lockTimeout = "HOUR_1"), current).lockTimeout)
        assertEquals(LockTimeout.MINUTE_1, SettingsFile.applied(SettingsFileContent(lockTimeout = "MINUTE_1"), current).lockTimeout)
    }

    @Test fun removedProtocolLogKeyIsIgnored() {
        val f = SettingsFile.parse("""{"format":"soundctrl-settings","version":1,"autoConnect":false,"protocolLog":true}""")
        assertEquals(false, SettingsFile.applied(f, AppSettings()).autoConnect)
    }

    @Test fun plainSettingsAreTaken() {
        val after = SettingsFile.applied(SettingsFileContent(autoConnect = false), AppSettings())
        assertEquals(false, after.autoConnect)
    }
}
