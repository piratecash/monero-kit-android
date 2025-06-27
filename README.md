# monero-kit-android

**monero-kit-android** is a demo Android application that shows how to use Monero Kit (based on Monerujo) for working with a Monero wallet: getting wallet info, viewing transactions, and sending XMR.

## Features

- Get wallet information (address, network, balance, sync state)
- View transaction history (incoming, outgoing, status, details)
- Send XMR to another address with optional notes
- Clear and re-initialize the wallet
- Tor support (NetCipher)

## Requirements

- **Android SDK**: minSdk 27, targetSdk 35
- **JVM**: 11
- **NDK**: ABI support for arm64-v8a, armeabi-v7a, x86_64
- **Internet**: Requires `android.permission.INTERNET`
- **local.properties**: You must add the following fields:
  ```
  words="seed_25_words"
  restore_height="num_height restore"
  ```
  - `words` — your 25-word Monero seed phrase (in quotes)
  - `restore_height` — block height or restore date (e.g., "2024-01-01" or block number)

## Build & Run

1. Clone the repository:
    ```sh
    git clone https://github.com/piratecash/monero-kit-android.git
    cd monero-kit-android
    ```

2. Add your Monero seed and restore height to `local.properties`:
    ```
    words="seed_25_words"
    restore_height="num_height restore"
    ```

3. Build and run the project via Android Studio or with:
    ```sh
    ./gradlew assembleDebug
    ```

4. Install the APK on your device or emulator.

## Usage

- On first launch, the app initializes the wallet using your seed and restore height.
- The main screen displays your address, network, balance, and sync state.
- The "Transactions" tab shows your transaction history.
- The "Send" tab allows you to send XMR to another address, specifying the amount and an optional note.
- The "Clear" button deletes the wallet and allows you to re-create it.

## Dependencies

- [Monerujo](https://github.com/m2049r/xmrwallet) (via the `monerokit` module)
- Jetpack Compose, Material3, Timber, NetCipher, OkHttp, Guava, and more (see build.gradle.kts)

## Project Structure

- `app/` — demo Compose application
- `monerokit/` — Monero library (JNI, Java/Kotlin wrappers)

## License

> _Specify your project license here_

## Author

- PirateCash Team, based on [Monerujo](https://github.com/m2049r/xmrwallet) 