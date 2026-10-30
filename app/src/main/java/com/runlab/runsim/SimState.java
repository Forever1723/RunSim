package com.runlab.runsim;

import java.util.ArrayList;
import java.util.List;

/**
 * 全局模拟状态。
 * 路线有两种表达：routeWgs 是用户在地图上画的原始经纬度点，
 * route 是相对原点（第一个点）的本地平面坐标，用于里程插值。
 */
public final class SimState {

    private static final double M_PER_DEG_LAT = 111320.0;

    /* ---------------- 地理原点 ---------------- */
    public static double originLat = 39.9042;
    public static double originLng = 116.4074;
    private static double mLngPerDeg = M_PER_DEG_LAT * Math.cos(Math.toRadians(originLat));

    /* ---------------- 路线 ---------------- */
    public static final List<double[]> routeWgs = new ArrayList<double[]>();
    public static final List<double[]> route = new ArrayList<double[]>();
    private static double[] cum = new double[0];
    public static double routeLen = 0;
    public static double routeS = 0;
    public static boolean routeClosed = true;

    /* ---------------- 跑者 ---------------- */
    public static double x = 0;
    public static double y = 0;
    public static float heading = 0f;

    /* ---------------- 记录（WGS 经纬度） ---------------- */
    public static final List<double[]> track = new ArrayList<double[]>();

    /* ---------------- 真实定位 ---------------- */
    public static volatile boolean hasMyLocation = false;
    public static volatile double myLat = 0;
    public static volatile double myLng = 0;
    public static volatile float myAcc = 0f;

    /* ---------------- 运行状态 ---------------- */
    public static volatile boolean running = false;
    public static volatile boolean finished = false;
    public static volatile String lastError = null;
    public static float paceSec = 330f;
    public static double distance = 0;
    public static long elapsedMs = 0;
    public static float speed = 0f;
    public static float heartRate = 0f;
    public static float accuracy = 3f;
    public static int kmDone = 0;
    public static long lastTrackMs = -99999;

    private SimState() {
    }

    /* ================= 坐标换算 ================= */

    public static double toLat(double ym) {
        return originLat + ym / M_PER_DEG_LAT;
    }

    public static double toLng(double xm) {
        return originLng + xm / mLngPerDeg;
    }

    public static double fromLat(double lat) {
        return (lat - originLat) * M_PER_DEG_LAT;
    }

    public static double fromLng(double lng) {
        return (lng - originLng) * mLngPerDeg;
    }

    /* ================= 路线 ================= */

    /** 只改原点（地图跳到别处、还没画路线时用） */
    public static synchronized void setOrigin(double lat, double lng) {
        originLat = lat;
        originLng = lng;
        mLngPerDeg = M_PER_DEG_LAT * Math.cos(Math.toRadians(lat));
        routeWgs.clear();
        route.clear();
        cum = new double[0];
        routeLen = 0;
        routeS = 0;
        x = 0;
        y = 0;
        distance = 0;
        elapsedMs = 0;
        speed = 0;
        heartRate = 0;
        kmDone = 0;
        finished = false;
        running = false;
        track.clear();
        lastTrackMs = -99999;
    }

    /** 设置用户规划好的路线（WGS 经纬度点），并以第一个点为原点 */
    public static synchronized void setRoute(List<double[]> wgs, boolean closed) {
        routeWgs.clear();
        route.clear();
        cum = new double[0];
        routeLen = 0;
        routeS = 0;
        routeClosed = closed;
        if (wgs == null || wgs.size() < 2) {
            return;
        }
        originLat = wgs.get(0)[0];
        originLng = wgs.get(0)[1];
        mLngPerDeg = M_PER_DEG_LAT * Math.cos(Math.toRadians(originLat));

        for (int i = 0; i < wgs.size(); i++) {
            double[] p = wgs.get(i);
            routeWgs.add(new double[]{p[0], p[1]});
            route.add(new double[]{fromLng(p[1]), fromLat(p[0])});
        }
        recomputeCum();
        x = 0;
        y = 0;
        routeS = 0;
    }

    public static synchronized double routeLength() {
        return routeLen;
    }

    private static void recomputeCum() {
        int n = route.size();
        cum = new double[n];
        if (n < 2) {
            routeLen = 0;
            return;
        }
        double t = 0;
        for (int i = 1; i < n; i++) {
            t += dist(route.get(i - 1), route.get(i));
            cum[i] = t;
        }
        if (routeClosed) {
            t += dist(route.get(n - 1), route.get(0));
        }
        routeLen = t;
    }

    private static double dist(double[] a, double[] b) {
        return Math.hypot(a[0] - b[0], a[1] - b[1]);
    }

    public static synchronized int nearestIndex(double px, double py) {
        int best = 0;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < route.size(); i++) {
            double[] p = route.get(i);
            double d = (p[0] - px) * (p[0] - px) + (p[1] - py) * (p[1] - py);
            if (d < bd) {
                bd = d;
                best = i;
            }
        }
        return best;
    }

    /** 取路线 s 米处的位置：{x, y, 方向弧度} */
    public static synchronized double[] pointAt(double s) {
        int n = route.size();
        if (routeLen <= 0 || n < 2) return new double[]{0, 0, 0};

        double total = routeLen;
        if (routeClosed) {
            s = s % total;
            if (s < 0) s += total;
        } else {
            if (s < 0) s = 0;
            if (s > total) s = total;
        }

        int lo = 0;
        int hi = n - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (cum[mid] <= s) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        if (!routeClosed && lo >= n - 1) lo = n - 2;
        if (lo < 0) lo = 0;

        double[] a = route.get(lo);
        double[] b = route.get((lo + 1) % n);
        double segEnd = (lo + 1 < n) ? cum[lo + 1] : total;
        double segLen = Math.max(1e-6, segEnd - cum[lo]);
        double f = (s - cum[lo]) / segLen;
        if (f < 0) f = 0;
        if (f > 1) f = 1;

        double px = a[0] + (b[0] - a[0]) * f;
        double py = a[1] + (b[1] - a[1]) * f;
        double ang = Math.atan2(b[1] - a[1], b[0] - a[0]);
        return new double[]{px, py, ang};
    }

    /* ================= 模拟 ================= */

    public static synchronized void resetSession() {
        running = false;
        finished = false;
        distance = 0;
        elapsedMs = 0;
        speed = 0;
        heartRate = 0;
        kmDone = 0;
        lastTrackMs = -99999;
        track.clear();
        routeS = 0;
        if (route.size() >= 2) {
            x = route.get(0)[0];
            y = route.get(0)[1];
        }
    }

    /** 推进 dt 秒 */
    public static synchronized void step(double dt) {
        if (!running || routeLen <= 0) return;
        if (dt <= 0) return;
        if (dt > 4) dt = 4;

        elapsedMs += (long) (dt * 1000);
        double sec = elapsedMs / 1000.0;
        double base = 1000.0 / Math.max(60.0, (double) paceSec);
        double wobble = 1 + 0.045 * Math.sin(sec / 9.1) + 0.02 * Math.sin(sec / 2.7 + 1.3);
        speed = (float) (base * wobble);

        double ds = speed * dt;
        if (!routeClosed) {
            ds = Math.min(ds, Math.max(0, routeLen - routeS));
        }
        distance += ds;
        routeS += ds;

        double[] p = pointAt(routeS);
        x = p[0];
        y = p[1];
        heading = (float) Math.toDegrees(p[2]);

        float targetHr = (float) clamp(88 + 92 * (speed / 4.2), 95, 196);
        heartRate += (targetHr - heartRate) * Math.min(1f, (float) dt / 22f);

        int km = (int) Math.floor(distance / 1000.0);
        if (km > kmDone) {
            kmDone = km;
        }
        if (elapsedMs - lastTrackMs >= 2000) addTrack();

        if (!routeClosed && routeS >= routeLen - 0.05) {
            running = false;
            finished = true;
            addTrack();
        }
    }

    private static void addTrack() {
        double r = accuracy * Math.sqrt(Math.random());
        double a = Math.random() * Math.PI * 2;
        track.add(new double[]{
                toLat(y + Math.sin(a) * r),
                toLng(x + Math.cos(a) * r)
        });
        lastTrackMs = elapsedMs;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /* ================= 统计 ================= */

    public static double avgPaceSec() {
        if (distance < 5) return 0;
        return (elapsedMs / 1000.0) / (distance / 1000.0);
    }

    public static double currentPaceSec() {
        if (speed <= 0.1f) return 0;
        return 1000.0 / speed;
    }

    public static synchronized int trackSize() {
        return track.size();
    }

    public static synchronized double[] trackAt(int i) {
        if (i < 0 || i >= track.size()) return null;
        return track.get(i);
    }
}
