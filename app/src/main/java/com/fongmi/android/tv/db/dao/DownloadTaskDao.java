package com.fongmi.android.tv.db.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.fongmi.android.tv.download.DownloadTask;

import java.util.List;

@Dao
public interface DownloadTaskDao {
    @Query("SELECT * FROM DownloadTask ORDER BY createdAt DESC, id ASC")
    LiveData<List<DownloadTask>> observeAll();

    @Query("SELECT * FROM DownloadTask ORDER BY createdAt ASC, id ASC")
    List<DownloadTask> getAll();

    @Query("SELECT * FROM DownloadTask WHERE id = :id")
    DownloadTask find(String id);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insertIgnore(DownloadTask task);

    @Update
    void update(DownloadTask task);

    @Query("DELETE FROM DownloadTask WHERE id = :id")
    void delete(String id);

    @Query("UPDATE DownloadTask SET state = 'PAUSED' WHERE state IN ('RESOLVING', 'DOWNLOADING')")
    void recoverInterrupted();
}
