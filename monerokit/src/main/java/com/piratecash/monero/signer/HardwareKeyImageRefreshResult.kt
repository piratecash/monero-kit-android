package com.piratecash.monero.signer

/**
 * Immutable checked result of one synchronous, scoped Trezor Live Refresh. [completed] is false when
 * the pass was stopped at a batch boundary: [finalHeight] is then a checkpoint, not the chain tip.
 */
data class HardwareKeyImageRefreshResult(
    val startHeight: Long,
    val finalHeight: Long,
    val suffixDetached: Boolean,
    val protocolStarted: Boolean,
    val finalAckChecked: Boolean,
    val unknownKeyImagesRemain: Boolean,
    val completed: Boolean,
) {
    data class Request(val mode: Mode, val startProtocolEagerly: Boolean, val restoreHeight: Long = 0) {
        init {
            require(restoreHeight >= 0) { "Restore height must not be negative" }
            require(mode == Mode.Continue || restoreHeight > 0) {
                "ResetToRestoreHeight requires a positive restore height"
            }
        }
    }

    enum class Mode(val nativeValue: Int) { Continue(0), ResetToRestoreHeight(1) }

    init {
        require(startHeight >= 0 && finalHeight >= 0) { "Invalid refresh heights" }
        // A stopped reset may end inside the hash-only fast refresh below the restore height.
        require(finalHeight >= startHeight || !completed) { "A completed refresh cannot end below its start" }
        require(!protocolStarted || finalAckChecked) {
            "A started Live Refresh protocol requires a checked final acknowledgement"
        }
        require(!unknownKeyImagesRemain) { "Live Refresh completed with unknown key images" }
    }

    companion object {
        @JvmStatic
        fun fromNative(values: LongArray?): HardwareKeyImageRefreshResult {
            require(values != null && values.size == 7) { "Invalid hardware key image refresh result" }
            fun flag(index: Int): Boolean {
                require(values[index] == 0L || values[index] == 1L) { "Invalid refresh flag" }
                return values[index] != 0L
            }
            return HardwareKeyImageRefreshResult(
                startHeight = values[0],
                finalHeight = values[1],
                suffixDetached = flag(2),
                protocolStarted = flag(3),
                finalAckChecked = flag(4),
                unknownKeyImagesRemain = flag(5),
                completed = flag(6),
            )
        }
    }
}
