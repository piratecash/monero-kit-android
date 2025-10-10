package com.m2049r.xmrwallet.service

/**
 * Thrown when opening a wallet fails due to a critical status, typically caused by
 * a corrupted cache that needs to be rebuilt.
 */
class WalletCorruptedException(message: String?) : RuntimeException(message)
