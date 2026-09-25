package com.fongmi.android.tv.utils;

import android.app.Activity;
import android.os.Looper;
import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.github.catvod.utils.Prefers;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class DependencyTrust {

    private static final String TRUST_KEY = "dependency_trust_all_sources";
    private static final String PLAY_PREFIX = "play_trust_";
    private static final Set<String> INSECURE_NOTICES = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<String, Object> TRUST_LOCKS = new ConcurrentHashMap<>();

    public static void rejectInsecure(String type) {
        if (!INSECURE_NOTICES.add(type)) return;
        App.post(() -> Notify.show(R.string.error_dependency_insecure));
    }

    public static boolean confirmPlay(String origin, String url) {
        if (TextUtils.isEmpty(url)) return false;
        if (TextUtils.isEmpty(origin)) return false;
        String key = PLAY_PREFIX + sha256(origin);
        if (Prefers.getBoolean(key, false)) return true;
        if (Looper.myLooper() == Looper.getMainLooper()) return false;
        Activity activity = App.activity();
        if (activity == null || activity.isFinishing()) return false;
        CountDownLatch latch = new CountDownLatch(1);
        boolean[] result = new boolean[]{false};
        App.post(() -> new MaterialAlertDialogBuilder(activity)
                .setTitle("播放确认")
                .setMessage("来源：\n" + origin + "\n\n将播放：\n" + url + "\n\n仅信任你确认来源的页面。")
                .setNegativeButton("拒绝", (dialog, which) -> latch.countDown())
                .setPositiveButton("信任并播放", (dialog, which) -> {
                    Prefers.put(key, true);
                    result[0] = true;
                    latch.countDown();
                })
                .setOnCancelListener(dialog -> latch.countDown())
                .show());
        try {
            latch.await(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return result[0];
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.substring(0, 32);
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    public static boolean confirm(String type, String url, String hashType, String hash, File file) {
        if (TextUtils.isEmpty(url)) return false;
        // 远程依赖允许无哈希：首次由用户确认信任，之后全局记忆
        String key = TRUST_KEY;
        if (Prefers.getBoolean(key, false)) return true;
        if (Looper.myLooper() == Looper.getMainLooper()) return false;
        synchronized (TRUST_LOCKS.computeIfAbsent(key, ignored -> new Object())) {
            // Multiple sites can request their JARs concurrently. Recheck after acquiring the
            // global lock so one acceptance authorizes the remaining dependencies.
            if (Prefers.getBoolean(key, false)) return true;
            Activity activity = App.activity();
            if (activity == null || activity.isFinishing()) return false;
            CountDownLatch latch = new CountDownLatch(1);
            boolean[] result = new boolean[]{false};
            App.post(() -> new MaterialAlertDialogBuilder(activity)
                    .setTitle("远程依赖确认")
                    .setMessage(message(type, url, hashType, hash, file))
                    .setNegativeButton("拒绝", (dialog, which) -> latch.countDown())
                    .setPositiveButton("信任并加载", (dialog, which) -> {
                        Prefers.put(key, true);
                        result[0] = true;
                        latch.countDown();
                    })
                    .setOnCancelListener(dialog -> latch.countDown())
                    .show());
            try {
                latch.await(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return result[0];
        }
    }

    private static String message(String type, String url, String hashType, String hash, File file) {
        StringBuilder sb = new StringBuilder();
        sb.append("类型：").append(type).append('\n');
        sb.append("配置源：").append(VodConfig.getUrl()).append('\n');
        sb.append("URL：").append(url).append('\n');
        sb.append("Hash：").append(TextUtils.isEmpty(hash) ? "无（确认后全局信任）" : hashType + ":" + hash).append('\n');
        if (file != null && file.exists()) sb.append("大小：").append(file.length()).append(" bytes\n");
        sb.append('\n').append("确认后，后续所有线路的远程").append(type).append("依赖都将自动加载。");
        sb.append('\n').append("授权保存在本机，更换线路后仍生效；后续 HTTP 依赖未加密，可能被篡改。");
        sb.append('\n').append("仅在你信任当前和后续线路时授权。");
        return sb.toString();
    }
}
