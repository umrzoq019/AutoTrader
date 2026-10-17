package com.autotrader.mobile;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Public endpoints only. Standalone foreground scanner; never authenticates, signs, or places orders. */
public final class MarketScanner {
    public static final class Quote {
        final String venue,symbol,quoteCurrency;
        final double bid,ask,liquidity;
        final long when;
        Quote(String v,String s,String q,double b,double a,double l,long t) {
            venue=v; symbol=s; quoteCurrency=q;bid=b;ask=a;liquidity=l;when=t;
        }
        double mid(){return (bid+ask)*0.5;}
        double spreadBps(){return 10000*(ask-bid)/mid();}
    }
    private final ForecastEngine model=new ForecastEngine();
    private final Map<String,Quote> books = new HashMap<>();
    private final Map<String,Double> volumes = new HashMap<>();
    private final Map<String,ForecastEngine.Signal> signals = new HashMap<>();
    private final Map<String,Long> lastUpdate = new HashMap<>();
    private final Map<String,String> providerError = new HashMap<>();
    private final Set<String> universe = new HashSet<>();
    private long lastDiscovery=0,lastKraken=0,lastCoinbase=0, quoteCount=0;
    private int coinbaseOffset=0;
    private static final String[] BASES={"BTC","ETH","SOL","XRP","ADA","DOGE","LINK","LTC"};
    private static final double DEFAULT_BUY_FEE_BPS=10, DEFAULT_SELL_FEE_BPS=10;
    private static final double EXECUTION_BUFFER_BPS=6;

    public MarketScanner(){
        Collections.addAll(universe,"BTCUSDT","ETHUSDT","SOLUSDT","XRPUSDT","ADAUSDT",
            "DOGEUSDT","LINKUSDT","LTCUSDT","AVAXUSDT","BNBUSDT","TRXUSDT");
    }
    private static String get(String target) throws Exception {
        URL url=new URL(target);
        if (!url.getProtocol().equals("https")) throw new SecurityException("HTTPS only");
        HttpURLConnection c=(HttpURLConnection)url.openConnection();
        c.setRequestMethod("GET"); c.setInstanceFollowRedirects(false); c.setConnectTimeout(4500); c.setReadTimeout(4500);
        c.setRequestProperty("Accept","application/json");
        c.setRequestProperty("User-Agent","AutoTraderV9Mobile/1.0 public-data-only");
        try {
            if (c.getResponseCode()!=200) throw new IllegalStateException("HTTP "+c.getResponseCode());
            try(BufferedReader br=new BufferedReader(new InputStreamReader(c.getInputStream(),"UTF-8"))) {
                StringBuilder sb=new StringBuilder(); String line;
                while((line=br.readLine())!=null) {
                    sb.append(line); if(sb.length()>3_000_000) throw new IllegalStateException("response too large");
                }
                return sb.toString();
            }
        } finally { c.disconnect(); }
    }
    private static double num(JSONObject j,String k){
        try {return Double.parseDouble(j.optString(k,"NaN"));}catch(Exception e){return Double.NaN;}
    }
    private void apply(String venue,String symbol,String quoteCurrency,double bid,double ask,double liq,long now) {
        if (!Double.isFinite(bid)||!Double.isFinite(ask)||bid<=0||ask<=bid||ask/bid>1.10) return;
        String key=venue+":"+symbol;
        books.put(key,new Quote(venue,symbol,quoteCurrency,bid,ask,liq,now));
        lastUpdate.put(venue,now); quoteCount++;
        // Datasets from USD vs USDT are strictly separate; only comparisons with identical quote currency.
        if (venue.equals("BINANCE")) {
            ForecastEngine.Signal signal=model.observe(symbol,now,(bid+ask)*0.5,10000*(ask-bid)/((ask+bid)*0.5));
            if(signal!=null) signals.put(symbol,signal);
        }
    }
    public synchronized JSONObject refresh() {
        long now=System.currentTimeMillis();
        if (now-lastDiscovery>5*60000 || universe.size()<15) {
            try {
                JSONArray arr=new JSONArray(get("https://data-api.binance.vision/api/v3/ticker/24hr"));
                List<JSONObject> eligible=new ArrayList<>();
                for(int i=0;i<arr.length();i++) {
                    JSONObject o=arr.optJSONObject(i); if(o==null) continue;
                    String s=o.optString("symbol","");
                    if(!s.endsWith("USDT")||s.length()<7)continue;
                    String base=s.substring(0,s.length()-4);
                    if(base.endsWith("UP")||base.endsWith("DOWN")||base.endsWith("BULL")||base.endsWith("BEAR")||
                       base.equals("USDC")||base.equals("FDUSD")||base.equals("TUSD")||base.equals("DAI")||base.equals("USDE"))continue;
                    double vol=num(o,"quoteVolume"); if(!Double.isFinite(vol)||vol<1_000_000)continue;
                    volumes.put(s,vol); eligible.add(o);
                }
                eligible.sort((a,b)->Double.compare(num(b,"quoteVolume"),num(a,"quoteVolume")));
                universe.clear();
                for(int i=0;i<Math.min(60,eligible.size());i++) universe.add(eligible.get(i).optString("symbol"));
                lastDiscovery=now; providerError.remove("BINANCE_DISCOVERY");
            }catch(Exception e){providerError.put("BINANCE_DISCOVERY",e.getClass().getSimpleName());lastDiscovery=now;}
        }
        try {
            JSONArray arr=new JSONArray(get("https://data-api.binance.vision/api/v3/ticker/bookTicker"));
            for(int i=0;i<arr.length();i++) {
                JSONObject o=arr.optJSONObject(i); if(o==null)continue;
                String symbol=o.optString("symbol","");
                if(!universe.contains(symbol))continue;
                apply("BINANCE",symbol,"USDT",num(o,"bidPrice"),num(o,"askPrice"),num(o,"bidQty"),now);
            }
            providerError.remove("BINANCE");
        }catch(Exception e){ providerError.put("BINANCE",e.getClass().getSimpleName()); }
        if(now-lastKraken>35000) {
            try {
                JSONObject root=new JSONObject(get("https://api.kraken.com/0/public/Ticker?pair=XBTUSD,ETHUSD,SOLUSD,XRPUSD,ADAUSD,DOGEUSD,LINKUSD,LTCUSD"));
                JSONArray errors=root.optJSONArray("error");
                if(errors!=null && errors.length()>0)throw new IllegalStateException("API error");
                JSONObject results=root.getJSONObject("result");
                java.util.Iterator<String> it=results.keys();
                while(it.hasNext()) {
                    String pair=it.next(); String base=krakenBase(pair); if(base==null) continue;
                    JSONObject o=results.optJSONObject(pair); if(o==null)continue;
                    JSONArray b=o.optJSONArray("b"),a=o.optJSONArray("a");
                    if(b==null||a==null)continue;
                    apply("KRAKEN",base+"USD","USD",Double.parseDouble(b.getString(0)),Double.parseDouble(a.getString(0)),0,now);
                }
                lastKraken=now;providerError.remove("KRAKEN");
            }catch(Exception e){providerError.put("KRAKEN",e.getClass().getSimpleName());lastKraken=now;}
        }
        if(now-lastCoinbase>30000) {
            // Two pairs per cycle to avoid flooding the public endpoint.
            for(int k=0;k<2;k++) {
                String base=BASES[(coinbaseOffset++)%BASES.length];
                try {
                    JSONObject o=new JSONObject(get("https://api.exchange.coinbase.com/products/"+base+"-USD/ticker"));
                    apply("COINBASE",base+"USD","USD",num(o,"bid"),num(o,"ask"),0,now);
                    providerError.remove("COINBASE");
                }catch(Exception e){providerError.put("COINBASE",e.getClass().getSimpleName());}
            }
            lastCoinbase=now;
        }
        books.entrySet().removeIf(e -> now-e.getValue().when>15*60000L);
        return snapshot();
    }
    private static String krakenBase(String pair) {
        String cleaned=pair.toUpperCase(Locale.ROOT).replace("/","");
        if(cleaned.endsWith("ZUSD"))cleaned=cleaned.substring(0,cleaned.length()-4);
        else if(cleaned.endsWith("USD"))cleaned=cleaned.substring(0,cleaned.length()-3);
        else return null;
        if(cleaned.startsWith("X")&&cleaned.length()>3)cleaned=cleaned.substring(1);
        if(cleaned.equals("XBT"))cleaned="BTC";
        if(cleaned.equals("XDG"))cleaned="DOGE";
        for(String known:BASES)if(known.equals(cleaned))return known;
        return null;
    }
    public synchronized String modelState(){return model.exportState();}
    public synchronized void restoreModel(String state){model.restoreState(state);}
    public synchronized JSONObject snapshot() {
        long now=System.currentTimeMillis();
        JSONObject o=new JSONObject(); JSONArray routes=new JSONArray();
        ArrayList<JSONObject> ranked=new ArrayList<>();
        int live=0;
        try {
            for(Quote q:books.values())if(now-q.when<45000)live++;
            for(Map.Entry<String,ForecastEngine.Signal> e:signals.entrySet()) {
                ForecastEngine.Signal s=e.getValue(); Quote q=books.get("BINANCE:"+s.symbol);
                if(q==null||now-q.when>30000)continue;
                // Signal quality is UNVERIFIED until genuinely evaluated. No scores disguised as realized returns.
                double spread=q.spreadBps();
                double estimatedNet=s.pUp>=0.5
                    ? Math.abs(s.expectedMoveBps)-spread-DEFAULT_BUY_FEE_BPS-DEFAULT_SELL_FEE_BPS-EXECUTION_BUFFER_BPS
                    : -Math.abs(s.expectedMoveBps); // downturn is a risk flag, NOT a short-sale opportunity.
                JSONObject r=new JSONObject();
                r.put("symbol",s.symbol).put("type","DIRECTIONAL").put("kind","DIRECTIONAL")
                  .put("venue","BINANCE").put("action",s.pUp>=0.5?"UP_LEAN":"DOWN_LEAN")
                  .put("confidence",Math.max(s.pUp,1-s.pUp))
                  .put("expected_net_bps",estimatedNet).put("regime",s.regime)
                  .put("samples",s.samples).put("indicative",true).put("age_ms",now-q.when);
                ranked.add(r);
            }
            // USD/USDT never mixed; cross-venue opportunity is hypothetical and fees are placeholders.
            for(String base:BASES){
                Quote a=books.get("COINBASE:"+base+"USD"),b=books.get("KRAKEN:"+base+"USD");
                if(a==null||b==null||now-a.when>15000||now-b.when>15000||Math.abs(a.when-b.when)>8000)continue;
                double ca=(b.bid-a.ask)/a.ask*10000, cb=(a.bid-b.ask)/b.ask*10000;
                double spread=Math.max(ca,cb);
                if(spread<0)continue;
                boolean coinbaseBuy=ca>=cb;
                double hypotheticalNet=spread-45-EXECUTION_BUFFER_BPS; // explicit conservative illustrative fees.
                JSONObject r=new JSONObject();r.put("symbol",base+"USD").put("type","ARBITRAGE")
                    .put("kind","ARBITRAGE").put("venue",coinbaseBuy?"COINBASE → KRAKEN":"KRAKEN → COINBASE")
                    .put("buy_venue",coinbaseBuy?"COINBASE":"KRAKEN")
                    .put("sell_venue",coinbaseBuy?"KRAKEN":"COINBASE")
                    .put("action","BUY_SELL").put("confidence",0.0)
                    .put("expected_net_bps",hypotheticalNet).put("indicative",true)
                    .put("age_ms",now-Math.min(a.when,b.when));ranked.add(r);
            }
            ranked.sort((x,y)->Double.compare(y.optDouble("expected_net_bps",-1e9),x.optDouble("expected_net_bps",-1e9)));
            for(int i=0;i<Math.min(60,ranked.size());i++)routes.put(ranked.get(i));
            o.put("routes",routes).put("markets_live",live).put("quotes_seen",quoteCount)
             .put("universe_size",universe.size()).put("last_quote_at",live>0?Instant.ofEpochMilli(latestValidQuote(now)).toString():"")
             .put("killed",false).put("standalone",true).put("accuracy_evaluated",model.evaluations())
             .put("model_quality",model.quality()).put("provider_errors",new JSONObject(providerError));
            JSONObject perf=new JSONObject();
            perf.put("latency",new JSONObject().put("engine_hotpath",new JSONObject().put("p99_us",JSONObject.NULL)));
            o.put("performance",perf);
        }catch(Exception ignored){}return o;
    }
    private long latestValidQuote(long now){long t=0;for(Quote q:books.values())if(now-q.when<45000)t=Math.max(t,q.when);return t;}
}
