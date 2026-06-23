/**
 * Copyright (c) 2017-2024 m2049r
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <inttypes.h>
#include <cassert>
#include <mutex>
#include <signal.h>
#include <setjmp.h>
#include <unistd.h>
#include "monerujo.h"
#include "wallet2_api.h"

// ---------------------------------------------------------------------------
// Signal-safe wallet close
//
// wallet2::store() -> get_cache_file_data() -> hashchain serialization may
// SIGSEGV when the wallet's internal deque<crypto::hash> has a null block
// pointer (wallet opened but never completed a full sync cycle). SIGSEGV is a
// hardware signal, not a C++ exception — try/catch cannot intercept it. We use
// sigsetjmp/siglongjmp with SA_ONSTACK to survive the fault.
//
// Design notes:
//  - The handler is installed ONCE in JNI_OnLoad (not per closeJ call) to
//    avoid install/uninstall races between threads competing on a process-wide
//    sigaction. g_prev_sigsegv_sa is a global captured at load time.
//  - g_in_safe_close and g_close_jmpbuf are thread_local so each thread
//    independently decides whether to recover or chain to the previous handler.
//  - On recovery we intentionally leak the wallet2 object: its state is
//    undefined after the fault and its destructor would hit the same null
//    pointer. Internal mutexes left locked inside wallet2 are also leaked but
//    are unreachable once the JNI handle is zeroed. This is an intentional
//    trade-off (bounded leak vs. process death). The proper fix belongs in
//    upstream Monero wallet2.cpp — add null-guards before hashchain serialization.
//  - g_safe_close_ready gates closeJ: if setup failed we fall back to
//    closeWallet(wallet, false) — losing the save is safer than crashing without
//    the signal protection in place.
// ---------------------------------------------------------------------------

static struct sigaction               g_prev_sigsegv_sa;      // global, set once in JNI_OnLoad
static bool                           g_safe_close_ready = false; // handler + altstack installed

static thread_local char              g_close_altstack[65536]; // per-thread alternate signal stack
static thread_local bool              g_altstack_ready   = false;
static thread_local sigjmp_buf        g_close_jmpbuf;
static thread_local volatile sig_atomic_t g_in_safe_close = 0;

static void safe_close_sigsegv_handler(int sig, siginfo_t *info, void *ctx) {
    if (g_in_safe_close) {
        siglongjmp(g_close_jmpbuf, 1);
    }
    // Foreign SIGSEGV (not from our protected section) — chain to previous handler.
    //
    // For SA_SIGINFO handlers (e.g. the JVM, which uses SIGSEGV for Java NPE
    // recovery): call the function pointer directly so our handler stays installed.
    // Using sigaction+raise would uninstall us, breaking protection for all future
    // closeJ calls after any Java NPE — a very common occurrence.
    //
    // For SIG_DFL: restore and raise — the process will crash regardless, so
    // losing our handler installation doesn't matter.
    //
    // Note: direct sa_sigaction call bypasses SA_RESETHAND/SA_NODEFER/mask
    // semantics, but this is the standard pattern used by Breakpad and Firebase
    // NDK for exactly this reason.
    if (g_prev_sigsegv_sa.sa_flags & SA_SIGINFO) {
        g_prev_sigsegv_sa.sa_sigaction(sig, info, ctx);
    } else {
        sigaction(SIGSEGV, &g_prev_sigsegv_sa, nullptr);
        raise(SIGSEGV);
    }
}

static void ensure_thread_altstack() {
    if (g_altstack_ready) return;
    stack_t ss;
    ss.ss_sp    = g_close_altstack;
    ss.ss_size  = sizeof(g_close_altstack);
    ss.ss_flags = 0;
    if (sigaltstack(&ss, nullptr) != 0) {
        // Logged by the caller; g_altstack_ready stays false.
        return;
    }
    g_altstack_ready = true;
}

#ifdef __cplusplus
extern "C"
{
#endif

#include <android/log.h>
#define LOG_TAG "WalletNDK"
#define LOGV(...) __android_log_print(ANDROID_LOG_VERBOSE, LOG_TAG,__VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG  , LOG_TAG,__VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO   , LOG_TAG,__VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN   , LOG_TAG,__VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR  , LOG_TAG,__VA_ARGS__)

static JavaVM *cachedJVM;
static jclass class_ArrayList;
static jclass class_WalletListener;
static jclass class_CoinsInfo;
static jclass class_TransactionInfo;
static jclass class_Transfer;
static jclass class_Ledger;
static jclass class_WalletStatus;
static jclass class_BluetoothService;
static jclass class_SidekickService;

std::mutex _listenerMutex;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *jvm, void *reserved) {
    cachedJVM = jvm;
    LOGI("JNI_OnLoad");
    JNIEnv *jenv;
    if (jvm->GetEnv(reinterpret_cast<void **>(&jenv), JNI_VERSION_1_6) != JNI_OK) {
        return -1;
    }

    // Install the SIGSEGV handler once, process-wide, at load time.
    // Doing this here avoids per-call install/uninstall races in closeJ.
    struct sigaction sa = {};
    sa.sa_sigaction = safe_close_sigsegv_handler;
    sigemptyset(&sa.sa_mask);
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
    if (sigaction(SIGSEGV, &sa, &g_prev_sigsegv_sa) == 0) {
        g_safe_close_ready = true;
        LOGI("JNI_OnLoad: safe-close SIGSEGV handler installed");
    } else {
        LOGW("JNI_OnLoad: sigaction failed — closeWallet will run without SIGSEGV protection");
    }

    class_ArrayList = static_cast<jclass>(jenv->NewGlobalRef(
            jenv->FindClass("java/util/ArrayList")));
    class_CoinsInfo = static_cast<jclass>(jenv->NewGlobalRef(
            jenv->FindClass("com/m2049r/xmrwallet/model/CoinsInfo")));
    class_TransactionInfo = static_cast<jclass>(jenv->NewGlobalRef(
            jenv->FindClass("com/m2049r/xmrwallet/model/TransactionInfo")));
    class_Transfer = static_cast<jclass>(jenv->NewGlobalRef(
            jenv->FindClass("com/m2049r/xmrwallet/model/Transfer")));
    class_WalletListener = static_cast<jclass>(jenv->NewGlobalRef(
            jenv->FindClass("com/m2049r/xmrwallet/model/WalletListener")));
    class_Ledger = static_cast<jclass>(jenv->NewGlobalRef(
            jenv->FindClass("com/m2049r/xmrwallet/ledger/Ledger")));
    class_WalletStatus = static_cast<jclass>(jenv->NewGlobalRef(
            jenv->FindClass("com/m2049r/xmrwallet/model/Wallet$Status")));
    class_BluetoothService = static_cast<jclass>(jenv->NewGlobalRef(
            jenv->FindClass("com/m2049r/xmrwallet/service/BluetoothService")));
    return JNI_VERSION_1_6;
}
#ifdef __cplusplus
}
#endif

int attachJVM(JNIEnv **jenv) {
    int envStat = cachedJVM->GetEnv((void **) jenv, JNI_VERSION_1_6);
    if (envStat == JNI_EDETACHED) {
        if (cachedJVM->AttachCurrentThread(jenv, nullptr) != 0) {
            LOGE("Failed to attach");
            return JNI_ERR;
        }
    } else if (envStat == JNI_EVERSION) {
        LOGE("GetEnv: version not supported");
        return JNI_ERR;
    }
    //LOGI("envStat=%i", envStat);
    return envStat;
}

void detachJVM(JNIEnv *jenv, int envStat) {
    //LOGI("envStat=%i", envStat);
    if (jenv->ExceptionCheck()) {
        jenv->ExceptionDescribe();
    }

    if (envStat == JNI_EDETACHED) {
        cachedJVM->DetachCurrentThread();
    }
}

struct MyWalletListener : Monero::WalletListener {
    jobject jlistener;

    MyWalletListener(JNIEnv *env, jobject aListener) {
        LOGD("Created MyListener");
        jlistener = env->NewGlobalRef(aListener);;
    }

    ~MyWalletListener() {
        LOGD("Destroyed MyListener");
    };

    void deleteGlobalJavaRef(JNIEnv *env) {
        std::lock_guard<std::mutex> lock(_listenerMutex);
        if (jlistener == nullptr) return;
        env->DeleteGlobalRef(jlistener);
        jlistener = nullptr;
    }

    /**
 * @brief updated  - generic callback, called when any event (sent/received/block reveived/etc) happened with the wallet;
 */
    void updated() {
        std::lock_guard<std::mutex> lock(_listenerMutex);
        if (jlistener == nullptr) return;
        LOGD("updated");
        JNIEnv *jenv;
        int envStat = attachJVM(&jenv);
        if (envStat == JNI_ERR) return;

        jmethodID listenerClass_updated = jenv->GetMethodID(class_WalletListener, "updated", "()V");
        jenv->CallVoidMethod(jlistener, listenerClass_updated);

        detachJVM(jenv, envStat);
    }


    /**
     * @brief moneySpent - called when money spent
     * @param txId       - transaction id
     * @param amount     - amount
     */
    void moneySpent(const std::string &txId, uint64_t amount) {
        std::lock_guard<std::mutex> lock(_listenerMutex);
        if (jlistener == nullptr) return;
        LOGD("moneySpent %"
                     PRIu64, amount);
    }

    /**
     * @brief moneyReceived - called when money received
     * @param txId          - transaction id
     * @param amount        - amount
     */
    void moneyReceived(const std::string &txId, uint64_t amount) {
        std::lock_guard<std::mutex> lock(_listenerMutex);
        if (jlistener == nullptr) return;
        LOGD("moneyReceived %"
                     PRIu64, amount);
    }

    /**
     * @brief unconfirmedMoneyReceived - called when payment arrived in tx pool
     * @param txId          - transaction id
     * @param amount        - amount
     */
    void unconfirmedMoneyReceived(const std::string &txId, uint64_t amount) {
        std::lock_guard<std::mutex> lock(_listenerMutex);
        if (jlistener == nullptr) return;
        LOGD("unconfirmedMoneyReceived %"
                     PRIu64, amount);
    }

    /**
     * @brief newBlock      - called when new block received
     * @param height        - block height
     */
    void newBlock(uint64_t height) {
        std::lock_guard<std::mutex> lock(_listenerMutex);
        if (jlistener == nullptr) return;
        //LOGD("newBlock");
        JNIEnv *jenv;
        int envStat = attachJVM(&jenv);
        if (envStat == JNI_ERR) return;

        jlong h = static_cast<jlong>(height);
        jmethodID listenerClass_newBlock = jenv->GetMethodID(class_WalletListener, "newBlock",
                                                             "(J)V");
        jenv->CallVoidMethod(jlistener, listenerClass_newBlock, h);

        detachJVM(jenv, envStat);
    }

/**
 * @brief refreshed - called when wallet refreshed by background thread or explicitly refreshed by calling "refresh" synchronously
 */
    void refreshed() {
        std::lock_guard<std::mutex> lock(_listenerMutex);
        if (jlistener == nullptr) return;
        LOGD("refreshed");
        JNIEnv *jenv;

        int envStat = attachJVM(&jenv);
        if (envStat == JNI_ERR) return;

        jmethodID listenerClass_refreshed = jenv->GetMethodID(class_WalletListener, "refreshed",
                                                              "()V");
        jenv->CallVoidMethod(jlistener, listenerClass_refreshed);
        detachJVM(jenv, envStat);
    }
};

// ---------------------------------------------------------------------------
// Retired listener pointers
//
// When closeJ runs, the Monero refresh thread may still be mid-callback
// inside Wallet2CallbackImpl::on_new_block → m_listener->newBlock().
// If we `delete` our MyWalletListener immediately, the vtable pointer becomes
// garbage and the virtual dispatch crashes (SIGSEGV).
//
// Instead we "retire" the listener: release its JNI global ref (so callbacks
// become no-ops via the existing mutex + nullptr check) but keep the C++
// object alive permanently so the vtable stays valid.
//
// Each MyWalletListener is ~64 bytes — even 100 stop/start cycles leak only
// ~6 KB, negligible for a mobile app's process lifetime. We intentionally
// never free them to eliminate any race window entirely.
// ---------------------------------------------------------------------------

//// helper methods
std::vector<std::string> java2cpp(JNIEnv *env, jobject arrayList) {

    jmethodID java_util_ArrayList_size = env->GetMethodID(class_ArrayList, "size", "()I");
    jmethodID java_util_ArrayList_get = env->GetMethodID(class_ArrayList, "get",
                                                         "(I)Ljava/lang/Object;");

    jint len = env->CallIntMethod(arrayList, java_util_ArrayList_size);
    std::vector<std::string> result;
    result.reserve(len);
    for (jint i = 0; i < len; i++) {
        jstring element = static_cast<jstring>(env->CallObjectMethod(arrayList,
                                                                     java_util_ArrayList_get, i));
        const char *pchars = env->GetStringUTFChars(element, nullptr);
        result.emplace_back(pchars);
        env->ReleaseStringUTFChars(element, pchars);
        env->DeleteLocalRef(element);
    }
    return result;
}

jobject cpp2java(JNIEnv *env, const std::vector<std::string> &vector) {

    jmethodID java_util_ArrayList_ = env->GetMethodID(class_ArrayList, "<init>", "(I)V");
    jmethodID java_util_ArrayList_add = env->GetMethodID(class_ArrayList, "add",
                                                         "(Ljava/lang/Object;)Z");

    jobject result = env->NewObject(class_ArrayList, java_util_ArrayList_,
                                    static_cast<jint> (vector.size()));
    for (const std::string &s: vector) {
        jstring element = env->NewStringUTF(s.c_str());
        env->CallBooleanMethod(result, java_util_ArrayList_add, element);
        env->DeleteLocalRef(element);
    }
    return result;
}

/// end helpers

#ifdef __cplusplus
extern "C"
{
#endif


/**********************************/
/********** WalletManager *********/
/**********************************/
JNIEXPORT jlong JNICALL
Java_com_m2049r_xmrwallet_model_WalletManager_createWalletJ(JNIEnv *env, jobject instance,
                                                            jstring path, jstring password,
                                                            jstring language,
                                                            jint networkType) {
    const char *_path = env->GetStringUTFChars(path, nullptr);
    const char *_password = env->GetStringUTFChars(password, nullptr);
    const char *_language = env->GetStringUTFChars(language, nullptr);
    Monero::NetworkType _networkType = static_cast<Monero::NetworkType>(networkType);

    Monero::Wallet *wallet =
            Monero::WalletManagerFactory::getWalletManager()->createWallet(
                    std::string(_path),
                    std::string(_password),
                    std::string(_language),
                    _networkType);

    env->ReleaseStringUTFChars(path, _path);
    env->ReleaseStringUTFChars(password, _password);
    env->ReleaseStringUTFChars(language, _language);
    return reinterpret_cast<jlong>(wallet);
}

JNIEXPORT jlong JNICALL
Java_com_m2049r_xmrwallet_model_WalletManager_openWalletJ(JNIEnv *env, jobject instance,
                                                          jstring path, jstring password,
                                                          jint networkType) {
    const char *_path = env->GetStringUTFChars(path, nullptr);
    const char *_password = env->GetStringUTFChars(password, nullptr);
    Monero::NetworkType _networkType = static_cast<Monero::NetworkType>(networkType);

    Monero::Wallet *wallet =
            Monero::WalletManagerFactory::getWalletManager()->openWallet(
                    std::string(_path),
                    std::string(_password),
                    _networkType);

    env->ReleaseStringUTFChars(path, _path);
    env->ReleaseStringUTFChars(password, _password);
    return reinterpret_cast<jlong>(wallet);
}

JNIEXPORT jlong JNICALL
Java_com_m2049r_xmrwallet_model_WalletManager_recoveryWalletJ(JNIEnv *env, jobject instance,
                                                              jstring path, jstring password,
                                                              jstring mnemonic, jstring offset,
                                                              jint networkType,
                                                              jlong restoreHeight) {
    const char *_path = env->GetStringUTFChars(path, nullptr);
    const char *_password = env->GetStringUTFChars(password, nullptr);
    const char *_mnemonic = env->GetStringUTFChars(mnemonic, nullptr);
    const char *_offset = env->GetStringUTFChars(offset, nullptr);
    Monero::NetworkType _networkType = static_cast<Monero::NetworkType>(networkType);

    Monero::Wallet *wallet =
            Monero::WalletManagerFactory::getWalletManager()->recoveryWallet(
                    std::string(_path),
                    std::string(_password),
                    std::string(_mnemonic),
                    _networkType,
                    (uint64_t) restoreHeight,
                    1, // kdf_rounds
                    std::string(_offset));

    env->ReleaseStringUTFChars(path, _path);
    env->ReleaseStringUTFChars(password, _password);
    env->ReleaseStringUTFChars(mnemonic, _mnemonic);
    env->ReleaseStringUTFChars(offset, _offset);
    return reinterpret_cast<jlong>(wallet);
}

JNIEXPORT jlong JNICALL
Java_com_m2049r_xmrwallet_model_WalletManager_createWalletFromKeysJ(JNIEnv *env, jobject instance,
                                                                    jstring path, jstring password,
                                                                    jstring language,
                                                                    jint networkType,
                                                                    jlong restoreHeight,
                                                                    jstring addressString,
                                                                    jstring viewKeyString,
                                                                    jstring spendKeyString) {
    const char *_path = env->GetStringUTFChars(path, nullptr);
    const char *_password = env->GetStringUTFChars(password, nullptr);
    const char *_language = env->GetStringUTFChars(language, nullptr);
    Monero::NetworkType _networkType = static_cast<Monero::NetworkType>(networkType);
    const char *_addressString = env->GetStringUTFChars(addressString, nullptr);
    const char *_viewKeyString = env->GetStringUTFChars(viewKeyString, nullptr);
    const char *_spendKeyString = env->GetStringUTFChars(spendKeyString, nullptr);

    Monero::Wallet *wallet =
            Monero::WalletManagerFactory::getWalletManager()->createWalletFromKeys(
                    std::string(_path),
                    std::string(_password),
                    std::string(_language),
                    _networkType,
                    (uint64_t) restoreHeight,
                    std::string(_addressString),
                    std::string(_viewKeyString),
                    std::string(_spendKeyString));

    env->ReleaseStringUTFChars(path, _path);
    env->ReleaseStringUTFChars(password, _password);
    env->ReleaseStringUTFChars(language, _language);
    env->ReleaseStringUTFChars(addressString, _addressString);
    env->ReleaseStringUTFChars(viewKeyString, _viewKeyString);
    env->ReleaseStringUTFChars(spendKeyString, _spendKeyString);
    return reinterpret_cast<jlong>(wallet);
}


// virtual void setSubaddressLookahead(uint32_t major, uint32_t minor) = 0;

JNIEXPORT jlong JNICALL
Java_com_m2049r_xmrwallet_model_WalletManager_createWalletFromDeviceJ(JNIEnv *env, jobject instance,
                                                                      jstring path,
                                                                      jstring password,
                                                                      jint networkType,
                                                                      jstring deviceName,
                                                                      jlong restoreHeight,
                                                                      jstring subaddressLookahead) {
    const char *_path = env->GetStringUTFChars(path, nullptr);
    const char *_password = env->GetStringUTFChars(password, nullptr);
    Monero::NetworkType _networkType = static_cast<Monero::NetworkType>(networkType);
    const char *_deviceName = env->GetStringUTFChars(deviceName, nullptr);
    const char *_subaddressLookahead = env->GetStringUTFChars(subaddressLookahead, nullptr);

    Monero::Wallet *wallet =
            Monero::WalletManagerFactory::getWalletManager()->createWalletFromDevice(
                    std::string(_path),
                    std::string(_password),
                    _networkType,
                    std::string(_deviceName),
                    (uint64_t) restoreHeight,
                    std::string(_subaddressLookahead));

    env->ReleaseStringUTFChars(path, _path);
    env->ReleaseStringUTFChars(password, _password);
    env->ReleaseStringUTFChars(deviceName, _deviceName);
    env->ReleaseStringUTFChars(subaddressLookahead, _subaddressLookahead);
    return reinterpret_cast<jlong>(wallet);
}

JNIEXPORT jboolean JNICALL
Java_com_m2049r_xmrwallet_model_WalletManager_walletExists(JNIEnv *env, jobject instance,
                                                           jstring path) {
    const char *_path = env->GetStringUTFChars(path, nullptr);
    bool exists =
            Monero::WalletManagerFactory::getWalletManager()->walletExists(std::string(_path));
    env->ReleaseStringUTFChars(path, _path);
    return static_cast<jboolean>(exists);
}

JNIEXPORT jboolean JNICALL
Java_com_m2049r_xmrwallet_model_WalletManager_verifyWalletPassword(JNIEnv *env, jobject instance,
                                                                   jstring keys_file_name,
                                                                   jstring password,
                                                                   jboolean watch_only) {
    const char *_keys_file_name = env->GetStringUTFChars(keys_file_name, nullptr);
    const char *_password = env->GetStringUTFChars(password, nullptr);
    bool passwordOk =
            Monero::WalletManagerFactory::getWalletManager()->verifyWalletPassword(
                    std::string(_keys_file_name), std::string(_password), watch_only);
    env->ReleaseStringUTFChars(keys_file_name, _keys_file_name);
    env->ReleaseStringUTFChars(password, _password);
    return static_cast<jboolean>(passwordOk);
}

//virtual int queryWalletHardware(const std::string &keys_file_name, const std::string &password) const = 0;
JNIEXPORT jint JNICALL
Java_com_m2049r_xmrwallet_model_WalletManager_queryWalletDeviceJ(JNIEnv *env, jobject instance,
                                                                 jstring keys_file_name,
                                                                 jstring password) {
    const char *_keys_file_name = env->GetStringUTFChars(keys_file_name, nullptr);
    const char *_password = env->GetStringUTFChars(password, nullptr);
    Monero::Wallet::Device device_type;
    bool ok = Monero::WalletManagerFactory::getWalletManager()->
            queryWalletDevice(device_type, std::string(_keys_file_name), std::string(_password));
    env->ReleaseStringUTFChars(keys_file_name, _keys_file_name);
    env->ReleaseStringUTFChars(password, _password);
    if (ok)
        return static_cast<jint>(device_type);
    else
        return -1;
}

JNIEXPORT jobject JNICALL
Java_com_m2049r_xmrwallet_model_WalletManager_findWallets(JNIEnv *env, jobject instance,
                                                          jstring path) {
    const char *_path = env->GetStringUTFChars(path, nullptr);
    std::vector<std::string> walletPaths =
            Monero::WalletManagerFactory::getWalletManager()->findWallets(std::string(_path));
    env->ReleaseStringUTFChars(path, _path);
    return cpp2java(env, walletPaths);
}

//TODO virtual bool checkPayment(const std::string &address, const std::string &txid, const std::string &txkey, const std::string &daemon_address, uint64_t &received, uint64_t &height, std::string &error) const = 0;

JNIEXPORT void JNICALL
Java_com_m2049r_xmrwallet_model_WalletManager_setDaemonAddressJ(JNIEnv *env, jobject instance,
                                                                jstring address) {
    const char *_address = env->GetStringUTFChars(address, nullptr);
    Monero::WalletManagerFactory::getWalletManager()->setDaemonAddress(std::string(_address));
    env->ReleaseStringUTFChars(address, _address);
}

// returns whether the daemon can be reached, and its version number
JNIEXPORT jint JNICALL
Java_com_m2049r_xmrwallet_model_WalletManager_getDaemonVersion(JNIEnv *env,
                                                               jobject instance) {
    uint32_t version;
    bool isConnected =
            Monero::WalletManagerFactory::getWalletManager()->connected(&version);
    if (!isConnected) version = 0;
    return version;
}

JNIEXPORT jlong JNICALL
Java_com_m2049r_xmrwallet_model_WalletManager_getBlockchainHeight(JNIEnv *env, jobject instance) {
    return Monero::WalletManagerFactory::getWalletManager()->blockchainHeight();
}

JNIEXPORT jlong JNICALL
Java_com_m2049r_xmrwallet_model_WalletManager_getBlockchainTargetHeight(JNIEnv *env,
                                                                        jobject instance) {
    return Monero::WalletManagerFactory::getWalletManager()->blockchainTargetHeight();
}

JNIEXPORT jlong JNICALL
Java_com_m2049r_xmrwallet_model_WalletManager_getNetworkDifficulty(JNIEnv *env, jobject instance) {
    return Monero::WalletManagerFactory::getWalletManager()->networkDifficulty();
}

JNIEXPORT jdouble JNICALL
Java_com_m2049r_xmrwallet_model_WalletManager_getMiningHashRate(JNIEnv *env, jobject instance) {
    return Monero::WalletManagerFactory::getWalletManager()->miningHashRate();
}

JNIEXPORT jlong JNICALL
Java_com_m2049r_xmrwallet_model_WalletManager_getBlockTarget(JNIEnv *env, jobject instance) {
    return Monero::WalletManagerFactory::getWalletManager()->blockTarget();
}

JNIEXPORT jboolean JNICALL
Java_com_m2049r_xmrwallet_model_WalletManager_isMining(JNIEnv *env, jobject instance) {
    return static_cast<jboolean>(Monero::WalletManagerFactory::getWalletManager()->isMining());
}

JNIEXPORT jboolean JNICALL
Java_com_m2049r_xmrwallet_model_WalletManager_startMining(JNIEnv *env, jobject instance,
                                                          jstring address,
                                                          jboolean background_mining,
                                                          jboolean ignore_battery) {
    const char *_address = env->GetStringUTFChars(address, nullptr);
    bool success =
            Monero::WalletManagerFactory::getWalletManager()->startMining(std::string(_address),
                                                                          background_mining,
                                                                          ignore_battery);
    env->ReleaseStringUTFChars(address, _address);
    return static_cast<jboolean>(success);
}

JNIEXPORT jboolean JNICALL
Java_com_m2049r_xmrwallet_model_WalletManager_stopMining(JNIEnv *env, jobject instance) {
    return static_cast<jboolean>(Monero::WalletManagerFactory::getWalletManager()->stopMining());
}

JNIEXPORT jstring JNICALL
Java_com_m2049r_xmrwallet_model_WalletManager_resolveOpenAlias(JNIEnv *env, jobject instance,
                                                               jstring address,
                                                               jboolean dnssec_valid) {
    const char *_address = env->GetStringUTFChars(address, nullptr);
    bool _dnssec_valid = (bool) dnssec_valid;
    std::string resolvedAlias =
            Monero::WalletManagerFactory::getWalletManager()->resolveOpenAlias(
                    std::string(_address),
                    _dnssec_valid);
    env->ReleaseStringUTFChars(address, _address);
    return env->NewStringUTF(resolvedAlias.c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_m2049r_xmrwallet_model_WalletManager_setProxy(JNIEnv *env, jobject instance,
                                                       jstring address) {
    const char *_address = env->GetStringUTFChars(address, nullptr);
    bool rc =
            Monero::WalletManagerFactory::getWalletManager()->setProxy(std::string(_address));
    env->ReleaseStringUTFChars(address, _address);
    return rc;
}


//TODO static std::tuple<bool, std::string, std::string, std::string, std::string> checkUpdates(const std::string &software, const std::string &subdir);

JNIEXPORT jboolean JNICALL
Java_com_m2049r_xmrwallet_model_WalletManager_closeJ(JNIEnv *env, jobject instance,
                                                     jobject walletInstance,
                                                     jboolean store) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, walletInstance);
    if (wallet == nullptr) {
        LOGE("wallet handle is null in closeJ");
        return JNI_FALSE;
    }

    // If handler setup failed at load time, fall back to close without save
    // so we don't crash unprotected.
    jboolean safeStore = store;
    if (store && !g_safe_close_ready) {
        LOGW("closeJ: SIGSEGV handler not ready — closing without save to avoid unprotected crash");
        safeStore = JNI_FALSE;
    }

    ensure_thread_altstack();
    if (!g_altstack_ready) {
        LOGW("closeJ: sigaltstack failed on this thread — SIGSEGV recovery may be unreliable");
    }

    // Keep the listener valid until close succeeds. If close fails, Java will
    // re-manage the wallet object and must still have a working listener.
    // We only invalidate the JNI global ref once the wallet is actually
    // considered closed (or we recover from a SIGSEGV during close).
    MyWalletListener *walletListener = getHandle<MyWalletListener>(env, walletInstance,
                                                                   "listenerHandle");

    bool closeSuccess = false;
    bool storeSuccess = false;
    bool sigsegvOccurred = false;

    // Signal the refresh thread to stop.
    wallet->pauseRefresh();

    // --- Store separately, before close ---
    //
    // Upstream WalletImpl::close(true) calls store() BEFORE stop(), so the
    // refresh thread may still be modifying the hashchain when store()
    // serialises it — causing heap corruption (Scudo "invalid chunk state").
    //
    // By calling store() ourselves and then closeWallet(false), we keep the
    // SIGSEGV protection around the dangerous store() call, while close(false)
    // only runs stop()/deinit() which properly joins the refresh thread.
    //
    // IMPORTANT: pauseRefresh() only flips a flag — if the refresh thread is
    // already inside doRefresh() it won't exit until that call returns.
    // There is no public API to join the thread without rebuilding the Monero
    // libs.  The fixed wait reduces the probability of a race; the SIGSEGV
    // handler is the actual safety net.
    if (safeStore) {
        // Best-effort wait for the refresh thread to leave doRefresh().
        // Not a synchronisation guarantee — see SIGSEGV handler below.
        usleep(200000); // 200 ms

        if (sigsetjmp(g_close_jmpbuf, 1) == 0) {
            g_in_safe_close = 1;
            storeSuccess = wallet->store("");  // empty string = default path
            g_in_safe_close = 0;
            if (!storeSuccess) {
                LOGE("closeJ: store() returned false — %s", wallet->errorString().c_str());
            }
        } else {
            // store() faulted — wallet state is undefined (internal mutexes may
            // be locked, data structures corrupted).  We MUST NOT touch the
            // wallet object again.  Leak it, same as the original safety contract.
            g_in_safe_close = 0;
            sigsegvOccurred = true;
            LOGE("closeJ: SIGSEGV during store() — leaking wallet object");
        }
    }

    // --- Close without store ---
    // close(false) skips store() and only runs stop()/deinit(), which
    // properly joins the refresh thread.  Safe regardless of store outcome.
    //
    // Skip if store() already SIGSEGV'd — the wallet object is in an
    // undefined state and must be leaked (see comment above).
    if (!sigsegvOccurred) {
        if (sigsetjmp(g_close_jmpbuf, 1) == 0) {
            g_in_safe_close = 1;
            closeSuccess = Monero::WalletManagerFactory::getWalletManager()->closeWallet(wallet, false);
            g_in_safe_close = 0;
        } else {
            g_in_safe_close = 0;
            sigsegvOccurred = true;
            LOGE("closeJ: SIGSEGV in closeWallet(store=false) — leaking wallet object");
        }
    }

    if (closeSuccess || sigsegvOccurred) {
        if (walletListener != nullptr) {
            walletListener->deleteGlobalJavaRef(env);
        }
        // Do NOT delete walletListener — the refresh thread may still be
        // unwinding through Wallet2CallbackImpl::on_new_block → our newBlock().
        // Keeping the C++ object alive (~64 bytes) ensures the vtable stays
        // valid. The JNI ref is released now, so any late callbacks become no-ops.
        env->SetLongField(walletInstance, getHandleField(env, walletInstance, "listenerHandle"), 0);
        env->SetLongField(walletInstance, getHandleField(env, walletInstance), 0);
    }

    // On SIGSEGV we return true: handles are already zeroed and listener cleaned up,
    // so from Java's perspective the wallet is closed. Returning false would cause
    // WalletManager.close() to call manageWallet() on the zeroed handle.
    LOGD("wallet closed (stored=%d close=%d sigsegv=%d)", storeSuccess, closeSuccess, sigsegvOccurred);
    return static_cast<jboolean>(closeSuccess || sigsegvOccurred);
}




/**********************************/
/************ Wallet **************/
/**********************************/

JNIEXPORT jstring JNICALL
Java_com_m2049r_xmrwallet_model_Wallet_getSeed(JNIEnv *env, jobject instance, jstring seedOffset) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    if (wallet == nullptr) {
        LOGE("wallet handle is null in %s", __FUNCTION__);
        return nullptr;
    }
    const char *_seedOffset = env->GetStringUTFChars(seedOffset, nullptr);
    jstring seed = env->NewStringUTF(wallet->seed(std::string(_seedOffset)).c_str());
    env->ReleaseStringUTFChars(seedOffset, _seedOffset);
    return seed;
}

JNIEXPORT jstring JNICALL
Java_com_m2049r_xmrwallet_model_Wallet_getSeedLanguage(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    if (wallet == nullptr) {
        LOGE("wallet handle is null in %s", __FUNCTION__);
        return nullptr;
    }
    return env->NewStringUTF(wallet->getSeedLanguage().c_str());
}

JNIEXPORT void JNICALL
Java_com_m2049r_xmrwallet_model_Wallet_setSeedLanguage(JNIEnv *env, jobject instance,
                                                       jstring language) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    if (wallet == nullptr) {
        LOGE("wallet handle is null in %s", __FUNCTION__);
        return;
    }
    const char *_language = env->GetStringUTFChars(language, nullptr);
    wallet->setSeedLanguage(std::string(_language));
    env->ReleaseStringUTFChars(language, _language);
}

JNIEXPORT jint JNICALL
Java_com_m2049r_xmrwallet_model_Wallet_getStatusJ(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    if (wallet == nullptr) {
        LOGE("wallet handle is null in %s", __FUNCTION__);
        return 0;
    }
    return wallet->status();
}

jobject newWalletStatusInstance(JNIEnv *env, int status, const std::string &errorString) {
    jmethodID init = env->GetMethodID(class_WalletStatus, "<init>",
                                      "(ILjava/lang/String;)V");
    jstring _errorString = env->NewStringUTF(errorString.c_str());
    jobject instance = env->NewObject(class_WalletStatus, init, status, _errorString);
    env->DeleteLocalRef(_errorString);
    return instance;
}


JNIEXPORT jobject JNICALL
Java_com_m2049r_xmrwallet_model_Wallet_statusWithErrorString(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    if (wallet == nullptr) {
        LOGE("wallet handle is null in %s", __FUNCTION__);
        return nullptr;
    }

    int status;
    std::string errorString;
    wallet->statusWithErrorString(status, errorString);

    return newWalletStatusInstance(env, status, errorString);
}

JNIEXPORT jboolean JNICALL
Java_com_m2049r_xmrwallet_model_Wallet_setPassword(JNIEnv *env, jobject instance,
                                                   jstring password) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    if (wallet == nullptr) {
        LOGE("wallet handle is null in %s", __FUNCTION__);
        return JNI_FALSE;
    }
    const char *_password = env->GetStringUTFChars(password, nullptr);
    bool success = wallet->setPassword(std::string(_password));
    env->ReleaseStringUTFChars(password, _password);
    return static_cast<jboolean>(success);
}

JNIEXPORT jstring JNICALL
Java_com_m2049r_xmrwallet_model_Wallet_getAddressJ(JNIEnv *env, jobject instance,
                                                   jint accountIndex,
                                                   jint addressIndex) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    if (wallet == nullptr) {
        LOGE("wallet handle is null in %s", __FUNCTION__);
        return nullptr;
    }
    return env->NewStringUTF(
            wallet->address((uint32_t) accountIndex, (uint32_t) addressIndex).c_str());
}

JNIEXPORT jstring JNICALL
Java_com_m2049r_xmrwallet_model_Wallet_getPath(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    if (wallet == nullptr) {
        LOGE("wallet handle is null in %s", __FUNCTION__);
        return nullptr;
    }
    return env->NewStringUTF(wallet->path().c_str());
}

JNIEXPORT jint JNICALL
Java_com_m2049r_xmrwallet_model_Wallet_nettype(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    if (wallet == nullptr) {
        LOGE("wallet handle is null in %s", __FUNCTION__);
        return 0;
    }
    return wallet->nettype();
}

//TODO virtual void hardForkInfo(uint8_t &version, uint64_t &earliest_height) const = 0;
//TODO virtual bool useForkRules(uint8_t version, int64_t early_blocks) const = 0;

JNIEXPORT jstring JNICALL
Java_com_m2049r_xmrwallet_model_Wallet_getIntegratedAddress(JNIEnv *env, jobject instance,
                                                            jstring payment_id) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    if (wallet == nullptr) {
        LOGE("wallet handle is null in %s", __FUNCTION__);
        return nullptr;
    }
    const char *_payment_id = env->GetStringUTFChars(payment_id, nullptr);
    std::string address = wallet->integratedAddress(_payment_id);
    env->ReleaseStringUTFChars(payment_id, _payment_id);
    return env->NewStringUTF(address.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_m2049r_xmrwallet_model_Wallet_getSecretViewKey(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    if (wallet == nullptr) {
        LOGE("wallet handle is null in %s", __FUNCTION__);
        return nullptr;
    }
    return env->NewStringUTF(wallet->secretViewKey().c_str());
}

JNIEXPORT jstring JNICALL
Java_com_m2049r_xmrwallet_model_Wallet_getSecretSpendKey(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    if (wallet == nullptr) {
        LOGE("wallet handle is null in %s", __FUNCTION__);
        return nullptr;
    }
    return env->NewStringUTF(wallet->secretSpendKey().c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_m2049r_xmrwallet_model_Wallet_store(JNIEnv *env, jobject instance,
                                             jstring path) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    if (wallet == nullptr) {
        LOGE("store() wallet is null");
        return static_cast<jboolean>(false);
    }
    const char *_path = env->GetStringUTFChars(path, nullptr);
    bool success = wallet->store(std::string(_path));
    if (!success) {
        LOGE("store() %s", wallet->errorString().c_str());
    }
    env->ReleaseStringUTFChars(path, _path);
    return static_cast<jboolean>(success);
}

JNIEXPORT jstring JNICALL
Java_com_m2049r_xmrwallet_model_Wallet_getFilename(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    if (wallet == nullptr) {
        LOGE("wallet handle is null in %s", __FUNCTION__);
        return nullptr;
    }
    return env->NewStringUTF(wallet->filename().c_str());
}

//    virtual std::string keysFilename() const = 0;

JNIEXPORT jboolean JNICALL
Java_com_m2049r_xmrwallet_model_Wallet_initJ(JNIEnv *env, jobject instance,
                                             jstring daemon_address,
                                             jlong upper_transaction_size_limit,
                                             jstring daemon_username, jstring daemon_password) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    if (wallet == nullptr) {
        LOGE("wallet handle is null in %s", __FUNCTION__);
        return JNI_FALSE;
    }
    const char *_daemon_address = env->GetStringUTFChars(daemon_address, nullptr);
    const char *_daemon_username = env->GetStringUTFChars(daemon_username, nullptr);
    const char *_daemon_password = env->GetStringUTFChars(daemon_password, nullptr);
    bool status = wallet->init(_daemon_address, (uint64_t) upper_transaction_size_limit,
                               _daemon_username,
                               _daemon_password);
    env->ReleaseStringUTFChars(daemon_address, _daemon_address);
    env->ReleaseStringUTFChars(daemon_username, _daemon_username);
    env->ReleaseStringUTFChars(daemon_password, _daemon_password);
    return static_cast<jboolean>(status);
}

//    virtual bool createWatchOnly(const std::string &path, const std::string &password, const std::string &language) const = 0;

JNIEXPORT void JNICALL
Java_com_m2049r_xmrwallet_model_Wallet_setRestoreHeight(JNIEnv *env, jobject instance,
                                                        jlong height) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    if (wallet == nullptr) {
        LOGE("wallet handle is null in %s", __FUNCTION__);
        return;
    }
    wallet->setRefreshFromBlockHeight((uint64_t) height);
}

JNIEXPORT jlong JNICALL
Java_com_m2049r_xmrwallet_model_Wallet_getRestoreHeight(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    if (wallet == nullptr) {
        LOGE("wallet handle is null in %s", __FUNCTION__);
        return 0;
    }
    return wallet->getRefreshFromBlockHeight();
}

//    virtual void setRecoveringFromSeed(bool recoveringFromSeed) = 0;
//    virtual bool connectToDaemon() = 0;

JNIEXPORT jint JNICALL
Java_com_m2049r_xmrwallet_model_Wallet_getConnectionStatusJ(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    if (wallet == nullptr) {
        LOGE("wallet handle is null in %s", __FUNCTION__);
        return 0;
    }
    try {
        return wallet->connected();
    } catch (const std::exception &e) {
        LOGE("getConnectionStatusJ: caught exception: %s", e.what());
        return 0; // ConnectionStatus_Disconnected
    }
}
//TODO virtual void setTrustedDaemon(bool arg) = 0;
//TODO virtual bool trustedDaemon() const = 0;

JNIEXPORT jboolean JNICALL
Java_com_m2049r_xmrwallet_model_Wallet_setProxy(JNIEnv *env, jobject instance,
                                                jstring address) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    if (wallet == nullptr) {
        LOGE("wallet handle is null in %s", __FUNCTION__);
        return JNI_FALSE;
    }
    const char *_address = env->GetStringUTFChars(address, nullptr);
    bool rc = wallet->setProxy(std::string(_address));
    env->ReleaseStringUTFChars(address, _address);
    return rc;
}

JNIEXPORT jlong JNICALL
Java_com_m2049r_xmrwallet_model_Wallet_getBalance(JNIEnv *env, jobject instance,
                                                  jint accountIndex) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    if (wallet == nullptr) {
        LOGE("wallet handle is null in %s", __FUNCTION__);
        return 0;
    }
    return wallet->balance((uint32_t) accountIndex);
}

JNIEXPORT jlong JNICALL
Java_com_m2049r_xmrwallet_model_Wallet_getBalanceAll(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    if (wallet == nullptr) {
        LOGE("wallet handle is null in %s", __FUNCTION__);
        return 0;
    }
    return wallet->balanceAll();
}

JNIEXPORT jlong JNICALL
Java_com_m2049r_xmrwallet_model_Wallet_getUnlockedBalance(JNIEnv *env, jobject instance,
                                                          jint accountIndex) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    if (wallet == nullptr) {
        LOGE("wallet handle is null in %s", __FUNCTION__);
        return 0;
    }
    return wallet->unlockedBalance((uint32_t) accountIndex);
}

JNIEXPORT jlong JNICALL
Java_com_m2049r_xmrwallet_model_Wallet_getUnlockedBalanceAll(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    if (wallet == nullptr) {
        LOGE("wallet handle is null in %s", __FUNCTION__);
        return 0;
    }
    return wallet->unlockedBalanceAll();
}

JNIEXPORT jboolean JNICALL
Java_com_m2049r_xmrwallet_model_Wallet_isWatchOnly(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    if (wallet == nullptr) {
        LOGE("wallet handle is null in %s", __FUNCTION__);
        return JNI_FALSE;
    }
    return static_cast<jboolean>(wallet->watchOnly());
}

JNIEXPORT jlong JNICALL
Java_com_m2049r_xmrwallet_model_Wallet_getBlockChainHeight(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    if (wallet == nullptr) {
        LOGE("wallet handle is null in %s", __FUNCTION__);
        return 0;
    }
    return wallet->blockChainHeight();
}

JNIEXPORT jlong JNICALL
Java_com_m2049r_xmrwallet_model_Wallet_getApproximateBlockChainHeight(JNIEnv *env,
                                                                      jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    if (wallet == nullptr) {
        LOGE("wallet handle is null in %s", __FUNCTION__);
        return 0;
    }
    return wallet->approximateBlockChainHeight();
}

JNIEXPORT jlong JNICALL
Java_com_m2049r_xmrwallet_model_Wallet_getDaemonBlockChainHeight(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    if (wallet == nullptr) {
        LOGE("wallet handle is null in %s", __FUNCTION__);
        return 0;
    }
    return wallet->daemonBlockChainHeight();
}

JNIEXPORT jlong JNICALL
Java_com_m2049r_xmrwallet_model_Wallet_getDaemonBlockChainTargetHeight(JNIEnv *env,
                                                                       jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    if (wallet == nullptr) {
        LOGE("wallet handle is null in %s", __FUNCTION__);
        return 0;
    }
    return wallet->daemonBlockChainTargetHeight();
}

JNIEXPORT jboolean JNICALL
Java_com_m2049r_xmrwallet_model_Wallet_isSynchronizedJ(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    if (wallet == nullptr) {
        LOGE("wallet handle is null in %s", __FUNCTION__);
        return JNI_FALSE;
    }
    return static_cast<jboolean>(wallet->synchronized());
}

JNIEXPORT jint JNICALL
Java_com_m2049r_xmrwallet_model_Wallet_getDeviceTypeJ(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    if (wallet == nullptr) {
        LOGE("wallet handle is null in %s", __FUNCTION__);
        return 0;
    }
    Monero::Wallet::Device device_type = wallet->getDeviceType();
    return static_cast<jint>(device_type);
}

//void cn_slow_hash(const void *data, size_t length, char *hash); // from crypto/hash-ops.h
JNIEXPORT jbyteArray JNICALL
Java_com_m2049r_xmrwallet_util_KeyStoreHelper_slowHash(JNIEnv *env, jclass clazz,
                                                       jbyteArray data, jint brokenVariant) {
    char hash[HASH_SIZE];
    jsize size = env->GetArrayLength(data);
    if ((brokenVariant > 0) && (size < 200 /*sizeof(union hash_state)*/)) {
        return nullptr;
    }

    jbyte *buffer = env->GetByteArrayElements(data, nullptr);
    switch (brokenVariant) {
        case 1:
            slow_hash_broken(buffer, hash, 1);
            break;
        case 2:
            slow_hash_broken(buffer, hash, 0);
            break;
        default: // not broken
            slow_hash(buffer, (size_t) size, hash);
    }
    env->ReleaseByteArrayElements(data, buffer, JNI_ABORT); // do not update java byte[]
    jbyteArray result = env->NewByteArray(HASH_SIZE);
    env->SetByteArrayRegion(result, 0, HASH_SIZE, (jbyte *) hash);
    return result;
}

JNIEXPORT jstring JNICALL
Java_com_m2049r_xmrwallet_model_Wallet_getDisplayAmount(JNIEnv *env, jclass clazz,
                                                        jlong amount) {
    return env->NewStringUTF(Monero::Wallet::displayAmount(amount).c_str());
}

JNIEXPORT jlong JNICALL
Java_com_m2049r_xmrwallet_model_Wallet_getAmountFromString(JNIEnv *env, jclass clazz,
                                                           jstring amount) {
    const char *_amount = env->GetStringUTFChars(amount, nullptr);
    uint64_t x = Monero::Wallet::amountFromString(_amount);
    env->ReleaseStringUTFChars(amount, _amount);
    return x;
}

JNIEXPORT jlong JNICALL
Java_com_m2049r_xmrwallet_model_Wallet_getAmountFromDouble(JNIEnv *env, jclass clazz,
                                                           jdouble amount) {
    return Monero::Wallet::amountFromDouble(amount);
}

JNIEXPORT jstring JNICALL
Java_com_m2049r_xmrwallet_model_Wallet_generatePaymentId(JNIEnv *env, jclass clazz) {
    return env->NewStringUTF(Monero::Wallet::genPaymentId().c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_m2049r_xmrwallet_model_Wallet_isPaymentIdValid(JNIEnv *env, jclass clazz,
                                                        jstring payment_id) {
    const char *_payment_id = env->GetStringUTFChars(payment_id, nullptr);
    bool isValid = Monero::Wallet::paymentIdValid(_payment_id);
    env->ReleaseStringUTFChars(payment_id, _payment_id);
    return static_cast<jboolean>(isValid);
}

JNIEXPORT jboolean JNICALL
Java_com_m2049r_xmrwallet_model_Wallet_isAddressValid(JNIEnv *env, jclass clazz,
                                                      jstring address, jint networkType) {
    const char *_address = env->GetStringUTFChars(address, nullptr);
    Monero::NetworkType _networkType = static_cast<Monero::NetworkType>(networkType);
    bool isValid = Monero::Wallet::addressValid(_address, _networkType);
    env->ReleaseStringUTFChars(address, _address);
    return static_cast<jboolean>(isValid);
}

JNIEXPORT jstring JNICALL
Java_com_m2049r_xmrwallet_model_Wallet_getPaymentIdFromAddress(JNIEnv *env, jclass clazz,
                                                               jstring address,
                                                               jint networkType) {
    Monero::NetworkType _networkType = static_cast<Monero::NetworkType>(networkType);
    const char *_address = env->GetStringUTFChars(address, nullptr);
    std::string payment_id = Monero::Wallet::paymentIdFromAddress(_address, _networkType);
    env->ReleaseStringUTFChars(address, _address);
    return env->NewStringUTF(payment_id.c_str());
}

JNIEXPORT jlong JNICALL
Java_com_m2049r_xmrwallet_model_Wallet_getMaximumAllowedAmount(JNIEnv *env, jclass clazz) {
    return Monero::Wallet::maximumAllowedAmount();
}

JNIEXPORT void JNICALL
Java_com_m2049r_xmrwallet_model_Wallet_startRefresh(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    if (wallet == nullptr) {
        LOGE("wallet handle is null in %s", __FUNCTION__);
        return;
    }
    wallet->startRefresh();
}

JNIEXPORT void JNICALL
Java_com_m2049r_xmrwallet_model_Wallet_pauseRefresh(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    if (wallet == nullptr) {
        LOGE("wallet handle is null in %s", __FUNCTION__);
        return;
    }
    wallet->pauseRefresh();
}

JNIEXPORT jboolean JNICALL
Java_com_m2049r_xmrwallet_model_Wallet_refresh(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    if (wallet == nullptr) {
        LOGE("wallet handle is null in %s", __FUNCTION__);
        return JNI_FALSE;
    }
    return static_cast<jboolean>(wallet->refresh());
}

JNIEXPORT void JNICALL
Java_com_m2049r_xmrwallet_model_Wallet_refreshAsync(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    if (wallet == nullptr) {
        LOGE("wallet handle is null in %s", __FUNCTION__);
        return;
    }
    wallet->refreshAsync();
}

//TODO virtual bool rescanBlockchain() = 0;

//virtual void rescanBlockchainAsync() = 0;
JNIEXPORT void JNICALL
Java_com_m2049r_xmrwallet_model_Wallet_rescanBlockchainAsyncJ(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    if (wallet == nullptr) {
        LOGE("wallet handle is null in %s", __FUNCTION__);
        return;
    }
    wallet->rescanBlockchainAsync();
}


//TODO virtual void setAutoRefreshInterval(int millis) = 0;
//TODO virtual int autoRefreshInterval() const = 0;

JNIEXPORT jlong JNICALL
Java_com_m2049r_xmrwallet_model_Wallet_createTransactionMultDest(JNIEnv *env, jobject instance,
                                                                 jobjectArray destinations,
                                                                 jstring payment_id,
                                                                 jlongArray amounts,
                                                                 jint mixin_count,
                                                                 jint priority,
                                                                 jint accountIndex,
                                                                 jintArray subaddresses) {
    std::vector<std::string> dst_addr;
    std::vector<uint64_t> amount;

    int destSize = env->GetArrayLength(destinations);
    assert(destSize == env->GetArrayLength(amounts));
    jlong *_amounts = env->GetLongArrayElements(amounts, nullptr);
    for (int i = 0; i < destSize; i++) {
        jstring dest = (jstring) env->GetObjectArrayElement(destinations, i);
        const char *_dest = env->GetStringUTFChars(dest, nullptr);
        dst_addr.emplace_back(_dest);
        env->ReleaseStringUTFChars(dest, _dest);
        amount.emplace_back((uint64_t) _amounts[i]);
    }
    env->ReleaseLongArrayElements(amounts, _amounts, 0);

    std::set<uint32_t> subaddr_indices;
    if (subaddresses != nullptr) {
        int subaddrSize = env->GetArrayLength(subaddresses);
        jint *_subaddresses = env->GetIntArrayElements(subaddresses, nullptr);
        for (int i = 0; i < subaddrSize; i++) {
            subaddr_indices.insert((uint32_t) _subaddresses[i]);
        }
        env->ReleaseIntArrayElements(subaddresses, _subaddresses, 0);
    }

    const char *_payment_id = env->GetStringUTFChars(payment_id, nullptr);

    Monero::PendingTransaction::Priority _priority =
            static_cast<Monero::PendingTransaction::Priority>(priority);

    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    if (wallet == nullptr) {
        LOGE("wallet handle is null in %s", __FUNCTION__);
        env->ReleaseStringUTFChars(payment_id, _payment_id);
        return 0;
    }

    Monero::PendingTransaction *tx =
            wallet->createTransactionMultDest(dst_addr, _payment_id,
                                              amount, (uint32_t) mixin_count,
                                              _priority,
                                              (uint32_t) accountIndex,
                                              subaddr_indices);

    env->ReleaseStringUTFChars(payment_id, _payment_id);
    return reinterpret_cast<jlong>(tx);
}

JNIEXPORT jlong JNICALL
Java_com_m2049r_xmrwallet_model_Wallet_createTransactionJ(JNIEnv *env, jobject instance,
                                                          jstring dst_addr, jstring payment_id,
                                                          jlong amount, jint mixin_count,
                                                          jint priority,
                                                          jint accountIndex) {

    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    if (wallet == nullptr) {
        LOGE("wallet handle is null in %s", __FUNCTION__);
        return 0;
    }
    const char *_dst_addr = env->GetStringUTFChars(dst_addr, nullptr);
    const char *_payment_id = env->GetStringUTFChars(payment_id, nullptr);
    Monero::PendingTransaction::Priority _priority =
            static_cast<Monero::PendingTransaction::Priority>(priority);

    Monero::PendingTransaction *tx = wallet->createTransaction(_dst_addr, _payment_id,
                                                               amount, (uint32_t) mixin_count,
                                                               _priority,
                                                               (uint32_t) accountIndex);

    env->ReleaseStringUTFChars(dst_addr, _dst_addr);
    env->ReleaseStringUTFChars(payment_id, _payment_id);
    return reinterpret_cast<jlong>(tx);
}

JNIEXPORT jlong JNICALL
Java_com_m2049r_xmrwallet_model_Wallet_createSweepTransaction(JNIEnv *env, jobject instance,
                                                              jstring dst_addr, jstring payment_id,
                                                              jint mixin_count,
                                                              jint priority,
                                                              jint accountIndex) {

    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    if (wallet == nullptr) {
        LOGE("wallet handle is null in %s", __FUNCTION__);
        return 0;
    }
    const char *_dst_addr = env->GetStringUTFChars(dst_addr, nullptr);
    const char *_payment_id = env->GetStringUTFChars(payment_id, nullptr);
    Monero::PendingTransaction::Priority _priority =
            static_cast<Monero::PendingTransaction::Priority>(priority);

    Monero::optional<uint64_t> empty;

    Monero::PendingTransaction *tx = wallet->createTransaction(_dst_addr, _payment_id,
                                                               empty, (uint32_t) mixin_count,
                                                               _priority,
                                                               (uint32_t) accountIndex);

    env->ReleaseStringUTFChars(dst_addr, _dst_addr);
    env->ReleaseStringUTFChars(payment_id, _payment_id);
    return reinterpret_cast<jlong>(tx);
}

JNIEXPORT jlong JNICALL
Java_com_m2049r_xmrwallet_model_Wallet_createSweepUnmixableTransactionJ(JNIEnv *env,
                                                                        jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    if (wallet == nullptr) {
        LOGE("wallet handle is null in %s", __FUNCTION__);
        return 0;
    }
    Monero::PendingTransaction *tx = wallet->createSweepUnmixableTransaction();
    return reinterpret_cast<jlong>(tx);
}

JNIEXPORT jboolean JNICALL
Java_com_m2049r_xmrwallet_model_Wallet_submitTransaction(JNIEnv *env, jobject instance,
                                                         jstring fileName) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    if (wallet == nullptr) {
        LOGE("wallet handle is null in %s", __FUNCTION__);
        return JNI_FALSE;
    }
    if (fileName == nullptr) {
        LOGE("submitTransaction fileName is null");
        return JNI_FALSE;
    }

    const char *_fileName = env->GetStringUTFChars(fileName, nullptr);
    if (_fileName == nullptr) {
        LOGE("submitTransaction failed to read fileName");
        return JNI_FALSE;
    }
    bool success = false;
    try {
        success = wallet->submitTransaction(std::string(_fileName));
    } catch (const std::exception &e) {
        LOGE("submitTransaction: caught exception: %s", e.what());
    }
    env->ReleaseStringUTFChars(fileName, _fileName);
    return static_cast<jboolean>(success);
}

JNIEXPORT jlong JNICALL
Java_com_m2049r_xmrwallet_model_Wallet_loadUnsignedTxJ(JNIEnv *env, jobject instance,
                                                       jstring unsignedFileName) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    if (wallet == nullptr) {
        LOGE("wallet handle is null in %s", __FUNCTION__);
        return 0;
    }
    if (unsignedFileName == nullptr) {
        LOGE("loadUnsignedTx unsignedFileName is null");
        return 0;
    }

    const char *_unsignedFileName = env->GetStringUTFChars(unsignedFileName, nullptr);
    if (_unsignedFileName == nullptr) {
        LOGE("loadUnsignedTx failed to read unsignedFileName");
        return 0;
    }
    Monero::UnsignedTransaction *tx = nullptr;
    try {
        tx = wallet->loadUnsignedTx(std::string(_unsignedFileName));
    } catch (const std::exception &e) {
        LOGE("loadUnsignedTx: caught exception: %s", e.what());
    }
    env->ReleaseStringUTFChars(unsignedFileName, _unsignedFileName);
    return reinterpret_cast<jlong>(tx);
}

JNIEXPORT void JNICALL
Java_com_m2049r_xmrwallet_model_Wallet_disposeTransaction(JNIEnv *env, jobject instance,
                                                          jobject pendingTransaction) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    if (wallet == nullptr) {
        LOGE("wallet handle is null in %s", __FUNCTION__);
        return;
    }
    Monero::PendingTransaction *_pendingTransaction =
            getHandle<Monero::PendingTransaction>(env, pendingTransaction);
    wallet->disposeTransaction(_pendingTransaction);
}

JNIEXPORT jlong JNICALL
Java_com_m2049r_xmrwallet_model_Wallet_estimateTransactionFee(JNIEnv *env, jobject instance,
                                                              jobjectArray addresses,
                                                              jlongArray amounts,
                                                              jint priority) {

    std::vector<std::pair<std::string, uint64_t>> destinations;

    int destSize = env->GetArrayLength(addresses);
    assert(destSize == env->GetArrayLength(amounts));
    jlong *_amounts = env->GetLongArrayElements(amounts, nullptr);
    for (int i = 0; i < destSize; i++) {
        std::pair<std::string, uint64_t> pair;
        jstring dest = (jstring) env->GetObjectArrayElement(addresses, i);
        const char *_dest = env->GetStringUTFChars(dest, nullptr);
        pair.first = _dest;
        env->ReleaseStringUTFChars(dest, _dest);
        pair.second = ((uint64_t) _amounts[i]);
        destinations.emplace_back(pair);
    }
    env->ReleaseLongArrayElements(amounts, _amounts, 0);

    Monero::PendingTransaction::Priority _priority =
            static_cast<Monero::PendingTransaction::Priority>(priority);

    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    if (wallet == nullptr) {
        LOGE("wallet handle is null in %s", __FUNCTION__);
        return -1;
    }

    try {
        return static_cast<jlong>(wallet->estimateTransactionFee(destinations, _priority));
    } catch (const std::exception &e) {
        LOGE("estimateTransactionFee: caught exception: %s", e.what());
        return -1;
    }
}

//virtual bool exportKeyImages(const std::string &filename) = 0;
//virtual bool importKeyImages(const std::string &filename) = 0;


//virtual TransactionHistory * history() const = 0;
JNIEXPORT jlong JNICALL
Java_com_m2049r_xmrwallet_model_Wallet_getHistoryJ(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    if (wallet == nullptr) {
        LOGE("wallet handle is null in %s", __FUNCTION__);
        return 0;
    }
    return reinterpret_cast<jlong>(wallet->history());
}

//virtual AddressBook * addressBook() const = 0;

JNIEXPORT jlong JNICALL
Java_com_m2049r_xmrwallet_model_Wallet_getCoinsJ(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    if (wallet == nullptr) {
        LOGE("wallet handle is null in %s", __FUNCTION__);
        return 0;
    }
    return reinterpret_cast<jlong>(wallet->coins());
}

JNIEXPORT jlong JNICALL
Java_com_m2049r_xmrwallet_model_Wallet_setListenerJ(JNIEnv *env, jobject instance,
                                                    jobject javaListener) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);

    // Invalidate old listener — release JNI ref but keep the C++ object alive
    // so any stale refresh thread callback lands on a valid vtable and returns
    // safely (jlistener == nullptr under the mutex).
    MyWalletListener *oldListener = getHandle<MyWalletListener>(env, instance,
                                                                "listenerHandle");
    if (oldListener != nullptr) {
        if (wallet != nullptr) {
            wallet->setListener(nullptr);
        }
        oldListener->deleteGlobalJavaRef(env);
        // Intentionally not deleted — see "Retired listener pointers" comment.
    }
    if (wallet == nullptr || javaListener == nullptr) {
        LOGD("setListenerJ: wallet=%p javaListener=%p", wallet, javaListener);
        return 0;
    }
    MyWalletListener *listener = new MyWalletListener(env, javaListener);
    wallet->setListener(listener);
    return reinterpret_cast<jlong>(listener);
}

JNIEXPORT jint JNICALL
Java_com_m2049r_xmrwallet_model_Wallet_getDefaultMixin(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    if (wallet == nullptr) {
        LOGE("wallet handle is null in %s", __FUNCTION__);
        return 0;
    }
    return wallet->defaultMixin();
}

JNIEXPORT void JNICALL
Java_com_m2049r_xmrwallet_model_Wallet_setDefaultMixin(JNIEnv *env, jobject instance, jint mixin) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    if (wallet == nullptr) {
        LOGE("wallet handle is null in %s", __FUNCTION__);
        return;
    }
    return wallet->setDefaultMixin(mixin);
}

JNIEXPORT jboolean JNICALL
Java_com_m2049r_xmrwallet_model_Wallet_setUserNote(JNIEnv *env, jobject instance,
                                                   jstring txid, jstring note) {

    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    if (wallet == nullptr) {
        LOGE("wallet handle is null in %s", __FUNCTION__);
        return JNI_FALSE;
    }

    const char *_txid = env->GetStringUTFChars(txid, nullptr);
    const char *_note = env->GetStringUTFChars(note, nullptr);

    bool success = wallet->setUserNote(_txid, _note);

    env->ReleaseStringUTFChars(txid, _txid);
    env->ReleaseStringUTFChars(note, _note);

    return static_cast<jboolean>(success);
}

JNIEXPORT jstring JNICALL
Java_com_m2049r_xmrwallet_model_Wallet_getUserNote(JNIEnv *env, jobject instance,
                                                   jstring txid) {

    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    if (wallet == nullptr) {
        LOGE("wallet handle is null in %s", __FUNCTION__);
        return nullptr;
    }

    const char *_txid = env->GetStringUTFChars(txid, nullptr);

    std::string note = wallet->getUserNote(_txid);

    env->ReleaseStringUTFChars(txid, _txid);
    return env->NewStringUTF(note.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_m2049r_xmrwallet_model_Wallet_getTxKey(JNIEnv *env, jobject instance,
                                                jstring txid) {

    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    if (wallet == nullptr) {
        LOGE("wallet handle is null in %s", __FUNCTION__);
        return nullptr;
    }

    const char *_txid = env->GetStringUTFChars(txid, nullptr);

    std::string txKey = wallet->getTxKey(_txid);

    env->ReleaseStringUTFChars(txid, _txid);
    return env->NewStringUTF(txKey.c_str());
}

//virtual void addSubaddressAccount(const std::string& label) = 0;
JNIEXPORT void JNICALL
Java_com_m2049r_xmrwallet_model_Wallet_addAccount(JNIEnv *env, jobject instance,
                                                  jstring label) {

    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    if (wallet == nullptr) {
        LOGE("wallet handle is null in %s", __FUNCTION__);
        return;
    }

    const char *_label = env->GetStringUTFChars(label, nullptr);
    wallet->addSubaddressAccount(_label);

    env->ReleaseStringUTFChars(label, _label);
}

//virtual std::string getSubaddressLabel(uint32_t accountIndex, uint32_t addressIndex) const = 0;
JNIEXPORT jstring JNICALL
Java_com_m2049r_xmrwallet_model_Wallet_getSubaddressLabel(JNIEnv *env, jobject instance,
                                                          jint accountIndex, jint addressIndex) {

    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    if (wallet == nullptr) {
        LOGE("wallet handle is null in %s", __FUNCTION__);
        return nullptr;
    }

    std::string label = wallet->getSubaddressLabel((uint32_t) accountIndex,
                                                   (uint32_t) addressIndex);

    return env->NewStringUTF(label.c_str());
}

//virtual void setSubaddressLabel(uint32_t accountIndex, uint32_t addressIndex, const std::string &label) = 0;
JNIEXPORT void JNICALL
Java_com_m2049r_xmrwallet_model_Wallet_setSubaddressLabel(JNIEnv *env, jobject instance,
                                                          jint accountIndex, jint addressIndex,
                                                          jstring label) {

    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    if (wallet == nullptr) {
        LOGE("wallet handle is null in %s", __FUNCTION__);
        return;
    }

    const char *_label = env->GetStringUTFChars(label, nullptr);
    wallet->setSubaddressLabel(accountIndex, addressIndex, _label);

    env->ReleaseStringUTFChars(label, _label);
}

// virtual size_t numSubaddressAccounts() const = 0;
JNIEXPORT jint JNICALL
Java_com_m2049r_xmrwallet_model_Wallet_getNumAccounts(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    if (wallet == nullptr) {
        LOGE("wallet handle is null in %s", __FUNCTION__);
        return 0;
    }
    return static_cast<jint>(wallet->numSubaddressAccounts());
}

//virtual size_t numSubaddresses(uint32_t accountIndex) const = 0;
JNIEXPORT jint JNICALL
Java_com_m2049r_xmrwallet_model_Wallet_getNumSubaddresses(JNIEnv *env, jobject instance,
                                                          jint accountIndex) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    if (wallet == nullptr) {
        LOGE("wallet handle is null in %s", __FUNCTION__);
        return 0;
    }
    return static_cast<jint>(wallet->numSubaddresses(accountIndex));
}

//virtual void addSubaddress(uint32_t accountIndex, const std::string &label) = 0;
JNIEXPORT void JNICALL
Java_com_m2049r_xmrwallet_model_Wallet_addSubaddress(JNIEnv *env, jobject instance,
                                                     jint accountIndex,
                                                     jstring label) {

    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    if (wallet == nullptr) {
        LOGE("wallet handle is null in %s", __FUNCTION__);
        return;
    }
    const char *_label = env->GetStringUTFChars(label, nullptr);
    wallet->addSubaddress(accountIndex, _label);
    env->ReleaseStringUTFChars(label, _label);
}

/*JNIEXPORT jstring JNICALL
Java_com_m2049r_xmrwallet_model_Wallet_getLastSubaddress(JNIEnv *env, jobject instance,
                                                         jint accountIndex) {

    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    size_t num = wallet->numSubaddresses(accountIndex);
    //wallet->subaddress()->getAll()[num]->getAddress().c_str()
    Monero::Subaddress *s = wallet->subaddress();
    s->refresh(accountIndex);
    std::vector<Monero::SubaddressRow *> v = s->getAll();
    return env->NewStringUTF(v[num - 1]->getAddress().c_str());
}
*/
//virtual std::string signMessage(const std::string &message) = 0;
//virtual bool verifySignedMessage(const std::string &message, const std::string &addres, const std::string &signature) const = 0;

//virtual bool parse_uri(const std::string &uri, std::string &address, std::string &payment_id, uint64_t &tvAmount, std::string &tx_description, std::string &recipient_name, std::vector<std::string> &unknown_parameters, std::string &error) = 0;
//virtual bool rescanSpent() = 0;


// TransactionHistory
JNIEXPORT jint JNICALL
Java_com_m2049r_xmrwallet_model_TransactionHistory_getCount(JNIEnv *env, jobject instance) {
    Monero::TransactionHistory *history = getHandle<Monero::TransactionHistory>(env,
                                                                                instance);
    return history->count();
}

jobject newTransferInstance(JNIEnv *env, uint64_t amount, const std::string &address) {
    jmethodID c = env->GetMethodID(class_Transfer, "<init>",
                                   "(JLjava/lang/String;)V");
    jstring _address = env->NewStringUTF(address.c_str());
    jobject transfer = env->NewObject(class_Transfer, c, static_cast<jlong> (amount), _address);
    env->DeleteLocalRef(_address);
    return transfer;
}

jobject newTransferList(JNIEnv *env, Monero::TransactionInfo *info) {
    const std::vector<Monero::TransactionInfo::Transfer> &transfers = info->transfers();
    if (transfers.empty()) { // don't create empty Lists
        return nullptr;
    }
    // make new ArrayList
    jmethodID java_util_ArrayList_ = env->GetMethodID(class_ArrayList, "<init>", "(I)V");
    jmethodID java_util_ArrayList_add = env->GetMethodID(class_ArrayList, "add",
                                                         "(Ljava/lang/Object;)Z");
    jobject result = env->NewObject(class_ArrayList, java_util_ArrayList_,
                                    static_cast<jint> (transfers.size()));
    // create Transfer objects and stick them in the List
    for (const Monero::TransactionInfo::Transfer &s: transfers) {
        jobject element = newTransferInstance(env, s.amount, s.address);
        env->CallBooleanMethod(result, java_util_ArrayList_add, element);
        env->DeleteLocalRef(element);
    }
    return result;
}

jobject newTransactionInfo(JNIEnv *env, Monero::TransactionInfo *info) {
    jmethodID c = env->GetMethodID(class_TransactionInfo, "<init>",
                                   "(IZZJJJLjava/lang/String;JLjava/lang/String;IIJJLjava/lang/String;Ljava/util/List;)V");
    jobject transfers = newTransferList(env, info);
    jstring _hash = env->NewStringUTF(info->hash().c_str());
    jstring _paymentId = env->NewStringUTF(info->paymentId().c_str());
    jstring _label = env->NewStringUTF(info->label().c_str());
    uint32_t subaddrIndex = 0;
    if (info->direction() == Monero::TransactionInfo::Direction_In) {
        const auto &indices = info->subaddrIndex();
        if (!indices.empty())
            subaddrIndex = *indices.begin();
    }
    jobject result = env->NewObject(class_TransactionInfo, c,
                                    info->direction(),
                                    info->isPending(),
                                    info->isFailed(),
                                    static_cast<jlong> (info->amount()),
                                    static_cast<jlong> (info->fee()),
                                    static_cast<jlong> (info->blockHeight()),
                                    _hash,
                                    static_cast<jlong> (info->timestamp()),
                                    _paymentId,
                                    static_cast<jint> (info->subaddrAccount()),
                                    static_cast<jint> (subaddrIndex),
                                    static_cast<jlong> (info->confirmations()),
                                    static_cast<jlong> (info->unlockTime()),
                                    _label,
                                    transfers);
    env->DeleteLocalRef(transfers);
    env->DeleteLocalRef(_hash);
    env->DeleteLocalRef(_paymentId);
    return result;
}

#include <stdio.h>
#include <stdlib.h>

// Coins

jobject newCoinsInfo(JNIEnv *env, Monero::CoinsInfo *info) {
    jstring _hash = env->NewStringUTF(info->hash().c_str());

    jmethodID c = env->GetMethodID(class_CoinsInfo, "<init>", "(IIJJLjava/lang/String;ZZJZ)V");
    jobject result = env->NewObject(class_CoinsInfo, c,
                                    static_cast<jint> (info->subaddrAccount()),
                                    static_cast<jint> (info->subaddrIndex()),
                                    static_cast<jlong> (info->amount()),
                                    static_cast<jlong> (info->blockHeight()),
                                    _hash,
                                    info->spent(),
                                    info->frozen(),
                                    static_cast<jlong> (info->unlockTime()),
                                    info->unlocked());
    env->DeleteLocalRef(_hash);
    return result;
}

jobject coinsInfoArrayList(JNIEnv *env, const std::vector<Monero::CoinsInfo *> &vector,
                           uint32_t accountIndex, bool unspentOnly) {

    jmethodID java_util_ArrayList_ = env->GetMethodID(class_ArrayList, "<init>", "(I)V");
    jmethodID java_util_ArrayList_add = env->GetMethodID(class_ArrayList, "add",
                                                         "(Ljava/lang/Object;)Z");

    jobject arrayList = env->NewObject(class_ArrayList, java_util_ArrayList_,
                                       static_cast<jint> (vector.size()));
    for (Monero::CoinsInfo *s: vector) {
        if (s->subaddrAccount() != accountIndex) continue;
        if (s->spent() && unspentOnly) continue;
        jobject info = newCoinsInfo(env, s);
        env->CallBooleanMethod(arrayList, java_util_ArrayList_add, info);
        env->DeleteLocalRef(info);
    }
    return arrayList;
}

JNIEXPORT jint JNICALL
Java_com_m2049r_xmrwallet_model_Coins_getCount(JNIEnv *env, jobject instance) {
    Monero::Coins *coins = getHandle<Monero::Coins>(env, instance);
    return coins->count();
}

JNIEXPORT jobject JNICALL
Java_com_m2049r_xmrwallet_model_Coins_refresh(JNIEnv *env, jobject instance, jint accountIndex,
                                              jboolean unspentOnly) {
    Monero::Coins *coins = getHandle<Monero::Coins>(env, instance);
    coins->refresh();
    return coinsInfoArrayList(env, coins->getAll(), (uint32_t) accountIndex, unspentOnly);
}

jobject
transactionInfoArrayList(JNIEnv *env, const std::vector<Monero::TransactionInfo *> &vector,
                         uint32_t accountIndex) {

    jmethodID java_util_ArrayList_ = env->GetMethodID(class_ArrayList, "<init>", "(I)V");
    jmethodID java_util_ArrayList_add = env->GetMethodID(class_ArrayList, "add",
                                                         "(Ljava/lang/Object;)Z");

    jobject arrayList = env->NewObject(class_ArrayList, java_util_ArrayList_,
                                       static_cast<jint> (vector.size()));
    for (Monero::TransactionInfo *s: vector) {
        if (s->subaddrAccount() != accountIndex) continue;
        jobject info = newTransactionInfo(env, s);
        env->CallBooleanMethod(arrayList, java_util_ArrayList_add, info);
        env->DeleteLocalRef(info);
    }
    return arrayList;
}

JNIEXPORT jobject JNICALL
Java_com_m2049r_xmrwallet_model_TransactionHistory_refreshJ(JNIEnv *env, jobject instance,
                                                            jint accountIndex) {
    Monero::TransactionHistory *history = getHandle<Monero::TransactionHistory>(env,
                                                                                instance);
    if (history == nullptr) {
        LOGE("history handle is null in %s", __FUNCTION__);
        jclass class_ArrayList = env->FindClass("java/util/ArrayList");
        jmethodID c = env->GetMethodID(class_ArrayList, "<init>", "()V");
        return env->NewObject(class_ArrayList, c);
    }
    try {
        history->refresh();
        return transactionInfoArrayList(env, history->getAll(), (uint32_t) accountIndex);
    } catch (const std::exception &e) {
        LOGE("refreshJ: caught exception: %s", e.what());
        jclass class_ArrayList = env->FindClass("java/util/ArrayList");
        jmethodID c = env->GetMethodID(class_ArrayList, "<init>", "()V");
        return env->NewObject(class_ArrayList, c);
    }
}

// TransactionInfo is implemented in Java - no need here

JNIEXPORT jint JNICALL
Java_com_m2049r_xmrwallet_model_UnsignedTransaction_getStatusJ(JNIEnv *env, jobject instance) {
    Monero::UnsignedTransaction *tx = getHandle<Monero::UnsignedTransaction>(env, instance);
    if (tx == nullptr) return Monero::UnsignedTransaction::Status_Critical;
    return tx->status();
}

JNIEXPORT jstring JNICALL
Java_com_m2049r_xmrwallet_model_UnsignedTransaction_getErrorString(JNIEnv *env, jobject instance) {
    Monero::UnsignedTransaction *tx = getHandle<Monero::UnsignedTransaction>(env, instance);
    if (tx == nullptr) return env->NewStringUTF("Unsigned transaction handle is null");
    return env->NewStringUTF(tx->errorString().c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_m2049r_xmrwallet_model_UnsignedTransaction_sign(JNIEnv *env, jobject instance,
                                                        jstring signedFileName) {
    Monero::UnsignedTransaction *tx = getHandle<Monero::UnsignedTransaction>(env, instance);
    if (tx == nullptr) {
        LOGE("unsigned transaction handle is null in %s", __FUNCTION__);
        return JNI_FALSE;
    }
    if (signedFileName == nullptr) {
        LOGE("unsigned transaction sign signedFileName is null");
        return JNI_FALSE;
    }

    const char *_signedFileName = env->GetStringUTFChars(signedFileName, nullptr);
    if (_signedFileName == nullptr) {
        LOGE("unsigned transaction sign failed to read signedFileName");
        return JNI_FALSE;
    }
    bool success = false;
    try {
        success = tx->sign(std::string(_signedFileName));
    } catch (const std::exception &e) {
        LOGE("unsigned transaction sign: caught exception: %s", e.what());
    }
    env->ReleaseStringUTFChars(signedFileName, _signedFileName);
    return static_cast<jboolean>(success);
}

JNIEXPORT void JNICALL
Java_com_m2049r_xmrwallet_model_UnsignedTransaction_disposeJ(JNIEnv *env, jobject instance) {
    Monero::UnsignedTransaction *tx = getHandle<Monero::UnsignedTransaction>(env, instance);
    if (tx != nullptr) {
        delete tx;
        setHandleFromLong(env, instance, 0);
    }
}

JNIEXPORT jint JNICALL
Java_com_m2049r_xmrwallet_model_PendingTransaction_getStatusJ(JNIEnv *env, jobject instance) {
    Monero::PendingTransaction *tx = getHandle<Monero::PendingTransaction>(env, instance);
    return tx->status();
}

JNIEXPORT jstring JNICALL
Java_com_m2049r_xmrwallet_model_PendingTransaction_getErrorString(JNIEnv *env, jobject instance) {
    Monero::PendingTransaction *tx = getHandle<Monero::PendingTransaction>(env, instance);
    return env->NewStringUTF(tx->errorString().c_str());
}

// commit transaction or save to file if filename is provided.
JNIEXPORT jboolean JNICALL
Java_com_m2049r_xmrwallet_model_PendingTransaction_commit(JNIEnv *env, jobject instance,
                                                          jstring filename, jboolean overwrite) {

    const char *_filename = env->GetStringUTFChars(filename, nullptr);

    Monero::PendingTransaction *tx = getHandle<Monero::PendingTransaction>(env, instance);
    bool success = tx->commit(_filename, overwrite);

    env->ReleaseStringUTFChars(filename, _filename);
    return static_cast<jboolean>(success);
}


JNIEXPORT jlong JNICALL
Java_com_m2049r_xmrwallet_model_PendingTransaction_getAmount(JNIEnv *env, jobject instance) {
    Monero::PendingTransaction *tx = getHandle<Monero::PendingTransaction>(env, instance);
    return static_cast<jlong>(tx->amount());
}

JNIEXPORT jlong JNICALL
Java_com_m2049r_xmrwallet_model_PendingTransaction_getDust(JNIEnv *env, jobject instance) {
    Monero::PendingTransaction *tx = getHandle<Monero::PendingTransaction>(env, instance);
    return static_cast<jlong>(tx->dust());
}

JNIEXPORT jlong JNICALL
Java_com_m2049r_xmrwallet_model_PendingTransaction_getFee(JNIEnv *env, jobject instance) {
    Monero::PendingTransaction *tx = getHandle<Monero::PendingTransaction>(env, instance);
    return static_cast<jlong>(tx->fee());
}

// TODO this returns a vector of strings - deal with this later - for now return first one
JNIEXPORT jstring JNICALL
Java_com_m2049r_xmrwallet_model_PendingTransaction_getFirstTxIdJ(JNIEnv *env, jobject instance) {
    Monero::PendingTransaction *tx = getHandle<Monero::PendingTransaction>(env, instance);
    std::vector<std::string> txids = tx->txid();
    if (!txids.empty())
        return env->NewStringUTF(txids.front().c_str());
    else
        return nullptr;
}

JNIEXPORT jlong JNICALL
Java_com_m2049r_xmrwallet_model_PendingTransaction_getTxCount(JNIEnv *env, jobject instance) {
    Monero::PendingTransaction *tx = getHandle<Monero::PendingTransaction>(env, instance);
    return static_cast<jlong>(tx->txCount());
}


// these are all in Monero::Wallet - which I find wrong, so they are here!
//static void init(const char *argv0, const char *default_log_base_name);
//static void debug(const std::string &category, const std::string &str);
//static void info(const std::string &category, const std::string &str);
//static void warning(const std::string &category, const std::string &str);
//static void error(const std::string &category, const std::string &str);
JNIEXPORT void JNICALL
Java_com_m2049r_xmrwallet_model_WalletManager_initLogger(JNIEnv *env, jclass clazz,
                                                         jstring argv0,
                                                         jstring default_log_base_name) {

    const char *_argv0 = env->GetStringUTFChars(argv0, nullptr);
    const char *_default_log_base_name = env->GetStringUTFChars(default_log_base_name, nullptr);

    Monero::Wallet::init(_argv0, _default_log_base_name);

    env->ReleaseStringUTFChars(argv0, _argv0);
    env->ReleaseStringUTFChars(default_log_base_name, _default_log_base_name);
}

JNIEXPORT void JNICALL
Java_com_m2049r_xmrwallet_model_WalletManager_logDebug(JNIEnv *env, jclass clazz,
                                                       jstring category, jstring message) {

    const char *_category = env->GetStringUTFChars(category, nullptr);
    const char *_message = env->GetStringUTFChars(message, nullptr);

    Monero::Wallet::debug(_category, _message);

    env->ReleaseStringUTFChars(category, _category);
    env->ReleaseStringUTFChars(message, _message);
}

JNIEXPORT void JNICALL
Java_com_m2049r_xmrwallet_model_WalletManager_logInfo(JNIEnv *env, jclass clazz,
                                                      jstring category, jstring message) {

    const char *_category = env->GetStringUTFChars(category, nullptr);
    const char *_message = env->GetStringUTFChars(message, nullptr);

    Monero::Wallet::info(_category, _message);

    env->ReleaseStringUTFChars(category, _category);
    env->ReleaseStringUTFChars(message, _message);
}

JNIEXPORT void JNICALL
Java_com_m2049r_xmrwallet_model_WalletManager_logWarning(JNIEnv *env, jclass clazz,
                                                         jstring category, jstring message) {

    const char *_category = env->GetStringUTFChars(category, nullptr);
    const char *_message = env->GetStringUTFChars(message, nullptr);

    Monero::Wallet::warning(_category, _message);

    env->ReleaseStringUTFChars(category, _category);
    env->ReleaseStringUTFChars(message, _message);
}

JNIEXPORT void JNICALL
Java_com_m2049r_xmrwallet_model_WalletManager_logError(JNIEnv *env, jclass clazz,
                                                       jstring category, jstring message) {

    const char *_category = env->GetStringUTFChars(category, nullptr);
    const char *_message = env->GetStringUTFChars(message, nullptr);

    Monero::Wallet::error(_category, _message);

    env->ReleaseStringUTFChars(category, _category);
    env->ReleaseStringUTFChars(message, _message);
}

JNIEXPORT void JNICALL
Java_com_m2049r_xmrwallet_model_WalletManager_setLogLevel(JNIEnv *env, jclass clazz,
                                                          jint level) {
    Monero::WalletManagerFactory::setLogLevel(level);
}

JNIEXPORT jstring JNICALL
Java_com_m2049r_xmrwallet_model_WalletManager_moneroVersion(JNIEnv *env, jclass clazz) {
    return env->NewStringUTF(MONERO_VERSION);
}

//
// Ledger Stuff
//

/**
 * @brief LedgerExchange - exchange data with Ledger Device
 * @param command        - buffer for data to send
 * @param cmd_len        - length of send to send
 * @param response       - buffer for received data
 * @param max_resp_len   - size of receive buffer
 *
 * @return length of received data in response or -1 if error
 */
int LedgerExchange(
        unsigned char *command,
        unsigned int cmd_len,
        unsigned char *response,
        unsigned int max_resp_len) {
    LOGD("LedgerExchange");
    JNIEnv *jenv;
    int envStat = attachJVM(&jenv);
    if (envStat == JNI_ERR) return -1;

    jmethodID exchangeMethod = jenv->GetStaticMethodID(class_Ledger, "Exchange", "([B)[B");

    jsize sendLen = static_cast<jsize>(cmd_len);
    jbyteArray dataSend = jenv->NewByteArray(sendLen);
    jenv->SetByteArrayRegion(dataSend, 0, sendLen, (jbyte *) command);
    jbyteArray dataRecv = (jbyteArray) jenv->CallStaticObjectMethod(class_Ledger, exchangeMethod,
                                                                    dataSend);
    jenv->DeleteLocalRef(dataSend);
    if (dataRecv == nullptr) {
        detachJVM(jenv, envStat);
        LOGD("LedgerExchange SCARD_E_NO_READERS_AVAILABLE");
        return -1;
    }
    jsize len = jenv->GetArrayLength(dataRecv);
    LOGD("LedgerExchange SCARD_S_SUCCESS %u/%d", cmd_len, len);
    if (len <= max_resp_len) {
        jenv->GetByteArrayRegion(dataRecv, 0, len, (jbyte *) response);
        jenv->DeleteLocalRef(dataRecv);
        detachJVM(jenv, envStat);
        return static_cast<int>(len);;
    } else {
        jenv->DeleteLocalRef(dataRecv);
        detachJVM(jenv, envStat);
        LOGE("LedgerExchange SCARD_E_INSUFFICIENT_BUFFER");
        return -1;
    }
}

/**
 * @brief LedgerFind - find Ledger Device and return it's name
 * @param buffer - buffer for name of found device
 * @param len    - length of buffer
 * @return  0 - success
 *         -1 - no device connected / found
 *         -2 - JVM not found
 */
int LedgerFind(char *buffer, size_t len) {
    LOGD("LedgerName");
    JNIEnv *jenv;
    int envStat = attachJVM(&jenv);
    if (envStat == JNI_ERR) return -2;

    jmethodID nameMethod = jenv->GetStaticMethodID(class_Ledger, "Name", "()Ljava/lang/String;");
    jstring name = (jstring) jenv->CallStaticObjectMethod(class_Ledger, nameMethod);

    int ret;
    if (name != nullptr) {
        const char *_name = jenv->GetStringUTFChars(name, nullptr);
        strncpy(buffer, _name, len);
        jenv->ReleaseStringUTFChars(name, _name);
        buffer[len - 1] = 0; // terminate in case _name is bigger
        ret = 0;
        LOGD("LedgerName is %s", buffer);
    } else {
        buffer[0] = 0;
        ret = -1;
    }

    detachJVM(jenv, envStat);
    return ret;
}

//
// SidekickWallet Stuff
//

/**
 * @brief BtExchange     - exchange data with Monerujo Device
 * @param request        - buffer for data to send
 * @param request_len    - length of data to send
 * @param response       - buffer for received data
 * @param max_resp_len   - size of receive buffer
 *
 * @return length of received data in response or -1 if error, -2 if response buffer too small
 */
int BtExchange(
        unsigned char *request,
        unsigned int request_len,
        unsigned char *response,
        unsigned int max_resp_len) {
    JNIEnv *jenv;
    int envStat = attachJVM(&jenv);
    if (envStat == JNI_ERR) return -16;

    jmethodID exchangeMethod = jenv->GetStaticMethodID(class_BluetoothService, "Exchange",
                                                       "([B)[B");

    auto reqLen = static_cast<jsize>(request_len);
    jbyteArray reqData = jenv->NewByteArray(reqLen);
    jenv->SetByteArrayRegion(reqData, 0, reqLen, (jbyte *) request);
    LOGD("BtExchange cmd: 0x%02x with %u bytes", request[0], reqLen);
    auto dataRecv = (jbyteArray)
            jenv->CallStaticObjectMethod(class_BluetoothService, exchangeMethod, reqData);
    jenv->DeleteLocalRef(reqData);
    if (dataRecv == nullptr) {
        detachJVM(jenv, envStat);
        LOGD("BtExchange: error reading");
        return -1;
    }
    jsize respLen = jenv->GetArrayLength(dataRecv);
    LOGD("BtExchange response is %u bytes", respLen);
    if (respLen <= max_resp_len) {
        jenv->GetByteArrayRegion(dataRecv, 0, respLen, (jbyte *) response);
        jenv->DeleteLocalRef(dataRecv);
        detachJVM(jenv, envStat);
        return static_cast<int>(respLen);;
    } else {
        jenv->DeleteLocalRef(dataRecv);
        detachJVM(jenv, envStat);
        LOGE("BtExchange response buffer too small: %u < %u", respLen, max_resp_len);
        return -2;
    }
}

/**
 * @brief ConfirmTransfers
 * @param transfers - string of "fee (':' address ':' amount)+"
 *
 * @return true on accept, false on reject
 */
bool ConfirmTransfers(const char *transfers) {
    JNIEnv *jenv;
    int envStat = attachJVM(&jenv);
    if (envStat == JNI_ERR) return -16;

    jmethodID confirmMethod = jenv->GetStaticMethodID(class_SidekickService, "ConfirmTransfers",
                                                      "(Ljava/lang/String;)Z");

    jstring _transfers = jenv->NewStringUTF(transfers);
    auto confirmed =
            jenv->CallStaticBooleanMethod(class_SidekickService, confirmMethod, _transfers);
    jenv->DeleteLocalRef(_transfers);
    return confirmed;
}

#ifdef __cplusplus
}
#endif
