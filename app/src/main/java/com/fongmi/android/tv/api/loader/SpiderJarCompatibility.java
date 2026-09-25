package com.fongmi.android.tv.api.loader;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import com.fongmi.android.tv.App;
import com.fongmi.hook.Hook;

import java.util.concurrent.Callable;

public final class SpiderJarCompatibility {

    private static final String PACKAGE_NAME = "com.fongmi.android.tv";
    private static final String APP_LABEL = "影视";
    private static final ThreadLocal<PackageManager> PACKAGE_MANAGER = new ThreadLocal<>();

    private SpiderJarCompatibility() {
    }

    public static boolean isActive() {
        return PACKAGE_MANAGER.get() != null;
    }

    public static PackageManager getPackageManager() {
        return PACKAGE_MANAGER.get();
    }

    public static ApplicationInfo getApplicationInfo() {
        PackageManager manager = getPackageManager();
        if (manager == null) return null;
        try {
            return manager.getApplicationInfo(PACKAGE_NAME, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    public static <T> T call(Callable<T> callable) throws Exception {
        if (isActive()) return callable.call();
        App app = App.get();
        PackageManager manager = app.getBaseContext().getPackageManager();
        String actualPackage = app.getBaseContext().getPackageName();
        PackageInfo actualInfo = manager.getPackageInfo(actualPackage, PackageManager.GET_SIGNATURES);
        Signature[] signatures = actualInfo.signatures;
        if (signatures == null || signatures.length == 0) return callable.call();
        PACKAGE_MANAGER.set(new Hook(signatures[0].toCharsString(), PACKAGE_NAME) {
            @Override
            public PackageInfo getPackageInfo(String packageName, int flags) {
                try {
                    if (!PACKAGE_NAME.equals(packageName)) return manager.getPackageInfo(packageName, flags);
                    PackageInfo info = manager.getPackageInfo(actualPackage, flags | PackageManager.GET_SIGNATURES);
                    info.packageName = PACKAGE_NAME;
                    info.applicationInfo = getApplicationInfo(PACKAGE_NAME, flags);
                    return info;
                } catch (NameNotFoundException e) {
                    return null;
                }
            }

            @Override
            public ApplicationInfo getApplicationInfo(String packageName, int flags) {
                try {
                    if (!PACKAGE_NAME.equals(packageName)) return manager.getApplicationInfo(packageName, flags);
                    ApplicationInfo info = new ApplicationInfo(manager.getApplicationInfo(actualPackage, flags));
                    info.packageName = PACKAGE_NAME;
                    info.nonLocalizedLabel = APP_LABEL;
                    info.targetSdkVersion = Build.VERSION_CODES.P;
                    return info;
                } catch (NameNotFoundException e) {
                    return null;
                }
            }

            @Override
            public CharSequence getApplicationLabel(ApplicationInfo info) {
                if (info != null && PACKAGE_NAME.equals(info.packageName)) return APP_LABEL;
                return manager.getApplicationLabel(info);
            }
        });
        try {
            return callable.call();
        } finally {
            PACKAGE_MANAGER.remove();
        }
    }
}
