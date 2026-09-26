package com.fongmi.android.tv.ui.dialog;

import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentActivity;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.download.DownloadService;
import com.fongmi.android.tv.ui.activity.DownloadActivity;
import java.util.ArrayList;
import java.util.List;

public final class DownloadEpisodesDialog {
    public static void show(FragmentActivity activity, int configId, String siteKey, String vodId, String title, Flag flag, Episode current, boolean useParse) {
        if (flag == null || flag.getEpisodes().isEmpty()) {
            android.widget.Toast.makeText(activity, R.string.download_no_episodes, android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        List<Episode> episodes = new ArrayList<>(flag.getEpisodes());
        boolean[] checked = new boolean[episodes.size()];
        String[] labels = new String[episodes.size()];
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (16 * activity.getResources().getDisplayMetrics().density);
        root.setPadding(padding, 0, padding, 0);
        TextView count = new TextView(activity);
        root.addView(count);
        LinearLayout actions = new LinearLayout(activity);
        Button all = new Button(activity);
        all.setText(R.string.download_all);
        Button clear = new Button(activity);
        clear.setText(R.string.download_clear);
        actions.addView(all, new LinearLayout.LayoutParams(0, -2, 1));
        actions.addView(clear, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(actions);
        ListView list = new ListView(activity);
        list.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        for (int i = 0; i < episodes.size(); i++) {
            labels[i] = episodes.get(i).getName();
            checked[i] = episodes.get(i).equals(current);
        }
        list.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_list_item_multiple_choice, labels));
        for (int i = 0; i < checked.length; i++) list.setItemChecked(i, checked[i]);
        root.addView(list, new LinearLayout.LayoutParams(-1, (int) (activity.getResources().getDisplayMetrics().heightPixels * .42f)));
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(R.string.download_select).setView(root)
                .setNegativeButton(R.string.dialog_negative, null)
                .setNeutralButton(R.string.download_title, (d, w) -> DownloadActivity.start(activity))
                .setPositiveButton(R.string.download_start, null).create();
        Runnable refresh = () -> {
            int selected = 0;
            for (boolean value : checked) if (value) selected++;
            count.setText(activity.getString(R.string.download_selected, selected, flag.getShow()));
            if (dialog.getButton(AlertDialog.BUTTON_POSITIVE) != null) dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(selected > 0);
        };
        list.setOnItemClickListener((p, v, position, id) -> { checked[position] = list.isItemChecked(position); refresh.run(); });
        all.setOnClickListener(v -> { for (int i = 0; i < checked.length; i++) { checked[i] = true; list.setItemChecked(i, true); } refresh.run(); });
        clear.setOnClickListener(v -> { for (int i = 0; i < checked.length; i++) { checked[i] = false; list.setItemChecked(i, false); } refresh.run(); });
        dialog.setOnShowListener(d -> {
            refresh.run();
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                List<Episode> selected = new ArrayList<>();
                for (int i = 0; i < checked.length; i++) if (checked[i]) selected.add(episodes.get(i));
                if (selected.isEmpty()) return;
                DownloadService.enqueueEpisodes(activity, configId, siteKey, vodId, title, flag.getFlag(), selected, useParse);
                dialog.dismiss();
                DownloadActivity.start(activity);
            });
        });
        dialog.show();
    }
}
