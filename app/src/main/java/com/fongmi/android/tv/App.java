package com.fongmi.android.tv;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.pm.PackageManager;
import android.system.ErrnoException;
import android.system.Os;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.os.HandlerCompat;

import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.playback.PlaybackRemoteSyncer;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.setting.ProxySetting;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.LocalNetworkPermission;
import com.fongmi.android.tv.utils.NsdDeviceDiscovery;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.PreviousProcessExitLogger;
import com.fongmi.android.tv.utils.EpgReminder;
import com.fongmi.hook.Hook;
import com.github.catvod.crawler.DebugLogStore;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.Init;
import com.github.catvod.utils.Prefers;
import com.google.gson.Gson;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.IdentityHashMap;
import java.util.Map;

import dalvik.system.DexClassLoader;

public class App extends Application implements Application.ActivityLifecycleCallbacks {

    private static volatile App instance;

    private final Handler handler;
    private final Gson gson;
    private final long time;
    private final Map<ActivityLifecycleCallbacks, ActivityLifecycleCallbacks> extensionCallbacks = new IdentityHashMap<>();

    private Activity activity;
    private Hook hook;

    public App() {
        instance = this;
        gson = new Gson();
        time = System.currentTimeMillis();
        handler = HandlerCompat.createAsync(Looper.getMainLooper());
    }

    public static App get() {
        return instance;
    }

    public static Gson gson() {
        return get().gson;
    }

    public static long time() {
        return get().time;
    }

    public static Activity activity() {
        return get().activity;
    }

    public static void post(Runnable runnable) {
        get().handler.post(runnable);
    }

    public static void post(Runnable runnable, long delayMillis) {
        get().handler.removeCallbacks(runnable);
        if (delayMillis >= 0) get().handler.postDelayed(runnable, delayMillis);
    }

    public static void removeCallbacks(Runnable runnable) {
        get().handler.removeCallbacks(runnable);
    }

    public static void removeCallbacks(Runnable... runnable) {
        for (Runnable r : runnable) get().handler.removeCallbacks(r);
    }

    public void setHook(Hook hook) {
        this.hook = hook;
    }

    @Override
    public void registerActivityLifecycleCallbacks(ActivityLifecycleCallbacks callback) {
        if (callback == this || !(callback.getClass().getClassLoader() instanceof DexClassLoader)) {
            super.registerActivityLifecycleCallbacks(callback);
            return;
        }
        synchronized (extensionCallbacks) {
            if (extensionCallbacks.containsKey(callback)) return;
            ActivityLifecycleCallbacks guarded = (ActivityLifecycleCallbacks) Proxy.newProxyInstance(
                    callback.getClass().getClassLoader(),
                    new Class<?>[]{ActivityLifecycleCallbacks.class},
                    (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            return switch (method.getName()) {
                                case "equals" -> proxy == args[0];
                                case "hashCode" -> System.identityHashCode(proxy);
                                case "toString" -> "GuardedActivityLifecycleCallback{" + callback.getClass().getName() + "}";
                                default -> null;
                            };
                        }
                        try {
                            return method.invoke(callback, args);
                        } catch (InvocationTargetException e) {
                            removeFailedExtensionCallback(callback, e.getCause());
                            return null;
                        } catch (Throwable e) {
                            removeFailedExtensionCallback(callback, e);
                            return null;
                        }
                    });
            extensionCallbacks.put(callback, guarded);
            super.registerActivityLifecycleCallbacks(guarded);
        }
    }

    @Override
    public void unregisterActivityLifecycleCallbacks(ActivityLifecycleCallbacks callback) {
        synchronized (extensionCallbacks) {
            ActivityLifecycleCallbacks guarded = extensionCallbacks.remove(callback);
            super.unregisterActivityLifecycleCallbacks(guarded == null ? callback : guarded);
        }
    }

    private void removeFailedExtensionCallback(ActivityLifecycleCallbacks callback, Throwable error) {
        Log.e("App", "Unregistering failed extension lifecycle callback " + callback.getClass().getName(), error);
        unregisterActivityLifecycleCallbacks(callback);
    }

    @Override
    protected void attachBaseContext(Context base) {
        Init.set(base);
        Context localized = Setting.wrapLanguage(base);
        super.attachBaseContext(localized);
        Init.set(localized);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        configureGoRuntime();
        Prefers.remove("theme_color");
        Prefers.remove("wall_color");
        Setting.restoreClassicGreenWall();
        Setting.applyLanguage();
        DebugLogStore.restoreEnabled();
        if (DebugLogStore.isEnabled()) {
            Setting.logDebugEnvironment("restore");
            PreviousProcessExitLogger.log(this);
        }
        Notify.createChannel();
        EpgReminder.createChannel();
        ProxySetting.apply();
        registerActivityLifecycleCallbacks(this);
        post(this::startBackgroundServices, 1200);
    }

    private void configureGoRuntime() {
        String current = System.getenv("GODEBUG");
        StringBuilder value = new StringBuilder();
        if (current != null) {
            for (String setting : current.split(",")) {
                if (setting.startsWith("asyncpreemptoff=")) continue;
                if (!setting.isEmpty()) {
                    if (value.length() > 0) value.append(',');
                    value.append(setting);
                }
            }
        }
        if (value.length() > 0) value.append(',');
        value.append("asyncpreemptoff=1");
        try {
            Os.setenv("GODEBUG", value.toString(), true);
        } catch (ErrnoException e) {
            Log.w("App", "Unable to configure Go runtime", e);
        }
    }

    private void startBackgroundServices() {
        SpiderDebug.log("startup", "background services start cost=%sms", System.currentTimeMillis() - time);
        Server.get().start();
        History.cleanExpired();
        if (LocalNetworkPermission.isGranted(this)) NsdDeviceDiscovery.register();
        EpgReminder.rebuildFromStorage();
        PlaybackRemoteSyncer.start();
        SpiderDebug.log("startup", "background services ready cost=%sms", System.currentTimeMillis() - time);
    }

    @Override
    public PackageManager getPackageManager() {
        return hook != null ? hook : getBaseContext().getPackageManager();
    }

    @Override
    public String getPackageName() {
        return hook != null ? hook.getPackageName() : getBaseContext().getPackageName();
    }

    @Override
    public void onActivityResumed(@NonNull Activity activity) {
        if (activity != activity()) this.activity = activity;
        if (LocalNetworkPermission.isGranted(this)) NsdDeviceDiscovery.register();
    }

    @Override
    public void onActivityPaused(@NonNull Activity activity) {
        if (activity == activity()) this.activity = null;
    }

    @Override
    public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
    }

    @Override
    public void onActivityDestroyed(@NonNull Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
    }

    @Override
    public void onActivityStarted(@NonNull Activity activity) {
    }

    @Override
    public void onActivityStopped(@NonNull Activity activity) {
    }
}
