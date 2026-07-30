package com.piratecash.monero.signer

enum class HardwareWalletErrorCode(val code: Int) {
    UnsupportedModel(1),
    DeviceNotFound(2),
    PermissionDenied(3),
    DeviceNotInitialized(4),
    AcquireTimeout(5),
    UsbOpenFailed(6),
    UsbInterfaceUnavailable(7),
    PacketTimeout(8),
    Disconnected(9),
    StaleLease(10),
    ShortPacket(11),
    WrongDevice(12),
    WrongWallet(13),
    InvalidWalletPassword(14),
    CapabilityMissing(15),
    FirmwareUnsupported(16),
    ColdKeyImageUnsupported(17),
    ColdSignUnsupported(18),
    PublicKeyDerivationIncomplete(19),
    ProvisioningTargetMissing(20),
    ProvisioningTargetMismatch(21),
    ProvisioningIdCollision(22),
    IncompleteCreation(23),
    PendingDeviceVerification(24),
    Protocol(25),
    Network(26),
    StoreFailed(27),
    FileCorrupted(28),
    EmptyTransaction(29),
    SplitTransactionUnsupported(30),
    Cancelled(31),
    ;

    companion object {
        @JvmStatic
        fun fromCode(code: Int): HardwareWalletErrorCode =
            entries.firstOrNull { it.code == code } ?: Protocol

        @JvmStatic
        fun fromCodeOrNull(code: Int): HardwareWalletErrorCode? =
            entries.firstOrNull { it.code == code }
    }
}

class HardwareWalletOperationException(
    val error: HardwareWalletErrorCode,
    detail: String?,
) : RuntimeException(detail ?: error.name)
