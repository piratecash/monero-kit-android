/*
 * Copyright (c) 2017 m2049r
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.m2049r.xmrwallet.util;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;

import com.m2049r.xmrwallet.model.WalletManager;

import java.io.File;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Locale;

import timber.log.Timber;

public class Helper {
    static public final String NOCRAZYPASS_FLAGFILE = ".nocrazypass";

    static public final int XMR_DECIMALS = 12;
    static public final long ONE_XMR = Math.round(Math.pow(10, Helper.XMR_DECIMALS));

    static public final boolean SHOW_EXCHANGERATES = true;
    static public boolean ALLOW_SHIFT = true;

    static private final String WALLET_DIR = "wallets";
    static private final String MONERO_DIR = "monero";

    static public int DISPLAY_DIGITS_INFO = 5;

    static public File getWalletRoot(Context context) {
        return getStorage(context, WALLET_DIR);
    }

    static public File getStorage(Context context, String folderName) {
        File dir = new File(context.getFilesDir(), folderName);
        if (!dir.exists()) {
            Timber.i("Creating %s", dir.getAbsolutePath());
            dir.mkdirs(); // try to make it
        }
        if (!dir.isDirectory()) {
            String msg = "Directory " + dir.getAbsolutePath() + " does not exist.";
            Timber.e(msg);
            throw new IllegalStateException(msg);
        }
        return dir;
    }

    static public File getWalletFile(Context context, String aWalletName) {
        File walletDir = getWalletRoot(context);
        File f = new File(walletDir, aWalletName);
        Timber.d("wallet=%s size= %d", f.getAbsolutePath(), f.length());
        return f;
    }

    static public BigDecimal getDecimalAmount(long amount) {
        return new BigDecimal(amount).scaleByPowerOfTen(-XMR_DECIMALS);
    }

    static public String getDisplayAmount(long amount) {
        return getDisplayAmount(amount, XMR_DECIMALS);
    }

    static public String getDisplayAmount(long amount, int maxDecimals) {
        // a Java bug does not strip zeros properly if the value is 0
        if (amount == 0) return "0.00";
        BigDecimal d = getDecimalAmount(amount)
                .setScale(maxDecimals, BigDecimal.ROUND_HALF_UP)
                .stripTrailingZeros();
        if (d.scale() < 2)
            d = d.setScale(2, BigDecimal.ROUND_UNNECESSARY);
        return d.toPlainString();
    }

    static public String getFormattedAmount(double amount, boolean isCrypto) {
        // at this point selection is XMR in case of error
        String displayB;
        if (isCrypto) {
            if ((amount >= 0) || (amount == 0)) {
                displayB = String.format(Locale.US, "%,.5f", amount);
            } else {
                displayB = null;
            }
        } else { // not crypto
            displayB = String.format(Locale.US, "%,.2f", amount);
        }
        return displayB;
    }

    static public String getDisplayAmount(double amount) {
        // a Java bug does not strip zeros properly if the value is 0
        BigDecimal d = new BigDecimal(amount)
                .setScale(XMR_DECIMALS, BigDecimal.ROUND_HALF_UP)
                .stripTrailingZeros();
        if (d.scale() < 1)
            d = d.setScale(1, BigDecimal.ROUND_UNNECESSARY);
        return d.toPlainString();
    }

    public static String bytesToHex(byte[] data) {
        if ((data != null) && (data.length > 0))
            return String.format("%0" + (data.length * 2) + "X", new BigInteger(1, data));
        else return "";
    }

    public static byte[] hexToBytes(String hex) {
        final int len = hex.length();
        final byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    static public void setMoneroHome(Context context) {
        try {
            String home = getStorage(context, MONERO_DIR).getAbsolutePath();
            Os.setenv("HOME", home, true);
        } catch (ErrnoException ex) {
            throw new IllegalStateException(ex);
        }
    }

    // TODO make the log levels refer to the  WalletManagerFactory::LogLevel enum ?
    static public void initLogger(Context context, int level) {
        String home = getStorage(context, MONERO_DIR).getAbsolutePath();
        WalletManager.initLogger(home + "/monerujo", "monerujo.log");
        if (level >= WalletManager.LOGLEVEL_SILENT)
            WalletManager.setLogLevel(level);
    }

    static public boolean useCrazyPass(Context context) {
        File flagFile = new File(getWalletRoot(context), NOCRAZYPASS_FLAGFILE);
        return !flagFile.exists();
    }

    // try to figure out what the real wallet password is given the user password
    // which could be the actual wallet password or a (maybe malformed) CrAzYpass
    // or the password used to derive the CrAzYpass for the wallet
    static public String getWalletPassword(Context context, String walletName, String password) {
        String walletPath = new File(getWalletRoot(context), walletName + ".keys").getAbsolutePath();

        // try with entered password (which could be a legacy password or a CrAzYpass)
        if (WalletManager.getInstance().verifyWalletPasswordOnly(walletPath, password)) {
            return password;
        }

        // maybe this is a malformed CrAzYpass?
        String possibleCrazyPass = CrazyPassEncoder.reformat(password);
        if (possibleCrazyPass != null) { // looks like a CrAzYpass
            if (WalletManager.getInstance().verifyWalletPasswordOnly(walletPath, possibleCrazyPass)) {
                return possibleCrazyPass;
            }
        }

        // generate & try with CrAzYpass
        String crazyPass = KeyStoreHelper.getCrazyPass(context, password);
        if (WalletManager.getInstance().verifyWalletPasswordOnly(walletPath, crazyPass)) {
            return crazyPass;
        }

        // or maybe it is a broken CrAzYpass? (of which we have two variants)
        String brokenCrazyPass2 = KeyStoreHelper.getBrokenCrazyPass(context, password, 2);
        if ((brokenCrazyPass2 != null)
                && WalletManager.getInstance().verifyWalletPasswordOnly(walletPath, brokenCrazyPass2)) {
            return brokenCrazyPass2;
        }
        String brokenCrazyPass1 = KeyStoreHelper.getBrokenCrazyPass(context, password, 1);
        if ((brokenCrazyPass1 != null)
                && WalletManager.getInstance().verifyWalletPasswordOnly(walletPath, brokenCrazyPass1)) {
            return brokenCrazyPass1;
        }

        return null;
    }
}
