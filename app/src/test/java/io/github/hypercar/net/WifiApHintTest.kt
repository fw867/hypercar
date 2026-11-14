package io.github.hypercar.net

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class WifiApHintTest {
    private val scan="  3e:af:6d:8e:33:09       5745        -38             2.203    Beam Pro                          [WPA2-PSK][ESS]"

    @Test fun matchesApByCarSuppliedSsidEvenWhenDeviceIdentifierIsNotTheBssid() {
        // 车机 0x5703 只给 SSID：deviceIdentifier 对不上/为空时仍要定位到具体 AP
        val hint=WifiApHint.fromScan(scan,"Beam Pro","54:2f:04:37:44:15")!!
        assertEquals("3e:af:6d:8e:33:09",hint.bssid)
        assertEquals(5745,hint.frequency)
        assertEquals(2.203,hint.ageSeconds,0.001)
        assertEquals("3e:af:6d:8e:33:09",WifiApHint.fromScan(scan,"Beam Pro",null)!!.bssid)
    }

    @Test fun requiresExactSsidMatch() {
        assertNull(WifiApHint.fromScan(scan,"Beam","3e:af:6d:8e:33:09"))
        assertNull(WifiApHint.fromScan(scan,"Beam Pro 2","3e:af:6d:8e:33:09"))
    }

    @Test fun prefersTheApMatchingTheDeviceIdentifier() {
        val older="  3e:af:6d:8e:33:09       5745        -38             9.500    Beam Pro                          [WPA2-PSK][ESS]"
        val newer="  3e:af:6d:8e:33:0a        5745        -52             0.500    Beam Pro                          [WPA2-PSK][ESS]"
        val two=older+"\n"+newer
        assertEquals("3e:af:6d:8e:33:09",WifiApHint.fromScan(two,"Beam Pro","3E:AF:6D:8E:33:09")!!.bssid)
        // 同名但不同 BSSID 时，扫描年龄不能证明哪一个属于本车。
        assertNull(WifiApHint.fromScan(two,"Beam Pro","54:2f:04:37:44:15"))
    }

    @Test fun rejectsMalformedRowsAndUnusableApAddresses() {
        assertNull(WifiApHint.fromScan("malformed","Beam Pro","3e:af:6d:8e:33:09"))
        // 非法/占位的 deviceIdentifier 不参与匹配，但不影响按 SSID 定位 AP
        assertEquals("3e:af:6d:8e:33:09",WifiApHint.fromScan(scan,"Beam Pro","unknown")!!.bssid)
        assertNull(WifiApHint.fromScan(scan.replace("3e:","ff:"),"Beam Pro","3e:af:6d:8e:33:09"))
        assertNull(WifiApHint.fromScan(scan.replace("3e:af:6d:8e:33:09","00:00:00:00:00:00"),"Beam Pro","3e:af:6d:8e:33:09"))
        assertNull(WifiApHint.fromScan(scan.replace("3e:af:6d:8e:33:09","02:00:00:00:00:00"),"Beam Pro","3e:af:6d:8e:33:09"))
    }

    @Test fun acceptsLocallyAdministeredCarHotspotAddress() {
        // BMW DIRECT-* 常用本地管理地址（第一字节 bit1），不能因为 LAA 就丢弃
        val laa="  12:34:56:78:9a:bc       5745        -40             1.000    Test Car                 [WPA2-PSK][ESS]"
        val hint=WifiApHint.fromScan(laa,"Test Car","aa:bb:cc:dd:ee:00")!!
        assertEquals("12:34:56:78:9a:bc",hint.bssid)
    }

    @Test fun stale_malformed_and_invalid_frequency_rows_never_pin_a_request() {
        for ((bad, id) in listOf(
            scan.replace("2.203", "30.001") to "3e:af:6d:8e:33:09",
            scan.replace("5745", "0") to null,
            scan.replace("3e:af:6d:8e:33:09", "3eaf6d8e330900000") to null,
        )) assertNull(WifiApHint.fromScan(bad,"Beam Pro",id))
        val duplicate=scan+"\n"+scan.replace("2.203","1.000")
        assertEquals(1.0,WifiApHint.fromScan(duplicate,"Beam Pro",null)!!.ageSeconds,0.001)
    }

    @Test fun late_identifier_is_read_again_and_a_cached_hit_does_not_start_a_scan() = runTest {
        var scans=0
        val hit=WifiApHint.resolve("Beam Pro",{ null },{ scan },{ scans++ },{ testScheduler.currentTime })
        assertNotNull(hit);assertEquals(0,scans);assertEquals(0L,testScheduler.currentTime)
        val ambiguous=scan+"\n"+scan.replace("3e:af:6d:8e:33:09","3e:af:6d:8e:33:0a")
        val late=WifiApHint.resolve("Beam Pro",{
            "3e:af:6d:8e:33:0a".takeIf { testScheduler.currentTime>=400 }
        },{ ambiguous },{ scans++ },{ testScheduler.currentTime })
        assertEquals("3e:af:6d:8e:33:0a",late!!.bssid)
        assertEquals(1,scans);assertEquals(400L,testScheduler.currentTime)
    }

    @Test fun scan_is_requested_once_and_waiting_is_bounded_and_cancellable() = runTest {
        var starts=0
        assertNull(WifiApHint.resolve("missing",{null},{scan},{starts++},{testScheduler.currentTime}))
        assertEquals(1,starts);assertEquals(WifiApHint.SCAN_WAIT_MS,testScheduler.currentTime)
        assertNull(WifiApHint.resolve("Beam Pro",{null},{throw SecurityException()},{starts++},{testScheduler.currentTime}))
        assertEquals(1,starts)
        try {
            WifiApHint.resolve("Beam Pro",{null},{throw CancellationException("stopped")},{starts++},{testScheduler.currentTime})
            fail("cancellation swallowed")
        } catch (_: CancellationException) { /* expected */ }
    }

    @Test fun new_scan_can_succeed_before_the_old_fixed_delay() = runTest {
        var starts=0
        val result=WifiApHint.resolve("Beam Pro",{null},{
            if (testScheduler.currentTime>=200) scan else ""
        },{starts++},{testScheduler.currentTime})
        assertNotNull(result);assertEquals(1,starts);assertEquals(200L,testScheduler.currentTime)
    }
}
