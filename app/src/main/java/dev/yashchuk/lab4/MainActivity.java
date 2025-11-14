package dev.yashchuk.lab4;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.ValueFormatter;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private TextView tvCurrentUv, tvRecommendation;
    private TextView tvMin, tvAvg, tvMax;
    private Button btnStartStop, btnClearData, btnFilterHour, btnFilterAll;
    private LineChart uvChart;

    private EditText etInterval;
    private Button btnZoomIn, btnZoomOut, btnExportCsv;
    private int SIMULATION_INTERVAL_MS = 2000;
    private boolean isCriticalAlertSent = false;
    private static final String CHANNEL_ID = "UV_ALERT_CHANNEL";

    private ActivityResultLauncher<String> notificationPermissionLauncher;
    private ActivityResultLauncher<Intent> createCsvFileLauncher;

    private AppDatabase db;
    private boolean isRecording = false;

    private final Handler simulationHandler = new Handler(Looper.getMainLooper());
    private boolean showOnlyLastHour = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initializeViews();

        db = AppDatabase.getDatabase(this);

        setupChart();

        setupListeners();

        setupLaunchers();
        createNotificationChannel();
        requestNotificationPermission();
        cleanOldData();

        loadDataFromDb();
    }

    private void initializeViews() {
        tvCurrentUv = findViewById(R.id.tvCurrentUv);
        tvRecommendation = findViewById(R.id.tvRecommendation);
        tvMin = findViewById(R.id.tvMin);
        tvAvg = findViewById(R.id.tvAvg);
        tvMax = findViewById(R.id.tvMax);
        btnStartStop = findViewById(R.id.btnStartStop);
        btnClearData = findViewById(R.id.btnClearData);
        btnFilterHour = findViewById(R.id.btnFilterHour);
        btnFilterAll = findViewById(R.id.btnFilterAll);
        uvChart = findViewById(R.id.uvChart);

        etInterval = findViewById(R.id.etInterval);
        btnZoomIn = findViewById(R.id.btnZoomIn);
        btnZoomOut = findViewById(R.id.btnZoomOut);
        btnExportCsv = findViewById(R.id.btnExportCsv);
    }

    private void setupChart() {
        uvChart.getDescription().setEnabled(false);
        uvChart.getLegend().setEnabled(false);
        uvChart.setTouchEnabled(true);
        uvChart.setDragEnabled(true);
        uvChart.setScaleEnabled(true);
        uvChart.setPinchZoom(true);

        XAxis xAxis = uvChart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setGranularity(1f);
    }

    private void setupListeners() {
        btnStartStop.setOnClickListener(v -> {
            isRecording = !isRecording;
            if (isRecording) {
                btnStartStop.setText("Stop Recording");
                btnStartStop.setBackgroundColor(Color.parseColor("#F44336"));
                etInterval.setEnabled(false);
                startSimulation();
            } else {
                btnStartStop.setText("Start Recording");
                btnStartStop.setBackgroundColor(Color.parseColor("#2196F3"));
                etInterval.setEnabled(true);
                stopSimulation();
            }
        });

        btnClearData.setOnClickListener(v -> {
            AppDatabase.databaseWriteExecutor.execute(() -> {
                db.uvReadingDao().deleteAll();
                runOnUiThread(this::loadDataFromDb);
            });
            Toast.makeText(this, "Історію очищено", Toast.LENGTH_SHORT).show();
        });

        btnFilterHour.setOnClickListener(v -> {
            showOnlyLastHour = true;
            loadDataFromDb();
            Toast.makeText(this, "Фільтр: Остання година", Toast.LENGTH_SHORT).show();
        });

        btnFilterAll.setOnClickListener(v -> {
            showOnlyLastHour = false;
            loadDataFromDb();
            Toast.makeText(this, "Фільтр: Весь час", Toast.LENGTH_SHORT).show();
        });

        btnZoomIn.setOnClickListener(v -> uvChart.zoomIn());
        btnZoomOut.setOnClickListener(v -> uvChart.zoomOut());

        btnExportCsv.setOnClickListener(v -> exportDataToCsv());
    }


    private final Runnable simulationRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRecording) return;

            double randomUv = Math.random() * 12;
            randomUv = Math.round(randomUv * 10.0) / 10.0;
            updateCurrentUvUI(randomUv);
            long timestamp = System.currentTimeMillis();
            UvReading newReading = new UvReading(timestamp, randomUv);

            AppDatabase.databaseWriteExecutor.execute(() -> {
                db.uvReadingDao().insert(newReading);
                runOnUiThread(() -> loadDataFromDb());
            });

            simulationHandler.postDelayed(this, SIMULATION_INTERVAL_MS);
        }
    };

    private void startSimulation() {
        try {
            String intervalStr = etInterval.getText().toString();
            int intervalSeconds = Integer.parseInt(intervalStr);
            SIMULATION_INTERVAL_MS = intervalSeconds * 1000;
            if (SIMULATION_INTERVAL_MS < 1000) {
                SIMULATION_INTERVAL_MS = 1000;
                etInterval.setText("1");
            }
            Toast.makeText(this, "Запис кожні " + intervalSeconds + " сек", Toast.LENGTH_SHORT).show();
        } catch (NumberFormatException e) {
            SIMULATION_INTERVAL_MS = 2000;
            etInterval.setText("2");
            Toast.makeText(this, "Невірний інтервал, встановлено 2 сек", Toast.LENGTH_SHORT).show();
        }

        isCriticalAlertSent = false;
        simulationHandler.post(simulationRunnable);
    }

    private void stopSimulation() {
        simulationHandler.removeCallbacks(simulationRunnable);
    }

    private void updateCurrentUvUI(double uvValue) {
        tvCurrentUv.setText(String.valueOf(uvValue));
        String recommendation;
        int color;

        if (uvValue < 3) {
            recommendation = "Низький (Безпечно)";
            color = Color.parseColor("#4CAF50");
        } else if (uvValue < 6) {
            recommendation = "Помірний (Захист бажаний)";
            color = Color.parseColor("#FFEB3B");
        } else if (uvValue < 8) {
            recommendation = "Високий (Потрібен захист)";
            color = Color.parseColor("#FF9800");
        } else if (uvValue < 11) {
            recommendation = "Дуже високий (Небезпечно)";
            color = Color.parseColor("#F44336");
        } else {
            recommendation = "Екстремальний (Уникайте сонця!)";
            color = Color.parseColor("#9C27B0");
        }
        tvRecommendation.setText(recommendation);
        tvRecommendation.setBackgroundColor(color);

        if (uvValue >= 8 && !isCriticalAlertSent) {
            sendUvNotification(uvValue, recommendation);
            isCriticalAlertSent = true;
        } else if (uvValue < 8) {
            isCriticalAlertSent = false;
        }
    }


    private void loadDataFromDb() {
        new Thread(() -> {
            List<UvReading> readings = null;
            try {
                if (showOnlyLastHour) {
                    long oneHourAgo = System.currentTimeMillis() - 3600000;
                    readings = db.uvReadingDao().getReadingsSince(oneHourAgo);
                } else {
                    readings = db.uvReadingDao().getAllReadings();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            final List<UvReading> finalReadings = (readings != null) ? readings : new ArrayList<>();
            runOnUiThread(() -> {
                updateChart(finalReadings);
                updateStatistics(finalReadings);
            });
        }).start();
    }

    private void updateChart(List<UvReading> readings) {
        if (readings == null || readings.isEmpty()) {
            uvChart.clear();
            uvChart.invalidate();
            return;
        }
        try {
            if (readings.size() == 0) {
                uvChart.clear();
                uvChart.invalidate();
                return;
            }
            final long startTime = readings.get(readings.size() - 1).timestamp;
            List<Entry> entries = new ArrayList<>();
            for (int i = readings.size() - 1; i >= 0; i--) {
                UvReading r = readings.get(i);
                if (r != null) {
                    float timeOffset = (float) (r.timestamp - startTime);
                    entries.add(new Entry(timeOffset, (float) r.uvValue));
                }
            }
            if (entries.isEmpty()) {
                uvChart.clear();
                uvChart.invalidate();
                return;
            }
            uvChart.getXAxis().setValueFormatter(new ValueFormatter() {
                private final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
                @Override
                public String getFormattedValue(float value) {
                    return sdf.format(new Date(startTime + (long) value));
                }
            });
            LineDataSet dataSet = new LineDataSet(entries, "UV Index");
            dataSet.setColor(Color.BLUE);
            dataSet.setLineWidth(2f);
            dataSet.setDrawCircles(false);
            dataSet.setDrawValues(false);
            dataSet.setMode(LineDataSet.Mode.CUBIC_BEZIER);
            dataSet.setDrawFilled(true);
            dataSet.setFillColor(Color.BLUE);
            LineData lineData = new LineData(dataSet);
            uvChart.setData(lineData);
            uvChart.notifyDataSetChanged();
            uvChart.invalidate();
        } catch (Exception e) {
            e.printStackTrace();
            uvChart.clear();
            uvChart.invalidate();
        }
    }

    private void updateStatistics(List<UvReading> readings) {
        if (readings == null || readings.isEmpty()) {
            tvMin.setText("-");
            tvAvg.setText("-");
            tvMax.setText("-");
            return;
        }
        try {
            double min = Double.MAX_VALUE;
            double max = Double.MIN_VALUE;
            double sum = 0;
            for (UvReading r : readings) {
                if (r != null) {
                    if (r.uvValue < min) min = r.uvValue;
                    if (r.uvValue > max) max = r.uvValue;
                    sum += r.uvValue;
                }
            }
            if (min == Double.MAX_VALUE || max == Double.MIN_VALUE) {
                tvMin.setText("-");
                tvAvg.setText("-");
                tvMax.setText("-");
                return;
            }
            double avg = sum / readings.size();
            tvMin.setText(String.format(Locale.getDefault(), "%.1f", min));
            tvMax.setText(String.format(Locale.getDefault(), "%.1f", max));
            tvAvg.setText(String.format(Locale.getDefault(), "%.1f", avg));
        } catch (Exception e) {
            e.printStackTrace();
            tvMin.setText("-");
            tvAvg.setText("-");
            tvMax.setText("-");
        }
    }

    private void cleanOldData() {
        AppDatabase.databaseWriteExecutor.execute(() -> {
            long twentyFourHoursAgo = System.currentTimeMillis() - (24 * 60 * 60 * 1000);
            int deletedRows = db.uvReadingDao().deleteOlderThan(twentyFourHoursAgo);
            if (deletedRows > 0) {
                Log.d("DB_CLEANUP", "Видалено " + deletedRows + " старих записів.");
                runOnUiThread(this::loadDataFromDb);
            }
        });
    }

    private void exportDataToCsv() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/csv");
        intent.putExtra(Intent.EXTRA_TITLE, "uv_readings_" + System.currentTimeMillis() + ".csv");
        createCsvFileLauncher.launch(intent);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = "UV Alerts";
            String description = "Сповіщення про небезпечний рівень УФ";
            int importance = NotificationManager.IMPORTANCE_HIGH;
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
            channel.setDescription(description);
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
    }

    private void sendUvNotification(double uvValue, String recommendation) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Критичний рівень УФ!")
                .setContentText("Індекс: " + uvValue + " (" + recommendation + ")")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true);

        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(this);
        notificationManager.notify(1, builder.build());
    }

    private void setupLaunchers() {
        notificationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                isGranted -> {
                    if (!isGranted) {
                        Toast.makeText(this, "Сповіщення вимкнено", Toast.LENGTH_SHORT).show();
                    }
                });

        createCsvFileLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Uri uri = result.getData().getData();
                        if (uri != null) {
                            writeCsvToUri(uri);
                        }
                    }
                });
    }

    private void writeCsvToUri(Uri uri) {
        AppDatabase.databaseWriteExecutor.execute(() -> {
            try (OutputStream outputStream = getContentResolver().openOutputStream(uri);
                 OutputStreamWriter writer = new OutputStreamWriter(outputStream)) {

                List<UvReading> readings = db.uvReadingDao().getAllReadings();

                StringBuilder csvBuilder = new StringBuilder();
                csvBuilder.append("Timestamp,ReadableDate,UV_Index\n");

                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

                for (UvReading r : readings) {
                    csvBuilder.append(r.timestamp).append(",");
                    csvBuilder.append(sdf.format(new Date(r.timestamp))).append(",");
                    csvBuilder.append(r.uvValue).append("\n");
                }

                writer.write(csvBuilder.toString());
                writer.flush();

                runOnUiThread(() -> Toast.makeText(this, "Експорт успішний", Toast.LENGTH_SHORT).show());

            } catch (IOException e) {
                e.printStackTrace();
                runOnUiThread(() -> Toast.makeText(this, "Помилка експорту", Toast.LENGTH_SHORT).show());
            }
        });
    }
}