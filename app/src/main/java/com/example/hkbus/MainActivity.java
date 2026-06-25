package com.example.hkbus;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.app.NotificationManager;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String UPDATE_REPO = "Zylofyy/HK-Bus";
    private static final String LATEST_RELEASE_URL = "https://api.github.com/repos/" + UPDATE_REPO + "/releases/latest";
    private static final int BG = Color.rgb(5, 7, 13);
    private static final int PANEL = Color.argb(165, 24, 28, 38);
    private static final int CARD = Color.rgb(17, 21, 31);
    private static final int STROKE = Color.rgb(47, 57, 74);
    private static final int TEXT = Color.rgb(242, 246, 255);
    private static final int MUTED = Color.rgb(144, 154, 173);
    private int BLUE = Color.rgb(10, 132, 255);
    private static final int GREEN = Color.rgb(48, 209, 88);

    private final ExecutorService io = Executors.newFixedThreadPool(5);
    private final Api api = new Api();
    private FrameLayout root;
    private LinearLayout content;
    private FrameLayout navIsland;
    private LinearLayout nav;
    private FrameLayout sheetOverlay;
    private ImageButton groupFab;
    private ImageButton topMenu;
    private int tab = 0;
    private SharedPreferences prefs;
    private final List<Route> routes = new ArrayList<>();
    private final Map<String, LinearLayout> previewEtaViews = new HashMap<>();
    private final Map<String, TextView> previewStopViews = new HashMap<>();
    private final Map<String, Button> trackingButtons = new HashMap<>();
    private final Set<String> expandedCustomRoutes = new HashSet<>();
    private final Map<String, List<Stop>> routeStopCache = new HashMap<>();
    private final List<Stop> allStops = new ArrayList<>();
    private boolean allStopsLoading = false;
    private boolean trackingReceiverRegistered = false;
    private final BroadcastReceiver trackingStoppedReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { updateTrackingButtons(); }
    };
    private Location lastLocation;
    private String selectedGroup = "All";
    private boolean groupOrderMode = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        prefs = getSharedPreferences("hkbus", MODE_PRIVATE);
        loadThemeColor();
        maybeAskLocation();
        buildShell();
        loadRoutes();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!trackingReceiverRegistered) {
            IntentFilter filter = new IntentFilter(BusTrackingService.ACTION_STOPPED);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(trackingStoppedReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(trackingStoppedReceiver, filter);
            trackingReceiverRegistered = true;
        }
        updateTrackingButtons();
    }

    @Override
    protected void onPause() {
        if (trackingReceiverRegistered) {
            unregisterReceiver(trackingStoppedReceiver);
            trackingReceiverRegistered = false;
        }
        super.onPause();
    }

    private void buildShell() {
        root = new FrameLayout(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(34), dp(18), dp(112));
        root.addView(content, new FrameLayout.LayoutParams(-1, -1));

        topMenu = themedImageButton(R.drawable.ic_more_vertical, floatingSurface(), buttonText(), Color.TRANSPARENT, dp(22));
        topMenu.setOnClickListener(v -> showMainMenuSheet());
        FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.TOP | Gravity.RIGHT);
        mp.setMargins(0, dp(28), dp(18), 0);
        root.addView(topMenu, mp);

        navIsland = new FrameLayout(this);
        nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(dp(16), dp(8), dp(16), dp(8));
        navIsland.addView(nav, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout.LayoutParams np = new FrameLayout.LayoutParams(-1, dp(104), Gravity.BOTTOM);
        root.addView(navIsland, np);

        groupFab = themedImageButton(R.drawable.ic_plus_round, floatingSurface(), buttonText(), Color.TRANSPARENT, dp(22));
        groupFab.setOnClickListener(v -> {
            if (tab == 1) showRouteFabMenu();
            else showCreateGroupDialog(null);
        });
        FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(dp(72), dp(72), Gravity.BOTTOM | Gravity.RIGHT);
        fp.setMargins(0, 0, dp(24), dp(124));
        root.addView(groupFab, fp);

        applyThemeSurfaces();
        setContentView(root);
        renderNav();
        showBookmarks();
    }
    private void renderNav() {
        nav.removeAllViews();
        nav.addView(navButton("Bookmarks", 0, R.drawable.ic_nav_bookmark), new LinearLayout.LayoutParams(0, -1, 1));
        nav.addView(navButton("Routes", 1, R.drawable.ic_nav_nodes), new LinearLayout.LayoutParams(0, -1, 1));
        nav.addView(navButton("Search", 2, R.drawable.ic_nav_search), new LinearLayout.LayoutParams(0, -1, 1));
    }

    private View navButton(String label, int index, int iconRes) {
        boolean selected = tab == index;
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setPadding(dp(4), 0, dp(4), 0);

        FrameLayout iconPill = new FrameLayout(this);
        iconPill.setBackground(round(selected ? navSelectedSurface() : Color.TRANSPARENT, dp(28), Color.TRANSPARENT));
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setColorFilter(selected ? Color.rgb(22, 27, 38) : navMutedText());
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER);
        iconPill.addView(icon, ip);
        LinearLayout.LayoutParams pillParams = new LinearLayout.LayoutParams(dp(76), dp(44));
        pillParams.gravity = Gravity.CENTER_HORIZONTAL;
        item.addView(iconPill, pillParams);

        TextView labelView = text(label, 14, selected ? Color.WHITE : navMutedText(), true);
        labelView.setGravity(Gravity.CENTER);
        labelView.setIncludeFontPadding(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(22));
        lp.setMargins(0, dp(3), 0, 0);
        item.addView(labelView, lp);
        item.setOnClickListener(x -> {
            tab = index;
            renderNav();
            showCurrentTab();
        });
        return item;
    }
    private void showRoutes() {
        updateGroupFab();
        content.removeAllViews();
        content.addView(pageTitle("Routes"));
        content.addView(text("Build named journeys from one stop to another.", 15, MUTED, false));

        ScrollView scroll = new ScrollView(this);
        applyCardScrollFade(scroll);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1);
        lp.setMargins(0, dp(16), 0, 0);
        content.addView(scroll, lp);

        List<CustomRoute> customRoutes = getCustomRoutes();
        if (customRoutes.isEmpty()) {
            list.addView(centerStatus("Tap + to create a route."));
            return;
        }
        for (CustomRoute route : customRoutes) list.addView(customRouteCard(route));
    }

    private void showRouteFabMenu() {
        showOptionSheet("Routes", new String[]{"Create Route"}, (index, label) -> showNewRouteNameSheet());
    }

    private void showNewRouteNameSheet() {
        showTextInputSheet("New Route", "", "Route name", value -> {
            String name = value == null ? "" : value.trim();
            if (name.length() == 0) return;
            CustomRoute customRoute = new CustomRoute(String.valueOf(System.currentTimeMillis()), name, new ArrayList<>());
            chooseJourneyStartStop(customRoute);
        });
    }

    private void chooseJourneyStartStop(CustomRoute customRoute) {
        showGlobalStopPickerSheet("Starting Bus Stop", stop -> chooseJourneyEndStop(customRoute, stop));
    }

    private void chooseJourneyEndStop(CustomRoute customRoute, Stop start) {
        showGlobalStopPickerSheet("Ending Bus Stop", stop -> generateCustomRoutePath(customRoute, start, stop));
    }

    private void generateCustomRoutePath(CustomRoute customRoute, Stop start, Stop end) {
        showLoadingSheet("Finding Route", "Finding the shortest bus path...");
        io.execute(() -> {
            try {
                List<CustomRouteLeg> legs = findShortestBusPath(start, end);
                runOnUiThread(() -> {
                    dismissSheet();
                    if (legs.isEmpty()) {
                        showInfoSheet("Route", "No bus path was found between those stops.");
                        return;
                    }
                    customRoute.legs.clear();
                    customRoute.legs.addAll(legs);
                    showCustomRouteEditor(customRoute);
                });
            } catch (Exception e) {
                runOnUiThread(() -> showInfoSheet("Route", "Could not generate route: " + e.getMessage()));
            }
        });
    }

    private View customRouteCard(CustomRoute customRoute) {
        LinearLayout box = card();
        box.setOnLongClickListener(v -> {
            showCustomRouteMenu(customRoute);
            return true;
        });
        TextView title = text(customRoute.name, 22, TEXT, true);
        box.addView(title);

        if (customRoute.legs.isEmpty()) {
            TextView empty = text("No bus routes added yet", 15, MUTED, false);
            LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(-1, -2);
            ep.setMargins(0, dp(10), 0, 0);
            box.addView(empty, ep);
        } else {
            CustomRouteLeg nearest = nearestLeg(customRoute);
            box.addView(collapsedRouteSummary(nearest));
            if (expandedCustomRoutes.contains(customRoute.id)) {
                boolean first = true;
                for (CustomRouteLeg leg : customRoute.legs) {
                    View legCard = customRouteLegCard(leg, false);
                    if (first) {
                        LinearLayout.LayoutParams firstLp = new LinearLayout.LayoutParams(-1, -2);
                        firstLp.setMargins(0, dp(12), 0, dp(12));
                        legCard.setLayoutParams(firstLp);
                        first = false;
                    }
                    box.addView(legCard);
                }
            }
        }

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(0, dp(12), 0, 0);
        Button expand = materialButton(expandedCustomRoutes.contains(customRoute.id) ? "Collapse" : "Expand");
        expand.setOnClickListener(v -> {
            if (expandedCustomRoutes.contains(customRoute.id)) expandedCustomRoutes.remove(customRoute.id);
            else expandedCustomRoutes.add(customRoute.id);
            showRoutes();
        });
        Button edit = materialButton("Edit");
        edit.setOnClickListener(v -> showCustomRouteEditor(customRoute));
        actions.addView(expand, new LinearLayout.LayoutParams(0, dp(44), 1));
        TextView gap = new TextView(this);
        actions.addView(gap, new LinearLayout.LayoutParams(dp(10), 1));
        actions.addView(edit, new LinearLayout.LayoutParams(0, dp(44), 1));
        box.addView(actions);
        return box;
    }

    private View collapsedRouteSummary(CustomRouteLeg leg) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dp(14), dp(12), dp(14), dp(12));
        wrap.setBackground(round(fieldSurface(), dp(18), outline()));
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(-1, -2);
        wp.setMargins(0, dp(12), 0, 0);
        wrap.setLayoutParams(wp);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView route = text(leg.route, 20, TEXT, true);
        row.addView(route, new LinearLayout.LayoutParams(0, -2, 1));
        TextView eta = text("Loading", 15, GREEN, true);
        eta.setGravity(Gravity.CENTER);
        eta.setSingleLine(true);
        eta.setPadding(dp(14), dp(8), dp(14), dp(8));
        eta.setBackground(round(surface(), dp(15), outline()));
        row.addView(eta, new LinearLayout.LayoutParams(-2, dp(38)));
        wrap.addView(row);

        TextView stop = text(leg.startName, 14, MUTED, false);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.setMargins(0, dp(6), 0, 0);
        wrap.addView(stop, sp);
        loadFirstEtaInto(leg, eta);
        return wrap;
    }

    private View customRouteLegCard(CustomRouteLeg leg, boolean editPage) {
        LinearLayout box = card();
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        String title = editPage ? leg.route + "  " + opName(leg.operator) : leg.route;
        header.addView(text(title, 22, TEXT, true), new LinearLayout.LayoutParams(0, -2, 1));
        if (!editPage) header.addView(groupPill(opName(leg.operator)));
        box.addView(header);

        TextView start = text(leg.startName, 15, TEXT, true);
        start.setPadding(dp(14), dp(10), dp(14), dp(10));
        start.setBackground(round(fieldSurface(), dp(14), outline()));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.setMargins(0, dp(12), 0, dp(10));
        box.addView(start, sp);

        LinearLayout etaRow = etaBoxRow(null);
        box.addView(etaRow);
        loadEtaRowInto(leg.bookmark(), leg.startStop(), etaRow);

        TextView connector = text("|", 20, MUTED, false);
        connector.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.setMargins(0, dp(6), 0, dp(2));
        box.addView(connector, cp);

        TextView end = text(leg.endName, 14, MUTED, false);
        end.setGravity(Gravity.CENTER);
        box.addView(end);
        return box;
    }

    private void showCustomRouteEditor(CustomRoute customRoute) {
        if (navIsland != null) navIsland.setVisibility(View.GONE);
        if (groupFab != null) groupFab.setVisibility(View.GONE);
        if (topMenu != null) topMenu.setVisibility(View.GONE);
        content.removeAllViews();

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(customRoute.name, 24, TEXT, true);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button done = materialButton("Done");
        done.setOnClickListener(v -> {
            saveCustomRoute(customRoute);
            if (navIsland != null) navIsland.setVisibility(View.VISIBLE);
            if (topMenu != null) topMenu.setVisibility(View.VISIBLE);
            tab = 1;
            showRoutes();
        });
        header.addView(done, new LinearLayout.LayoutParams(dp(96), dp(44)));
        content.addView(header);

        ScrollView scroll = new ScrollView(this);
        applyCardScrollFade(scroll);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1);
        lp.setMargins(0, dp(18), 0, 0);
        content.addView(scroll, lp);

        if (customRoute.legs.isEmpty()) {
            list.addView(centerStatus("No bus routes in this path."));
        } else {
            for (int i = 0; i < customRoute.legs.size(); i++) {
                final int index = i;
                View card = customRouteLegCard(customRoute.legs.get(i), true);
                card.setOnLongClickListener(v -> {
                    showCustomRouteLegMenu(customRoute, index);
                    return true;
                });
                list.addView(card);
            }
        }
    }

    private void showCustomRouteLegMenu(CustomRoute customRoute, int index) {
        showOptionSheet("Bus Route", new String[]{"Remove Bus Route", "Add Bus Route Above", "Add Bus Route Below"}, (choiceIndex, choice) -> {
            if ("Remove Bus Route".equals(choice)) {
                if (index >= 0 && index < customRoute.legs.size()) customRoute.legs.remove(index);
                showCustomRouteEditor(customRoute);
            } else if ("Add Bus Route Above".equals(choice)) {
                beginAddRouteLegAt(customRoute, Math.max(0, index));
            } else if ("Add Bus Route Below".equals(choice)) {
                beginAddRouteLegAt(customRoute, Math.min(customRoute.legs.size(), index + 1));
            }
        });
    }

    private void showCreateRouteFlow(CustomRoute editing) {
        if (editing == null) showNewRouteNameSheet();
        else showCustomRouteEditor(editing);
    }

    private void beginAddRouteLegAt(CustomRoute customRoute, int insertIndex) {
        if (routes.isEmpty()) { showInfoSheet("Routes", "Routes are still loading."); return; }
        List<Route> choices = new ArrayList<>(routes);
        Collections.sort(choices, Comparator.comparing((Route r) -> r.route.length()).thenComparing(r -> r.route));
        showRoutePickerSheet("Select Bus Route", choices, route -> chooseRouteStops(route, customRoute, insertIndex));
    }

    private void chooseRouteStops(Route route, CustomRoute customRoute, int insertIndex) {
        showLoadingSheet("Loading Bus Route", "Loading bus route stops...");
        Bookmark b = new Bookmark(route.operator, route.route, "outbound", route.serviceType, route.orig, route.dest, "Ungrouped");
        io.execute(() -> {
            try {
                List<Stop> stops = cachedRouteStops(route, "outbound");
                runOnUiThread(() -> chooseStartStop(route, stops, customRoute, insertIndex));
            } catch (Exception e) {
                runOnUiThread(() -> showInfoSheet("Route", "Could not load stops: " + e.getMessage()));
            }
        });
    }

    private void chooseStartStop(Route route, List<Stop> stops, CustomRoute customRoute, int insertIndex) {
        showStopPickerSheet("Starting Stop", stops, (startIndex, label) -> chooseEndStop(route, stops, startIndex, customRoute, insertIndex));
    }

    private void chooseEndStop(Route route, List<Stop> stops, int startIndex, CustomRoute customRoute, int insertIndex) {
        showStopPickerSheet("Ending Stop", stops, (endIndex, label) -> {
            Stop start = stops.get(startIndex);
            Stop end = stops.get(endIndex);
            CustomRouteLeg leg = new CustomRouteLeg(route.operator, route.route, "outbound", route.serviceType, route.orig, route.dest, start.id, start.name, start.seq, start.lat, start.lon, end.id, end.name, end.seq, end.lat, end.lon);
            int safeIndex = Math.max(0, Math.min(insertIndex, customRoute.legs.size()));
            customRoute.legs.add(safeIndex, leg);
            showCustomRouteEditor(customRoute);
        });
    }

    private String[] stopOptionLabels(List<Stop> stops) {
        String[] names = new String[Math.min(stops.size(), 120)];
        for (int i = 0; i < names.length; i++) names[i] = stops.get(i).seq + ". " + stops.get(i).name;
        return names;
    }

    private CustomRouteLeg nearestLeg(CustomRoute route) {
        if (route.legs.isEmpty()) return null;
        updateLocation();
        if (lastLocation == null) return route.legs.get(0);
        CustomRouteLeg best = route.legs.get(0);
        double bestMeters = Double.MAX_VALUE;
        for (CustomRouteLeg leg : route.legs) {
            if (leg.startLat == 0 && leg.startLon == 0) continue;
            float[] out = new float[1];
            Location.distanceBetween(lastLocation.getLatitude(), lastLocation.getLongitude(), leg.startLat, leg.startLon, out);
            if (out[0] < bestMeters) {
                bestMeters = out[0];
                best = leg;
            }
        }
        return best;
    }

    private List<CustomRouteLeg> findShortestBusPath(Stop start, Stop end) throws Exception {
        List<RoutePattern> patterns = loadRoutePatterns();
        Map<String, List<PatternStop>> grid = buildPatternGrid(patterns);
        PriorityQueue<PathState> queue = new PriorityQueue<>((a, b) -> {
            if (a.transfers != b.transfers) return a.transfers - b.transfers;
            if (a.stops != b.stops) return a.stops - b.stops;
            return a.legs.size() - b.legs.size();
        });
        for (PatternStop candidate : nearbyPatternStops(grid, start, 450)) {
            RoutePattern pattern = patterns.get(candidate.patternIndex);
            if (distanceMeters(pattern.stops.get(candidate.stopIndex), start) > 450) continue;
            if (candidate.stopIndex < pattern.stops.size() - 1) {
                queue.add(new PathState(candidate.patternIndex, candidate.stopIndex, 0, 0, new ArrayList<>()));
            }
        }
        Map<String, Integer> best = new HashMap<>();
        int explored = 0;
        while (!queue.isEmpty() && explored < 12000) {
            explored++;
            PathState state = queue.poll();
            String key = state.patternIndex + ":" + state.stopIndex + ":" + state.transfers;
            Integer prev = best.get(key);
            if (prev != null && prev <= state.stops) continue;
            best.put(key, state.stops);
            RoutePattern pattern = patterns.get(state.patternIndex);
            int endIndex = firstReachableIndex(pattern, end, state.stopIndex + 1, 450);
            if (endIndex >= 0) {
                List<CustomRouteLeg> legs = new ArrayList<>(state.legs);
                legs.add(legFrom(pattern, state.stopIndex, endIndex));
                return legs;
            }
            if (state.transfers >= 2) continue;
            for (int alight = state.stopIndex + 1; alight < pattern.stops.size(); alight++) {
                Stop transferStop = pattern.stops.get(alight);
                if (transferStop.lat == 0 && transferStop.lon == 0) continue;
                int rideStops = alight - state.stopIndex;
                List<CustomRouteLeg> ridden = new ArrayList<>(state.legs);
                ridden.add(legFrom(pattern, state.stopIndex, alight));
                for (PatternStop next : nearbyPatternStops(grid, transferStop, 260)) {
                    if (next.patternIndex == state.patternIndex) continue;
                    RoutePattern nextPattern = patterns.get(next.patternIndex);
                    if (distanceMeters(nextPattern.stops.get(next.stopIndex), transferStop) > 260) continue;
                    if (next.stopIndex >= nextPattern.stops.size() - 1) continue;
                    queue.add(new PathState(next.patternIndex, next.stopIndex, state.transfers + 1, state.stops + rideStops, ridden));
                }
            }
        }
        return new ArrayList<>();
    }

    private List<RoutePattern> loadRoutePatterns() throws Exception {
        List<RoutePattern> patterns = new ArrayList<>();
        for (Route route : routes) {
            if ("MTR".equals(route.operator)) continue;
            addRoutePattern(patterns, route, "outbound");
            addRoutePattern(patterns, route, "inbound");
        }
        return patterns;
    }

    private void addRoutePattern(List<RoutePattern> patterns, Route route, String dir) {
        try {
            List<Stop> stops = cachedRouteStops(route, dir);
            if (stops.size() > 1) patterns.add(new RoutePattern(route, dir, stops));
        } catch (Exception ignored) {}
    }

    private List<Stop> cachedRouteStops(Route route, String dir) throws Exception {
        String key = route.operator + "|" + route.route + "|" + dir + "|" + route.serviceType;
        synchronized (routeStopCache) {
            if (routeStopCache.containsKey(key)) return routeStopCache.get(key);
        }
        String from = "inbound".equals(dir) ? route.dest : route.orig;
        String to = "inbound".equals(dir) ? route.orig : route.dest;
        Bookmark b = new Bookmark(route.operator, route.route, dir, route.serviceType, from, to, "Ungrouped");
        List<Stop> stops = api.routeStops(b);
        synchronized (routeStopCache) { routeStopCache.put(key, stops); }
        return stops;
    }

    private Map<String, List<PatternStop>> buildPatternGrid(List<RoutePattern> patterns) {
        Map<String, List<PatternStop>> grid = new HashMap<>();
        for (int p = 0; p < patterns.size(); p++) {
            List<Stop> stops = patterns.get(p).stops;
            for (int i = 0; i < stops.size(); i++) {
                Stop stop = stops.get(i);
                if (stop.lat == 0 && stop.lon == 0) continue;
                String key = gridKey(stop.lat, stop.lon);
                if (!grid.containsKey(key)) grid.put(key, new ArrayList<>());
                grid.get(key).add(new PatternStop(p, i));
            }
        }
        return grid;
    }

    private List<PatternStop> nearbyPatternStops(Map<String, List<PatternStop>> grid, Stop stop, double meters) {
        List<PatternStop> out = new ArrayList<>();
        if (stop.lat == 0 && stop.lon == 0) return out;
        int lat = gridBucket(stop.lat);
        int lon = gridBucket(stop.lon);
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                List<PatternStop> cell = grid.get((lat + dy) + ":" + (lon + dx));
                if (cell != null) out.addAll(cell);
            }
        }
        return out;
    }

    private int firstReachableIndex(RoutePattern pattern, Stop target, int startIndex, double meters) {
        for (int i = Math.max(0, startIndex); i < pattern.stops.size(); i++) {
            if (distanceMeters(pattern.stops.get(i), target) <= meters) return i;
        }
        return -1;
    }

    private CustomRouteLeg legFrom(RoutePattern pattern, int startIndex, int endIndex) {
        Stop start = pattern.stops.get(startIndex);
        Stop end = pattern.stops.get(endIndex);
        Route route = pattern.route;
        String from = "inbound".equals(pattern.dir) ? route.dest : route.orig;
        String to = "inbound".equals(pattern.dir) ? route.orig : route.dest;
        return new CustomRouteLeg(route.operator, route.route, pattern.dir, route.serviceType, from, to, start.id, start.name, start.seq, start.lat, start.lon, end.id, end.name, end.seq, end.lat, end.lon);
    }

    private static int gridBucket(double value) { return (int) Math.floor(value * 400); }
    private static String gridKey(double lat, double lon) { return gridBucket(lat) + ":" + gridBucket(lon); }
    private static double distanceMeters(Stop a, Stop b) {
        if (a.lat == 0 && a.lon == 0) return Double.MAX_VALUE;
        if (b.lat == 0 && b.lon == 0) return Double.MAX_VALUE;
        float[] out = new float[1];
        Location.distanceBetween(a.lat, a.lon, b.lat, b.lon, out);
        return out[0];
    }

    private void loadFirstEtaInto(CustomRouteLeg leg, TextView target) {
        io.execute(() -> {
            try {
                List<String> times = api.etas(leg.bookmark(), leg.startStop());
                String label = times.isEmpty() ? "No data" : times.get(0);
                runOnUiThread(() -> {
                    target.setText(label);
                    target.setTextColor(times.isEmpty() ? MUTED : GREEN);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    target.setText("No data");
                    target.setTextColor(MUTED);
                });
            }
        });
    }

    private void showCustomRouteMenu(CustomRoute customRoute) {
        showOptionSheet(customRoute.name, new String[]{"Rename", "Delete"}, (index, choice) -> {
            if ("Rename".equals(choice)) showRenameCustomRouteSheet(customRoute);
            else if ("Delete".equals(choice)) {
                deleteCustomRoute(customRoute);
                showRoutes();
            }
        });
    }

    private void showRenameCustomRouteSheet(CustomRoute customRoute) {
        showTextInputSheet("Rename Route", customRoute.name, "Route name", value -> {
            String name = value == null ? "" : value.trim();
            if (name.length() == 0) return;
            CustomRoute renamed = new CustomRoute(customRoute.id, name, customRoute.legs);
            saveCustomRoute(renamed);
            showRoutes();
        });
    }

    private void deleteCustomRoute(CustomRoute customRoute) {
        Set<String> set = new HashSet<>(prefs.getStringSet("customRoutes", new HashSet<>()));
        Set<String> next = new HashSet<>();
        for (String s : set) {
            CustomRoute existing = CustomRoute.parse(s);
            if (existing == null || !existing.id.equals(customRoute.id)) next.add(s);
        }
        prefs.edit().putStringSet("customRoutes", next).apply();
        expandedCustomRoutes.remove(customRoute.id);
    }

    private void saveCustomRoute(CustomRoute customRoute) {
        Set<String> set = new HashSet<>(prefs.getStringSet("customRoutes", new HashSet<>()));
        Set<String> next = new HashSet<>();
        for (String s : set) {
            CustomRoute existing = CustomRoute.parse(s);
            if (existing == null || !existing.id.equals(customRoute.id)) next.add(s);
        }
        next.add(customRoute.serialize());
        prefs.edit().putStringSet("customRoutes", next).apply();
    }

    private List<CustomRoute> getCustomRoutes() {
        List<CustomRoute> out = new ArrayList<>();
        for (String s : prefs.getStringSet("customRoutes", new HashSet<>())) {
            CustomRoute customRoute = CustomRoute.parse(s);
            if (customRoute != null) out.add(customRoute);
        }
        Collections.sort(out, Comparator.comparing(r -> r.name.toLowerCase(Locale.US)));
        return out;
    }

    private void showSearch() {
        updateGroupFab();
        content.removeAllViews();
        content.addView(pageTitle("Search"));
        content.addView(text("Find KMB, Citybus, and MTR Bus routes, then save either direction.", 15, MUTED, false));
        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("Route number or destination");
        search.setHintTextColor(MUTED);
        search.setTextColor(TEXT);
        search.setTextSize(18);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setPadding(dp(18), 0, dp(18), 0);
        search.setBackground(round(fieldSurface(), dp(24), outline()));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, dp(54));
        sp.setMargins(0, dp(18), 0, dp(12));
        content.addView(search, sp);

        ScrollView scroll = new ScrollView(this);
        applyCardScrollFade(scroll);
        LinearLayout results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(results);
        content.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        Runnable redraw = () -> renderSearchResults(results, search.getText().toString());
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            public void onTextChanged(CharSequence s, int st, int b, int c) { redraw.run(); }
            public void afterTextChanged(Editable e) {}
        });
        redraw.run();
    }

    private void renderSearchResults(LinearLayout results, String query) {
        results.removeAllViews();
        String q = query.trim().toLowerCase(Locale.US);
        if (routes.isEmpty()) {
            results.addView(centerStatus("Loading routes..."));
            return;
        }
        int count = 0;
        for (Route r : routes) {
            String hay = (r.route + " " + r.orig + " " + r.dest + " " + r.operator).toLowerCase(Locale.US);
            if (!q.isEmpty() && !hay.contains(q)) continue;
            results.addView(routeCard(r));
            if (++count >= 80) break;
        }
        if (count == 0) results.addView(centerStatus("No matching routes"));
    }

    private View routeCard(Route r) {
        LinearLayout box = card();
        TextView title = text(r.route + "  " + opName(r.operator), 24, TEXT, true);
        box.addView(title);
        box.addView(text(r.orig + "  ->  " + r.dest, 15, MUTED, false));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setPadding(0, dp(12), 0, 0);
        Button outbound = materialButton("Save Route in This Direction");
        outbound.setTextSize(13);
        outbound.setOnClickListener(v -> saveBookmark(new Bookmark(r.operator, r.route, "outbound", r.serviceType, r.orig, r.dest, "Ungrouped")));
        Button inbound = materialButton("Save Route in Opposite Direction");
        inbound.setTextSize(13);
        inbound.setOnClickListener(v -> saveBookmark(new Bookmark(r.operator, r.route, "inbound", r.serviceType, r.dest, r.orig, "Ungrouped")));
        actions.addView(outbound, new LinearLayout.LayoutParams(-1, dp(52)));
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(-1, dp(52));
        ip.setMargins(0, dp(8), 0, 0);
        actions.addView(inbound, ip);
        box.addView(actions);
        return box;
    }

    private void showBookmarks() {
        updateGroupFab();
        content.removeAllViews();
        content.addView(pageTitle("Bookmarks"));
        content.addView(text("Nearest stop is selected from your current GPS position.", 15, MUTED, false));
        content.addView(groupControls());

        ScrollView scroll = new ScrollView(this);
        applyCardScrollFade(scroll);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1);
        lp.setMargins(0, dp(14), 0, 0);
        content.addView(scroll, lp);

        List<Bookmark> marks = getBookmarks();
        if (marks.isEmpty()) {
            list.addView(centerStatus("Search for a route and save it here."));
            return;
        }

        List<Bookmark> visible = new ArrayList<>();
        for (Bookmark b : marks) {
            if ("All".equals(selectedGroup) || b.group.equals(selectedGroup)) visible.add(b);
        }
        if (visible.isEmpty()) {
            list.addView(centerStatus("No routes in " + selectedGroup));
            return;
        }

        previewEtaViews.clear();
        previewStopViews.clear();
        trackingButtons.clear();
        for (Bookmark b : visible) {
            LinearLayout box = card();
            box.setOnClickListener(v -> showRouteDetail(b));
            box.setOnLongClickListener(v -> {
                showBookmarkMenu(v, b);
                return true;
            });

            LinearLayout header = new LinearLayout(this);
            header.setGravity(Gravity.CENTER_VERTICAL);
            TextView title = text(b.route + "  " + opName(b.operator), 25, TEXT, true);
            header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
            Button tracking = trackingButton(b);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, dp(30));
            tp.setMargins(dp(8), 0, dp(8), 0);
            header.addView(tracking, tp);
            trackingButtons.put(b.storageKey(), tracking);
            header.addView(groupPill(b.group));
            box.addView(header);

            TextView direction = text(b.from + "  ->  " + b.to, 15, TEXT, false);
            direction.setPadding(dp(14), dp(10), dp(14), dp(10));
            direction.setBackground(round(fieldSurface(), dp(14), outline()));
            LinearLayout.LayoutParams dpv = new LinearLayout.LayoutParams(-1, -2);
            dpv.setMargins(0, dp(12), 0, dp(10));
            box.addView(direction, dpv);

            TextView nearest = text("Nearest stop loading", 13, MUTED, false);
            box.addView(nearest);

            LinearLayout etaRow = etaBoxRow(null);
            LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(-1, -2);
            ep.setMargins(0, dp(8), 0, 0);
            box.addView(etaRow, ep);
            previewStopViews.put(b.storageKey(), nearest);
            previewEtaViews.put(b.storageKey(), etaRow);
            list.addView(box);
            loadPreview(b);
        }
    }
    private void showRouteDetail(Bookmark b) {
        navIsland.setVisibility(View.GONE);
        if (topMenu != null) topMenu.setVisibility(View.GONE);
        if (groupFab != null) groupFab.setVisibility(View.GONE);
        content.removeAllViews();
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button back = pill("Back");
        back.setOnClickListener(v -> {
            navIsland.setVisibility(View.VISIBLE);
            if (topMenu != null) topMenu.setVisibility(View.VISIBLE);
            showBookmarks();
        });
        header.addView(back, new LinearLayout.LayoutParams(dp(86), dp(44)));
        TextView title = text("  " + b.route + " to " + b.to, 24, TEXT, true);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        content.addView(header);

        ScrollView scroll = new ScrollView(this);
        LinearLayout stops = new LinearLayout(this);
        stops.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(stops);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1);
        lp.setMargins(0, dp(18), 0, 0);
        content.addView(scroll, lp);
        stops.addView(centerStatus("Loading bus stops..."));
        io.execute(() -> {
            try {
                updateLocation();
                List<Stop> routeStops = api.routeStops(b);
                Stop nearestStop = nearest(routeStops);
                runOnUiThread(() -> {
                    stops.removeAllViews();
                    final View[] nearestCard = new View[1];
                    for (Stop s : routeStops) {
                        boolean isNearest = nearestStop != null && nearestStop.id.equals(s.id);
                        LinearLayout box = card();
                        if (isNearest) box.setBackground(round(surface(), dp(28), BLUE));
                        LinearLayout stopHeader = new LinearLayout(this);
                        stopHeader.setGravity(Gravity.CENTER_VERTICAL);
                        stopHeader.addView(text(s.seq + ". " + s.name, 18, TEXT, true), new LinearLayout.LayoutParams(0, -2, 1));
                        if (isNearest) stopHeader.addView(nearestPill());
                        box.addView(stopHeader);
                        LinearLayout etaRow = etaBoxRow(null);
                        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(-1, -2);
                        ep.setMargins(0, dp(10), 0, 0);
                        box.addView(etaRow, ep);
                        stops.addView(box);
                        loadEtaRowInto(b, s, etaRow);
                        if (isNearest) nearestCard[0] = box;
                    }
                    if (nearestCard[0] != null) {
                        scroll.post(() -> {
                            int target = nearestCard[0].getTop() - Math.max(0, (scroll.getHeight() - nearestCard[0].getHeight()) / 2);
                            scroll.smoothScrollTo(0, Math.max(0, target));
                        });
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    stops.removeAllViews();
                    stops.addView(centerStatus("Could not load stops: " + e.getMessage()));
                });
            }
        });
    }


    private TextView nearestPill() {
        TextView pill = text("Nearest", 12, Color.WHITE, true);
        pill.setGravity(Gravity.CENTER);
        pill.setPadding(dp(12), dp(5), dp(12), dp(5));
        pill.setBackground(round(BLUE, dp(16), Color.TRANSPARENT));
        return pill;
    }

    private void loadEtaRowInto(Bookmark b, Stop s, LinearLayout row) {
        io.execute(() -> {
            try {
                List<String> times = api.etas(b, s);
                runOnUiThread(() -> renderEtaBoxes(row, times));
            } catch (Exception e) {
                runOnUiThread(() -> renderEtaBoxes(row, new ArrayList<>()));
            }
        });
    }
    private void loadRoutes() {
        io.execute(() -> {
            try {
                List<Route> loaded = api.routes();
                Collections.sort(loaded, Comparator.comparing((Route r) -> r.route.length()).thenComparing(r -> r.route));
                runOnUiThread(() -> {
                    routes.clear();
                    routes.addAll(loaded);
                    if (tab == 2) showSearch();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    content.addView(centerStatus("Route API failed: " + e.getMessage()));
                });
            }
        });
    }

    private void loadPreview(Bookmark b) {
        io.execute(() -> {
            try {
                updateLocation();
                List<Stop> stops = api.routeStops(b);
                Stop nearest = nearest(stops);
                List<String> times = api.etas(b, nearest);
                runOnUiThread(() -> {
                    TextView stop = previewStopViews.get(b.storageKey());
                    LinearLayout eta = previewEtaViews.get(b.storageKey());
                    if (stop != null) stop.setText(nearest.name);
                    if (eta != null) renderEtaBoxes(eta, times);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    TextView stop = previewStopViews.get(b.storageKey());
                    LinearLayout eta = previewEtaViews.get(b.storageKey());
                    if (stop != null) stop.setText("Arrival preview unavailable");
                    if (eta != null) renderEtaBoxes(eta, new ArrayList<>());
                });
            }
        });
    }
    private void loadEtaInto(Bookmark b, Stop s, TextView target, boolean strong) {
        io.execute(() -> {
            try {
                List<String> times = api.etas(b, s);
                runOnUiThread(() -> {
                    target.setText(joinTimes(times));
                    target.setTextColor(strong ? GREEN : MUTED);
                });
            } catch (Exception e) {
                runOnUiThread(() -> target.setText("No live arrival data"));
            }
        });
    }

    private Stop nearest(List<Stop> stops) {
        if (lastLocation == null || stops.isEmpty()) return stops.get(0);
        Stop best = stops.get(0);
        double bestMeters = Double.MAX_VALUE;
        for (Stop s : stops) {
            float[] out = new float[1];
            Location.distanceBetween(lastLocation.getLatitude(), lastLocation.getLongitude(), s.lat, s.lon, out);
            if (out[0] < bestMeters) {
                bestMeters = out[0];
                best = s;
            }
        }
        return best;
    }

    private String joinTimes(List<String> times) {
        if (times.isEmpty()) return "No upcoming arrivals";
        return String.join("   ", times);
    }

    private void maybeAskLocation() {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 41);
        } else {
            updateLocation();
        }
    }

    private void updateLocation() {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        Location gps = null;
        Location net = null;
        try { gps = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER); } catch (Exception ignored) {}
        try { net = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER); } catch (Exception ignored) {}
        if (gps != null && net != null) lastLocation = gps.getTime() > net.getTime() ? gps : net;
        else lastLocation = gps != null ? gps : net;
    }


    private View groupControls() {
        LinearLayout rootRow = new LinearLayout(this);
        rootRow.setGravity(Gravity.CENTER_VERTICAL);
        rootRow.setPadding(0, dp(14), 0, 0);

        rootRow.addView(groupChip("All", false));

        View divider = new View(this);
        divider.setBackgroundColor(outline());
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(dp(1), dp(34));
        dividerParams.setMargins(dp(2), 0, dp(10), 0);
        rootRow.addView(divider, dividerParams);

        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        for (String group : getVisibleMovableGroups()) {
            row.addView(groupOrderMode ? reorderGroupControl(group) : groupChip(group, true));
        }
        scroll.addView(row);
        rootRow.addView(scroll, new LinearLayout.LayoutParams(0, dp(48), 1));

        if (groupOrderMode) {
            Button done = materialButton("Done");
            done.setOnClickListener(v -> {
                groupOrderMode = false;
                showBookmarks();
            });
            LinearLayout.LayoutParams dpv = new LinearLayout.LayoutParams(dp(84), dp(42));
            dpv.setMargins(dp(8), 0, 0, 0);
            rootRow.addView(done, dpv);
        }
        return rootRow;
    }

    private TextView groupChip(String label, boolean movable) {
        boolean selected = selectedGroup.equals(label);
        TextView chip = text(label, 14, selected ? TEXT : MUTED, true);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(16), 0, dp(16), 0);
        chip.setSingleLine(true);
        int fill = selected ? getGroupColor(label) : elevatedSurface();
        chip.setBackground(round(fill, dp(21), selected ? Color.TRANSPARENT : outline()));
        chip.setOnClickListener(v -> {
            selectedGroup = label;
            showBookmarks();
        });
        chip.setOnLongClickListener(v -> {
            showGroupMenu(v, label);
            return true;
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(42));
        lp.setMargins(0, 0, dp(8), 0);
        chip.setLayoutParams(lp);
        return chip;
    }

    private TextView reorderGroupControl(String group) {
        TextView chip = groupChip(group, true);
        chip.setText(group);
        chip.setTag(group);
        chip.setAlpha(0.96f);
        chip.setBackground(round(getGroupColor(group), dp(21), outline()));
        chip.setOnClickListener(v -> {
            selectedGroup = group;
            showBookmarks();
        });
        chip.setOnLongClickListener(v -> {
            ClipData data = ClipData.newPlainText("group", group);
            View.DragShadowBuilder shadow = new View.DragShadowBuilder(v);
            if (Build.VERSION.SDK_INT >= 24) v.startDragAndDrop(data, shadow, group, 0);
            else v.startDrag(data, shadow, group, 0);
            v.setAlpha(0.45f);
            return true;
        });
        chip.setOnDragListener((v, event) -> {
            String target = (String) v.getTag();
            Object state = event.getLocalState();
            if (!(state instanceof String) || target == null) return true;
            String dragged = (String) state;
            switch (event.getAction()) {
                case DragEvent.ACTION_DRAG_ENTERED:
                    v.setBackground(round(blend(getGroupColor(target), Color.WHITE, 0.18f), dp(21), TEXT));
                    return true;
                case DragEvent.ACTION_DRAG_EXITED:
                    v.setBackground(round(getGroupColor(target), dp(21), outline()));
                    return true;
                case DragEvent.ACTION_DROP:
                    moveGroupNear(dragged, target, event.getX() > v.getWidth() / 2f);
                    return true;
                case DragEvent.ACTION_DRAG_ENDED:
                    v.setAlpha(0.96f);
                    v.setBackground(round(getGroupColor(target), dp(21), outline()));
                    return true;
                default:
                    return true;
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(42));
        lp.setMargins(0, 0, dp(8), 0);
        chip.setLayoutParams(lp);
        return chip;
    }
    private void showCreateGroupDialog(Bookmark moveAfterCreate) {
        showTextInputSheet("New Group", "", "Group name", value -> {
            String group = cleanGroup(value);
            addGroup(group);
            selectedGroup = group;
            if (moveAfterCreate != null) moveBookmark(moveAfterCreate, group);
            else showBookmarks();
        });
    }
    private void showMoveDialog(Bookmark b) {
        List<String> choices = new ArrayList<>();
        choices.add("Ungrouped");
        choices.addAll(getGroups());
        choices.add("+ New Group");
        showOptionSheet("Move " + b.route, choices.toArray(new String[0]), (index, choice) -> {
            if ("+ New Group".equals(choice)) showCreateGroupDialog(b);
            else moveBookmark(b, choice);
        });
    }
    private void deleteSelectedGroup() {
        String removed = selectedGroup;
        Set<String> groups = new HashSet<>(prefs.getStringSet("groups", new HashSet<>()));
        groups.remove(removed);
        Set<String> moved = new HashSet<>();
        for (Bookmark b : getBookmarks()) {
            moved.add((b.group.equals(removed) ? b.withGroup("Ungrouped") : b).serialize());
        }
        selectedGroup = "All";
        prefs.edit().putStringSet("groups", groups).putStringSet("bookmarks", moved).apply();
        showBookmarks();
    }

    private void moveBookmark(Bookmark b, String group) {
        group = cleanGroup(group);
        if (!"Ungrouped".equals(group)) addGroup(group);
        Set<String> set = new HashSet<>(prefs.getStringSet("bookmarks", new HashSet<>()));
        set.remove(b.serialize());
        set.add(b.withGroup(group).serialize());
        selectedGroup = group;
        prefs.edit().putStringSet("bookmarks", set).apply();
        showBookmarks();
    }


    private List<String> getVisibleMovableGroups() {
        List<String> groups = new ArrayList<>();
        boolean hasUngrouped = hasGroupCards("Ungrouped");
        for (String group : getMovableGroups()) {
            if (!"Ungrouped".equals(group) || hasUngrouped) groups.add(group);
        }
        if (selectedGroup.equals("Ungrouped") && !hasUngrouped) selectedGroup = "All";
        return groups;
    }

    private boolean hasGroupCards(String group) {
        for (Bookmark b : getBookmarks()) {
            if (b.group.equals(group)) return true;
        }
        return false;
    }
    private List<String> getGroups() {
        List<String> groups = new ArrayList<>();
        for (String group : getMovableGroups()) {
            if (!"Ungrouped".equals(group)) groups.add(group);
        }
        return groups;
    }

    private List<String> getMovableGroups() {
        Set<String> custom = getCustomGroupSet();
        List<String> ordered = new ArrayList<>();
        ordered.add("Ungrouped");
        String stored = prefs.getString("groupOrder", "");
        if (stored.length() > 0) {
            for (String raw : stored.split("\\|", -1)) {
                String group = cleanGroup(raw);
                if ("Ungrouped".equals(group) || custom.contains(group)) {
                    if (!ordered.contains(group)) ordered.add(group);
                }
            }
        }
        List<String> missing = new ArrayList<>(custom);
        Collections.sort(missing, String::compareToIgnoreCase);
        for (String group : missing) {
            if (!ordered.contains(group)) ordered.add(group);
        }
        saveGroupOrder(ordered);
        return ordered;
    }

    private Set<String> getCustomGroupSet() {
        Set<String> custom = new HashSet<>();
        for (String group : prefs.getStringSet("groups", new HashSet<>())) {
            group = cleanGroup(group);
            if (!isSystemGroup(group)) custom.add(group);
        }
        return custom;
    }

    private void saveGroupOrder(List<String> groups) {
        prefs.edit().putString("groupOrder", String.join("|", groups)).apply();
    }

    private void moveGroupOrder(String group, int delta) {
        List<String> groups = getMovableGroups();
        int index = groups.indexOf(group);
        int target = index + delta;
        if (index < 0 || target < 0 || target >= groups.size()) return;
        Collections.swap(groups, index, target);
        saveGroupOrder(groups);
        showBookmarks();
    }

    private void moveGroupBefore(String dragged, String target) {
        moveGroupNear(dragged, target, false);
    }

    private void moveGroupNear(String dragged, String target, boolean after) {
        if (dragged == null || target == null || dragged.equals(target)) return;
        List<String> groups = getMovableGroups();
        if (!groups.remove(dragged)) return;
        int targetIndex = groups.indexOf(target);
        if (targetIndex < 0) groups.add(dragged);
        else groups.add(Math.min(groups.size(), targetIndex + (after ? 1 : 0)), dragged);
        saveGroupOrder(groups);
        showBookmarks();
    }    private void addGroup(String group) {
        group = cleanGroup(group);
        if (isSystemGroup(group)) return;
        Set<String> groups = new HashSet<>(prefs.getStringSet("groups", new HashSet<>()));
        boolean added = groups.add(group);
        SharedPreferences.Editor edit = prefs.edit().putStringSet("groups", groups);
        if (added) {
            List<String> order = getMovableGroups();
            if (!order.contains(group)) order.add(group);
            edit.putString("groupOrder", String.join("|", order));
        }
        edit.apply();
    }
    private static boolean isSystemGroup(String group) {
        return "All".equals(group) || "Ungrouped".equals(group);
    }

    private static String cleanGroup(String group) {
        String cleaned = group == null ? "" : group.trim().replace("|", " ");
        if (cleaned.length() == 0) return "Ungrouped";
        return cleaned.length() > 30 ? cleaned.substring(0, 30) : cleaned;
    }

    private LinearLayout etaBoxRow(List<String> times) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        renderEtaBoxes(row, times);
        return row;
    }

    private void renderEtaBoxes(LinearLayout row, List<String> times) {
        row.removeAllViews();
        List<String> safe = times == null ? new ArrayList<>() : times;
        for (int i = 0; i < 3; i++) {
            String label = i < safe.size() ? safe.get(i) : (safe.isEmpty() && i == 0 ? "No data" : "--");
            TextView box = text(label, 15, i < safe.size() ? GREEN : MUTED, true);
            box.setGravity(Gravity.CENTER);
            box.setPadding(dp(8), dp(10), dp(8), dp(10));
            box.setSingleLine(true);
            box.setBackground(round(fieldSurface(), dp(14), outline()));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            if (i > 0) lp.setMargins(dp(8), 0, 0, 0);
            row.addView(box, lp);
        }
    }

    private TextView groupPill(String group) {
        TextView pill = text(group, 13, TEXT, true);
        pill.setGravity(Gravity.CENTER);
        pill.setPadding(dp(12), dp(6), dp(12), dp(6));
        pill.setSingleLine(true);
        pill.setBackground(round(getGroupColor(group), dp(18), Color.TRANSPARENT));
        return pill;
    }

    private void showBookmarkMenu(View anchor, Bookmark b) {
        List<String> items = new ArrayList<>();
        items.add("Move");
        if (!isSystemGroup(b.group)) items.add("Rename Group");
        items.add("Group Color");
        items.add("Delete");
        showOptionSheet(b.route, items.toArray(new String[0]), (index, choice) -> {
            if ("Move".equals(choice)) showMoveDialog(b);
            else if ("Rename Group".equals(choice)) showRenameGroupDialog(b.group);
            else if ("Group Color".equals(choice)) showGroupColorDialog(b.group);
            else if ("Delete".equals(choice)) {
                removeBookmark(b);
                showBookmarks();
            }
        });
    }
    private void showGroupMenu(View anchor, String group) {
        List<String> items = new ArrayList<>();
        if (!"All".equals(group)) items.add("Move");
        if (!isSystemGroup(group)) items.add("Rename");
        items.add("Group Color");
        if (!isSystemGroup(group)) items.add("Delete");
        showOptionSheet(group, items.toArray(new String[0]), (index, choice) -> {
            if ("Move".equals(choice)) {
                groupOrderMode = true;
                showBookmarks();
            } else if ("Rename".equals(choice)) showRenameGroupDialog(group);
            else if ("Group Color".equals(choice)) showGroupColorDialog(group);
            else if ("Delete".equals(choice)) deleteGroup(group);
        });
    }
    private void showRenameGroupDialog(String oldGroup) {
        showTextInputSheet("Rename Group", oldGroup, "Group name", value -> renameGroup(oldGroup, value));
    }
    private void renameGroup(String oldGroup, String newGroupRaw) {
        String newGroup = cleanGroup(newGroupRaw);
        if (isSystemGroup(oldGroup) || isSystemGroup(newGroup) || oldGroup.equals(newGroup)) return;
        Set<String> groups = new HashSet<>(prefs.getStringSet("groups", new HashSet<>()));
        groups.remove(oldGroup);
        groups.add(newGroup);
        Set<String> moved = new HashSet<>();
        for (Bookmark b : getBookmarks()) {
            moved.add((b.group.equals(oldGroup) ? b.withGroup(newGroup) : b).serialize());
        }
        int oldColor = getGroupColor(oldGroup);
        SharedPreferences.Editor edit = prefs.edit().putStringSet("groups", groups).putStringSet("bookmarks", moved).remove("groupColor:" + oldGroup);
        edit.putInt("groupColor:" + newGroup, oldColor).apply();
        if (selectedGroup.equals(oldGroup)) selectedGroup = newGroup;
        showBookmarks();
    }

    private void showGroupColorDialog(String group) {
        String[] names = {"Blue", "Teal", "Green", "Orange", "Pink", "Purple", "Gray"};
        int[] colors = {BLUE, Color.rgb(64, 200, 224), Color.rgb(48, 209, 88), Color.rgb(255, 159, 10), Color.rgb(255, 55, 95), Color.rgb(191, 90, 242), Color.rgb(99, 110, 128)};
        showOptionSheet("Group Color", names, (index, choice) -> {
            setGroupColor(group, colors[index]);
            showBookmarks();
        });
    }
    private void deleteGroup(String group) {
        if (isSystemGroup(group)) return;
        Set<String> groups = new HashSet<>(prefs.getStringSet("groups", new HashSet<>()));
        groups.remove(group);
        Set<String> moved = new HashSet<>();
        for (Bookmark b : getBookmarks()) {
            moved.add((b.group.equals(group) ? b.withGroup("Ungrouped") : b).serialize());
        }
        if (selectedGroup.equals(group)) selectedGroup = "All";
        prefs.edit().putStringSet("groups", groups).putStringSet("bookmarks", moved).remove("groupColor:" + group).apply();
        showBookmarks();
    }

    private void setGroupColor(String group, int color) {
        prefs.edit().putInt("groupColor:" + cleanGroup(group), color).apply();
    }

    private int getGroupColor(String group) {
        if ("All".equals(group)) return BLUE;
        return prefs.getInt("groupColor:" + cleanGroup(group), "Ungrouped".equals(group) ? Color.rgb(99, 110, 128) : BLUE);
    }

    interface OptionHandler { void onSelect(int index, String label); }
    interface RouteOptionHandler { void onSelect(Route route); }
    interface StopSelectHandler { void onSelect(Stop stop); }
    interface TextHandler { void onText(String value); }

    private void loadThemeColor() {
        if (prefs.getBoolean("themeSystem", true) && Build.VERSION.SDK_INT >= 31) {
            BLUE = getColor(android.R.color.system_accent1_600);
        } else {
            BLUE = prefs.getInt("themeColor", Color.rgb(10, 132, 255));
        }
    }

    private void applyThemeSurfaces() {
        if (root != null) root.setBackgroundColor(appBackground());
        if (navIsland != null) navIsland.setBackground(round(navSurface(), dp(0), Color.TRANSPARENT));
        if (topMenu != null) {
            topMenu.setColorFilter(buttonText());
            topMenu.setBackground(round(floatingSurface(), dp(22), Color.TRANSPARENT));
        }
        if (groupFab != null) {
            groupFab.setColorFilter(buttonText());
            groupFab.setBackground(round(floatingSurface(), dp(22), Color.TRANSPARENT));
        }
    }

    private int appBackground() { return blend(BG, BLUE, 0.16f); }
    private int surface() { return blend(Color.rgb(18, 21, 30), BLUE, 0.13f); }
    private int elevatedSurface() { return blend(Color.rgb(27, 31, 42), BLUE, 0.17f); }
    private int fieldSurface() { return blend(Color.rgb(14, 18, 27), BLUE, 0.12f); }
    private int sheetSurface() { return blend(Color.rgb(24, 28, 38), BLUE, 0.18f); }
    private int outline() { return blend(STROKE, BLUE, 0.28f); }
    private int navSurface() { return blend(Color.rgb(16, 20, 29), BLUE, 0.36f); }
    private int navSelectedSurface() { return blend(Color.rgb(210, 220, 244), BLUE, 0.30f); }
    private int floatingSurface() { return blend(Color.rgb(226, 232, 242), BLUE, 0.20f); }
    private int buttonText() { return Color.rgb(17, 22, 30); }
    private int navText() { return Color.WHITE; }
    private int menuButtonSurface() { return blend(Color.rgb(226, 232, 242), BLUE, 0.22f); }
    private int navMutedText() { return blend(Color.rgb(170, 190, 218), BLUE, 0.42f); }

    private TextView iconSymbolButton(String symbol, int sp) {
        TextView button = text(symbol, sp, navText(), true);
        button.setGravity(Gravity.CENTER);
        button.setIncludeFontPadding(false);
        button.setPadding(0, 0, 0, 0);
        return button;
    }



    private void applyCardScrollFade(ScrollView scroll) {
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(dp(34));
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        if (Build.VERSION.SDK_INT >= 23) {
            scroll.setOnScrollChangeListener((v, sx, sy, ox, oy) -> {
                if (sy > oy) setFabForScroll(false);
                else if (sy < oy) setFabForScroll(true);
            });
        }
    }

    private void setFabForScroll(boolean visible) {
        if (groupFab == null || groupFab.getVisibility() == View.GONE || !groupFab.isEnabled()) return;
        groupFab.animate().cancel();
        groupFab.animate()
                .alpha(visible ? 1f : 0f)
                .translationY(visible ? 0 : dp(22))
                .setDuration(140)
                .withStartAction(() -> {
                    if (visible) groupFab.setVisibility(View.VISIBLE);
                })
                .withEndAction(() -> {
                    if (!visible) groupFab.setVisibility(View.INVISIBLE);
                })
                .start();
    }
    private ImageButton themedImageButton(int iconRes, int fill, int iconColor, int stroke, int radius) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(iconRes);
        b.setColorFilter(iconColor);
        b.setScaleType(ImageView.ScaleType.CENTER);
        b.setPadding(dp(10), dp(10), dp(10), dp(10));
        b.setBackground(round(fill, radius, stroke));
        if (Build.VERSION.SDK_INT >= 21) b.setStateListAnimator(null);
        return b;
    }

    private Button trackingButton(Bookmark bookmark) {
        Button b = materialButton("Track");
        b.setTextSize(12);
        b.setPadding(dp(14), 0, dp(14), 0);
        b.setOnClickListener(v -> {
            if (isTracking(bookmark)) stopTracking();
            else startTracking(bookmark);
        });
        updateTrackingButton(b, bookmark);
        return b;
    }

    private void updateTrackingButtons() {
        for (Map.Entry<String, Button> entry : trackingButtons.entrySet()) {
            Bookmark bookmark = Bookmark.parse(entry.getKey());
            if (bookmark != null) updateTrackingButton(entry.getValue(), bookmark);
        }
    }

    private void updateTrackingButton(Button button, Bookmark bookmark) {
        boolean tracking = isTracking(bookmark);
        button.setText(tracking ? "Cancel" : "Track");
        button.setTextColor(tracking ? Color.WHITE : buttonText());
        button.setBackground(round(tracking ? BLUE : menuButtonSurface(), dp(16), Color.TRANSPARENT));
        button.setContentDescription(tracking ? "Stop live tracking" : "Start live tracking");
    }

    private boolean isTracking(Bookmark bookmark) {
        return bookmark != null && bookmark.storageKey().equals(prefs.getString(BusTrackingService.PREF_TRACKING_KEY, ""));
    }

    private void startTracking(Bookmark bookmark) {
        if (!ensureNotificationPermission()) return;
        Intent intent = new Intent(this, BusTrackingService.class)
                .setAction(BusTrackingService.ACTION_START)
                .putExtra(BusTrackingService.EXTRA_BOOKMARK, bookmark.serialize());
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
        else startService(intent);
        prefs.edit().putString(BusTrackingService.PREF_TRACKING_KEY, bookmark.storageKey()).apply();
        updateTrackingButtons();
    }

    private void stopTracking() {
        Intent intent = new Intent(this, BusTrackingService.class).setAction(BusTrackingService.ACTION_CANCEL);
        startService(intent);
        prefs.edit().remove(BusTrackingService.PREF_TRACKING_KEY).apply();
        updateTrackingButtons();
    }

    private boolean ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 42);
            return false;
        }
        if (Build.VERSION.SDK_INT >= 36) {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null && !manager.canPostPromotedNotifications()) {
                showPromotedNotificationsSheet();
                return false;
            }
        }
        return true;
    }

    private void showPromotedNotificationsSheet() {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.addView(text("Enable Live Updates for HK Bus so tracking can appear as an Android 16 live update instead of a regular notification.", 16, MUTED, false));
        Button open = sheetButton("Open Live Update Settings");
        open.setOnClickListener(v -> {
            dismissSheet();
            try {
                Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
                startActivity(intent);
            } catch (Exception e) {
                Intent fallback = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
                startActivity(fallback);
            }
        });
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(-1, dp(56));
        op.setMargins(0, dp(16), 0, 0);
        body.addView(open, op);
        showBottomSheet("Enable Live Updates", body);
    }
    private void showCurrentTab() {
        if (navIsland != null) navIsland.setVisibility(View.VISIBLE);
        if (topMenu != null) topMenu.setVisibility(View.VISIBLE);
        applyThemeSurfaces();
        renderNav();
        updateGroupFab();
        if (tab == 0) showBookmarks();
        else if (tab == 1) showRoutes();
        else showSearch();
    }


    private void updateGroupFab() {
        boolean detailOpen = navIsland != null && navIsland.getVisibility() != View.VISIBLE;
        if (groupFab != null) {
            boolean visible = (tab == 0 || tab == 1) && !groupOrderMode && !detailOpen;
            groupFab.setVisibility(visible ? View.VISIBLE : View.GONE);
            groupFab.setAlpha(visible ? 1f : 0f);
            groupFab.setTranslationY(0);
            groupFab.setEnabled(visible);
        }
    }
    private TextView pageTitle(String label) {
        TextView title = text(label, 34, TEXT, true);
        title.setPadding(0, 0, dp(72), dp(8));
        return title;
    }

    private void showMainMenuSheet() {
        showOptionSheet("Menu", new String[]{"Theme", "Languages", "About"}, (index, label) -> {
            if (index == 0) showThemeSheet();
            else if (index == 2) showAboutSheet();
            else showInfoSheet(label, label + " will be available later.");
        });
    }

    private void showThemeSheet() {
        String[] names = {"Follow System", "Blue", "Teal", "Green", "Orange", "Pink", "Purple"};
        int[] colors = {BLUE, Color.rgb(10, 132, 255), Color.rgb(64, 200, 224), Color.rgb(48, 209, 88), Color.rgb(255, 159, 10), Color.rgb(255, 55, 95), Color.rgb(191, 90, 242)};
        showOptionSheet("Theme", names, (index, label) -> {
            if (index == 0) prefs.edit().putBoolean("themeSystem", true).apply();
            else prefs.edit().putBoolean("themeSystem", false).putInt("themeColor", colors[index]).apply();
            loadThemeColor();
            showCurrentTab();
        });
    }


    private void showAboutSheet() {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        TextView version = text("Current version: " + currentVersionName(), 16, TEXT, true);
        body.addView(version);
        TextView status = text("", 14, MUTED, false);
        Button check = sheetButton("Check for new release");
        check.setOnClickListener(v -> checkForRelease(status, check));
        body.addView(check, new LinearLayout.LayoutParams(-1, dp(56)));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.setMargins(0, dp(12), 0, 0);
        body.addView(status, sp);
        showBottomSheet("About", body);
    }

    private String currentVersionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "unknown";
        }
    }

    private int currentVersionCode() {
        try {
            if (Build.VERSION.SDK_INT >= 28) return (int) getPackageManager().getPackageInfo(getPackageName(), 0).getLongVersionCode();
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    private void checkForRelease(TextView status, Button check) {
        check.setEnabled(false);
        status.setText("Checking GitHub releases...");
        io.execute(() -> {
            try {
                JSONObject release = new JSONObject(httpGet(LATEST_RELEASE_URL));
                String tag = release.optString("tag_name", release.optString("name", ""));
                int latestCode = release.optInt("version_code", 0);
                String apkUrl = null;
                String apkName = null;
                JSONArray assets = release.optJSONArray("assets");
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject asset = assets.getJSONObject(i);
                        String name = asset.optString("name", "");
                        if (name.toLowerCase(Locale.US).endsWith(".apk")) {
                            apkName = name;
                            apkUrl = asset.optString("browser_download_url", null);
                            break;
                        }
                    }
                }
                boolean newer = isNewerVersion(tag, currentVersionName()) || latestCode > currentVersionCode();
                String finalApkUrl = apkUrl;
                String finalApkName = apkName == null ? "hk-bus-arrivals-update.apk" : apkName;
                String finalTag = tag.length() == 0 ? "latest release" : tag;
                runOnUiThread(() -> {
                    check.setEnabled(true);
                    if (finalApkUrl == null || finalApkUrl.length() == 0) {
                        status.setText("Latest GitHub release has no APK asset.");
                    } else if (!newer) {
                        status.setText("You are already on the latest release: " + currentVersionName());
                    } else {
                        status.setText("New release found: " + finalTag);
                        showUpdateDownloadSheet(finalTag, finalApkName, finalApkUrl);
                    }
                });
            } catch (HttpStatusException e) {
                runOnUiThread(() -> {
                    check.setEnabled(true);
                    if (e.code == 404) status.setText("No GitHub releases have been published yet.");
                    else status.setText("Could not check releases. HTTP " + e.code);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    check.setEnabled(true);
                    status.setText("Could not check releases. " + e.getMessage());
                });
            }
        });
    }

    private void showUpdateDownloadSheet(String tag, String apkName, String apkUrl) {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.addView(text("Release: " + tag, 16, TEXT, true));
        TextView file = text(apkName, 14, MUTED, false);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, -2);
        fp.setMargins(0, dp(8), 0, dp(14));
        body.addView(file, fp);
        TextView status = text("", 14, MUTED, false);
        Button download = sheetButton("Download and install");
        download.setOnClickListener(v -> downloadAndInstallApk(apkUrl, apkName, status));
        body.addView(download, new LinearLayout.LayoutParams(-1, dp(56)));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.setMargins(0, dp(12), 0, 0);
        body.addView(status, sp);
        showBottomSheet("Update Available", body);
    }

    private void downloadAndInstallApk(String apkUrl, String apkName, TextView status) {
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            status.setText("Allow HK Bus to install unknown apps, then try again.");
            Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()));
            startActivity(settings);
            return;
        }
        status.setText("Downloading update...");
        io.execute(() -> {
            try {
                DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(apkUrl));
                request.setTitle("HK Bus update");
                request.setDescription(apkName);
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, apkName);
                long id = dm.enqueue(request);
                Uri apkUri = waitForDownload(dm, id);
                runOnUiThread(() -> {
                    status.setText("Opening installer...");
                    installApk(apkUri);
                });
            } catch (Exception e) {
                runOnUiThread(() -> status.setText("Download failed. " + e.getMessage()));
            }
        });
    }

    private Uri waitForDownload(DownloadManager dm, long id) throws Exception {
        DownloadManager.Query query = new DownloadManager.Query().setFilterById(id);
        long deadline = System.currentTimeMillis() + 180000;
        while (System.currentTimeMillis() < deadline) {
            Cursor cursor = dm.query(query);
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    if (status == DownloadManager.STATUS_SUCCESSFUL) return dm.getUriForDownloadedFile(id);
                    if (status == DownloadManager.STATUS_FAILED) throw new RuntimeException("Android download manager failed.");
                }
            } finally {
                if (cursor != null) cursor.close();
            }
            Thread.sleep(1200);
        }
        throw new RuntimeException("Timed out waiting for download.");
    }

    private void installApk(Uri apkUri) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    private boolean isNewerVersion(String latest, String current) {
        int[] a = parseVersion(latest);
        int[] b = parseVersion(current);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int av = i < a.length ? a[i] : 0;
            int bv = i < b.length ? b[i] : 0;
            if (av != bv) return av > bv;
        }
        return false;
    }

    private int[] parseVersion(String value) {
        String cleaned = value == null ? "" : value.replaceFirst("^[vV]", "").replaceAll("[^0-9.].*$", "");
        if (cleaned.length() == 0) return new int[]{0};
        String[] parts = cleaned.split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try { out[i] = Integer.parseInt(parts[i]); } catch (Exception e) { out[i] = 0; }
        }
        return out;
    }

    private String httpGet(String u) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(12000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("User-Agent", "HK-Bus-Android");
        int code = c.getResponseCode();
        java.io.InputStream stream = code >= 400 ? c.getErrorStream() : c.getInputStream();
        StringBuilder sb = new StringBuilder();
        if (stream != null) {
            BufferedReader r = new BufferedReader(new InputStreamReader(stream));
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        }
        if (code >= 400) throw new HttpStatusException(code, sb.toString());
        return sb.toString();
    }
    private void showInfoSheet(String title, String message) {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.addView(text(message, 16, MUTED, false));
        showBottomSheet(title, body);
    }

    private void showOptionSheet(String title, String[] options, OptionHandler handler) {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < options.length; i++) {
            final int index = i;
            Button option = sheetButton(options[i]);
            option.setOnClickListener(v -> {
                dismissSheet();
                handler.onSelect(index, options[index]);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(56));
            lp.setMargins(0, 0, 0, dp(8));
            body.addView(option, lp);
        }
        if (options.length > 7) {
            ScrollView scroll = new ScrollView(this);
            scroll.setFillViewport(false);
            scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
            scroll.addView(body);
            showBottomSheet(title, scroll);
        } else {
            showBottomSheet(title, body);
        }
    }



    private void showLoadingSheet(String title, String message) {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        TextView status = text(message, 16, MUTED, false);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(18), dp(22), dp(18), dp(22));
        body.addView(status, new LinearLayout.LayoutParams(-1, -2));
        showBottomSheet(title, body);
    }

    private void showStopPickerSheet(String title, List<Stop> stops, OptionHandler handler) {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("Search stop");
        search.setHintTextColor(MUTED);
        search.setTextColor(TEXT);
        search.setTextSize(16);
        search.setPadding(dp(18), 0, dp(18), 0);
        search.setBackground(round(fieldSurface(), dp(22), tint(BLUE, 0.45f)));
        body.addView(search, new LinearLayout.LayoutParams(-1, dp(54)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, dp(360));
        slp.setMargins(0, dp(12), 0, 0);
        body.addView(scroll, slp);

        Runnable render = () -> {
            list.removeAllViews();
            String q = search.getText().toString().trim().toLowerCase(Locale.US);
            int shown = 0;
            boolean filtering = !q.isEmpty();
            for (int i = 0; i < stops.size(); i++) {
                Stop stop = stops.get(i);
                String label = stop.seq + ". " + stop.name;
                if (!q.isEmpty() && !label.toLowerCase(Locale.US).contains(q)) continue;
                final int index = i;
                Button option = sheetButton(label);
                option.setOnClickListener(v -> {
                    dismissSheet();
                    handler.onSelect(index, label);
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(56));
                lp.setMargins(0, 0, 0, dp(8));
                list.addView(option, lp);
                shown++;
                if (!filtering && shown >= 160) break;
            }
            if (shown == 0) list.addView(centerStatus("No matching stops"));
        };
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            public void onTextChanged(CharSequence s, int st, int b, int c) { render.run(); }
            public void afterTextChanged(Editable e) {}
        });
        render.run();
        showBottomSheet(title, body);
        focusSearchField(search);
    }

    private void showGlobalStopPickerSheet(String title, StopSelectHandler handler) {
        if (!allStops.isEmpty()) {
            showPhysicalStopPickerSheet(title, allStops, handler);
            return;
        }
        if (allStopsLoading) {
            showLoadingSheet("Loading Stops", "Loading bus stops...");
            return;
        }
        allStopsLoading = true;
        showLoadingSheet("Loading Stops", "Loading bus stops...");
        io.execute(() -> {
            try {
                List<Stop> loaded = dedupeStops(api.allStops());
                runOnUiThread(() -> {
                    allStopsLoading = false;
                    allStops.clear();
                    allStops.addAll(loaded);
                    showPhysicalStopPickerSheet(title, allStops, handler);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    allStopsLoading = false;
                    showInfoSheet("Stops", "Could not load stops: " + e.getMessage());
                });
            }
        });
    }

    private void showPhysicalStopPickerSheet(String title, List<Stop> stops, StopSelectHandler handler) {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("Search bus stop");
        search.setHintTextColor(MUTED);
        search.setTextColor(TEXT);
        search.setTextSize(16);
        search.setPadding(dp(18), 0, dp(18), 0);
        search.setBackground(round(fieldSurface(), dp(22), tint(BLUE, 0.45f)));
        body.addView(search, new LinearLayout.LayoutParams(-1, dp(54)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, dp(380));
        slp.setMargins(0, dp(12), 0, 0);
        body.addView(scroll, slp);

        Runnable render = () -> {
            list.removeAllViews();
            String q = search.getText().toString().trim().toLowerCase(Locale.US);
            int shown = 0;
            boolean filtering = !q.isEmpty();
            for (Stop stop : stops) {
                String label = stop.name;
                if (filtering && !label.toLowerCase(Locale.US).contains(q)) continue;
                Button option = sheetButton(label);
                option.setOnClickListener(v -> {
                    dismissSheet();
                    handler.onSelect(stop);
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(56));
                lp.setMargins(0, 0, 0, dp(8));
                list.addView(option, lp);
                shown++;
                if (!filtering && shown >= 120) break;
            }
            if (shown == 0) list.addView(centerStatus("No matching stops"));
        };
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            public void onTextChanged(CharSequence s, int st, int b, int c) { render.run(); }
            public void afterTextChanged(Editable e) {}
        });
        render.run();
        showBottomSheet(title, body);
        focusSearchField(search);
    }

    private List<Stop> dedupeStops(List<Stop> stops) {
        List<Stop> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Stop stop : stops) {
            if (stop.lat == 0 && stop.lon == 0) continue;
            String key = stop.name.toLowerCase(Locale.US) + "@" + Math.round(stop.lat * 10000) + ":" + Math.round(stop.lon * 10000);
            if (seen.add(key)) out.add(stop);
        }
        Collections.sort(out, Comparator.comparing(st -> st.name.toLowerCase(Locale.US)));
        return out;
    }

    private void showRoutePickerSheet(String title, List<Route> choices, RouteOptionHandler handler) {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("Search bus route");
        search.setHintTextColor(MUTED);
        search.setTextColor(TEXT);
        search.setTextSize(16);
        search.setPadding(dp(18), 0, dp(18), 0);
        search.setBackground(round(fieldSurface(), dp(22), tint(BLUE, 0.45f)));
        body.addView(search, new LinearLayout.LayoutParams(-1, dp(54)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, dp(360));
        slp.setMargins(0, dp(12), 0, 0);
        body.addView(scroll, slp);

        Runnable render = () -> {
            list.removeAllViews();
            String q = search.getText().toString().trim().toLowerCase(Locale.US);
            int shown = 0;
            for (Route route : choices) {
                String label = route.route + " " + opName(route.operator) + "  " + route.orig + " -> " + route.dest;
                if (!q.isEmpty() && !label.toLowerCase(Locale.US).contains(q)) continue;
                Button option = sheetButton(label);
                option.setOnClickListener(v -> {
                    dismissSheet();
                    handler.onSelect(route);
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(56));
                lp.setMargins(0, 0, 0, dp(8));
                list.addView(option, lp);
                shown++;
                if (q.isEmpty() && shown >= 80) break;
            }
            if (shown == 0) list.addView(centerStatus("No matching routes"));
        };
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            public void onTextChanged(CharSequence s, int st, int b, int c) { render.run(); }
            public void afterTextChanged(Editable e) {}
        });
        render.run();
        showBottomSheet(title, body);
        focusSearchField(search);
    }

    private void showTextInputSheet(String title, String initial, String hint, TextHandler handler) {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(initial == null ? "" : initial);
        input.setHint(hint);
        input.setSelectAllOnFocus(true);
        input.setTextColor(TEXT);
        input.setHintTextColor(MUTED);
        input.setPadding(dp(18), 0, dp(18), 0);
        input.setBackground(round(fieldSurface(), dp(18), tint(BLUE, 0.45f)));
        body.addView(input, new LinearLayout.LayoutParams(-1, dp(58)));
        Button save = sheetButton("Done");
        save.setOnClickListener(v -> {
            dismissSheet();
            handler.onText(input.getText().toString());
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, dp(56));
        sp.setMargins(0, dp(14), 0, 0);
        body.addView(save, sp);
        showBottomSheet(title, body);
        input.requestFocus();
        input.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        }, 220);
    }

    private void showBottomSheet(String title, View body) {
        dismissSheet();
        sheetOverlay = new FrameLayout(this);
        sheetOverlay.setBackgroundColor(Color.argb(150, 0, 0, 0));
        sheetOverlay.setOnClickListener(v -> dismissSheet());

        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(22), dp(18), dp(22), dp(22));
        sheet.setBackground(round(sheetSurface(), dp(30), tint(BLUE, 0.38f)));
        sheet.setOnClickListener(v -> {});
        sheet.addView(text(title, 24, TEXT, true));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
        bp.setMargins(0, dp(16), 0, 0);
        if (body instanceof ScrollView) {
            bp.height = Math.min(dp(420), Math.round(getResources().getDisplayMetrics().heightPixels * 0.62f));
        }
        sheet.addView(body, bp);

        final int sideMargin = dp(10);
        final int bottomMargin = dp(10);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        sp.setMargins(sideMargin, 0, sideMargin, bottomMargin);
        sheetOverlay.addView(sheet, sp);
        if (Build.VERSION.SDK_INT >= 30) {
            sheetOverlay.setOnApplyWindowInsetsListener((v, insets) -> {
                Insets ime = insets.getInsets(WindowInsets.Type.ime());
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) sheet.getLayoutParams();
                lp.setMargins(sideMargin, 0, sideMargin, bottomMargin + ime.bottom);
                sheet.setLayoutParams(lp);
                return insets;
            });
        }
        root.addView(sheetOverlay, new FrameLayout.LayoutParams(-1, -1));
        if (Build.VERSION.SDK_INT >= 30) sheetOverlay.requestApplyInsets();
        sheet.setTranslationY(dp(320));
        sheet.animate().translationY(0).setDuration(220).start();
    }


    private void focusSearchField(EditText search) {
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.requestFocus();
        search.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT);
        }, 220);
    }

    private void dismissSheet() {
        if (sheetOverlay != null) {
            root.removeView(sheetOverlay);
            sheetOverlay = null;
        }
    }

    private Button materialButton(String label) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(label);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(18), 0, dp(18), 0);
        b.setTextSize(15);
        b.setTextColor(buttonText());
        if (Build.VERSION.SDK_INT >= 21) b.setStateListAnimator(null);
        b.setMinHeight(0);
        b.setMinWidth(0);
        b.setBackground(round(menuButtonSurface(), dp(28), Color.TRANSPARENT));
        return b;
    }

    private Button sheetButton(String label) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(label);
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
        b.setPadding(dp(20), 0, dp(20), 0);
        b.setTextSize(16);
        b.setTextColor(buttonText());
        if (Build.VERSION.SDK_INT >= 21) b.setStateListAnimator(null);
        b.setMinHeight(0);
        b.setMinWidth(0);
        b.setBackground(round(menuButtonSurface(), dp(28), Color.TRANSPARENT));
        return b;
    }
    private void saveBookmark(Bookmark b) {
        Set<String> set = new HashSet<>(prefs.getStringSet("bookmarks", new HashSet<>()));
        set.add(b.serialize());
        prefs.edit().putStringSet("bookmarks", set).apply();
        tab = 0;
        renderNav();
        showBookmarks();
    }

    private void removeBookmark(Bookmark b) {
        Set<String> set = new HashSet<>(prefs.getStringSet("bookmarks", new HashSet<>()));
        set.remove(b.serialize());
        prefs.edit().putStringSet("bookmarks", set).apply();
    }

    private List<Bookmark> getBookmarks() {
        List<Bookmark> out = new ArrayList<>();
        for (String s : prefs.getStringSet("bookmarks", new HashSet<>())) {
            Bookmark b = Bookmark.parse(s);
            if (b != null) out.add(b);
        }
        Collections.sort(out, Comparator.comparing((Bookmark b) -> b.group.toLowerCase(Locale.US)).thenComparing(b -> b.route.length()).thenComparing(b -> b.route));
        return out;
    }

    private LinearLayout card() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(16), dp(18), dp(16));
        box.setBackground(round(surface(), dp(28), outline()));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, dp(12));
        box.setLayoutParams(lp);
        return box;
    }

    private TextView centerStatus(String s) {
        TextView v = text(s, 16, MUTED, false);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(18), dp(40), dp(18), dp(40));
        return v;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setIncludeFontPadding(true);
        if (bold) v.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return v;
    }

    private Button pill(String s) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(s);
        b.setTextColor(TEXT);
        b.setTextSize(14);
        b.setBackground(round(elevatedSurface(), dp(22), BLUE));
        return b;
    }


    private Button compactPill(String s) {
        Button b = pill(s);
        b.setTextSize(16);
        b.setPadding(0, 0, 0, 0);
        return b;
    }
    private android.graphics.drawable.Drawable round(int fill, int radius, int stroke) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radius);
        if (stroke != Color.TRANSPARENT) g.setStroke(dp(1), stroke);
        return g;
    }



    private static int tint(int color, float amount) {
        return blend(color, Color.WHITE, amount);
    }
    private static int blend(int from, int to, float amount) {
        int r = Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * amount);
        int g = Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * amount);
        int b = Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * amount);
        return Color.rgb(r, g, b);
    }
    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static String opName(String op) {
        if ("KMB".equals(op)) return "KMB";
        if ("MTR".equals(op)) return "MTR";
        return "Citybus";
    }


    static class RoutePattern {
        final Route route;
        final String dir;
        final List<Stop> stops;
        RoutePattern(Route route, String dir, List<Stop> stops) {
            this.route = route; this.dir = dir; this.stops = stops;
        }
    }

    static class PatternStop {
        final int patternIndex, stopIndex;
        PatternStop(int patternIndex, int stopIndex) {
            this.patternIndex = patternIndex; this.stopIndex = stopIndex;
        }
    }

    static class PathState {
        final int patternIndex, stopIndex, transfers, stops;
        final List<CustomRouteLeg> legs;
        PathState(int patternIndex, int stopIndex, int transfers, int stops, List<CustomRouteLeg> legs) {
            this.patternIndex = patternIndex; this.stopIndex = stopIndex; this.transfers = transfers; this.stops = stops; this.legs = legs;
        }
    }

    private static class HttpStatusException extends Exception {
        final int code;
        final String body;
        HttpStatusException(int code, String body) {
            super("HTTP " + code);
            this.code = code;
            this.body = body;
        }
    }
    static class Route {
        final String operator, route, orig, dest, serviceType;
        Route(String operator, String route, String orig, String dest, String serviceType) {
            this.operator = operator; this.route = route; this.orig = orig; this.dest = dest; this.serviceType = serviceType;
        }
    }


    static class CustomRoute {
        final String id, name;
        final List<CustomRouteLeg> legs;
        CustomRoute(String id, String name, List<CustomRouteLeg> legs) {
            this.id = clean(id);
            this.name = clean(name);
            this.legs = legs == null ? new ArrayList<>() : legs;
        }
        String serialize() {
            try {
                JSONObject o = new JSONObject();
                o.put("id", id);
                o.put("name", name);
                JSONArray arr = new JSONArray();
                for (CustomRouteLeg leg : legs) arr.put(leg.toJson());
                o.put("legs", arr);
                return o.toString();
            } catch (Exception e) {
                return id + "|" + name;
            }
        }
        static CustomRoute parse(String s) {
            if (s == null || s.length() == 0) return null;
            try {
                if (s.trim().startsWith("{")) {
                    JSONObject o = new JSONObject(s);
                    JSONArray arr = o.optJSONArray("legs");
                    List<CustomRouteLeg> legs = new ArrayList<>();
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            CustomRouteLeg leg = CustomRouteLeg.fromJson(arr.getJSONObject(i));
                            if (leg != null) legs.add(leg);
                        }
                    }
                    return new CustomRoute(o.optString("id", String.valueOf(System.currentTimeMillis())), o.optString("name", "Route"), legs);
                }
                String[] p = s.split("\\|", -1);
                if (p.length < 12) return null;
                CustomRouteLeg leg = new CustomRouteLeg(p[2], p[3], p[4], p[5], p[7], p[10], p[6], p[7], parseInt(p[8]), 0, 0, p[9], p[10], parseInt(p[11]), 0, 0);
                List<CustomRouteLeg> legs = new ArrayList<>();
                legs.add(leg);
                return new CustomRoute(p[0], p[1], legs);
            } catch (Exception e) { return null; }
        }
        static int parseInt(String value) { try { return Integer.parseInt(value); } catch (Exception e) { return 0; } }
        static String clean(String value) { return value == null ? "" : value.replace("|", " "); }
    }

    static class CustomRouteLeg {
        final String operator, route, dir, serviceType, from, to, startStopId, startName, endStopId, endName;
        final int startSeq, endSeq;
        final double startLat, startLon, endLat, endLon;
        CustomRouteLeg(String operator, String route, String dir, String serviceType, String from, String to, String startStopId, String startName, int startSeq, double startLat, double startLon, String endStopId, String endName, int endSeq, double endLat, double endLon) {
            this.operator = operator; this.route = route; this.dir = dir; this.serviceType = serviceType; this.from = clean(from); this.to = clean(to);
            this.startStopId = clean(startStopId); this.startName = clean(startName); this.startSeq = startSeq; this.startLat = startLat; this.startLon = startLon;
            this.endStopId = clean(endStopId); this.endName = clean(endName); this.endSeq = endSeq; this.endLat = endLat; this.endLon = endLon;
        }
        Bookmark bookmark() { return new Bookmark(operator, route, dir, serviceType, from, to, "Ungrouped"); }
        Stop startStop() { return new Stop(startStopId, startName, startSeq, startLat, startLon); }
        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("operator", operator); o.put("route", route); o.put("dir", dir); o.put("serviceType", serviceType); o.put("from", from); o.put("to", to);
            o.put("startStopId", startStopId); o.put("startName", startName); o.put("startSeq", startSeq); o.put("startLat", startLat); o.put("startLon", startLon);
            o.put("endStopId", endStopId); o.put("endName", endName); o.put("endSeq", endSeq); o.put("endLat", endLat); o.put("endLon", endLon);
            return o;
        }
        static CustomRouteLeg fromJson(JSONObject o) {
            try {
                return new CustomRouteLeg(o.optString("operator"), o.optString("route"), o.optString("dir", "outbound"), o.optString("serviceType", "1"), o.optString("from"), o.optString("to"), o.optString("startStopId"), o.optString("startName"), o.optInt("startSeq", 0), o.optDouble("startLat", 0), o.optDouble("startLon", 0), o.optString("endStopId"), o.optString("endName"), o.optInt("endSeq", 0), o.optDouble("endLat", 0), o.optDouble("endLon", 0));
            } catch (Exception e) { return null; }
        }
        static String clean(String value) { return value == null ? "" : value.replace("|", " "); }
    }

    static class Bookmark {
        final String operator, route, dir, serviceType, from, to, group;
        Bookmark(String operator, String route, String dir, String serviceType, String from, String to, String group) {
            this.operator = operator; this.route = route; this.dir = dir; this.serviceType = serviceType; this.from = from; this.to = to; this.group = cleanGroup(group);
        }
        String key() { return operator + "|" + route + "|" + dir + "|" + serviceType; }
        String storageKey() { return serialize(); }
        Bookmark withGroup(String group) { return new Bookmark(operator, route, dir, serviceType, from, to, group); }
        String serialize() { return key() + "|" + from.replace("|", " ") + "|" + to.replace("|", " ") + "|" + group.replace("|", " "); }
        static Bookmark parse(String s) {
            String[] p = s.split("\\|", -1);
            if (p.length < 6) return null;
            return new Bookmark(p[0], p[1], p[2], p[3], p[4], p[5], p.length >= 7 ? p[6] : "Ungrouped");
        }
    }

    static class Stop {
        final String id, name;
        final int seq;
        final double lat, lon;
        Stop(String id, String name, int seq, double lat, double lon) {
            this.id = id; this.name = name; this.seq = seq; this.lat = lat; this.lon = lon;
        }
    }

    static class Api {
        private final Map<String, Stop> stopCache = new HashMap<>();

        List<Stop> allStops() throws Exception {
            List<Stop> out = new ArrayList<>();
            JSONArray kmb = new JSONObject(get("https://data.etabus.gov.hk/v1/transport/kmb/stop")).getJSONArray("data");
            for (int i = 0; i < kmb.length(); i++) {
                JSONObject o = kmb.getJSONObject(i);
                out.add(new Stop("KMB:" + o.getString("stop"), best(o, "name_en", "name_tc"), i + 1, Double.parseDouble(o.getString("lat")), Double.parseDouble(o.getString("long"))));
            }
            JSONArray ctb = new JSONObject(get("https://rt.data.gov.hk/v2/transport/citybus/stop/CTB")).getJSONArray("data");
            for (int i = 0; i < ctb.length(); i++) {
                JSONObject o = ctb.getJSONObject(i);
                out.add(new Stop("CTB:" + o.getString("stop"), best(o, "name_en", "name_tc"), i + 1, Double.parseDouble(o.getString("lat")), Double.parseDouble(o.getString("long"))));
            }
            return out;
        }

        List<Route> routes() throws Exception {
            List<Route> out = new ArrayList<>();
            JSONArray kmb = new JSONObject(get("https://data.etabus.gov.hk/v1/transport/kmb/route/")).getJSONArray("data");
            for (int i = 0; i < kmb.length(); i++) {
                JSONObject o = kmb.getJSONObject(i);
                out.add(new Route("KMB", o.getString("route"), best(o, "orig_en", "orig_tc"), best(o, "dest_en", "dest_tc"), o.optString("service_type", "1")));
            }
            JSONArray ctb = new JSONObject(get("https://rt.data.gov.hk/v2/transport/citybus/route/CTB")).getJSONArray("data");
            for (int i = 0; i < ctb.length(); i++) {
                JSONObject o = ctb.getJSONObject(i);
                out.add(new Route("CTB", o.getString("route"), best(o, "orig_en", "orig_tc"), best(o, "dest_en", "dest_tc"), "1"));
            }
            addMtrRoutes(out);
            return out;
        }

        private void addMtrRoutes(List<Route> out) {
            String[][] routes = {
                    {"506", "Tuen Mun Ferry Pier", "Siu Lun / Tuen Mun Station"},
                    {"K12", "Tai Po Market Station", "Eightland Garden"},
                    {"K14", "Tai Po Mega Mall", "Tai Po Market Station"},
                    {"K17", "Tai Po Market Station", "Fu Shin"},
                    {"K18", "Tai Po Market Station", "Kwong Fuk"},
                    {"K51", "Fu Tai", "Tai Lam / Siu Hong Station"},
                    {"K51A", "Fu Tai", "So Kwun Wat"},
                    {"K52", "Yuet Wu Villa / LR Tuen Mun Stop", "Lung Kwu Tan"},
                    {"K52A", "Tuen Mun Station", "Tsang Tsui"},
                    {"K52P", "Lung Kwu Tan", "Tuen Mun Station"},
                    {"K53", "Tuen Mun Station", "So Kwun Wat circular"},
                    {"K53S", "Tuen Mun Station", "Yip Wong Estate circular"},
                    {"K54", "Wo Tin Estate", "Tuen Mun Town Centre circular"},
                    {"K54A", "Wo Tin Estate", "Siu Hong Station"},
                    {"K58", "Fu Tai", "So Kwun Wat"},
                    {"K65", "Yuen Long Station", "Lau Fau Shan"},
                    {"K65A", "Tin Shui Wai Station", "Lau Fau Shan"},
                    {"K66", "Long Ping", "Tai Tong / Nam Hang Pai"},
                    {"K68", "Yuen Long Industrial Estate", "Yuen Long Park circular"},
                    {"K73", "Tin Heng / Tin Yan", "Yuen Long West"},
                    {"K74", "Tin Shui Wai Town Centre", "Au Tau circular"},
                    {"K75A", "Tin Shui Wai Station", "Hung Shui Kiu circular"},
                    {"K75P", "Tin Shui", "Hung Shui Kiu circular"},
                    {"K75S", "Tin Shui Wai Station", "Hung Fuk Estate"},
                    {"K76", "Tin Heng", "Tin Shui Wai Station"},
                    {"K76S", "Wetland Park Road", "Tin Shing Court / Tin Shui Wai Station"}
            };
            for (String[] r : routes) out.add(new Route("MTR", r[0], r[1], r[2], "1"));
        }

        List<Stop> routeStops(Bookmark b) throws Exception {
            List<Stop> out = new ArrayList<>();
            if ("MTR".equals(b.operator)) return mtrRouteStops(b);
            JSONArray arr;
            if ("KMB".equals(b.operator)) {
                String url = "https://data.etabus.gov.hk/v1/transport/kmb/route-stop/" + enc(b.route) + "/" + b.dir + "/" + enc(b.serviceType);
                arr = new JSONObject(get(url)).getJSONArray("data");
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject rs = arr.getJSONObject(i);
                    Stop s = stop("KMB", rs.getString("stop"));
                    out.add(new Stop(s.id, s.name, rs.optInt("seq", i + 1), s.lat, s.lon));
                }
            } else {
                String url = "https://rt.data.gov.hk/v2/transport/citybus/route-stop/CTB/" + enc(b.route) + "/" + b.dir;
                arr = new JSONObject(get(url)).getJSONArray("data");
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject rs = arr.getJSONObject(i);
                    Stop s = stop("CTB", rs.getString("stop"));
                    out.add(new Stop(s.id, s.name, rs.optInt("seq", i + 1), s.lat, s.lon));
                }
            }
            Collections.sort(out, Comparator.comparingInt(s -> s.seq));
            return out;
        }

        private List<Stop> mtrRouteStops(Bookmark b) throws Exception {
            JSONObject schedule = mtrSchedule(b.route);
            JSONArray arr = schedule.getJSONArray("busStop");
            List<Stop> out = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject stop = arr.getJSONObject(i);
                String id = stop.optString("busStopId", b.route + ":" + (i + 1));
                out.add(new Stop(id, id, i + 1, 0, 0));
            }
            return out;
        }

        private List<String> mtrEtas(Bookmark b, Stop s) throws Exception {
            JSONArray stops = mtrSchedule(b.route).getJSONArray("busStop");
            for (int i = 0; i < stops.length(); i++) {
                JSONObject stop = stops.getJSONObject(i);
                if (!s.id.equals(stop.optString("busStopId"))) continue;
                JSONArray buses = stop.optJSONArray("bus");
                List<String> times = new ArrayList<>();
                if (buses == null) return times;
                for (int j = 0; j < buses.length() && times.size() < 3; j++) {
                    JSONObject bus = buses.getJSONObject(j);
                    String label = bus.optString("arrivalTimeText", "");
                    if (label.length() == 0) label = bus.optString("departureTimeText", "");
                    if (label.length() == 0) {
                        int seconds = bus.optInt("arrivalTimeInSecond", 108000);
                        if (seconds <= 0) label = "Due";
                        else if (seconds < 108000) label = Math.max(1, Math.round(seconds / 60f)) + " min";
                    }
                    label = label.replace("Arriving / Departed", "Due").replace("Departing / Departed", "Due").replace(" minutes", " min");
                    if (label.length() > 0) times.add(label);
                }
                return times;
            }
            return new ArrayList<>();
        }

        private JSONObject mtrSchedule(String routeName) throws Exception {
            return new JSONObject(post("https://rt.data.gov.hk/v1/transport/mtr/bus/getSchedule", "{\"language\":\"en\",\"routeName\":\"" + routeName + "\"}"));
        }

        List<String> etas(Bookmark b, Stop s) throws Exception {
            if ("MTR".equals(b.operator)) return mtrEtas(b, s);
            JSONArray arr;
            if ("KMB".equals(b.operator)) {
                String url = "https://data.etabus.gov.hk/v1/transport/kmb/eta/" + enc(s.id) + "/" + enc(b.route) + "/" + enc(b.serviceType);
                arr = new JSONObject(get(url)).getJSONArray("data");
            } else {
                String url = "https://rt.data.gov.hk/v2/transport/citybus/eta/CTB/" + enc(s.id) + "/" + enc(b.route);
                arr = new JSONObject(get(url)).getJSONArray("data");
            }
            List<String> times = new ArrayList<>();
            String dirCode = b.dir.startsWith("out") ? "O" : "I";
            for (int i = 0; i < arr.length() && times.size() < 3; i++) {
                JSONObject o = arr.getJSONObject(i);
                if (o.has("dir") && !dirCode.equalsIgnoreCase(o.optString("dir"))) continue;
                String eta = o.optString("eta", "");
                if (eta.length() == 0 || "null".equals(eta)) continue;
                times.add(relative(eta));
            }
            return times;
        }

        private Stop stop(String operator, String id) throws Exception {
            String key = operator + ":" + id;
            if (stopCache.containsKey(key)) return stopCache.get(key);
            JSONObject data;
            if ("KMB".equals(operator)) {
                data = new JSONObject(get("https://data.etabus.gov.hk/v1/transport/kmb/stop/" + enc(id))).getJSONObject("data");
            } else {
                data = new JSONObject(get("https://rt.data.gov.hk/v2/transport/citybus/stop/" + enc(id))).getJSONObject("data");
            }
            Stop s = new Stop(id, best(data, "name_en", "name_tc"), 0, Double.parseDouble(data.getString("lat")), Double.parseDouble(data.getString("long")));
            stopCache.put(key, s);
            return s;
        }

        private static String relative(String iso) {
            try {
                OffsetDateTime t = OffsetDateTime.parse(iso, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                long mins = Duration.between(OffsetDateTime.now(t.getOffset()), t).toMinutes();
                if (mins <= 0) return "Due";
                if (mins < 60) return mins + " min";
                return t.toLocalTime().toString().substring(0, 5);
            } catch (Exception e) {
                return iso.replace("T", " ").replace("+08:00", "");
            }
        }

        private static String best(JSONObject o, String en, String tc) {
            String v = o.optString(en, "");
            return v.length() > 0 ? v : o.optString(tc, "");
        }

        private static String enc(String s) throws Exception {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        }

        private static String post(String u, String body) throws Exception {
            HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
            c.setConnectTimeout(12000);
            c.setReadTimeout(12000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            byte[] bytes = body.getBytes("UTF-8");
            c.getOutputStream().write(bytes);
            int code = c.getResponseCode();
            BufferedReader r = new BufferedReader(new InputStreamReader(code >= 400 ? c.getErrorStream() : c.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            if (code >= 400) throw new RuntimeException("HTTP " + code);
            return sb.toString();
        }

        private static String get(String u) throws Exception {
            HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
            c.setConnectTimeout(12000);
            c.setReadTimeout(12000);
            c.setRequestProperty("Accept", "application/json");
            int code = c.getResponseCode();
            BufferedReader r = new BufferedReader(new InputStreamReader(code >= 400 ? c.getErrorStream() : c.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            if (code >= 400) throw new RuntimeException("HTTP " + code);
            return sb.toString();
        }
    }
}
