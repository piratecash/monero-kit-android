package com.piratecash.monero

import java.io.File
import java.nio.file.Files
import java.util.Locale

/** Desktop has no packaged JNI dir, so the library is unpacked from the jar on first use. */
actual object MoneroNative {
    private const val LIBRARY = "monerujo"
    private val SUPPORTED_HOSTS = setOf("linux-x64", "macos-arm64", "windows-x64")

    private val library = lazy { loadFromResources() }

    @JvmStatic
    actual fun load() {
        library.value
    }

    @JvmStatic
    actual fun isHostSupported(): Boolean = hostResource()?.let(MoneroNative::class.java::getResource) != null

    private fun loadFromResources() {
        val resource = hostResource() ?: throw UnsupportedOperationException(
            "monero-kit has no native library for ${System.getProperty("os.name")}/${System.getProperty("os.arch")}; " +
                "supported: ${SUPPORTED_HOSTS.joinToString()}"
        )
        val source = MoneroNative::class.java.getResourceAsStream(resource)
            ?: throw IllegalStateException("Native library not found in resources: $resource")

        val dir = Files.createTempDirectory("monero-kit").toFile().apply { deleteOnExit() }
        val file = File(dir, System.mapLibraryName(LIBRARY)).apply { deleteOnExit() }
        source.use { input -> file.outputStream().use(input::copyTo) }
        System.load(file.absolutePath)
    }

    private fun hostResource(): String? =
        hostTarget()?.takeIf { it in SUPPORTED_HOSTS }?.let { "/native/$it/${System.mapLibraryName(LIBRARY)}" }

    private fun hostTarget(): String? {
        val os = System.getProperty("os.name").lowercase(Locale.ROOT)
        val arch = when (System.getProperty("os.arch").lowercase(Locale.ROOT)) {
            "amd64", "x86_64" -> "x64"
            "aarch64", "arm64" -> "arm64"
            else -> return null
        }
        return when {
            os.startsWith("mac") -> "macos-$arch"
            os.startsWith("windows") -> "windows-$arch"
            os.startsWith("linux") -> "linux-$arch"
            else -> null
        }
    }
}
