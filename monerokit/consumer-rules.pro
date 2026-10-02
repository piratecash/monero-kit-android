# libmonerujo finds these classes, fields and methods by name (FindClass/GetFieldID/GetMethodID).
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

-keepclassmembers class com.m2049r.xmrwallet.model.** {
    long handle;
    long listenerHandle;
}

-keep class com.m2049r.xmrwallet.model.TransactionInfo {
    <init>(int, boolean, boolean, long, long, long, java.lang.String, long, java.lang.String, int, int, long, long, java.lang.String, java.util.List);
}
-keep class com.m2049r.xmrwallet.model.Transfer {
    <init>(long, java.lang.String);
}
-keep class com.m2049r.xmrwallet.model.CoinsInfo {
    <init>(int, int, long, long, java.lang.String, boolean, boolean, long, boolean);
}
-keep class com.m2049r.xmrwallet.model.Wallet$Status {
    <init>(int, java.lang.String);
    <init>(int, java.lang.String, int);
}
-keep interface com.m2049r.xmrwallet.model.WalletListener {
    void updated();
    void newBlock(long);
    void refreshed();
}
-keepclassmembers class * implements com.m2049r.xmrwallet.model.WalletListener {
    void updated();
    void newBlock(long);
    void refreshed();
}

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
