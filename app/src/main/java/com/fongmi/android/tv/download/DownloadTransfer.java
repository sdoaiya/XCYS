package com.fongmi.android.tv.download;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public final class DownloadTransfer {
    public static class Failure extends IOException {
        public Failure(String message) {
            super(message);
        }
    }

    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)");
    private static final long SPACE_RESERVE = 8L * 1024 * 1024;
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
            .followSslRedirects(false)
            .addNetworkInterceptor(chain -> {
                Request request = chain.request();
                HttpUrl original = request.tag(HttpUrl.class);
                if (original != null && (!original.host().equals(request.url().host()) || original.port() != request.url().port())) {
                    request = request.newBuilder().removeHeader("Cookie").removeHeader("Authorization")
                            .removeHeader("Proxy-Authorization").build();
                }
                return chain.proceed(request);
            }).build();

    public interface Progress {
        void update(DownloadTask task);
    }

    public static void run(DownloadTask task, String url, Map<String, String> headers, File part,
                           File completed, Progress progress, BooleanSupplier cancelled) throws Exception {
        checkCancelled(cancelled);
        HttpUrl parsed = HttpUrl.parse(url);
        if (parsed == null || !(parsed.scheme().equals("http") || parsed.scheme().equals("https")))
            throw new Failure("仅支持 HTTP/HTTPS 下载地址");
        File directory = part.getParentFile();
        if (directory == null || (!directory.isDirectory() && !directory.mkdirs())) throw new Failure("无法创建下载目录");
        if (completed.exists()) throw new Failure("目标文件已存在");
        long offset = part.exists() ? part.length() : 0;
        String validator = validator(task.etag, task.lastModified);
        // Only a persisted validator can bind partial bytes to the current resource.
        if (validator.isEmpty() || (task.total >= 0 && offset > task.total)) offset = 0;
        Request.Builder request = new Request.Builder().url(parsed).tag(HttpUrl.class, parsed);
        if (headers != null) for (Map.Entry<String, String> entry : headers.entrySet()) {
            String name = entry.getKey();
            if (name == null || entry.getValue() == null || ownedHeader(name)) continue;
            request.header(name, entry.getValue());
        }
        request.header("Accept-Encoding", "identity");
        if (offset > 0) request.header("Range", "bytes=" + offset + "-").header("If-Range", validator);
        Call call = CLIENT.newCall(request.build());
        AtomicBoolean finished = new AtomicBoolean();
        Thread caller = Thread.currentThread();
        Thread watcher = new Thread(() -> {
            try {
                while (!finished.get()) {
                    if (cancelled.getAsBoolean() || caller.isInterrupted()) { call.cancel(); return; }
                    Thread.sleep(200);
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "download-cancel");
        watcher.setDaemon(true);
        watcher.start();
        try (Response response = call.execute()) {
            checkCancelled(cancelled);
            if (response.code() != 200 && response.code() != 206) throw new Failure("下载服务器返回 HTTP " + response.code());
            ResponseBody body = response.body();
            if (body == null) throw new Failure("下载响应为空");
            String encoding = response.header("Content-Encoding", "identity");
            if (!encoding.equalsIgnoreCase("identity")) throw new Failure("服务器未提供可校验的原始文件");
            String mime = response.header("Content-Type", "").toLowerCase(Locale.ROOT).split(";", 2)[0].trim();
            rejectMime(mime);
            long total;
            long expected;
            if (response.code() == 206) {
                long[] range = range(response.header("Content-Range"), offset);
                total = range[2];
                expected = range[1] - range[0] + 1;
                if (offset > 0 && (!validator.equals(validator(response.header("ETag", ""), response.header("Last-Modified", "")))
                        || (task.total >= 0 && total != task.total))) throw new Failure("下载资源已变化，请删除任务后重新下载");
                if (body.contentLength() >= 0 && body.contentLength() != expected) throw new Failure("续传响应长度不一致");
            } else {
                // An ignored Range or failed If-Range means replace, never append.
                offset = 0;
                total = body.contentLength();
                expected = total;
            }
            if (total == 0) throw new Failure("下载文件为空");
            long free = directory.getUsableSpace();
            if (free < SPACE_RESERVE || (total > 0 && total - offset > free - SPACE_RESERVE)) throw new Failure("存储空间不足");
            try (BufferedInputStream input = new BufferedInputStream(body.byteStream(), 32768)) {
                input.mark(4096);
                byte[] prefix = readPrefix(input);
                input.reset();
                rejectText(prefix);
                if (offset == 0) requireMedia(prefix);
                else try (FileInputStream existing = new FileInputStream(part)) { requireMedia(readPrefix(existing)); }
                checkCancelled(cancelled);
                try (FileOutputStream output = new FileOutputStream(part, offset > 0)) {
                    task.etag = response.header("ETag", "");
                    task.lastModified = response.header("Last-Modified", "");
                    task.mime = mime;
                    task.downloaded = offset;
                    task.total = total;
                    task.state = "DOWNLOADING";
                    task.error = "";
                    progress.update(task);
                    byte[] buffer = new byte[32768];
                    long received = 0;
                    long nextProgress = System.nanoTime();
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        checkCancelled(cancelled);
                        if (expected >= 0 && received > expected - count) throw new Failure("文件数据超出响应长度");
                        output.write(buffer, 0, count);
                        received += count;
                        task.downloaded = offset + received;
                        if (System.nanoTime() >= nextProgress) {
                            if (directory.getUsableSpace() < SPACE_RESERVE) throw new Failure("存储空间不足");
                            progress.update(task);
                            nextProgress = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
                        }
                    }
                    checkCancelled(cancelled);
                    if (received == 0 || (expected >= 0 && received != expected)) throw new Failure("文件未完整下载，请重试");
                    if (total >= 0 && task.downloaded != total) throw new Failure("文件长度校验失败");
                    task.total = task.downloaded;
                    output.getFD().sync();
                }
            }
            checkCancelled(cancelled);
            if (!part.renameTo(completed)) throw new Failure("无法保存下载文件");
            progress.update(task);
        } finally {
            finished.set(true);
            watcher.interrupt();
        }
    }

    private static boolean ownedHeader(String name) {
        return name.equalsIgnoreCase("Range") || name.equalsIgnoreCase("If-Range") || name.equalsIgnoreCase("Accept-Encoding")
                || name.equalsIgnoreCase("Host") || name.equalsIgnoreCase("Content-Length") || name.equalsIgnoreCase("Transfer-Encoding");
    }

    static String validator(String etag, String lastModified) {
        if (etag != null && etag.length() >= 2 && etag.startsWith("\"") && etag.endsWith("\"")) return etag;
        return lastModified == null ? "" : lastModified;
    }

    static long[] range(String header, long offset) throws IOException {
        Matcher match = CONTENT_RANGE.matcher(header == null ? "" : header);
        if (!match.matches()) throw new Failure("无效的续传响应");
        try {
            long start = Long.parseLong(match.group(1));
            long end = Long.parseLong(match.group(2));
            long total = Long.parseLong(match.group(3));
            if (start != offset || end < start || total <= end || end != total - 1) throw new Failure("续传区间不完整或不匹配");
            return new long[]{start, end, total};
        } catch (NumberFormatException e) {
            throw new Failure("续传长度无效");
        }
    }

    private static byte[] readPrefix(InputStream input) throws IOException {
        byte[] bytes = new byte[1024];
        int size = 0;
        while (size < bytes.length) {
            int count = input.read(bytes, size, bytes.length - size);
            if (count == -1) break;
            size += count;
        }
        return java.util.Arrays.copyOf(bytes, size);
    }

    static void rejectMime(String mime) throws IOException {
        if (mime.startsWith("text/") || mime.contains("mpegurl") || mime.contains("dash") || mime.contains("html")
                || mime.contains("json") || mime.contains("xml") || mime.contains("drm") || mime.contains("widevine"))
            throw new Failure("此资源为网页、分段流或受保护内容，暂不支持下载");
    }

    private static void rejectText(byte[] bytes) throws IOException {
        String text = new String(bytes, StandardCharsets.UTF_8).trim().toLowerCase(Locale.ROOT);
        if (text.startsWith("\ufeff")) text = text.substring(1).trim();
        if (text.startsWith("#extm3u") || text.startsWith("<!doctype") || text.startsWith("<html") || text.startsWith("<?xml")
                || text.startsWith("<mpd") || text.startsWith("{") || text.startsWith("["))
            throw new Failure("此资源不是可下载的单文件媒体");
    }

    static void requireMedia(byte[] bytes) throws IOException {
        rejectText(bytes);
        // ponytail: sniff common file containers only; segmented streams need their own downloader.
        String prefix = new String(bytes, StandardCharsets.ISO_8859_1);
        if (prefix.contains("pssh") || prefix.contains("sinf")) throw new Failure("暂不支持受保护媒体下载");
        boolean mp4 = bytes.length >= 12 && (prefix.startsWith("ftyp", 4) || prefix.startsWith("moov", 4)
                || prefix.startsWith("mdat", 4) || prefix.startsWith("wide", 4));
        boolean ebml = bytes.length >= 4 && (bytes[0] & 255) == 0x1a && (bytes[1] & 255) == 0x45
                && (bytes[2] & 255) == 0xdf && (bytes[3] & 255) == 0xa3;
        boolean ts = bytes.length > 376 && bytes[0] == 0x47 && bytes[188] == 0x47 && bytes[376] == 0x47;
        boolean riff = prefix.startsWith("RIFF") && bytes.length >= 12 && (prefix.startsWith("AVI ", 8) || prefix.startsWith("WAVE", 8));
        if (!(mp4 || ebml || ts || riff || prefix.startsWith("FLV") || prefix.startsWith("OggS")
                || prefix.startsWith("ID3") || prefix.startsWith("fLaC"))) throw new Failure("无法识别为完整媒体文件，暂不支持下载");
    }

    private static void checkCancelled(BooleanSupplier cancelled) throws InterruptedIOException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("下载已暂停");
    }
}
