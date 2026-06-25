package com.example.hkbus;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class RouteGraphStore {
    private static final String ASSET_NAME = "route_graph.db";
    private static final String PREF_GRAPH_VERSION = "routeGraphAssetVersion";
    private final Context context;

    RouteGraphStore(Context context) {
        this.context = context.getApplicationContext();
    }

    List<MainActivity.RoutePattern> loadPatterns() throws Exception {
        SQLiteDatabase db = openReadOnly();
        if (db == null) return new ArrayList<>();
        try {
            Map<Integer, PatternBuilder> builders = new LinkedHashMap<>();
            Cursor routes = db.rawQuery("SELECT pattern_id, operator, route, dir, service_type, orig, dest FROM route_patterns ORDER BY pattern_id", null);
            try {
                while (routes.moveToNext()) {
                    int id = routes.getInt(0);
                    MainActivity.Route route = new MainActivity.Route(routes.getString(1), routes.getString(2), routes.getString(5), routes.getString(6), routes.getString(4));
                    builders.put(id, new PatternBuilder(route, routes.getString(3)));
                }
            } finally {
                routes.close();
            }
            Cursor stops = db.rawQuery("SELECT pattern_id, seq, stop_id, name, lat, lon, transfer_key FROM pattern_stops ORDER BY pattern_id, seq", null);
            try {
                while (stops.moveToNext()) {
                    PatternBuilder builder = builders.get(stops.getInt(0));
                    if (builder == null) continue;
                    builder.stops.add(new MainActivity.Stop(stops.getString(2), stops.getString(3), stops.getInt(1), stops.getDouble(4), stops.getDouble(5), stops.getString(6)));
                }
            } finally {
                stops.close();
            }
            List<MainActivity.RoutePattern> patterns = new ArrayList<>();
            for (PatternBuilder builder : builders.values()) {
                if (builder.stops.size() > 1) patterns.add(new MainActivity.RoutePattern(builder.route, builder.dir, builder.stops));
            }
            return patterns;
        } finally {
            db.close();
        }
    }

    List<MainActivity.Stop> loadPhysicalStops() throws Exception {
        SQLiteDatabase db = openReadOnly();
        if (db == null) return new ArrayList<>();
        try {
            List<MainActivity.Stop> stops = new ArrayList<>();
            Cursor cursor = db.rawQuery("SELECT transfer_key, name, lat, lon FROM physical_stops ORDER BY name", null);
            try {
                int seq = 1;
                while (cursor.moveToNext()) {
                    String key = cursor.getString(0);
                    stops.add(new MainActivity.Stop(key, cursor.getString(1), seq++, cursor.getDouble(2), cursor.getDouble(3), key));
                }
            } finally {
                cursor.close();
            }
            return stops;
        } finally {
            db.close();
        }
    }

    private SQLiteDatabase openReadOnly() throws Exception {
        File dbFile = databaseFile();
        if (!ensureDatabase(dbFile)) return null;
        return SQLiteDatabase.openDatabase(dbFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
    }

    private boolean ensureDatabase(File dbFile) throws Exception {
        SharedPreferences prefs = context.getSharedPreferences("hkbus", Context.MODE_PRIVATE);
        String appVersion = appVersionKey();
        if (dbFile.exists() && appVersion.equals(prefs.getString(PREF_GRAPH_VERSION, ""))) return true;
        File parent = dbFile.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        File tmp = new File(dbFile.getAbsolutePath() + ".tmp");
        try (InputStream in = context.getAssets().open(ASSET_NAME); FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] buffer = new byte[32768];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        } catch (Exception e) {
            if (tmp.exists()) tmp.delete();
            return dbFile.exists();
        }
        if (dbFile.exists() && !dbFile.delete()) throw new IllegalStateException("Could not replace route graph database");
        if (!tmp.renameTo(dbFile)) throw new IllegalStateException("Could not install route graph database");
        prefs.edit().putString(PREF_GRAPH_VERSION, appVersion).apply();
        return true;
    }

    private File databaseFile() {
        return context.getDatabasePath(ASSET_NAME);
    }

    private String appVersionKey() {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            long code = android.os.Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
            return info.versionName + ":" + code;
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static final class PatternBuilder {
        final MainActivity.Route route;
        final String dir;
        final List<MainActivity.Stop> stops = new ArrayList<>();
        PatternBuilder(MainActivity.Route route, String dir) {
            this.route = route;
            this.dir = dir;
        }
    }
}
