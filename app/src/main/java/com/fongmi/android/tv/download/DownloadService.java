package com.fongmi.android.tv.download;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.ui.activity.DownloadActivity;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Task;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

public final class DownloadService extends Service {
    private static final String ACTION_PAUSE = "download.pause";
    private static final String ACTION_RESUME = "download.resume";
    private static final String ACTION_DELETE = "download.delete";
    private static final String EXTRA_ID = "id";
    private static final int NOTIFICATION_ID = Notify.ID + 20;
    private static final ConcurrentHashMap<String, Boolean> CANCELLED = new ConcurrentHashMap<>();

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "download-worker");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean destroyed;

    public static void enqueueEpisodes(Context context, int configId, String siteKey, String vodId, String title,
                                       String flag, List<Episode> episodes, boolean useParse) {
        Task.execute(() -> {
            long now = System.currentTimeMillis();
            for (Episode episode : episodes) {
                if (episode == null || TextUtils.isEmpty(episode.getUrl())) continue;
                DownloadTask task = new DownloadTask();
                task.id = UUID.randomUUID().toString();
                task.title = title == null ? "" : title;
                task.episodeName = episode.getName();
                task.siteKey = siteKey == null ? "" : siteKey;
                task.vodId = vodId == null ? "" : vodId;
                task.flag = flag == null ? "" : flag;
                try {
                    task.episodeUrl = DownloadSecrets.encrypt(episode.getUrl());
                } catch (Exception error) {
                    continue;
                }
                task.state = "QUEUED";
                task.configId = configId;
                task.total = -1;
                task.createdAt = now++;
                task.updatedAt = task.createdAt;
                task.useParse = useParse;
                task.source = task.id + extension(episode.getUrl());
                AppDatabase.get().getDownloadTaskDao().insertIgnore(task);
            }
            start(context);
        });
    }

    public static void start(Context context) {
        Intent intent = new Intent(context, DownloadService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ContextCompat.startForegroundService(context, intent);
        else context.startService(intent);
    }

    public static void pause(Context context, String id) {
        Task.execute(() -> {
            DownloadTask task = AppDatabase.get().getDownloadTaskDao().find(id);
            if (task == null || "COMPLETED".equals(task.state)) return;
            CANCELLED.put(id, true);
            task.state = "PAUSED";
            task.updatedAt = System.currentTimeMillis();
            AppDatabase.get().getDownloadTaskDao().update(task);
        });
    }

    public static void resume(Context context, String id) {
        Task.execute(() -> {
            DownloadTask task = AppDatabase.get().getDownloadTaskDao().find(id);
            if (task == null || "COMPLETED".equals(task.state)) return;
            CANCELLED.remove(id);
            task.state = "QUEUED";
            task.error = "";
            task.updatedAt = System.currentTimeMillis();
            AppDatabase.get().getDownloadTaskDao().update(task);
            start(context);
        });
    }

    public static void pauseAll(Context context) {
        Task.execute(() -> {
            for (DownloadTask task : AppDatabase.get().getDownloadTaskDao().getAll()) {
                if (!"COMPLETED".equals(task.state) && !"CANCELED".equals(task.state)) {
                    CANCELLED.put(task.id, true);
                    task.state = "PAUSED";
                    task.updatedAt = System.currentTimeMillis();
                    AppDatabase.get().getDownloadTaskDao().update(task);
                }
            }
        });
    }

    public static void resumeAll(Context context) {
        Task.execute(() -> {
            for (DownloadTask task : AppDatabase.get().getDownloadTaskDao().getAll()) {
                if ("PAUSED".equals(task.state) || "FAILED".equals(task.state)) {
                    CANCELLED.remove(task.id);
                    task.state = "QUEUED";
                    task.error = "";
                    task.updatedAt = System.currentTimeMillis();
                    AppDatabase.get().getDownloadTaskDao().update(task);
                }
            }
            start(context);
        });
    }

    public static void delete(Context context, String id) {
        Task.execute(() -> {
            DownloadTask task = AppDatabase.get().getDownloadTaskDao().find(id);
            CANCELLED.put(id, true);
            if (task != null) {
                File part = new File(directory(context), task.source + ".part");
                File completed = new File(directory(context), task.source);
                if (part.exists()) part.delete();
                if (completed.exists()) completed.delete();
                AppDatabase.get().getDownloadTaskDao().delete(id);
            }
        });
    }

    public static File completedFile(DownloadTask task) {
        if (task == null || TextUtils.isEmpty(task.source)) return null;
        return new File(directory(null), task.source);
    }

    private static File directory(Context context) {
        Context app = context == null ? App.get() : context.getApplicationContext();
        File directory = new File(app.getFilesDir(), "downloads");
        if (!directory.isDirectory()) directory.mkdirs();
        return directory;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Notify.createChannel();
        startForegroundCompat(notification(null));
        worker.execute(this::drain);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            String id = intent.getStringExtra(EXTRA_ID);
            if (id != null && (ACTION_PAUSE.equals(action) || ACTION_RESUME.equals(action) || ACTION_DELETE.equals(action))) {
                Task.execute(() -> handleAction(action, id));
                if (ACTION_RESUME.equals(action)) worker.execute(this::drain);
            }
        }
        return START_NOT_STICKY;
    }

    private void handleAction(String action, String id) {
        DownloadTask task = AppDatabase.get().getDownloadTaskDao().find(id);
        if (task == null) return;
        if (ACTION_DELETE.equals(action)) {
            CANCELLED.put(id, true);
            File part = new File(directory(this), task.source + ".part");
            File completed = new File(directory(this), task.source);
            if (part.exists()) part.delete();
            if (completed.exists()) completed.delete();
            AppDatabase.get().getDownloadTaskDao().delete(id);
        } else if (ACTION_PAUSE.equals(action)) {
            CANCELLED.put(id, true);
            if (!"COMPLETED".equals(task.state)) task.state = "PAUSED";
            task.updatedAt = System.currentTimeMillis();
            AppDatabase.get().getDownloadTaskDao().update(task);
        } else if (!"COMPLETED".equals(task.state)) {
            CANCELLED.remove(id);
            task.state = "QUEUED";
            task.error = "";
            task.updatedAt = System.currentTimeMillis();
            AppDatabase.get().getDownloadTaskDao().update(task);
        }
    }

    private void drain() {
        try {
            // Room forbids main-thread access; recover any tasks left mid-flight by a previous process here.
            AppDatabase.get().getDownloadTaskDao().recoverInterrupted();
            int idle = 0;
            while (!destroyed && idle < 10) {
                DownloadTask task = next();
                if (task == null) {
                    idle++;
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    continue;
                }
                idle = 0;
                process(task);
            }
        } finally {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    private DownloadTask next() {
        for (DownloadTask task : AppDatabase.get().getDownloadTaskDao().getAll()) {
            if ("QUEUED".equals(task.state)) return task;
        }
        return null;
    }

    private void process(DownloadTask task) {
        BooleanSupplier cancelled = () -> destroyed || CANCELLED.containsKey(task.id);
        try {
            task.state = "RESOLVING";
            task.error = "";
            save(task);
            DownloadResolver.Source source = DownloadResolver.resolve(task, cancelled);
            File part = new File(directory(this), task.source + ".part");
            File completed = new File(directory(this), task.source);
            DownloadTransfer.run(task, source.url, source.headers, part, completed, current -> {
                current.updatedAt = System.currentTimeMillis();
                save(current);
                updateNotification(current);
            }, cancelled);
            task.state = "COMPLETED";
            task.downloaded = task.total;
            task.updatedAt = System.currentTimeMillis();
            save(task);
        } catch (Throwable error) {
            task.updatedAt = System.currentTimeMillis();
            if (cancelled.getAsBoolean()) task.state = "PAUSED";
            else {
                task.state = "FAILED";
                task.error = message(error);
            }
            save(task);
        } finally {
            CANCELLED.remove(task.id);
        }
    }

    private void save(DownloadTask task) {
        AppDatabase.get().getDownloadTaskDao().update(task);
    }

    private void updateNotification(DownloadTask task) {
        androidx.core.app.NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification(task));
    }

    private Notification notification(DownloadTask task) {
        String title = task == null ? getString(R.string.download_title) : task.title + " · " + task.episodeName;
        String text = task == null ? getString(R.string.download_queued) : getString(R.string.download_downloading);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, Notify.DEFAULT)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, DownloadActivity.class), pendingFlags()))
                .setOngoing(task != null)
                .setSilent(true);
        if (task != null && task.total > 0) builder.setProgress((int) Math.min(Integer.MAX_VALUE, task.total), (int) Math.min(Integer.MAX_VALUE, task.downloaded), false);
        return builder.build();
    }

    private int pendingFlags() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_UPDATE_CURRENT;
    }

    private void startForegroundCompat(Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(NOTIFICATION_ID, notification);
    }

    private static String extension(String url) {
        try {
            String path = okhttp3.HttpUrl.parse(url).encodedPath();
            int dot = path.lastIndexOf('.');
            if (dot >= 0 && dot < path.length() - 1) {
                String ext = path.substring(dot).toLowerCase(java.util.Locale.ROOT);
                if (ext.matches("\\.(mp4|mkv|webm|ts|flv|avi|mov|m4v|mp3|m4a|ogg|flac)")) return ext;
            }
        } catch (Exception ignored) {
        }
        return ".media";
    }

    private static String message(Throwable error) {
        if (error instanceof DownloadTransfer.Failure && error.getMessage() != null) return error.getMessage();
        return error.getMessage() == null ? "下载失败，请重试" : error.getMessage();
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        worker.shutdownNow();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
