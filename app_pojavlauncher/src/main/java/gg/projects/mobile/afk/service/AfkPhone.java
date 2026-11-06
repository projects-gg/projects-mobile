package gg.projects.mobile.afk.service;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;

import androidx.annotation.RequiresApi;

import gg.projects.mobile.afk.api.AfkLimits;
import gg.projects.mobile.afk.core.AfkEngine;

/**
 * PHONE-AFK's watch on the phone while the service runs (its main thread): the network for AFK (Wi-Fi or
 * unmetered, mobile data only, none), the battery ({@link AfkLimits#BATTERY_STOP_PERCENT} or less while not
 * charging) and the heat (Android 10+: "severe" or worse), and the partial wake lock kept while accounts run
 * (phone-afk.md §3: a foreground service keeps the network in Doze but not the CPU).
 */
final class AfkPhone {
    interface Listener {
        void network(AfkEngine.Net net);

        void batteryLow();

        void tooHot();
    }

    private static final long WAKE_TIMEOUT_MS = 15 * 60 * 1000L;

    private final Context app;
    private final Listener listener;
    private PowerManager.WakeLock wakeLock;
    private ConnectivityManager.NetworkCallback networkCallback;
    private BroadcastReceiver batteryReceiver;
    private Object thermalListener;

    AfkPhone(Context context, Listener listener) {
        this.app = context.getApplicationContext();
        this.listener = listener;
    }

    void start() {
        watchNetwork();
        watchBattery();
        if (Build.VERSION.SDK_INT >= 29) watchHeat();
    }

    void stop() {
        ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null && networkCallback != null) {
            try {
                cm.unregisterNetworkCallback(networkCallback);
            } catch (RuntimeException ignored) {
                // Not registered.
            }
        }
        networkCallback = null;
        if (batteryReceiver != null) {
            try {
                app.unregisterReceiver(batteryReceiver);
            } catch (RuntimeException ignored) {
                // Not registered.
            }
        }
        batteryReceiver = null;
        if (Build.VERSION.SDK_INT >= 29) unwatchHeat();
        holdAwake(false);
    }

    /** Keeps the CPU awake (re-held with a timeout by the service's tick) or lets it sleep. */
    @SuppressLint("WakelockTimeout")
    void holdAwake(boolean on) {
        if (on) {
            if (wakeLock == null) {
                PowerManager power = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
                if (power == null) return;
                wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ProjectsLauncher:afk");
                wakeLock.setReferenceCounted(false);
            }
            wakeLock.acquire(WAKE_TIMEOUT_MS);
        } else if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    // ---- network ----

    private void watchNetwork() {
        ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                listener.network(netOf(caps));
            }

            @Override
            public void onLost(Network network) {
                listener.network(AfkEngine.Net.NONE);
            }
        };
        try {
            cm.registerDefaultNetworkCallback(networkCallback, new android.os.Handler(android.os.Looper.getMainLooper()));
        } catch (RuntimeException e) {
            networkCallback = null;
            return;
        }
        Network active = cm.getActiveNetwork();
        NetworkCapabilities caps = active == null ? null : cm.getNetworkCapabilities(active);
        listener.network(caps == null ? AfkEngine.Net.NONE : netOf(caps));
    }

    static AfkEngine.Net netOf(NetworkCapabilities caps) {
        if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return AfkEngine.Net.NONE;
        boolean wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
        boolean cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
        if (wifi) return AfkEngine.Net.WIFI;
        if (!cellular && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) return AfkEngine.Net.WIFI;
        return AfkEngine.Net.MOBILE;
    }

    // ---- battery ----

    private void watchBattery() {
        batteryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                check(intent);
            }
        };
        Intent sticky = app.registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (sticky != null) check(sticky);
    }

    private void check(Intent battery) {
        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        if (level < 0 || scale <= 0 || plugged != 0) return;
        if (level * 100 / scale <= AfkLimits.BATTERY_STOP_PERCENT) listener.batteryLow();
    }

    /** The battery is low and not charging now (a start would stop at once). */
    boolean batteryLowNow() {
        Intent battery = app.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery == null) return false;
        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        return level >= 0 && scale > 0 && plugged == 0 && level * 100 / scale <= AfkLimits.BATTERY_STOP_PERCENT;
    }

    // ---- heat ----

    @RequiresApi(29)
    private void watchHeat() {
        PowerManager power = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
        if (power == null) return;
        PowerManager.OnThermalStatusChangedListener l = status -> {
            if (status >= PowerManager.THERMAL_STATUS_SEVERE) listener.tooHot();
        };
        power.addThermalStatusListener(l);
        thermalListener = l;
    }

    @RequiresApi(29)
    private void unwatchHeat() {
        PowerManager power = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
        if (power != null && thermalListener != null) {
            power.removeThermalStatusListener((PowerManager.OnThermalStatusChangedListener) thermalListener);
        }
        thermalListener = null;
    }

    /** Too hot right now (Android 10+). */
    boolean tooHotNow() {
        if (Build.VERSION.SDK_INT < 29) return false;
        PowerManager power = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
        return power != null && power.getCurrentThermalStatus() >= PowerManager.THERMAL_STATUS_SEVERE;
    }
}
