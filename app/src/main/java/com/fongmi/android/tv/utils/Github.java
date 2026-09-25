package com.fongmi.android.tv.utils;

import com.github.catvod.utils.Prefers;

import java.util.ArrayList;
import java.util.List;

public class Github {

    private static final String GITHUB = "https://github.com/sdoaiya/XCYS/releases/latest/download";
    private static final String SERVER = GITHUB;
    private static final String CNB = GITHUB;
    private static final String PREF_MIRROR = "update_mirror";

    private static volatile String baseUrl;

    public static String getUrl() {
        if (baseUrl == null) baseUrl = resolveBase();
        return baseUrl;
    }

    public static void setMirror(String mirror) {
        Prefers.put(PREF_MIRROR, mirror);
        baseUrl = GITHUB;
    }

    public static String getMirror() {
        return Prefers.getString(PREF_MIRROR, "auto");
    }

    private static String resolveBase() {
        return GITHUB;
    }

    private static String getUrl(String name) {
        String base = getUrl();
        return base + (base.contains("/releases/") ? "/" : "/apk/") + name;
    }

    public static String getJson(String name) {
        return getUrl(name + ".json");
    }

    public static String getApk(String name) {
        return getUrl(name + ".apk");
    }

    public static String getServerApk(String name) {
        return SERVER + "/" + name;
    }

    public static String getServerJson(String name) {
        return SERVER + "/" + name + ".json";
    }

    public static List<String> getJsonCandidates(String name) {
        List<String> result = new ArrayList<>();
        result.add(getUrl(name + ".json"));
        result.add(SERVER + "/" + name + ".json");
        result.add(GITHUB + "/" + name + ".json");
        result.add(CNB + "/" + name + ".json");
        return result.stream().distinct().collect(java.util.stream.Collectors.toList());
    }

    public static String getBase() {
        return getUrl();
    }
}
