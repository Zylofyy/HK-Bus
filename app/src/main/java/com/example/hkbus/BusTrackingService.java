package com.example.hkbus;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Icon;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BusTrackingService extends Service {
    public static final String ACTION_START = "com.example.hkbus.action.START_TRACKING";
    public static final String ACTION_CANCEL = "com.example.hkbus.action.CANCEL_TRACKING";
    public static final String ACTION_STOPPED = "com.example.hkbus.action.TRACKING_STOPPED";
    public static final String EXTRA_BOOKMARK = "bookmark";
    public static final String PREF_TRACKING_KEY = "trackingKey";

    private static final String CHANNEL_ID = "bus_tracking_live_updates";
    private static final int NOTIFICATION_ID = 4128;
    private static final String TAG = "BusTrackingService";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final MainActivity.Api api = new MainActivity.Api();
    private MainActivity.Bookmark bookmark;
    private Runnable pollRunnable;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
            stopTracking();
            return START_NOT_STICKY;
        }
        String serialized = intent == null ? null : intent.getStringExtra(EXTRA_BOOKMARK);
        MainActivity.Bookmark parsed = MainActivity.Bookmark.parse(serialized == null ? "" : serialized);
        if (parsed == null) {
            stopTracking();
            return START_NOT_STICKY;
        }
        bookmark = parsed;
        getSharedPreferences("hkbus", MODE_PRIVATE).edit().putString(PREF_TRACKING_KEY, bookmark.storageKey()).apply();
        startForeground(NOTIFICATION_ID, buildNotification("Finding nearest stop", "Live bus tracking", 0));
        schedulePoll(0);
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        io.shutdownNow();
        super.onDestroy();
    }

    private void schedulePoll(long delayMs) {
        if (pollRunnable != null) handler.removeCallbacks(pollRunnable);
        pollRunnable = this::poll;
        handler.postDelayed(pollRunnable, delayMs);
    }

    private void poll() {
        MainActivity.Bookmark active = bookmark;
        if (active == null) return;
        io.execute(() -> {
            try {
                List<MainActivity.Stop> stops = api.routeStops(active);
                MainActivity.Stop nearest = nearest(stops);
                List<String> times = api.etas(active, nearest);
                String next = times.isEmpty() ? "No upcoming arrivals" : times.get(0);
                int progress = progressFromEta(next);
                handler.post(() -> {
                    if (bookmark == null || !bookmark.storageKey().equals(active.storageKey())) return;
                    Notification notification = buildNotification(next, nearest.name, progress);
                    ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID, notification);
                    schedulePoll("Due".equals(next) ? 12000 : 30000);
                });
            } catch (Exception e) {
                handler.post(() -> {
                    if (bookmark == null) return;
                    ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(
                            NOTIFICATION_ID,
                            buildNotification("Tracking paused", "Could not refresh arrivals", 0));
                    schedulePoll(60000);
                });
            }
        });
    }

    private Notification buildNotification(String next, String stopName, int progress) {
        MainActivity.Bookmark b = bookmark;
        String route = b == null ? "HK Bus" : b.route + " " + opName(b.operator);
        int accent = themeColor();
        Intent appIntent = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, appIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent cancelIntent = new Intent(this, BusTrackingService.class).setAction(ACTION_CANCEL);
        PendingIntent cancelPending = PendingIntent.getService(this, 1, cancelIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_bus)
                .setContentTitle(route + " next bus")
                .setContentText(next + " at " + stopName)
                .setSubText("Live bus tracking")
                .setContentIntent(contentIntent)
                .setColor(accent)
                .setColorized(false)
                .setCategory(Notification.CATEGORY_STATUS)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(true)
                .setWhen(System.currentTimeMillis())
                .setProgress(100, progress, false)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_track_cancel),
                        "Cancel",
                        cancelPending).build());
        requestPromotedLiveUpdate(builder);
        applyProgressStyle(builder, progress);
        if (Build.VERSION.SDK_INT >= 36) builder.setShortCriticalText(next);
        Notification notification = builder.build();
        if (Build.VERSION.SDK_INT >= 36) {
            boolean requested = notification.extras != null && notification.extras.getBoolean("android.requestPromotedOngoing", false);
            Log.d(TAG, "Live update requested=" + requested + " promotable=" + notification.hasPromotableCharacteristics());
        }
        return notification;
    }


    private void requestPromotedLiveUpdate(Notification.Builder builder) {
        Bundle extras = new Bundle();
        extras.putBoolean("android.requestPromotedOngoing", true);
        builder.addExtras(extras);
    }

    private void applyProgressStyle(Notification.Builder builder, int progress) {
        if (Build.VERSION.SDK_INT < 36) return;
        int clamped = Math.max(0, Math.min(100, progress));
        Notification.ProgressStyle style = new Notification.ProgressStyle()
                .setProgress(clamped)
                .setStyledByProgress(true);
        builder.setStyle(style);
    }

    private MainActivity.Stop nearest(List<MainActivity.Stop> stops) {
        MainActivity.Stop best = stops.get(0);
        Location here = lastLocation();
        if (here == null) return best;
        float[] result = new float[1];
        float bestDistance = Float.MAX_VALUE;
        for (MainActivity.Stop stop : stops) {
            Location.distanceBetween(here.getLatitude(), here.getLongitude(), stop.lat, stop.lon, result);
            if (result[0] < bestDistance) {
                bestDistance = result[0];
                best = stop;
            }
        }
        return best;
    }

    private Location lastLocation() {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null;
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        Location gps = null;
        Location net = null;
        try { gps = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER); } catch (Exception ignored) {}
        try { net = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER); } catch (Exception ignored) {}
        if (gps != null && net != null) return gps.getTime() > net.getTime() ? gps : net;
        return gps != null ? gps : net;
    }

    private int progressFromEta(String eta) {
        if (eta == null || eta.length() == 0) return 0;
        if ("Due".equalsIgnoreCase(eta)) return 100;
        String lower = eta.toLowerCase(Locale.US);
        if (lower.endsWith(" min")) {
            try {
                int minutes = Integer.parseInt(lower.replace(" min", "").trim());
                return Math.max(0, Math.min(100, 100 - Math.round(minutes * 100f / 20f)));
            } catch (Exception ignored) {}
        }
        return 0;
    }

    private void stopTracking() {
        bookmark = null;
        handler.removeCallbacksAndMessages(null);
        getSharedPreferences("hkbus", MODE_PRIVATE).edit().remove(PREF_TRACKING_KEY).apply();
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(NOTIFICATION_ID);
        Intent stopped = new Intent(ACTION_STOPPED).setPackage(getPackageName());
        sendBroadcast(stopped);
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
        stopSelf();
    }

    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Bus tracking", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("Live Hong Kong bus arrival tracking");
        channel.setShowBadge(false);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
    }

    private int themeColor() {
        SharedPreferences prefs = getSharedPreferences("hkbus", MODE_PRIVATE);
        if (prefs.getBoolean("themeSystem", true) && Build.VERSION.SDK_INT >= 31) return getColor(android.R.color.system_accent1_600);
        return prefs.getInt("themeColor", Color.rgb(10, 132, 255));
    }

    private static String opName(String op) { if ("KMB".equals(op)) return "KMB"; if ("MTR".equals(op)) return "MTR"; return "Citybus"; }
}
