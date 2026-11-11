package com.airbuds.companion.data.repository

import com.airbuds.companion.data.ble.GattException
import com.airbuds.companion.domain.model.BudsError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BudsErrorMappingTest {

    @Test
    fun `maps a missing Airoha service to an unsupported device`() {
        assertEquals(
            BudsError.UnsupportedDevice,
            GattException("Airoha service not found").toBudsError(),
        )
    }

    @Test
    fun `maps a disabled adapter`() {
        assertEquals(
            BudsError.BluetoothDisabled,
            GattException("Bluetooth is disabled").toBudsError(),
        )
    }

    @Test
    fun `keeps other GATT failures as transport errors`() {
        val error = GattException("GATT connection error (status 133)").toBudsError()
        assertTrue(error is BudsError.Transport)
        assertEquals("GATT connection error (status 133)", (error as BudsError.Transport).message)
    }
}
