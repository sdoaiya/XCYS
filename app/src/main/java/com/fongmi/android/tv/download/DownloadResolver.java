package com.fongmi.android.tv.download;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.impl.ParseCallback;
import com.fongmi.android.tv.player.ParseJob;
import com.fongmi.android.tv.player.PlayerHelper;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.Task;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import okhttp3.HttpUrl;

final class DownloadResolver {
    static final class Source {
        String url;
        Map<String, String> headers;

        Source(String url, Map<String, String> headers) {
            this.url = url;
            this.headers = headers == null ? new HashMap<>() : new HashMap<>(headers);
        }
    }

    static Source resolve(DownloadTask task, BooleanSupplier cancelled) throws Exception {
        if (task.configId != VodConfig.getCid()) throw new DownloadTransfer.Failure("站点配置已切换，请切回原配置后重试");
        String episodeUrl = DownloadSecrets.decrypt(task.episodeUrl);
        Future<Result> request = Task.executor().submit(() -> SiteApi.downloadContent(task.siteKey, task.flag, episodeUrl));
        Result result;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        try {
            while (true) {
                checkCancelled(cancelled);
                try {
                    result = request.get(200, TimeUnit.MILLISECONDS);
                    break;
                } catch (TimeoutException e) {
                    if (System.nanoTime() > deadline) throw new DownloadTransfer.Failure("获取下载地址超时，请重试");
                }
            }
        } finally {
            request.cancel(true);
        }
        if (task.configId != VodConfig.getCid()) throw new DownloadTransfer.Failure("站点配置已切换，请切回原配置后重试");
        if (result == null || result.hasMsg() || result.getUrl().isEmpty()) throw new DownloadTransfer.Failure("站点未返回可下载地址");
        if (result.getDrm() != null) throw new DownloadTransfer.Failure("不支持下载 DRM 保护的内容");
        String directUrl = result.getRealUrl();
        HttpUrl direct = HttpUrl.parse(directUrl);
        boolean directFile = direct != null && ("http".equalsIgnoreCase(direct.scheme()) || "https".equalsIgnoreCase(direct.scheme()))
                && !result.getUrl().isMulti();
        Source source = directFile ? new Source(directUrl, result.getHeader()) : parse(result, task.useParse, cancelled);
        if (source.url == null || HttpUrl.parse(source.url) == null) throw new DownloadTransfer.Failure("只支持 HTTP(S) 单文件下载，此线路需要专用播放器或代理");
        source.headers.entrySet().removeIf(entry -> entry.getKey() == null || entry.getValue() == null);
        if (source.headers.keySet().stream().noneMatch("User-Agent"::equalsIgnoreCase)) {
            source.headers.put("User-Agent", Setting.getUa().isEmpty() ? PlayerHelper.getDefaultUa() : Setting.getUa());
        }
        checkCancelled(cancelled);
        return source;
    }

    private static Source parse(Result result, boolean useParse, BooleanSupplier cancelled) throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<Source> source = new AtomicReference<>();
        AtomicReference<String> error = new AtomicReference<>();
        AtomicReference<ParseJob> job = new AtomicReference<>();
        App.post(() -> {
            if (cancelled.getAsBoolean()) { ready.countDown(); return; }
            ParseJob created = ParseJob.create(new ParseCallback() {
                @Override
                public void onParseSuccess(Map<String, String> headers, String url, String from) {
                    source.set(new Source(url, headers));
                    ready.countDown();
                }

                @Override
                public void onParseError() {
                    error.set("下载地址解析失败，请换线路或重试");
                    ready.countDown();
                }
            });
            job.set(created);
            try {
                created.startDownload(result, useParse);
            } catch (Exception e) {
                error.set("下载暂不支持此解析方式，请选择直链或 JSON 解析线路");
                created.stop();
                ready.countDown();
            }
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        try {
            while (!ready.await(200, TimeUnit.MILLISECONDS)) {
                checkCancelled(cancelled);
                if (System.nanoTime() > deadline) throw new DownloadTransfer.Failure("下载地址解析超时，请重试");
            }
            checkCancelled(cancelled);
            if (source.get() == null) throw new DownloadTransfer.Failure(error.get() == null ? "下载地址解析失败" : error.get());
            return source.get();
        } finally {
            App.post(() -> { if (job.get() != null) job.get().stop(); });
        }
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException();
    }
}
