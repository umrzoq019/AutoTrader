package com.autotrader.mobile;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Foreground, standalone multi-venue scanner with optional read-only V7 server view. */
public final class MainActivity extends Activity {
    private static final int ACCENT = Color.rgb(87,205,186);
    private static final int WHITE = Color.rgb(241,246,255);
    private static final int MUTED = Color.rgb(149,164,189);
    private static final int AMBER = Color.rgb(232,186,110);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final MarketScanner scanner = new MarketScanner();
    private EditText urlField, tokenField;
    private TextView banner, alert, quotes, latency, footer;
    private LinearLayout routes;
    private Button connectButton, scanButton;
    private Button[] filters;
    private String baseUrl = "", token = "";
    private boolean active = false;
    private boolean demo = false;
    private boolean standalone = false;
    private boolean busy = false;
    private boolean foreground = false;
    private int filter = 0;
    private long generation = 0;
    private JSONObject lastStatus;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!active || demo || !foreground) return;
            if (busy) { handler.postDelayed(this, 700); return; }
            busy = true;
            final long myGeneration = generation;
            io.execute(() -> {
                try {
                    JSONObject data = standalone ? scanner.refresh() : new JSONObject(fetch(baseUrl + "/api/status", token));
                    handler.post(() -> {
                        if (active && !demo && foreground && myGeneration == generation) render(data, false);
                        if (standalone && myGeneration == generation)
                            getPreferences(MODE_PRIVATE).edit().putString("online_model_state",scanner.modelState()).apply();
                        busy = false;
                        if (active && !demo && foreground && myGeneration == generation) handler.postDelayed(ticker, standalone ? 12000 : 2000);
                    });
                } catch (Exception error) {
                    String message = error.getMessage() == null ? "network failure" : error.getMessage();
                    handler.post(() -> {
                        if (active && !demo && foreground && myGeneration == generation) {
                            banner.setText("DISCONNECTED  ·  Data unavailable");
                            banner.setTextColor(AMBER);
                            alert.setText("Connection unavailable: " + message + ". No live signal is shown.");
                            routes.removeAllViews();
                            quotes.setText("—");
                            latency.setText("—");
                        }
                        busy = false;
                        if (active && !demo && foreground && myGeneration == generation) handler.postDelayed(ticker, standalone ? 15000 : 5000);
                    });
                }
            });
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        scanner.restoreModel(getPreferences(MODE_PRIVATE).getString("online_model_state", ""));
        setContentView(R.layout.activity_main);
        urlField = findViewById(R.id.serverUrl);
        tokenField = findViewById(R.id.controllerToken);
        tokenField.setSaveEnabled(false);
        tokenField.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        banner = findViewById(R.id.banner);
        alert = findViewById(R.id.alert);
        quotes = findViewById(R.id.quotes);
        latency = findViewById(R.id.latency);
        footer = findViewById(R.id.footer);
        routes = findViewById(R.id.routes);
        connectButton = findViewById(R.id.connect);
        scanButton = findViewById(R.id.scanLive);
        filters = new Button[]{findViewById(R.id.filterAll),findViewById(R.id.filterUp),
              findViewById(R.id.filterDown),findViewById(R.id.filterArb)};
        for (int i=0;i<filters.length;i++) {
            final int index=i;
            filters[i].setOnClickListener(v -> { filter=index; setFilterStyle(); if(lastStatus!=null) renderRoutes(lastStatus); });
        }
        setFilterStyle();
        connectButton.setOnClickListener(v -> connectOrDisconnect());
        scanButton.setOnClickListener(v -> startOrStopScan());
        findViewById(R.id.demo).setOnClickListener(v -> showDemo());
        footer.setText("Standalone public APIs · Optional HTTPS backend · No brokerage keys · Foreground only");
        showEmpty("Tap SCAN LIVE to monitor public markets directly on your phone.");
    }

    private void startOrStopScan() {
        if(active && standalone) { stopUpdates(); return; }
        handler.removeCallbacks(ticker);
        generation++;
        active=true;
        demo=false;
        standalone=true;
        lastStatus=null;
        token="";
        tokenField.setText("");
        scanButton.setText("Stop scan");
        connectButton.setText("Connect backend");
        banner.setText("CONNECTING · Public market feeds");
        banner.setTextColor(AMBER);
        alert.setText("Loading Binance market universe; other public venues refresh more slowly. No predictions until history accumulates.");
        routes.removeAllViews();
        quotes.setText("—");
        latency.setText("—");
        handler.post(ticker);
    }

    private void connectOrDisconnect() {
        if (active && !demo && !standalone) { stopUpdates(); return; }
        if (active) stopUpdates();
        String candidate = urlField.getText().toString().trim().replaceAll("/+$", "");
        if (!candidate.startsWith("https://")) {
            alert.setText("A valid HTTPS backend address is required. HTTP and local insecure connections are blocked.");
            return;
        }
        try {
            URL parsed = new URL(candidate);
            if (parsed.getHost().isEmpty() || parsed.getUserInfo()!=null || parsed.getQuery()!=null || parsed.getRef()!=null) {
                throw new IllegalArgumentException("invalid backend URL");
            }
        } catch (Exception e) { alert.setText("Invalid server address."); return; }
        handler.removeCallbacks(ticker);
        generation++;
        active=true;
        demo=false;
        standalone=false;
        baseUrl=candidate;
        token=tokenField.getText().toString(); // RAM only; never stored or logged.
        lastStatus=null;
        connectButton.setText("Disconnect");
        scanButton.setText("Scan live");
        banner.setText("CONNECTING  ·  Read-only feed");
        banner.setTextColor(AMBER);
        alert.setText("Checking backend and market data freshness...");
        handler.post(ticker);
    }

    private void stopUpdates() {
        active=false;
        demo=false;
        standalone=false;
        generation++;
        handler.removeCallbacks(ticker);
        token="";
        tokenField.setText("");
        connectButton.setText("Connect backend");
        scanButton.setText("Scan live");
        lastStatus=null;
        banner.setText("OFFLINE  ·  Read-only viewer");
        banner.setTextColor(AMBER);
        quotes.setText("—");
        latency.setText("—");
        showEmpty("Disconnected. No orders or money were affected.");
    }

    private void showDemo() {
        active=false;
        demo=true;
        standalone=false;
        generation++;
        handler.removeCallbacks(ticker);
        token="";
        tokenField.setText("");
        connectButton.setText("Connect backend");
        scanButton.setText("Scan live");
        try {
            JSONObject sample = new JSONObject();
            sample.put("quotes_seen", 248702);
            sample.put("last_quote_at", Instant.now().toString());
            sample.put("killed", false);
            JSONObject latencyInfo = new JSONObject().put("p99_us", 83.7);
            sample.put("performance",new JSONObject().put("latency",new JSONObject().put("engine_hotpath",latencyInfo)));
            JSONArray sampleRoutes = new JSONArray();
            sampleRoutes.put(demoRoute("BTCUSDT", "BUY", "DIRECTIONAL", "BINANCE", 0.66, 13.4));
            sampleRoutes.put(demoRoute("ETHUSDT", "EXIT_OR_AVOID", "DIRECTIONAL", "BINANCE", 0.64, 9.1));
            sampleRoutes.put(demoRoute("SOLUSD", "BUY_SELL", "ARBITRAGE", "COINBASE → KRAKEN", 0.71, 7.2));
            sample.put("routes",sampleRoutes);
            render(sample,true);
        } catch(Exception e) { showEmpty("Demo unavailable."); }
    }

    private JSONObject demoRoute(String symbol,String action,String kind,String venue,double conf,double edge) throws Exception {
        return new JSONObject().put("symbol",symbol).put("action",action).put("kind",kind)
            .put("type",kind).put("venue",venue).put("confidence",conf).put("expected_net_bps",edge)
            .put("buy_venue","COINBASE").put("sell_venue","KRAKEN");
    }

    private void render(JSONObject status,boolean isDemo) {
        lastStatus=status;
        int n=status.optInt("quotes_seen",0);
        quotes.setText(String.format(Locale.US,"%,d",n));
        JSONObject perf=status.optJSONObject("performance");
        JSONObject latencyGroup=perf==null?null:perf.optJSONObject("latency");
        JSONObject hot=latencyGroup==null?null:latencyGroup.optJSONObject("engine_hotpath");
        double p99=hot==null?Double.NaN:hot.optDouble("p99_us",Double.NaN);
        latency.setText(Double.isFinite(p99)?String.format(Locale.US,"%.1f µs",p99):"—");
        String freshness=lastQuoteHealth(status.optString("last_quote_at",""),status.optBoolean("standalone",false));
        boolean killed=status.optBoolean("killed",false);
        if (status.optBoolean("standalone",false)) {
            latency.setText(status.optString("model_quality","LEARNING"));
            quotes.setText(String.valueOf(status.optInt("markets_live",0)));
            latency.setTextSize(12);
        } else { latency.setTextSize(22); }
        if (isDemo) {
            banner.setText("DEMO  ·  Fictitious examples");
            banner.setTextColor(AMBER);
            alert.setText("Demonstration data only. The examples above are invented and imply no actual profitability.");
        } else if (killed) {
            banner.setText("ENGINE STOPPED  ·  No active feed");
            banner.setTextColor(AMBER);
            alert.setText("Engine reports stopped. Reason: " + status.optString("kill_reason","not provided"));
            routes.removeAllViews();
            return;
        } else if (!"FRESH".equals(freshness)) {
            banner.setText("MARKET DATA " + freshness + "  ·  Do not rely on signals");
            banner.setTextColor(AMBER);
            alert.setText("The latest market quote is missing or too old. Candidates are hidden until it recovers.");
            routes.removeAllViews();
            return;
        } else {
            banner.setText(status.optBoolean("standalone",false)?"PUBLIC LIVE DATA · " + status.optInt("universe_size",0) + " eligible markets":"BACKEND LIVE DATA · Read-only view");
            banner.setTextColor(ACCENT);
            String msg=status.optBoolean("standalone",false)?
                "Standalone: forecasts learn from later prices; fee and latency estimates are placeholders. No orders.":
                "Forecasts are uncertain model estimates, not verified profits or trade instructions.";
            JSONObject problems=status.optJSONObject("provider_errors");
            if(problems!=null && problems.length()>0) {
                java.util.Iterator<String> keys=problems.keys();
                StringBuilder names=new StringBuilder();
                while(keys.hasNext()) {if(names.length()>0)names.append(", ");names.append(keys.next());}
                msg += " Unavailable: " + names;
            }
            alert.setText(msg);
        }
        renderRoutes(status);
    }

    static String lastQuoteHealth(String timestamp){return lastQuoteHealth(timestamp,false);}
    static String lastQuoteHealth(String timestamp,boolean standalone) {
        if (timestamp==null || timestamp.isEmpty() || "null".equalsIgnoreCase(timestamp)) return "UNKNOWN";
        try {
            Instant latest=Instant.parse(timestamp.endsWith("Z")?timestamp:timestamp.replace("+00:00","Z"));
            long age=Duration.between(latest,Instant.now()).toMillis();
            if (age< -5000L) return "CLOCK ERROR";
            return age>(standalone?60000L:12000L)?"STALE":"FRESH";
        } catch(Exception e) { return "UNKNOWN"; }
    }

    private void renderRoutes(JSONObject data) {
        routes.removeAllViews();
        JSONArray rs=data.optJSONArray("routes");
        if(rs==null || rs.length()==0) { showEmpty("No qualified candidates. The model may simply have no current edge."); return; }
        int shown=0;
        for(int i=0; i<rs.length() && shown<12; i++) {
            JSONObject r=rs.optJSONObject(i);
            if(r==null) continue;
            String action=r.optString("action","");
            String kind=r.optString("route_type",r.optString("type",r.optString("kind","")));
            boolean arb=kind.equalsIgnoreCase("ARBITRAGE") || action.equalsIgnoreCase("BUY_SELL");
            if (filter==1 && !action.equalsIgnoreCase("BUY") && !action.equalsIgnoreCase("UP_LEAN")) continue;
            if (filter==2 && !action.contains("EXIT") && !action.contains("AVOID") && !action.equalsIgnoreCase("SELL") && !action.equalsIgnoreCase("DOWN_LEAN")) continue;
            if (filter==3 && !arb) continue;
            addRoute(r,arb); shown++;
        }
        if (shown==0) showEmpty("No signals match this filter.");
    }

    private void addRoute(JSONObject r,boolean arb) {
        LinearLayout card=new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16),dp(13),dp(16),dp(14));
        card.setBackgroundResource(R.drawable.panel);
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);
        params.bottomMargin=dp(10); routes.addView(card,params);
        LinearLayout first=new LinearLayout(this);
        first.setOrientation(LinearLayout.HORIZONTAL);
        card.addView(first);
        String symbol=r.optString("symbol","UNKNOWN");
        TextView name=makeText(symbol,19,WHITE,true);
        first.addView(name,new LinearLayout.LayoutParams(0,-2,1f));
        String action=r.optString("action","OBSERVE");
        TextView direction=makeText(action.replace('_',' '),12,arb?AMBER:ACCENT,true);
        first.addView(direction);
        double net=r.optDouble("expected_net_bps",Double.NaN);
        String venue=r.optString("venue","");
        if(arb) venue=r.optString("buy_venue","?")+" → "+r.optString("sell_venue","?");
        TextView market=makeText((arb?"Cross-venue spread":"Directional model")+"   ·   "+venue,12,MUTED,false);
        market.setPadding(0,dp(4),0,dp(8)); card.addView(market);
        String edge=action.equalsIgnoreCase("DOWN_LEAN")?"Potential decline · no spot shorting":
            (Double.isFinite(net)?String.format(Locale.US,"%+.1f bps hypothetical net (modeled costs)",net):"Net estimate unavailable");
        TextView magnitude=makeText(edge,15,WHITE,true);
        card.addView(magnitude);
        double conf=r.optDouble("confidence",Double.NaN);
        String confLabel=arb?"USD quotes only · Costs/latency may invalidate spread":(Double.isFinite(conf)?String.format(Locale.US,"Raw forecast score: %.0f%% · Not calibrated probability",Math.max(0,Math.min(1,conf))*100):"Forecast unavailable");
        TextView confView=makeText(confLabel,12,MUTED,false);
        confView.setPadding(0,dp(6),0,0); card.addView(confView);
    }

    private void showEmpty(String msg) {
        routes.removeAllViews();
        TextView empty=makeText(msg,14,MUTED,false);
        empty.setPadding(dp(12),dp(20),dp(12),dp(20));
        routes.addView(empty);
    }
    private TextView makeText(String s,int size,int color,boolean strong) {
        TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);
        if(strong)t.setTypeface(null,1);return t;
    }
    private void setFilterStyle() {
        for(int i=0;i<filters.length;i++) {
            filters[i].setBackgroundResource(i==filter?R.drawable.button_fill:R.drawable.button_ghost);
            filters[i].setTextColor(i==filter?Color.rgb(10,16,33):WHITE);
        }
    }
    private int dp(int v) {return Math.round(v*getResources().getDisplayMetrics().density);}

    private static String fetch(String address, String bearer) throws Exception {
        URL url=new URL(address);
        if(!"https".equalsIgnoreCase(url.getProtocol())) throw new SecurityException("HTTPS required");
        HttpURLConnection conn=(HttpURLConnection)url.openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(5000);
            conn.setUseCaches(false);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("Accept","application/json");
            if(!bearer.isEmpty()) conn.setRequestProperty("Authorization","Bearer "+bearer);
            int status=conn.getResponseCode();
            if(status<200||status>=300) throw new IllegalStateException("server returned HTTP "+status);
            int contentLength=conn.getContentLength();
            if(contentLength>1_000_000) throw new IllegalStateException("status response too large");
            try(BufferedReader reader=new BufferedReader(new InputStreamReader(conn.getInputStream(),StandardCharsets.UTF_8))) {
                char[] buffer=new char[4096];int n;int total=0;
                StringBuilder out=new StringBuilder();
                while((n=reader.read(buffer))!=-1) {total+=n;if(total>1_000_000) throw new IllegalStateException("status response too large");out.append(buffer,0,n);}
                return out.toString();
            }
        } finally {conn.disconnect();}
    }

    @Override protected void onStop() {
        // Foreground-only updates: no background network loop, wake lock, or continuous phone execution.
        foreground=false;
        handler.removeCallbacks(ticker);
        super.onStop();
    }
    @Override protected void onStart() {
        super.onStart();
        foreground=true;
        if(active && !demo) {
            if(!busy) handler.post(ticker);
            else handler.postDelayed(() -> { if(active && !demo && foreground && !busy) handler.post(ticker); }, 1200);
        }
    }
    @Override protected void onDestroy() {
        active=false; generation++; token=""; handler.removeCallbacksAndMessages(null);io.shutdownNow();
        super.onDestroy();
    }
}
