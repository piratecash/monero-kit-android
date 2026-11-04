/*
 * Copyright (c) 2021 m2049r
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

package com.m2049r.levin.util;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import androidx.core.content.ContextCompat;

import com.piratecash.monero.net.MoneroHttpClient;

import java.net.Proxy;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import info.guardianproject.netcipher.client.StrongOkHttpClientBuilder;
import info.guardianproject.netcipher.proxy.MyOrbotHelper;
import info.guardianproject.netcipher.proxy.SignatureUtils;
import info.guardianproject.netcipher.proxy.StatusCallback;
import okhttp3.OkHttpClient;
import com.piratecash.monero.log.MoneroLog;

public class NetCipherHelper implements StatusCallback {
    private static final String TAG = "MoneroKit:Http";

    public static final int TOR_TIMEOUT_CONNECT = 10000; //ms
    public static final int TOR_TIMEOUT = 5000; //ms

    public interface OnStatusChangedListener {
        void connected();

        void disconnected();

        void notInstalled();

        void notEnabled();
    }

    final private Context context;
    final private MyOrbotHelper orbot;

    @SuppressLint("StaticFieldLeak")
    private static NetCipherHelper Instance;

    public static void createInstance(Context context) {
        if (Instance == null) {
            synchronized (NetCipherHelper.class) {
                if (Instance == null) {
                    final Context applicationContext = context.getApplicationContext();
                    Instance = new NetCipherHelper(applicationContext, MyOrbotHelper.get(context).statusTimeout(5000));
                }
            }
        }
    }

    public static NetCipherHelper getInstance() {
        if (Instance == null) throw new IllegalStateException("NetCipherHelper is null");
        return Instance;
    }

    private void createTorClient(Intent statusIntent) {
        String orbotStatus = statusIntent.getStringExtra(MyOrbotHelper.EXTRA_STATUS);
        if (orbotStatus == null) throw new IllegalStateException("status is null");
        if (!orbotStatus.equals(MyOrbotHelper.STATUS_ON))
            throw new IllegalStateException("Orbot is not ON");
        try {
            final OkHttpClient.Builder okBuilder = new OkHttpClient.Builder()
                    .connectTimeout(TOR_TIMEOUT_CONNECT, TimeUnit.MILLISECONDS)
                    .writeTimeout(TOR_TIMEOUT, TimeUnit.MILLISECONDS)
                    .readTimeout(TOR_TIMEOUT, TimeUnit.MILLISECONDS);
            MoneroHttpClient.setClient(new StrongOkHttpClientBuilder(context)
                    .withSocksProxy()
                    .applyTo(okBuilder, statusIntent)
                    .build());
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private void createClearnetClient() {
        MoneroHttpClient.setClient(MoneroHttpClient.newClearnetClient());
    }

    private OnStatusChangedListener onStatusChangedListener;

    public static void deregister() {
        getInstance().onStatusChangedListener = null;
    }

    public static void register(OnStatusChangedListener listener) {
        final NetCipherHelper me = getInstance();
        me.onStatusChangedListener = listener;

        // NOT_INSTALLED is dealt with through the callbacks
        me.orbot.removeStatusCallback(me) // make sure we are registered just once
                .addStatusCallback(me);

        // deal with  org.torproject.android.intent.action.STATUS = STARTS_DISABLED
        ContextCompat.registerReceiver(me.context, orbotStatusReceiver, new IntentFilter(MyOrbotHelper.ACTION_STATUS), ContextCompat.RECEIVER_EXPORTED);

        me.startTor();
    }

    // for StatusCallback
    public enum Status {
        STARTING,
        ENABLED,
        STOPPING,
        DISABLED,
        NOT_INSTALLED,
        NOT_ENABLED,
        UNKNOWN;
    }

    private Status status = Status.UNKNOWN;

    @Override
    public void onStarting() {
        MoneroLog.d(TAG, "onStarting");
        status = Status.STARTING;
    }

    @Override
    public void onEnabled(Intent statusIntent) {
        MoneroLog.d(TAG, "onEnabled");
        if (getTorPref() != Status.ENABLED) return; // do we want Tor?
        createTorClient(statusIntent);
        status = Status.ENABLED;
        if (onStatusChangedListener != null) {
            new Thread(() -> onStatusChangedListener.connected()).start();
        }
    }

    @Override
    public void onStopping() {
        MoneroLog.d(TAG, "onStopping");
        status = Status.STOPPING;
    }

    @Override
    public void onDisabled() {
        MoneroLog.d(TAG, "onDisabled");
        createClearnetClient();
        status = Status.DISABLED;
        if (onStatusChangedListener != null) {
            new Thread(() -> onStatusChangedListener.disconnected()).start();
        }
    }

    @Override
    public void onStatusTimeout() {
        MoneroLog.d(TAG, "onStatusTimeout");
        createClearnetClient();
        // (timeout does not not change the status)
        if (onStatusChangedListener != null) {
            new Thread(() -> onStatusChangedListener.disconnected()).start();
        }
        orbotInit = false; // do init() next time we try to open Tor
    }

    @Override
    public void onNotYetInstalled() {
        MoneroLog.d(TAG, "onNotYetInstalled");
        // never mind then
        orbot.removeStatusCallback(this);
        createClearnetClient();
        status = Status.NOT_INSTALLED;
        if (onStatusChangedListener != null) {
            new Thread(() -> onStatusChangedListener.notInstalled()).start();
        }
    }

    // user has not enabled background Orbot starts
    public void onNotEnabled() {
        MoneroLog.d(TAG, "onNotEnabled");
        // keep the callback in case they turn it on manually
        setTorPref(Status.DISABLED);
        createClearnetClient();
        status = Status.NOT_ENABLED;
        if (onStatusChangedListener != null) {
            new Thread(() -> onStatusChangedListener.notEnabled()).start();
        }
    }

    static public Status getStatus() {
        return getInstance().status;
    }

    public void toggle() {
        switch (getStatus()) {
            case ENABLED:
                onDisabled();
                setTorPref(Status.DISABLED);
                break;
            case DISABLED:
                setTorPref(Status.ENABLED);
                startTor();
                break;
        }
    }

    private boolean orbotInit = false;

    private void startTor() {
        if (!isOrbotInstalled()) {
            onNotYetInstalled();
        } else if (getTorPref() == Status.DISABLED) {
            onDisabled();
        } else if (!orbotInit) {
            orbotInit = orbot.init();
        } else {
            orbot.requestStart(context);
        }
    }

    // extracted from OrbotHelper
    private boolean isOrbotInstalled() {
        ArrayList<String> hashes = new ArrayList<>();
        // Tor Project signing key
        hashes.add("A4:54:B8:7A:18:47:A8:9E:D7:F5:E7:0F:BA:6B:BA:96:F3:EF:29:C2:6E:09:81:20:4F:E3:47:BF:23:1D:FD:5B");
        // f-droid.org signing key
        hashes.add("A7:02:07:92:4F:61:FF:09:37:1D:54:84:14:5C:4B:EE:77:2C:55:C1:9E:EE:23:2F:57:70:E1:82:71:F7:CB:AE");

        return null != SignatureUtils.validateBroadcastIntent(context,
                MyOrbotHelper.getOrbotStartIntent(context),
                hashes, false);
    }


    static public boolean isTor() {
        return getStatus() == Status.ENABLED;
    }

    static public String getProxy() {
        if (!isTor()) return "";
        final Proxy proxy = MoneroHttpClient.getClient().proxy();
        if (proxy == null) return "";
        return proxy.address().toString().substring(1);
    }

    private static final String PREFS_NAME = "tor";
    private static final String PREFS_STATUS = "status";
    private Status currentPref = Status.UNKNOWN;

    private Status getTorPref() {
        if (currentPref != Status.UNKNOWN) return currentPref;
        currentPref = Status.valueOf(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(PREFS_STATUS, "DISABLED"));
        return currentPref;
    }

    private void setTorPref(Status status) {
        if (getTorPref() == status) return; // no change
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(PREFS_STATUS, status.name())
                .apply();
        currentPref = status;
    }

    private static final BroadcastReceiver orbotStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            MoneroLog.d(TAG, "%s/%s", intent.getAction(), intent.getStringExtra(MyOrbotHelper.EXTRA_STATUS));
            if (MyOrbotHelper.ACTION_STATUS.equals(intent.getAction())) {
                if (MyOrbotHelper.STATUS_STARTS_DISABLED.equals(intent.getStringExtra(MyOrbotHelper.EXTRA_STATUS))) {
                    getInstance().onNotEnabled();
                }
            }
        }
    };

    public void installOrbot(Activity host) {
        host.startActivity(MyOrbotHelper.getOrbotInstallIntent(context));
    }

    public NetCipherHelper(Context context, MyOrbotHelper orbot) {
        this.context = context;
        this.orbot = orbot;
    }
}
