package com.fongmi.android.tv.db.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.fongmi.android.tv.bean.PlaybackDeleteTombstone;

import java.util.List;

@Dao
public abstract class PlaybackDeleteTombstoneDao {

    @Query("SELECT * FROM PlaybackDeleteTombstone ORDER BY deletedAt DESC")
    public abstract List<PlaybackDeleteTombstone> findAll();

    @Query("SELECT * FROM PlaybackDeleteTombstone WHERE id = :id LIMIT 1")
    public abstract PlaybackDeleteTombstone find(String id);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public abstract void insertOrUpdate(PlaybackDeleteTombstone tombstone);

    @Query("DELETE FROM PlaybackDeleteTombstone WHERE deletedAt < :cutoff")
    public abstract void deleteBefore(long cutoff);
}
