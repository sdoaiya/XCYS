package com.fongmi.android.tv.download;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "DownloadTask")
public class DownloadTask {
    @PrimaryKey
    @NonNull
    public String id = "";
    public String title = "";
    public String episodeName = "";
    public String siteKey = "";
    public String vodId = "";
    public String flag = "";
    public String episodeUrl = "";
    public String source = "";
    public String state = "QUEUED";
    public String error = "";
    public String etag = "";
    public String lastModified = "";
    public String mime = "";
    public int configId;
    public long downloaded;
    public long total = -1;
    public long createdAt;
    public long updatedAt;
    public boolean useParse;
}
