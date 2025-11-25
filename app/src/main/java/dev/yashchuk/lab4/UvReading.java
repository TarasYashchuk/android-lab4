package dev.yashchuk.lab4;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "uv_readings_table")
public class UvReading {

    @PrimaryKey(autoGenerate = true)
    public int id;

    // ID запису в Firebase (для синхронізації)
    public String firebaseId;

    public long timestamp;
    public double uvValue;

    // Метадані
    public String deviceId;
    public String deviceName;

    public UvReading() {} // Порожній конструктор для Firebase

    public UvReading(long timestamp, double uvValue, String deviceId, String deviceName, String firebaseId) {
        this.timestamp = timestamp;
        this.uvValue = uvValue;
        this.deviceId = deviceId;
        this.deviceName = deviceName;
        this.firebaseId = firebaseId;
    }
}