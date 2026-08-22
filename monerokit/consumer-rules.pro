-keep class com.piratecash.monero.signer.ExternalSignerNativeBridge {
    static long currentGeneration();
    static byte[] currentSessionId(long);
    static void writePacket(long, byte[]);
    static byte[] readPacket(long, long);
    static void cancel(long);
}

-keep class com.piratecash.monero.signer.ExternalSignerError { *; }
-keep class com.piratecash.monero.signer.ExternalSignerException { *; }
-keep class com.piratecash.monero.signer.HardwareWalletErrorCode { *; }
-keep class com.m2049r.xmrwallet.model.Wallet$Status {
    <init>(int, java.lang.String);
    <init>(int, java.lang.String, int);
}
