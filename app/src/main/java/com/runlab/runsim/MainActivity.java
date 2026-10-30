package com.runlab.runsim;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 只做三件事：承载 WebView、提供页面与原生之间的桥、控制模拟定位服务。
 * 界面全部在 assets/app.html 里（自绘瓦片地图 + 路线规划）。
 */
public class MainActivity extends Activity {

    private static final String TAG = "RunSim";
    private static final int REQ_PERM = 1001;
    private static final String PAGE = "file:///android_asset/app.html";

    private WebView web;
    private Handler ui;
    private LocationManager lm;
    private LocationListener locListener;

    private boolean pageReady = false;
    private boolean polling = false;
    private int lastTrackSent = 0;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!pageReady) {
                ui.postDelayed(this, 500);
                return;
            }
            pushState();
            pushTrack();
            pollErrors();
            ui.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ui = new Handler(Looper.getMainLooper());
        lm = (LocationManager) getSystemService(LOCATION_SERVICE);

        web = (WebView) findViewById(R.id.web);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        ws.setUseWideViewPort(true);
        ws.setLoadWithOverviewMode(true);
        ws.setSupportZoom(false);
        ws.setBuiltInZoomControls(false);
        ws.setTextZoom(100);
        try {
            WebView.setWebContentsDebuggingEnabled(true);
        } catch (Throwable ignored) {
        }
        web.setBackgroundColor(0xFF060A0F);
        web.setOverScrollMode(WebView.OVER_SCROLL_NEVER);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage cm) {
                Log.i(TAG, "JS " + cm.message() + " @" + cm.lineNumber());
                return true;
            }
        });
        web.loadUrl(PAGE);

        if (!hasFinePermission()) {
            requestPerms();
        }
    }

    /* ===================== 生命周期 ===================== */

    @Override
    protected void onResume() {
        super.onResume();
        startPolling();
        startLocation();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopPolling();
        stopLocation();
    }

    private void startPolling() {
        if (polling) return;
        polling = true;
        ui.post(poll);
    }

    private void stopPolling() {
        polling = false;
        ui.removeCallbacks(poll);
    }

    @Override
    public void onBackPressed() {
        if (web == null) {
            finish();
            return;
        }
        web.evaluateJavascript("(window.onBack && window.onBack()) ? '1' : '0'", new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                if (!"\"1\"".equals(value)) {
                    finish();
                }
            }
        });
    }

    /* ===================== 真实定位 ===================== */

    @SuppressWarnings("deprecation")
    private void startLocation() {
        if (!hasFinePermission() || lm == null) return;
        try {
            Location last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (last == null) last = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (last != null && !isMock(last)) applyMyLocation(last);
        } catch (SecurityException ignored) {
        }

        if (locListener == null) {
            locListener = new LocationListener() {
                @Override
                public void onLocationChanged(Location location) {
                    if (location != null && !isMock(location)) applyMyLocation(location);
                }

                @Override
                public void onStatusChanged(String provider, int status, Bundle extras) {
                }

                @Override
                public void onProviderEnabled(String provider) {
                }

                @Override
                public void onProviderDisabled(String provider) {
                }
            };
        }
        try {
            if (lm.getAllProviders().contains(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000, 3f, locListener, Looper.getMainLooper());
            }
            if (lm.getAllProviders().contains(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 4000, 5f, locListener, Looper.getMainLooper());
            }
        } catch (SecurityException ignored) {
        } catch (Exception e) {
            Log.w(TAG, "location: " + e.getClass().getSimpleName());
        }
    }

    private void stopLocation() {
        if (lm != null && locListener != null) {
            try {
                lm.removeUpdates(locListener);
            } catch (Exception ignored) {
            }
        }
    }

    private static boolean isMock(Location l) {
        try {
            if (Build.VERSION.SDK_INT >= 31) return l.isMock();
            return l.isFromMockProvider();
        } catch (Throwable t) {
            return false;
        }
    }

    private void applyMyLocation(Location l) {
        SimState.myLat = l.getLatitude();
        SimState.myLng = l.getLongitude();
        SimState.myAcc = l.hasAccuracy() ? l.getAccuracy() : 20f;
        SimState.hasMyLocation = true;
        pushMyLocation();
    }

    private void pushMyLocation() {
        if (!pageReady || !SimState.hasMyLocation) return;
        js("RunUI.onMyLocation(" + num(SimState.myLat) + "," + num(SimState.myLng) + "," + num(SimState.myAcc) + ")");
    }

    /* ===================== 推送到页面 ===================== */

    private void pushState() {
        double lat = SimState.toLat(SimState.y);
        double lng = SimState.toLng(SimState.x);
        String json = "{\"running\":" + SimState.running
                + ",\"finished\":" + SimState.finished
                + ",\"dist\":" + num(SimState.distance)
                + ",\"time\":" + (SimState.elapsedMs / 1000.0)
                + ",\"pace\":" + num(SimState.paceSec)
                + ",\"hr\":" + num(SimState.heartRate)
                + ",\"kcal\":" + num(SimState.distance / 1000.0 * 62)
                + ",\"lat\":" + num(lat)
                + ",\"lng\":" + num(lng)
                + ",\"heading\":" + num(SimState.heading)
                + "}";
        js("RunUI.onState(" + json + ")");
    }

    private void pushTrack() {
        int n = SimState.trackSize();
        if (n < lastTrackSent) {
            lastTrackSent = 0;
            js("RunUI.clearTrack()");
        }
        if (n == lastTrackSent) return;
        StringBuilder sb = new StringBuilder();
        while (lastTrackSent < n) {
            double[] p = SimState.trackAt(lastTrackSent);
            lastTrackSent++;
            if (p == null) continue;
            sb.append("RunUI.addTrackPoint(").append(num(p[0])).append(',').append(num(p[1])).append(");");
        }
        if (sb.length() > 0) js(sb.toString());
    }

    private void pollErrors() {
        String err = SimState.lastError;
        if (err != null) {
            SimState.lastError = null;
            js("RunUI.onError(" + org.json.JSONObject.quote(err) + ")");
        }
    }

    private void js(final String code) {
        if (web == null) return;
        web.evaluateJavascript(code, null);
    }

    private static String num(double v) {
        return String.format(Locale.US, "%.7f", v);
    }

    /* ===================== 桥 ===================== */

    private class Bridge {

        @JavascriptInterface
        public void ready() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    pageReady = true;
                    restoreRoute();
                    lastTrackSent = Math.max(0, SimState.trackSize() - 800);
                    js("RunUI.clearTrack()");
                    pushTrack();
                    pushState();
                    pushMyLocation();
                }
            });
        }

        @JavascriptInterface
        public void setRoute(final String json, final String closed) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    List<double[]> pts = parsePoints(json);
                    SimState.setRoute(pts, "1".equals(closed));
                    SimState.resetSession();
                    lastTrackSent = 0;
                    js("RunUI.clearTrack()");
                    pushState();
                }
            });
        }

        @JavascriptInterface
        public void clearTrack() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    lastTrackSent = 0;
                }
            });
        }

        @JavascriptInterface
        public void routeChanged() {
            // 页面自己维护路线，这里只留钩子
        }

        @JavascriptInterface
        public void start(final int pace) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    doStart(pace);
                }
            });
        }

        @JavascriptInterface
        public void pause() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    stopService(new Intent(MainActivity.this, MockRunService.class));
                    SimState.running = false;
                    pushState();
                }
            });
        }

        @JavascriptInterface
        public void reset() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    stopService(new Intent(MainActivity.this, MockRunService.class));
                    SimState.resetSession();
                    lastTrackSent = 0;
                    js("RunUI.clearTrack()");
                    pushState();
                }
            });
        }

        @JavascriptInterface
        public void setPace(final int pace) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    SimState.paceSec = pace;
                }
            });
        }

        @JavascriptInterface
        public void locate() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (!hasFinePermission()) {
                        requestPerms();
                        return;
                    }
                    startLocation();
                    if (SimState.hasMyLocation) {
                        js("RunUI.centerOn(" + num(SimState.myLat) + "," + num(SimState.myLng) + ",16)");
                    } else {
                        toast("还没拿到定位，到室外再试");
                    }
                }
            });
        }

        @JavascriptInterface
        public void jumpTo(final double lat, final double lng) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    SimState.setOrigin(lat, lng);
                    lastTrackSent = 0;
                    js("RunUI.clearTrack()");
                    pushState();
                }
            });
        }

        @JavascriptInterface
        public void openDevOptions() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    openDeveloperOptions();
                }
            });
        }

        @JavascriptInterface
        public void requestPerms() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    requestPerms();
                }
            });
        }
    }

    /* ===================== 逻辑 ===================== */

    private void restoreRoute() {
        if (SimState.routeWgs.size() < 2) return;
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < SimState.routeWgs.size(); i++) {
            double[] p = SimState.routeWgs.get(i);
            if (i > 0) sb.append(',');
            sb.append('[').append(num(p[0])).append(',').append(num(p[1])).append(']');
        }
        sb.append(']');
        js("RunUI.restoreRoute(" + org.json.JSONObject.quote(sb.toString()) + "," + (SimState.routeClosed ? "1" : "0") + ")");
    }

    private List<double[]> parsePoints(String json) {
        List<double[]> out = new ArrayList<double[]>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONArray p = arr.optJSONArray(i);
                if (p == null || p.length() < 2) continue;
                double lat = p.optDouble(0);
                double lng = p.optDouble(1);
                if (Double.isNaN(lat) || Double.isNaN(lng)) continue;
                if (Math.abs(lat) > 85 || Math.abs(lng) > 180) continue;
                out.add(new double[]{lat, lng});
            }
        } catch (Exception e) {
            Log.w(TAG, "parse route failed: " + e.getMessage());
        }
        return out;
    }

    private void doStart(int pace) {
        if (pace >= 120 && pace <= 900) SimState.paceSec = pace;
        if (!hasFinePermission()) {
            requestPerms();
            return;
        }
        if (SimState.routeLength() <= 0) {
            toast("先在地图上点几个点，把路线画出来");
            return;
        }
        SimState.lastError = null;
        Intent i = new Intent(this, MockRunService.class);
        i.setAction(MockRunService.ACTION_START);
        i.putExtra(MockRunService.EXTRA_PACE, SimState.paceSec);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(i);
            } else {
                startService(i);
            }
        } catch (Exception e) {
            if (SimState.routeLength() > 0) {
                try {
                    startService(i);
                } catch (Exception e2) {
                    toast("启动模拟服务失败：" + e2.getClass().getSimpleName());
                }
            }
        }
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                pushState();
            }
        }, 600);
    }

    /* ===================== 权限 ===================== */

    private boolean hasFinePermission() {
        return checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPerms() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION,
                    android.Manifest.permission.POST_NOTIFICATIONS
            }, REQ_PERM);
        } else {
            requestPermissions(new String[]{
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION
            }, REQ_PERM);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERM) {
            if (hasFinePermission()) {
                toast("定位权限已获取");
                startLocation();
                pushMyLocation();
            } else {
                toast("没有精确定位权限，无法写入模拟位置");
            }
        }
    }

    private void openDeveloperOptions() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
        } catch (Exception e) {
            toast("请手动打开：设置 → 关于手机 → 连点版本号 7 次");
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        stopPolling();
        stopLocation();
        if (web != null) {
            try {
                web.removeJavascriptInterface("Android");
                web.destroy();
            } catch (Throwable ignored) {
            }
            web = null;
        }
        super.onDestroy();
    }
}
