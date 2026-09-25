package com.fongmi.android.tv.player.danmaku;

import androidx.annotation.Nullable;
import androidx.media3.ui.danmaku.Danmaku;
import androidx.media3.ui.danmaku.parser.Parser;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Generic danmaku parser for third-party JSON APIs. Registered ahead of the built-in parsers but
 * sniffing is deliberately narrow: plain arrays must carry both a text-like and a time-like key,
 * wrapped objects must expose a known wrapper key pointing at an array, and the platform-specific
 * JSON markers (barrage_list / playat / propertis) are left to their dedicated parsers.
 */
public final class JsonDanmakuParser implements Parser {

    private static final Pattern WRAPPER_ARRAY = Pattern.compile("\"(data|danmaku|danmus|comments|list)\"\\s*:\\s*\\[");
    private static final Pattern TEXT_KEY = Pattern.compile("\"(text|content|txt|message)\"\\s*:");
    private static final Pattern TIME_KEY = Pattern.compile("\"(time|p|stime|timepoint|position)\"\\s*:");
    private static final String[] TEXT_KEYS = {"text", "content", "txt", "message"};
    private static final String[] TIME_KEYS = {"time", "stime", "timepoint", "position"};
    private static final String[] MODE_KEYS = {"mode", "m", "type"};
    private static final String[] COLOR_KEYS = {"color", "c", "colour"};
    private static final String[] SIZE_KEYS = {"size", "fontsize"};
    private static final String[] WRAPPER_KEYS = {"data", "danmaku", "danmus", "comments", "list"};

    @Override
    public boolean sniff(InputStream is, int sniffLength) throws IOException {
        String head = readHead(is, sniffLength).trim();
        if (head.startsWith("[")) return TEXT_KEY.matcher(head).find() && TIME_KEY.matcher(head).find();
        if (!head.startsWith("{")) return false;
        if (head.contains("\"barrage_list\"") || head.contains("\"playat\"") || head.contains("\"propertis\"")) return false;
        return WRAPPER_ARRAY.matcher(head).find() && TEXT_KEY.matcher(head).find();
    }

    @Override
    public List<Danmaku> parse(InputStream is) throws IOException {
        List<Danmaku> result = new ArrayList<>();
        try {
            JSONArray array = rootArray(readAll(is).trim());
            if (array == null) return result;
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                Danmaku danmaku = parseItem(item);
                if (danmaku != null) result.add(danmaku);
            }
        } catch (JSONException e) {
            throw new IOException("Failed to parse JSON danmaku", e);
        }
        Collections.sort(result, Danmaku.BY_TIME);
        return result;
    }

    @Nullable
    private static JSONArray rootArray(String json) throws JSONException {
        if (json.startsWith("[")) return new JSONArray(json);
        if (!json.startsWith("{")) return null;
        JSONObject root = new JSONObject(json);
        for (String key : WRAPPER_KEYS) {
            JSONArray array = root.optJSONArray(key);
            if (array != null) return array;
        }
        return null;
    }

    @Nullable
    private static Danmaku parseItem(JSONObject item) {
        String text = firstString(item, TEXT_KEYS);
        if (text == null || text.isEmpty()) return null;
        String p = item.optString("p", "");
        if (p.contains(",")) return parsePAttr(p, text);
        double time = firstDouble(item, TIME_KEYS, -1);
        if (time < 0) return null;
        int type = mapMode((int) firstDouble(item, MODE_KEYS, 1));
        if (type < 0) return null;
        int color = 0xFF000000 | (firstColor(item, COLOR_KEYS) & 0xFFFFFF);
        float textSizeSp = mapTextSize((int) firstDouble(item, SIZE_KEYS, 25));
        return new Danmaku(text, (long) (time * 1000), type, color, textSizeSp);
    }

    @Nullable
    private static Danmaku parsePAttr(String pAttr, String text) {
        String[] parts = pAttr.split(",");
        if (parts.length < 4) return null;
        try {
            int type = mapMode(Integer.parseInt(parts[1].trim()));
            if (type < 0) return null;
            long timeMs = (long) (Float.parseFloat(parts[0].trim()) * 1000);
            float textSizeSp = mapTextSize(Integer.parseInt(parts[2].trim()));
            int color = 0xFF000000 | (int) (Long.parseLong(parts[3].trim()) & 0xFFFFFF);
            return new Danmaku(text, timeMs, type, color, textSizeSp);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int mapMode(int mode) {
        switch (mode) {
            case 1:
            case 2:
            case 3:
                return Danmaku.TYPE_SCROLL;
            case 4:
                return Danmaku.TYPE_BOTTOM;
            case 5:
                return Danmaku.TYPE_TOP;
            case 6:
                return Danmaku.TYPE_REVERSE;
            default:
                return -1;
        }
    }

    private static float mapTextSize(int rawSize) {
        if (rawSize <= 18) return 12f;
        if (rawSize >= 36) return 18f;
        return 0f; // 0 falls back to the configured default size inside DanmakuView
    }

    private static int firstColor(JSONObject item, String[] keys) {
        for (String key : keys) {
            Object value = item.opt(key);
            if (value instanceof Number) return ((Number) value).intValue();
            if (value instanceof String) {
                String hex = ((String) value).trim();
                if (hex.startsWith("#")) hex = hex.substring(1);
                if (hex.length() == 6) {
                    try {
                        return (int) Long.parseLong(hex, 16);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        return 0xFFFFFF;
    }

    private static double firstDouble(JSONObject item, String[] keys, double def) {
        for (String key : keys) {
            double value = item.optDouble(key, Double.NaN);
            if (!Double.isNaN(value)) return value;
        }
        return def;
    }

    @Nullable
    private static String firstString(JSONObject item, String[] keys) {
        for (String key : keys) {
            String value = item.optString(key, "");
            if (!value.isEmpty()) return value;
        }
        return null;
    }

    private static String readHead(InputStream is, int sniffLength) throws IOException {
        byte[] buf = new byte[sniffLength];
        int read = 0;
        int n;
        while (read < sniffLength && (n = is.read(buf, read, sniffLength - read)) != -1) read += n;
        int start = read >= 3 && (buf[0] & 0xFF) == 0xEF && (buf[1] & 0xFF) == 0xBB && (buf[2] & 0xFF) == 0xBF ? 3 : 0;
        return new String(buf, start, read - start, StandardCharsets.UTF_8);
    }

    private static String readAll(InputStream is) throws IOException {
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            char[] buf = new char[2048];
            int n;
            while ((n = reader.read(buf)) != -1) builder.append(buf, 0, n);
        }
        return builder.toString();
    }
}
