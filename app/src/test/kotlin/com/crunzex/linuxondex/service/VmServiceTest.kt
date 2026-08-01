package com.crunzex.linuxondex.service

import android.content.pm.ServiceInfo
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VmServiceTest {

    @Test
    fun android13StartsForegroundServiceWithoutAndroid14Type() {
        assertNull(VmService.foregroundServiceTypeForSdk(Build.VERSION_CODES.TIRAMISU))
    }

    @Test
    fun android14UsesDeclaredSpecialUseType() {
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            VmService.foregroundServiceTypeForSdk(Build.VERSION_CODES.UPSIDE_DOWN_CAKE),
        )
    }
}
