package dev.yashchuk.lab4;

import android.Manifest;
import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.Legend;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.ValueFormatter;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    // UI Elements
    private TextView tvCurrentUv, tvRecommendation;
    private TextView tvMin, tvAvg, tvMax;
    private Button btnStartStop, btnClearData, btnFilterHour, btnFilterAll;
    private Button btnSimulateOther, btnSelectDevices, btnLogout;
    private LineChart uvChart;
    private EditText etInterval;
    private Button btnZoomIn, btnZoomOut, btnExportCsv;

    // Logic Variables
    private int SIMULATION_INTERVAL_MS = 2000;
    private boolean isCriticalAlertSent = false;
    private static final String CHANNEL_ID = "UV_ALERT_CHANNEL";
    private boolean isRecording = false;
    // Змінюємо false на true
    private boolean showOnlyLastHour = true;

    // System Components
    private ActivityResultLauncher<String> notificationPermissionLauncher;
    private ActivityResultLauncher<Intent> createCsvFileLauncher;
    private AppDatabase db;
    private final Handler simulationHandler = new Handler(Looper.getMainLooper());

    // Firebase & Identification
    private FirebaseAuth mAuth;
    private DatabaseReference firebaseRef;
    private String currentDeviceId;
    private String currentDeviceName;

    // Filtering Logic
    private Set<String> hiddenDevices = new HashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 1. Перевірка авторизації
        mAuth = FirebaseAuth.getInstance();
        if (mAuth.getCurrentUser() == null) {
            goToLogin();
            return;
        }

        setContentView(R.layout.activity_main);

        // 2. Ініціалізація Firebase та ID пристрою
        firebaseRef = FirebaseDatabase.getInstance().getReference("uv_readings");
        currentDeviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        currentDeviceName = Build.MODEL; // Назва моделі телефону

        // 3. Ініціалізація UI та Бази Даних
        initializeViews();
        db = AppDatabase.getDatabase(this);

        // 4. Налаштування компонентів
        setupChart();
        setupListeners();
        setupLaunchers();
        createNotificationChannel();
        requestNotificationPermission();

        // 5. Очистка дуже старих даних та завантаження актуальних
        cleanOldData();
        loadDataFromDb();

        // 6. Запуск синхронізації з хмарою
        setupFirebaseSync();
    }

    private void goToLogin() {
        Intent intent = new Intent(this, LoginActivity.class);
        startActivity(intent);
        finish();
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

        // Нові кнопки
        btnSimulateOther = findViewById(R.id.btnSimulateOther);
        btnSelectDevices = findViewById(R.id.btnSelectDevices);
        btnLogout = findViewById(R.id.btnLogout);
    }

    private void setupChart() {
        uvChart.getDescription().setEnabled(false);
        uvChart.setTouchEnabled(true);
        uvChart.setDragEnabled(true);
        uvChart.setScaleEnabled(true);
        uvChart.setPinchZoom(true);

        XAxis xAxis = uvChart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setGranularity(1f);

        Legend l = uvChart.getLegend();
        l.setEnabled(true);
        l.setVerticalAlignment(Legend.LegendVerticalAlignment.TOP);
        l.setHorizontalAlignment(Legend.LegendHorizontalAlignment.RIGHT);
        l.setOrientation(Legend.LegendOrientation.VERTICAL);
        l.setDrawInside(true);
    }

    /**
     * Основна логіка синхронізації.
     * Слухає додавання нових записів у Firebase.
     */
    private void setupFirebaseSync() {
        firebaseRef.addChildEventListener(new ChildEventListener() {
            @Override
            public void onChildAdded(@NonNull DataSnapshot snapshot, @Nullable String previousChildName) {
                UvReading remoteReading = snapshot.getValue(UvReading.class);
                if (remoteReading == null) return;

                // Важливо: Щоб уникнути дублювання, якщо ми самі відправили цей запис,
                // ми його не зберігаємо повторно (бо ми зберегли його при генерації).
                if (currentDeviceId.equals(remoteReading.deviceId) && !remoteReading.deviceName.contains("Simulated")) {
                    return;
                }

                // Зберігаємо "чужий" або симульований запис у локальну БД
                AppDatabase.databaseWriteExecutor.execute(() -> {
                    // В ідеалі тут потрібна перевірка на існування по firebaseId,
                    // але для спрощення просто вставляємо.
                    db.uvReadingDao().insert(remoteReading);
                    runOnUiThread(() -> loadDataFromDb());
                });
            }

            @Override public void onChildChanged(@NonNull DataSnapshot snapshot, @Nullable String previousChildName) {}
            @Override public void onChildRemoved(@NonNull DataSnapshot snapshot) {}
            @Override public void onChildMoved(@NonNull DataSnapshot snapshot, @Nullable String previousChildName) {}
            @Override public void onCancelled(@NonNull DatabaseError error) {}
        });
    }

    private void setupListeners() {
        // Старт/Стоп запису
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

        // Очистка історії
        btnClearData.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("Підтвердження")
                    .setMessage("Видалити всю локальну історію?")
                    .setPositiveButton("Так", (dialog, which) -> {
                        AppDatabase.databaseWriteExecutor.execute(() -> {
                            db.uvReadingDao().deleteAll();
                            runOnUiThread(this::loadDataFromDb);
                        });
                        Toast.makeText(this, "Історію очищено", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("Ні", null)
                    .show();
        });

        // Фільтри часу
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

        // Зум
        btnZoomIn.setOnClickListener(v -> uvChart.zoomIn());
        btnZoomOut.setOnClickListener(v -> uvChart.zoomOut());

        // Експорт
        btnExportCsv.setOnClickListener(v -> exportDataToCsv());

        // --- НОВІ ФУНКЦІЇ ---

        // Вихід з акаунту
        btnLogout.setOnClickListener(v -> {
            mAuth.signOut();
            goToLogin();
        });

        // Симуляція "Чужого" пристрою
        btnSimulateOther.setOnClickListener(v -> {
            generateAndSaveReading(true); // true = alien
            Toast.makeText(this, "Дані іншого пристрою надіслано", Toast.LENGTH_SHORT).show();
        });

        // Фільтр пристроїв
        btnSelectDevices.setOnClickListener(v -> showDeviceFilterDialog());
    }

    /**
     * Показує діалог з чекбоксами для вибору пристроїв, які відображати на графіку.
     */
    private void showDeviceFilterDialog() {
        AppDatabase.databaseWriteExecutor.execute(() -> {
            List<UvReading> allReadings = db.uvReadingDao().getAllReadings();

            // Збираємо унікальні пристрої
            Set<String> uniqueDeviceNames = new HashSet<>();
            Map<String, String> displayToIdMap = new HashMap<>();

            for (UvReading r : allReadings) {
                if (r.deviceId != null && r.deviceName != null) {
                    String displayName = r.deviceName + " (" + r.deviceId.substring(0, Math.min(r.deviceId.length(), 4)) + ")";
                    uniqueDeviceNames.add(displayName);
                    displayToIdMap.put(displayName, r.deviceId);
                }
            }

            if (uniqueDeviceNames.isEmpty()) {
                runOnUiThread(() -> Toast.makeText(this, "Немає даних для фільтрації", Toast.LENGTH_SHORT).show());
                return;
            }

            String[] deviceArray = uniqueDeviceNames.toArray(new String[0]);
            boolean[] checkedItems = new boolean[deviceArray.length];

            // Визначаємо, які зараз увімкнені
            for (int i = 0; i < deviceArray.length; i++) {
                String devId = displayToIdMap.get(deviceArray[i]);
                checkedItems[i] = !hiddenDevices.contains(devId);
            }

            runOnUiThread(() -> {
                new AlertDialog.Builder(this)
                        .setTitle("Виберіть пристрої")
                        .setMultiChoiceItems(deviceArray, checkedItems, (dialog, which, isChecked) -> {
                            String selectedDisplay = deviceArray[which];
                            String selectedId = displayToIdMap.get(selectedDisplay);

                            if (isChecked) {
                                hiddenDevices.remove(selectedId);
                            } else {
                                hiddenDevices.add(selectedId);
                            }
                        })
                        .setPositiveButton("OK", (dialog, which) -> loadDataFromDb())
                        .setNegativeButton("Cancel", null)
                        .show();
            });
        });
    }

    // Runnable для автоматичної генерації (Наш пристрій)
    private final Runnable simulationRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRecording) return;
            generateAndSaveReading(false); // false = наш пристрій
            simulationHandler.postDelayed(this, SIMULATION_INTERVAL_MS);
        }
    };

    private void startSimulation() {
        try {
            String intervalStr = etInterval.getText().toString();
            int intervalSeconds = Integer.parseInt(intervalStr);
            SIMULATION_INTERVAL_MS = intervalSeconds * 1000;
            if (SIMULATION_INTERVAL_MS < 1000) SIMULATION_INTERVAL_MS = 1000;
            Toast.makeText(this, "Запис кожні " + intervalSeconds + " сек", Toast.LENGTH_SHORT).show();
        } catch (NumberFormatException e) {
            SIMULATION_INTERVAL_MS = 2000;
            etInterval.setText("2");
        }
        isCriticalAlertSent = false;
        simulationHandler.post(simulationRunnable);
    }

    private void stopSimulation() {
        simulationHandler.removeCallbacks(simulationRunnable);
    }

    /**
     * Генерує дані, зберігає локально та відправляє в Firebase.
     * @param isAlienSimulation Якщо true, використовує фейковий ID пристрою.
     */
    private void generateAndSaveReading(boolean isAlienSimulation) {
        double randomUv = Math.random() * 12;
        randomUv = Math.round(randomUv * 10.0) / 10.0;
        long timestamp = System.currentTimeMillis();

        // Генеруємо унікальний ключ для Firebase
        String firebaseId = firebaseRef.push().getKey();

        String devId, devName;
        if (isAlienSimulation) {
            // Випадковий суфікс, щоб симулювати різні "чужі" пристрої або один стабільний
            devId = "ALIEN_DEVICE_ID_999";
            devName = "Simulated Pixel 9";
        } else {
            devId = currentDeviceId;
            devName = currentDeviceName;
            // Оновлюємо UI тільки для нашого пристрою
            updateCurrentUvUI(randomUv);
        }

        UvReading reading = new UvReading(timestamp, randomUv, devId, devName, firebaseId);

        // 1. Зберігаємо локально (для миттєвого відображення)
        AppDatabase.databaseWriteExecutor.execute(() -> {
            db.uvReadingDao().insert(reading);
            runOnUiThread(() -> loadDataFromDb());
        });

        // 2. Відправляємо в хмару (щоб інші бачили)
        if (firebaseId != null) {
            firebaseRef.child(firebaseId).setValue(reading);
        }
    }

    private void updateCurrentUvUI(double uvValue) {
        tvCurrentUv.setText(String.valueOf(uvValue));
        String recommendation;
        int color;

        if (uvValue < 3) {
            recommendation = "Низький (Безпечно)"; color = Color.parseColor("#4CAF50");
        } else if (uvValue < 6) {
            recommendation = "Помірний (Захист бажаний)"; color = Color.parseColor("#FFEB3B");
        } else if (uvValue < 8) {
            recommendation = "Високий (Потрібен захист)"; color = Color.parseColor("#FF9800");
        } else if (uvValue < 11) {
            recommendation = "Дуже високий (Небезпечно)"; color = Color.parseColor("#F44336");
        } else {
            recommendation = "Екстремальний (Уникайте сонця!)"; color = Color.parseColor("#9C27B0");
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

    // --- РОБОТА З БД ТА ГРАФІКОМ ---

    private void loadDataFromDb() {
        new Thread(() -> {
            List<UvReading> readings;
            try {
                if (showOnlyLastHour) {
                    long oneHourAgo = System.currentTimeMillis() - 3600000;
                    readings = db.uvReadingDao().getReadingsSince(oneHourAgo);
                } else {
                    readings = db.uvReadingDao().getAllReadings();
                }
            } catch (Exception e) {
                e.printStackTrace();
                readings = new ArrayList<>();
            }
            final List<UvReading> finalReadings = readings;
            runOnUiThread(() -> {
                updateChart(finalReadings);
                updateStatistics(finalReadings);
            });
        }).start();
    }

    /**
     * Оновлює графік, групуючи дані по пристроях.
     */
    private void updateChart(List<UvReading> readings) {
        if (readings == null || readings.isEmpty()) {
            uvChart.clear();
            uvChart.invalidate();
            return;
        }

        // Групування даних за deviceId
        Map<String, List<Entry>> groupedEntries = new HashMap<>();
        Map<String, Integer> deviceColors = new HashMap<>();
        Map<String, String> deviceNames = new HashMap<>();

        long startTime = readings.get(readings.size() - 1).timestamp;

        // Йдемо з кінця (найстаріші) до початку (найновіші), оскільки SQL ORDER BY DESC
        // Але для графіка Entry краще додавати хронологічно.
        // Тому проходимось з кінця списку (найдавніший час) до початку.
        for (int i = readings.size() - 1; i >= 0; i--) {
            UvReading r = readings.get(i);

            // Якщо пристрій приховано фільтром - пропускаємо
            if (hiddenDevices.contains(r.deviceId)) continue;
            // Захист від null (старі дані можуть не мати deviceId)
            if (r.deviceId == null) r.deviceId = "UNKNOWN";

            if (!groupedEntries.containsKey(r.deviceId)) {
                groupedEntries.put(r.deviceId, new ArrayList<>());

                // Генеруємо колір на основі хешу ID
                int hash = r.deviceId.hashCode();
                int color = Color.HSVToColor(new float[]{ Math.abs(hash) % 360, 0.8f, 0.9f });
                deviceColors.put(r.deviceId, color);

                String name = (r.deviceName != null) ? r.deviceName : "Unknown";
                deviceNames.put(r.deviceId, name);
            }

            float timeOffset = (float) (r.timestamp - startTime);
            groupedEntries.get(r.deviceId).add(new Entry(timeOffset, (float) r.uvValue));
        }

        ArrayList<ILineDataSet> dataSets = new ArrayList<>();

        for (String devId : groupedEntries.keySet()) {
            List<Entry> entries = groupedEntries.get(devId);
            if (entries == null || entries.isEmpty()) continue;

            String label = deviceNames.get(devId);
            // Додаємо ID в легенду для унікальності
            if (label.length() > 10) label = label.substring(0, 10);
            label += "..." + devId.substring(0, Math.min(devId.length(), 3));

            LineDataSet set = new LineDataSet(entries, label);
            int color = deviceColors.get(devId);
            set.setColor(color);
            set.setCircleColor(color);
            set.setLineWidth(2f);
            set.setCircleRadius(3f);
            set.setDrawValues(false);
            set.setMode(LineDataSet.Mode.CUBIC_BEZIER);

            dataSets.add(set);
        }

        LineData lineData = new LineData(dataSets);
        uvChart.setData(lineData);

        // Форматування осі X
        uvChart.getXAxis().setValueFormatter(new ValueFormatter() {
            private final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
            @Override
            public String getFormattedValue(float value) {
                return sdf.format(new Date(startTime + (long) value));
            }
        });

        uvChart.notifyDataSetChanged();
        uvChart.invalidate();
    }

    private void updateStatistics(List<UvReading> readings) {
        if (readings == null || readings.isEmpty()) {
            tvMin.setText("-"); tvAvg.setText("-"); tvMax.setText("-");
            return;
        }

        // Статистика розраховується по ВСІХ відображених даних (всі пристрої)
        // або можна фільтрувати лише "свій" пристрій.
        // Тут реалізовано по всім видимим (не прихованим).

        double min = Double.MAX_VALUE;
        double max = Double.MIN_VALUE;
        double sum = 0;
        int count = 0;

        for (UvReading r : readings) {
            if (hiddenDevices.contains(r.deviceId)) continue;

            if (r.uvValue < min) min = r.uvValue;
            if (r.uvValue > max) max = r.uvValue;
            sum += r.uvValue;
            count++;
        }

        if (count == 0) {
            tvMin.setText("-"); tvAvg.setText("-"); tvMax.setText("-");
            return;
        }

        double avg = sum / count;
        tvMin.setText(String.format(Locale.getDefault(), "%.1f", min));
        tvMax.setText(String.format(Locale.getDefault(), "%.1f", max));
        tvAvg.setText(String.format(Locale.getDefault(), "%.1f", avg));
    }

    private void cleanOldData() {
        AppDatabase.databaseWriteExecutor.execute(() -> {
            long twentyFourHoursAgo = System.currentTimeMillis() - (24 * 60 * 60 * 1000);
            int deleted = db.uvReadingDao().deleteOlderThan(twentyFourHoursAgo);
            if (deleted > 0) {
                Log.d("DB", "Deleted " + deleted + " old records");
                runOnUiThread(this::loadDataFromDb);
            }
        });
    }

    // --- ЕКСПОРТ ТА СПОВІЩЕННЯ ---

    private void exportDataToCsv() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/csv");
        intent.putExtra(Intent.EXTRA_TITLE, "uv_readings_" + System.currentTimeMillis() + ".csv");
        createCsvFileLauncher.launch(intent);
    }

    private void setupLaunchers() {
        notificationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                isGranted -> {
                    if (!isGranted) Toast.makeText(this, "Сповіщення вимкнено", Toast.LENGTH_SHORT).show();
                });

        createCsvFileLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Uri uri = result.getData().getData();
                        if (uri != null) writeCsvToUri(uri);
                    }
                });
    }

    private void writeCsvToUri(Uri uri) {
        AppDatabase.databaseWriteExecutor.execute(() -> {
            try (OutputStream outputStream = getContentResolver().openOutputStream(uri);
                 OutputStreamWriter writer = new OutputStreamWriter(outputStream)) {

                List<UvReading> readings = db.uvReadingDao().getAllReadings();
                StringBuilder csvBuilder = new StringBuilder();
                csvBuilder.append("Timestamp,Date,DeviceName,DeviceID,UV_Index\n");

                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

                for (UvReading r : readings) {
                    csvBuilder.append(r.timestamp).append(",");
                    csvBuilder.append(sdf.format(new Date(r.timestamp))).append(",");
                    csvBuilder.append(r.deviceName != null ? r.deviceName : "Unknown").append(",");
                    csvBuilder.append(r.deviceId != null ? r.deviceId : "Unknown").append(",");
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
}