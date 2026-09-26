package com.fongmi.android.tv.ui.activity;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.format.Formatter;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.download.DownloadService;
import com.fongmi.android.tv.download.DownloadTask;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public final class DownloadActivity extends AppCompatActivity {
    private final List<DownloadTask> tasks = new ArrayList<>();

    public static void start(Context context) {
        context.startActivity(new Intent(context, DownloadActivity.class));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.download_title);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(padding, padding, padding, padding);
        LinearLayout actions = new LinearLayout(this);
        addButton(actions, R.string.dialog_negative, this::finish);
        addButton(actions, R.string.download_pause_all, () -> DownloadService.pauseAll(this));
        addButton(actions, R.string.download_resume_all, () -> DownloadService.resumeAll(this));
        root.addView(actions);
        TextView empty = new TextView(this);
        empty.setText(R.string.download_empty);
        root.addView(empty);
        ListView list = new ListView(this);
        ArrayAdapter<DownloadTask> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_2, android.R.id.text1, tasks) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                DownloadTask task = getItem(position);
                ((TextView) view.findViewById(android.R.id.text1)).setText(task.title + " · " + task.episodeName);
                String progress = Formatter.formatFileSize(DownloadActivity.this, task.downloaded);
                if (task.total > 0) progress += " / " + Formatter.formatFileSize(DownloadActivity.this, task.total);
                String status = getString(stateLabel(task.state)) + " · " + progress;
                if ("FAILED".equals(task.state) && task.error != null && !task.error.isEmpty()) status += "\n" + task.error;
                ((TextView) view.findViewById(android.R.id.text2)).setText(status);
                return view;
            }
        };
        list.setAdapter(adapter);
        list.setEmptyView(empty);
        list.setOnItemClickListener((p, v, position, id) -> showActions(tasks.get(position)));
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        AppDatabase.get().getDownloadTaskDao().observeAll().observe(this, values -> {
            tasks.clear();
            if (values != null) tasks.addAll(values);
            adapter.notifyDataSetChanged();
        });
    }

    private void addButton(LinearLayout row, int text, Runnable action) {
        Button button = new Button(this);
        button.setText(text);
        button.setOnClickListener(v -> action.run());
        row.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
    }

    private int stateLabel(String state) {
        return switch (state) {
            case "RESOLVING" -> R.string.download_resolving;
            case "DOWNLOADING" -> R.string.download_downloading;
            case "PAUSED" -> R.string.download_paused;
            case "COMPLETED" -> R.string.download_completed;
            case "FAILED" -> R.string.download_failed;
            default -> R.string.download_queued;
        };
    }

    private void showActions(DownloadTask task) {
        boolean completed = "COMPLETED".equals(task.state);
        boolean paused = "PAUSED".equals(task.state) || "FAILED".equals(task.state);
        int action = completed ? R.string.download_play : paused ? ("FAILED".equals(task.state) ? R.string.download_retry : R.string.download_resume) : R.string.download_pause;
        new AlertDialog.Builder(this).setTitle(task.episodeName)
                .setItems(new String[]{getString(action), getString(R.string.download_delete)}, (dialog, which) -> {
                    if (which == 1) {
                        new AlertDialog.Builder(this).setMessage(R.string.download_delete_confirm)
                                .setNegativeButton(R.string.dialog_negative, null)
                                .setPositiveButton(R.string.dialog_positive, (d, w) -> DownloadService.delete(this, task.id)).show();
                    } else if (completed) {
                        File file = DownloadService.completedFile(task);
                        if (file != null && file.isFile()) VideoActivity.file(this, file.getAbsolutePath());
                        else Toast.makeText(this, R.string.download_file_missing, Toast.LENGTH_SHORT).show();
                    } else if (paused) DownloadService.resume(this, task.id);
                    else DownloadService.pause(this, task.id);
                }).show();
    }
}
