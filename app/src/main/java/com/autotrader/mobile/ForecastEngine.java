package com.autotrader.mobile;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Pure-Java, causal, incremental directional estimator. No outcome leakage or fabricated success rates. */
public final class ForecastEngine {
    public static final class Signal {
        public final String symbol;
        public final double pUp, expectedMoveBps, volatilityBps;
        public final String regime;
        public final int samples;
        Signal(String s, double p, double edge, double vol, String r, int n) {
            symbol=s; pUp=p; expectedMoveBps=edge; volatilityBps=vol; regime=r; samples=n;
        }
    }
    private static final class Point {
        final long when; final double mid;
        Point(long t,double p) { when=t; mid=p; }
    }
    private static final class Pending {
        final long due; final double reference; final double[] features; final double probability;
        Pending(long d,double r,double[] f,double p) { due=d; reference=r; features=f; probability=p; }
    }
    private final Map<String,ArrayDeque<Point>> histories = new HashMap<>();
    private final Map<String,ArrayDeque<Pending>> pending = new HashMap<>();
    private final Map<String,Long> lastEvaluation = new HashMap<>();
    // Bounded linear model; coefficients update ONLY after a future outcome is observed.
    private final double[] weights = {0.0, 0.25, 0.16, -0.10, -0.05};
    private int evaluated=0, correct=0;
    private double brierSum=0;

    private static double clamp(double x,double lo,double hi) { return Math.max(lo,Math.min(hi,x)); }
    private static double logReturn(double p,double q) { return Math.log(p/q); }
    private static double sigmoid(double z) { return 1.0/(1.0+Math.exp(-clamp(z,-15,15))); }

    public synchronized Signal observe(String symbol, long timestampMs,double mid,double spreadBps) {
        if (!Double.isFinite(mid) || mid<=0 || !Double.isFinite(spreadBps) || spreadBps<0) return null;
        ArrayDeque<Point> history = histories.computeIfAbsent(symbol,s->new ArrayDeque<>());
        // Drop out-of-order observations and duplicate time buckets.
        if (!history.isEmpty() && timestampMs <= history.getLast().when) return null;
        history.addLast(new Point(timestampMs,mid));
        while (history.size()>90) history.removeFirst();
        ArrayDeque<Pending> waits = pending.computeIfAbsent(symbol,s->new ArrayDeque<>());
        while (!waits.isEmpty() && waits.getFirst().due<=timestampMs) {
            Pending pred=waits.removeFirst();
            if (Math.abs(logReturn(mid,pred.reference)) >= 0.00002) {
                double actual=mid>pred.reference?1:0;
                double delta=actual-pred.probability;
                // online logistic SGD using causal, cached input only; bounded learning update.
                for (int j=0;j<weights.length;j++) weights[j]=clamp(weights[j]+0.018*delta*pred.features[j],-2.5,2.5);
                evaluated++;
                if ((pred.probability>=0.5)==(actual==1.0)) correct++;
                brierSum+=(actual-pred.probability)*(actual-pred.probability);
            }
        }
        if (history.size()<8) return null;
        Point[] arr=history.toArray(new Point[0]); int n=arr.length;
        double rShort=logReturn(mid,arr[Math.max(0,n-4)].mid)*10000;
        double rLong=logReturn(mid,arr[Math.max(0,n-8)].mid)*10000;
        double absSum=0; int k=0;
        for (int i=Math.max(1,n-10);i<n;i++) { absSum+=Math.abs(logReturn(arr[i].mid,arr[i-1].mid))*10000; k++; }
        double vol=absSum/Math.max(1,k);
        double[] x={1.0,clamp(rShort/50.0,-2,2),clamp(rLong/90.0,-2,2),
            clamp(spreadBps/25.0,0,2),clamp(vol/60.0,0,2)};
        double z=0;
        for (int j=0;j<weights.length;j++) z+=weights[j]*x[j];
        double p=sigmoid(z);
        String regime=vol>100?"STRESSED":(vol>40?"VOLATILE":(vol<2?"QUIET":"NORMAL"));
        // Do not train predictions in highly unstable/missing-data conditions.
        Long last=lastEvaluation.get(symbol);
        if ((last==null || timestampMs-last>=60000) && !regime.equals("STRESSED")) {
            waits.addLast(new Pending(timestampMs+60000,mid,x.clone(),p));
            lastEvaluation.put(symbol,timestampMs);
        }
        // Estimated signed movement; NOT a claim of realizable profitability.
        double expected=clamp((p-0.5)*2.0*Math.min(80,Math.max(10,vol*2.5)), -80,80);
        return new Signal(symbol,p,expected,vol,regime,history.size());
    }
    public synchronized String exportState() {
        StringBuilder out=new StringBuilder("v1");
        for(double w:weights)out.append(',').append(w);
        out.append(',').append(evaluated).append(',').append(correct).append(',').append(brierSum);
        return out.toString();
    }
    public synchronized void restoreState(String state) {
        if(state==null || state.length()>512)return;
        try {
            String[] a=state.split(","); if(a.length!=9||!"v1".equals(a[0]))return;
            double[] candidate=new double[weights.length];
            for(int i=0;i<weights.length;i++) {
                candidate[i]=Double.parseDouble(a[i+1]);
                if(!Double.isFinite(candidate[i])||Math.abs(candidate[i])>2.5)return;
            }
            int ev=Integer.parseInt(a[6]), corr=Integer.parseInt(a[7]);
            double bs=Double.parseDouble(a[8]);
            if(ev<0||ev>1000000||corr<0||corr>ev||!Double.isFinite(bs)||bs<0||bs>ev)return;
            System.arraycopy(candidate,0,weights,0,weights.length);
            evaluated=ev;correct=corr;brierSum=bs;
        }catch(Exception ignored){}
    }
    public synchronized int evaluations() { return evaluated; }
    public synchronized String quality() {
        if (evaluated<30) return "CALIBRATING ("+evaluated+" evaluated)";
        return String.format(Locale.US,"Observed hit rate %.1f%% / Brier %.3f (%d)",
            100.0*correct/evaluated,brierSum/evaluated,evaluated);
    }
    public synchronized double brier() { return evaluated==0?Double.NaN:brierSum/evaluated; }
    public synchronized double hitRate() { return evaluated==0?Double.NaN:(double)correct/evaluated; }
}
