package com.piratecash.monero.signer

enum class ExternalSignerError(val hardwareErrorCode: HardwareWalletErrorCode) {
    NO_CHANNEL(HardwareWalletErrorCode.DeviceNotFound),
    STALE_CHANNEL(HardwareWalletErrorCode.StaleLease),
    INVALID_PACKET(HardwareWalletErrorCode.ShortPacket),
    CANCELLED(HardwareWalletErrorCode.Cancelled),
    CHANNEL_FAILURE(HardwareWalletErrorCode.Protocol),
    CHANNEL_ALREADY_REGISTERED(HardwareWalletErrorCode.AcquireTimeout),
    ;

    val code: Int
        get() = hardwareErrorCode.code

    companion object {
        @JvmStatic
        fun fromCode(code: Int): ExternalSignerError =
            entries.firstOrNull { it.code == code } ?: CHANNEL_FAILURE

        internal fun fromHardwareError(code: HardwareWalletErrorCode): ExternalSignerError =
            entries.firstOrNull { it.hardwareErrorCode == code } ?: CHANNEL_FAILURE
    }
}

class ExternalSignerException(
    val hardwareErrorCode: HardwareWalletErrorCode,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    constructor(
        error: ExternalSignerError,
        message: String,
        cause: Throwable? = null,
    ) : this(error.hardwareErrorCode, message, cause)

    val error: ExternalSignerError
        get() = ExternalSignerError.fromHardwareError(hardwareErrorCode)
}
