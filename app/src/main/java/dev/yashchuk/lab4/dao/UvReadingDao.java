package dev.yashchuk.lab4.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import java.util.List;

import dev.yashchuk.lab4.UvReading;

@Dao
public interface UvReadingDao {

    @Insert
    void insert(UvReading reading);

    @Query("SELECT * FROM uv_readings_table ORDER BY timestamp DESC")
    List<UvReading> getAllReadings();

    @Query("SELECT * FROM uv_readings_table WHERE timestamp >= :sinceTimestamp ORDER BY timestamp DESC")
    List<UvReading> getReadingsSince(long sinceTimestamp);

    @Query("DELETE FROM uv_readings_table")
    void deleteAll();

    @Query("DELETE FROM uv_readings_table WHERE timestamp < :olderThanTimestamp")
    int deleteOlderThan(long olderThanTimestamp);
}