package com.airbuds.companion.data.ble

import kotlinx.coroutines.CancellationException

/** Raised when the device answers with a payload the sequence cannot use. */
class RaceInitializationException(message: String) : Exception(message)

/**
 * Runs the initialization sequence from `PROTOCOL.md` §4.
 *
 * The sequence is strict: chip name, device type, NVDM version and TWS role
 * must succeed. The destination listing (`0x0D00`) is optional: a failure means
 * the device is treated as solo, so partner commands stay disabled.
 *
 * The caller owns connection setup; this class only issues commands through
 * [RaceCommandExecutor], which is serialized by [RaceCommandQueue].
 */
class RaceInitializer(
    private val executor: RaceCommandExecutor,
    private val maxInvalidChipNameAttempts: Int = DEFAULT_INVALID_ATTEMPTS,
) {
    init {
        require(maxInvalidChipNameAttempts >= 1) {
            "maxInvalidChipNameAttempts must be at least 1"
        }
    }

    suspend fun initialize(): RaceDeviceInfo {
        val chipName = readChipName()
        val deviceType = readDeviceType(chipName)
        val nvdmVersion = readNvdmVersion()
        val role = readRole()
        val peerId = readPeerIdOrNull()

        return RaceDeviceInfo(
            chipName = chipName,
            deviceType = deviceType,
            nvdmVersion = nvdmVersion,
            role = role,
            peerId = peerId,
        )
    }

    /**
     * Reads the chip name and retries an empty/invalid NVKEY payload.
     *
     * The SDK treats `L == 0` as invalid and tries again; transport failures are
     * already retried by the command queue and are not replayed here.
     */
    private suspend fun readChipName(): String {
        var lastError: RaceInitializationException? = null
        repeat(maxInvalidChipNameAttempts) {
            try {
                val data = readNvkey(RaceIds.NVKEY_CHIP_NAME)
                val name = data.toString(Charsets.US_ASCII)
                    .trimEnd('\u0000')
                    .trim()
                if (name.isEmpty()) {
                    throw RaceInitializationException("Chip name is empty")
                }
                return name
            } catch (error: RaceInitializationException) {
                lastError = error
            }
        }
        throw lastError ?: RaceInitializationException("Chip name could not be read")
    }

    private suspend fun readDeviceType(chipName: String): AirohaDeviceType {
        val nvkeyId = if (usesPrimaryDeviceTypeNvkey(chipName)) {
            RaceIds.NVKEY_DEVICE_TYPE_PRIMARY
        } else {
            RaceIds.NVKEY_DEVICE_TYPE_FALLBACK
        }
        return parseDeviceType(readNvkey(nvkeyId))
    }

    private suspend fun readNvdmVersion(): Int {
        val data = readNvkey(RaceIds.NVKEY_NVDM_VERSION)
        if (data.isEmpty()) {
            throw RaceInitializationException("NVDM version payload is empty")
        }
        return data[0].toInt() and 0xFF
    }

    private suspend fun readRole(): TwsRole {
        val response = executor.execute(RaceFrame(RaceType.Request, RaceIds.GET_ROLE))
        return parseRole(response.payload)
    }

    private suspend fun readPeerIdOrNull(): Int? =
        try {
            val response = executor.execute(
                RaceFrame(RaceType.Request, RaceIds.GET_AVAILABLE_DESTINATIONS),
            )
            parsePeerId(response.payload)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // PROTOCOL.md §4.3: a failed destination listing means "solo".
            null
        }

    /** Reads one NVKEY and returns its data (without the length prefix). */
    suspend fun readNvkey(nvkeyId: Int): ByteArray {        val request = RaceFrame(
            type = RaceType.Request,
            commandId = RaceIds.NVKEY_READ,
            payload = nvkeyReadPayload(nvkeyId),
        )
        val response = executor.execute(request)
        val payload = response.payload
        if (payload.size < 2) {
            throw RaceInitializationException("NVKEY 0x${nvkeyId.toString(16)} reply is too short")
        }

        val dataLength = readUInt16Le(payload, 0)
        if (dataLength == 0) {
            throw RaceInitializationException("NVKEY 0x${nvkeyId.toString(16)} is empty")
        }
        if (dataLength > payload.size - MIN_NVKEY_PREFIX_SIZE) {
            throw RaceInitializationException(
                "NVKEY 0x${nvkeyId.toString(16)} length $dataLength exceeds the payload",
            )
        }
        return payload.copyOfRange(MIN_NVKEY_PREFIX_SIZE, MIN_NVKEY_PREFIX_SIZE + dataLength)
    }

    private fun nvkeyReadPayload(nvkeyId: Int): ByteArray {
        val payload = ByteArray(NVKEY_REQUEST_SIZE)
        writeUInt16Le(payload, 0, nvkeyId)
        writeUInt16Le(payload, 2, RaceIds.NVKEY_REQUESTED_BYTES)
        return payload
    }

    companion object {
        const val DEFAULT_INVALID_ATTEMPTS = 3

        private const val MIN_NVKEY_PREFIX_SIZE = 2
        private const val NVKEY_REQUEST_SIZE = 4
        private const val DEVICE_TYPE_OFFSET = 0
        private const val AWS_MODE_OFFSET = 39
        private const val AWS_MODE_MIN_DATA_SIZE = AWS_MODE_OFFSET + 1
        private const val AWSPEER = RaceIds.DESTINATION_TYPE_AWSPEER

        private val PRIMARY_DEVICE_TYPE_CHIPS = listOf(
            "AB158",
            "AB157",
            "AB1568_V3",
            "AB1565_DUAL_V3",
            "AB1568_DUAL_V3",
        )

        /** Chips whose device type lives in NVKEY 0x183D instead of 0xF2B0. */
        internal fun usesPrimaryDeviceTypeNvkey(chipName: String): Boolean {
            val normalized = chipName.trim().uppercase()
            return PRIMARY_DEVICE_TYPE_CHIPS.any { normalized.startsWith(it) }
        }

        internal fun parseRole(payload: ByteArray): TwsRole {
            if (payload.size < 2) {
                throw RaceInitializationException("Role payload is too short")
            }

            val status = payload[0].toInt() and 0xFF
            if (status != 0) {
                throw RaceInitializationException("Role request failed (status $status)")
            }
            return when (val role = payload[1].toInt() and 0xFF) {
                0 -> TwsRole.Normal
                1 -> TwsRole.Master
                2 -> TwsRole.Slave
                else -> TwsRole.Unknown(role)
            }
        }

        internal fun parseDeviceType(data: ByteArray): AirohaDeviceType {
            if (data.isEmpty()) {
                throw RaceInitializationException("Device type payload is empty")
            }
            val type = data[DEVICE_TYPE_OFFSET].toInt() and 0xFF
            if (type == 0) return AirohaDeviceType.Headset

            val awsMode = if (data.size >= AWS_MODE_MIN_DATA_SIZE) {
                data[AWS_MODE_OFFSET].toInt() and 0xFF
            } else {
                null
            }
            return if (awsMode == 0 || awsMode == 2) {
                AirohaDeviceType.Earbuds
            } else {
                AirohaDeviceType.Headset
            }
        }

        internal fun parsePeerId(payload: ByteArray): Int? {
            var index = 0
            while (index + 1 < payload.size) {
                val type = payload[index].toInt() and 0xFF
                val id = payload[index + 1].toInt() and 0xFF
                if (type == AWSPEER) return id
                index += 2
            }
            return null
        }
    }
}
