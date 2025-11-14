package dev.yashchuk.lab4;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "uv_readings_table")
public class UvReading {

    @PrimaryKey(autoGenerate = true)
    public int id;

    public long timestamp;

    public double uvValue;

    public UvReading(long timestamp, double uvValue) {
        this.timestamp = timestamp;
        this.uvValue = uvValue;
    }
}
