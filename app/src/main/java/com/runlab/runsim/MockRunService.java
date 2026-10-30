package com.runlab.runsim;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import java.util.List;
import java.util.Locale;

/**
 * 前台服务：每秒推进一次模拟，并通过 LocationManager 的测试提供者写入位置。
 * 需要用户在「开发者选项 → 选择模拟位置信息应用」中选择本应用。
 */
public class MockRunService extends Service {

    public static final String ACTION_START = "com.runlab.runsim.action.START";
    public static final String ACTION_STOP = "com.runlab.runsim.action.STOP";
    public static final String EXTRA_PACE = "pace";

    private static final String TAG = "MockRunService";
    private static final String CHANNEL_ID = "run_sim";
    private static final int NOTIF_ID = 1001;

    private Handler handler;
    private LocationManager lm;
    private PowerManager.WakeLock wakeLock;
    private SensorManager sensorManager;
    private Sensor stepSensor;
    private boolean gpsReady = false;
    private boolean networkReady = false;
    private long lastTickMs = 0;
    private int tickCount = 0;

    /** 读取手机硬件计步器（开机以来的累计步数） */
    private final SensorEventListener stepListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (event != null && event.values != null && event.values.length > 0) {
                SimState.onHwSteps(event.values[0]);
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    };

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!SimState.running) {
                stopSelf();
                return;
            }
            long now = SystemClock.elapsedRealtime();
            double dt = lastTickMs == 0 ? 1.0 : Math.min(4.0, (now - lastTickMs) / 1000.0);
            lastTickMs = now;
            SimState.step(dt);
            pushLocation();
            tickCount++;
            if (tickCount % 5 == 0) updateNotification();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        registerStepSensor();
    }

    private void registerStepSensor() {
        try {
            sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
            if (sensorManager == null) return;
            stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
            if (stepSensor == null) {
                SimState.hwAvailable = false;
                return;
            }
            sensorManager.registerListener(stepListener, stepSensor, SensorManager.SENSOR_DELAY_UI);
        } catch (Exception e) {
            Log.w(TAG, "step sensor: " + e.getClass().getSimpleName());
            SimState.hwAvailable = false;
        }
    }

    private void unregisterStepSensor() {
        try {
            if (sensorManager != null) sensorManager.unregisterListener(stepListener);
        } catch (Exception ignored) {
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null) {
            float p = intent.getFloatExtra(EXTRA_PACE, SimState.paceSec);
            if (p > 0) SimState.paceSec = p;
        }

        startInForeground();

        if (SimState.routeLength() <= 0) {
            SimState.lastError = "还没有规划路线：先在地图上点几个点画出路线，再开始模拟。";
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!prepareProviders()) {
            if (SimState.lastError == null) {
                SimState.lastError = "无法创建模拟定位提供者。";
            }
            stopSelf();
            return START_NOT_STICKY;
        }

        boolean fresh = !SimState.running;
        SimState.running = true;
        if (fresh) {
            SimState.lastTrackMs = SimState.elapsedMs - 999999;
        }
        acquireWakeLock();
        lastTickMs = 0;
        handler.removeCallbacks(ticker);
        handler.post(ticker);
        Log.i(TAG, "模拟已启动，配速 " + SimState.paceSec + " s/km");
        return START_STICKY;
    }

    /* ===================== 模拟定位提供者 ===================== */

    @SuppressWarnings("deprecation")
    private boolean prepareProviders() {
        if (lm == null) {
            lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        }
        if (lm == null) {
            SimState.lastError = "系统定位服务不可用。";
            return false;
        }

        try {
            lm.addTestProvider(LocationManager.GPS_PROVIDER,
                    false, false, false, false, true, true, true,
                    Criteria.POWER_LOW, Criteria.ACCURACY_FINE);
        } catch (IllegalArgumentException already) {
            // 已经添加过，忽略
        } catch (SecurityException e) {
            SimState.lastError = "没有「模拟位置」权限。\n\n请打开开发者选项，在「选择模拟位置信息应用」中选择「跑步模拟器」，然后再试一次。";
            return false;
        }

        try {
            lm.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true);
            gpsReady = true;
        } catch (Exception e) {
            SimState.lastError = "无法启用模拟 GPS：" + e.getClass().getSimpleName();
            return false;
        }

        // 有网络定位源就一并模拟，兼容只监听 NETWORK_PROVIDER 的应用
        try {
            List<String> all = lm.getAllProviders();
            if (all != null && all.contains(LocationManager.NETWORK_PROVIDER)) {
                lm.addTestProvider(LocationManager.NETWORK_PROVIDER,
                        false, true, false, false, true, true, true,
                        Criteria.POWER_LOW, Criteria.ACCURACY_COARSE);
                lm.setTestProviderEnabled(LocationManager.NETWORK_PROVIDER, true);
                networkReady = true;
            }
        } catch (Exception ignored) {
            networkReady = false;
        }
        return true;
    }

    @SuppressWarnings("deprecation")
    private void pushLocation() {
        if (lm == null || !gpsReady) return;
        double lat = SimState.toLat(SimState.y);
        double lng = SimState.toLng(SimState.x);
        try {
            lm.setTestProviderLocation(LocationManager.GPS_PROVIDER,
                    buildLocation(lat, lng, LocationManager.GPS_PROVIDER));
        } catch (SecurityException e) {
            SimState.lastError = "写入模拟位置被系统拒绝，请重新在开发者选项中选择本应用。";
            stopSelf();
            return;
        } catch (IllegalArgumentException e) {
            SimState.lastError = "模拟定位提供者已失效，请重新开始。";
            stopSelf();
            return;
        }

        // 网络定位源失败不影响 GPS 模拟
        if (networkReady) {
            try {
                lm.setTestProviderLocation(LocationManager.NETWORK_PROVIDER,
                        buildLocation(lat, lng, LocationManager.NETWORK_PROVIDER));
            } catch (Exception ignored) {
                networkReady = false;
            }
        }
    }

    private Location buildLocation(double lat, double lng, String provider) {
        // 真实 GPS 每秒都会有几米内的随机漂移，这里加 ±0.7 米，避免输出"完美直线"
        double jitterLat = (Math.random() - 0.5) * 1.4 / 111320.0;
        double jitterLng = (Math.random() - 0.5) * 1.4
                / (111320.0 * Math.cos(Math.toRadians(lat)));
        Location loc = new Location(provider);
        loc.setLatitude(lat + jitterLat);
        loc.setLongitude(lng + jitterLng);
        loc.setAltitude(44.0 + 6.0 * Math.sin(SimState.distance / 900.0));
        loc.setAccuracy(SimState.accuracy);
        loc.setSpeed(SimState.speed);
        loc.setBearing(SimState.heading);
        loc.setTime(System.currentTimeMillis());
        loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        return loc;
    }

    /* ===================== 通知 ===================== */

    private void startInForeground() {
        Notification n = buildNotification("模拟跑步进行中", "正在向系统输出定位…");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void updateNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        String text = String.format(Locale.US, "%.2f km · %s · %s",
                SimState.distance / 1000.0, fmtTime(SimState.elapsedMs), fmtPace(SimState.currentPaceSec()));
        try {
            nm.notify(NOTIF_ID, buildNotification("模拟跑步进行中", text));
        } catch (SecurityException ignored) {
            // Android 13+ 未授予通知权限时忽略
        }
    }

    private Notification buildNotification(String title, String text) {
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;

        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentPi = PendingIntent.getActivity(this, 0, open, piFlags);

        Intent stop = new Intent(this, MockRunService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop, piFlags);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ensureChannel();
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        b.setSmallIcon(R.drawable.ic_stat_run)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(contentPi);
        b.addAction(android.R.drawable.ic_media_pause, "停止模拟", stopPi);
        return b.build();
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "跑步模拟", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
    }

    /* ===================== 生命周期 ===================== */

    private void acquireWakeLock() {
        try {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RunSim::mockLocation");
                    wakeLock.setReferenceCounted(false);
                }
            }
            if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire();
        } catch (Exception ignored) {
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) {
        }
        wakeLock = null;
    }

    @Override
    public void onDestroy() {
        SimState.running = false;
        if (handler != null) handler.removeCallbacksAndMessages(null);
        unregisterStepSensor();
        releaseWakeLock();
        if (lm != null) {
            try {
                lm.removeTestProvider(LocationManager.GPS_PROVIDER);
            } catch (Exception ignored) {
            }
            try {
                lm.removeTestProvider(LocationManager.NETWORK_PROVIDER);
            } catch (Exception ignored) {
            }
        }
        gpsReady = false;
        networkReady = false;
        try {
            stopForeground(true);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    /* ===================== 格式化 ===================== */

    private static String fmtTime(long ms) {
        long s = ms / 1000;
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, sec);
        return String.format(Locale.US, "%02d:%02d", m, sec);
    }

    private static String fmtPace(double paceSec) {
        if (paceSec <= 0) return "--'--\"";
        int t = (int) Math.round(paceSec);
        return String.format(Locale.US, "%d'%02d\"", t / 60, t % 60);
    }
}
