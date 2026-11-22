package com.vknxworks.soundctrl.device

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A session closed while a connect is still running must not leave a socket open (the Bluetooth parts can't run
 * on the JVM, so this checks the code keeps the two guards).
 */
class ConnectCleanupTest {
    private fun src(path: String): String {
        val f = File("src/main/java/com/vknxworks/soundctrl/$path")
        assertTrue("sources not found from ${File(".").absolutePath}", f.isFile)
        return f.readText()
    }

    @Test fun sppConnectClosesTheSocketWhenCancelled() {
        val spp = src("ble/SppLink.kt")
        val connect = spp.substringAfter("suspend fun connect(").substringBefore("override suspend fun write(")
        val handler = connect.substringAfter("catch (e: CancellationException)")
        assertTrue("SppLink.connect handles cancellation", connect.contains("catch (e: CancellationException)"))
        assertTrue("and closes the socket before rethrowing", handler.substringBefore("throw e").contains("s.close()"))
    }

    @Test fun sessionCloseAlsoClosesALinkStillConnecting() {
        val session = src("device/DeviceSession.kt")
        val close = session.substringAfter("fun close() {").substringBefore("\n    }")
        assertTrue(close.contains("connecting?.close()"))
        val once = session.substringAfter("private suspend fun connectOnce()")
        assertTrue(once.indexOf("connecting = spp") in 0 until once.indexOf("spp.connect()"))
        assertTrue(once.indexOf("connecting = gatt") in 0 until once.indexOf("gatt.connect(le)"))
    }
}
