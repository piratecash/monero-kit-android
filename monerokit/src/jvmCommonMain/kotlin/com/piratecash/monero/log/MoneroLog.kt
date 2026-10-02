package com.piratecash.monero.log

import co.touchlab.kermit.Logger

/**
 * Java-friendly facade over Kermit with printf-style arguments, applied only when the level is
 * enabled. Tags follow the `MoneroKit:<context>` convention.
 */
object MoneroLog {

    @JvmStatic
    fun d(tag: String, message: String, vararg args: Any?) =
        Logger.d(tag) { format(message, args) }

    @JvmStatic
    fun d(tag: String, message: String, throwable: Throwable) =
        Logger.d(tag, throwable) { message }

    @JvmStatic
    fun i(tag: String, message: String, vararg args: Any?) =
        Logger.i(tag) { format(message, args) }

    @JvmStatic
    fun w(tag: String, message: String, vararg args: Any?) =
        Logger.w(tag) { format(message, args) }

    @JvmStatic
    fun w(tag: String, message: String, throwable: Throwable) =
        Logger.w(tag, throwable) { message }

    @JvmStatic
    fun e(tag: String, message: String, vararg args: Any?) =
        Logger.e(tag) { format(message, args) }

    @JvmStatic
    fun e(tag: String, message: String, throwable: Throwable) =
        Logger.e(tag, throwable) { message }

    private fun format(message: String, args: Array<out Any?>): String =
        if (args.isEmpty()) message else message.format(*args)
}
