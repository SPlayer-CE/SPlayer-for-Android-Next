package top.imsyy.splayer_next.android.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanShareManagerTest {
  @Test
  fun observesReceiverBeforeHostAuthorizesCollaboration() {
    val ip = "192.168.255.254"
    LanShareManager.removeDevice(ip)

    try {
      LanShareManager.observeDevice(ip)

      val pending = LanShareManager.getVisibleDevices().find { it.ip == ip }
      assertNotNull(pending)
      assertFalse(pending!!.shareCollab)
      assertFalse(LanShareManager.isCollabAllowed(ip))

      val authorized = LanShareManager.setDeviceCollab(ip, true)
      assertTrue(authorized.shareCollab)
      assertTrue(LanShareManager.isCollabAllowed(ip))
    } finally {
      LanShareManager.removeDevice(ip)
    }
  }
}
