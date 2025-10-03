package app.ai.lab.tradeEngineLite.GraphUtils;

import java.awt.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class RsiUtilsL {

    private RsiUtilsL() {}

    // =============== RSI COMPUTATION (Wilder) ===============

    public static final class RsiState {
        private final List<Double> values = new ArrayList<>(); // one entry per CLOSED candle (may be null initially)
        private int period;
        private Double prevClose = null;
        private double avgGain = 0.0;
        private double avgLoss = 0.0;
        private int initCount = 0;
        private boolean initialized = false;

        public RsiState(int period) {
            this.period = Math.max(2, period);
        }

        public void reset(int period) {
            this.period = Math.max(2, period);
            values.clear();
            prevClose = null;
            avgGain = 0.0;
            avgLoss = 0.0;
            initCount = 0;
            initialized = false;
        }

        /** Call EXACTLY once per CLOSED candle (i.e., when next candle begins). Returns RSI for the closed candle, or null until seeded. */
        public Double onClose(double close) {
            Double out;
            if (prevClose == null) {
                prevClose = close;
                out = null;
            } else {
                double change = close - prevClose;
                double gain = Math.max(change, 0.0);
                double loss = Math.max(-change, 0.0);

                if (!initialized) {
                    avgGain += gain;
                    avgLoss += loss;
                    initCount++;
                    if (initCount >= period) {
                        avgGain /= period;
                        avgLoss /= period;
                        initialized = true;
                        out = compute(avgGain, avgLoss);
                    } else {
                        out = null;
                    }
                } else {
                    // Wilder smoothing
                    avgGain = ((avgGain * (period - 1)) + gain) / period;
                    avgLoss = ((avgLoss * (period - 1)) + loss) / period;
                    out = compute(avgGain, avgLoss);
                }
                prevClose = close;
            }
            values.add(out);
            return out;
        }

        public List<Double> getValues() { return values; }
        public int getPeriod() { return period; }

        private static double compute(double avgGain, double avgLoss) {
            if (avgLoss == 0.0) return 100.0;
            double rs = avgGain / avgLoss;
            return 100.0 - (100.0 / (1.0 + rs));
        }
    }

    // =============== DIVERGENCE (REGULAR) ===================

    public enum DivergenceType { BEARISH_REGULAR, BULLISH_REGULAR }

    public static final class Pivot {
        public final int index;
        public final double value;
        public Pivot(int i, double v) { index = i; value = v; }
    }

    public static final class Divergence {
        public final DivergenceType type;
        public final int startIndex;   // older pivot index used
        public final int endIndex;     // newer pivot index used
        public final Pivot priceA, priceB; // price pivots
        public final Pivot rsiA, rsiB;     // rsi pivots

        public Divergence(DivergenceType type, Pivot priceA, Pivot priceB, Pivot rsiA, Pivot rsiB) {
            this.type = type;
            this.priceA = priceA;
            this.priceB = priceB;
            this.rsiA = rsiA;
            this.rsiB = rsiB;
            this.startIndex = priceA.index;
            this.endIndex = priceB.index;
        }
    }

    private static final double EPS = 1e-9;

    /** Find local extrema using strict comparison within ±lookback window. */
    public static List<Pivot> findPivots(List<Double> series, int lookback, boolean highs, int minLength) {
        if (series == null || series.size() < (2 * lookback + 1)) return Collections.emptyList();
        int n = series.size();
        List<Pivot> pivots = new ArrayList<>();

        for (int i = lookback; i <= n - 1 - lookback; i++) {
            Double v = series.get(i);
            if (v == null) continue;
            boolean ok = true;
            for (int j = i - lookback; j <= i + lookback; j++) {
                if (j == i) continue;
                Double w = series.get(j);
                if (w == null) { ok = false; break; }
                if (highs) { if (!(v > w + EPS)) { ok = false; break; } }
                else       { if (!(v < w - EPS)) { ok = false; break; } }
            }
            if (ok) pivots.add(new Pivot(i, v));
        }

        // Enforce min spacing; keep more extreme when crowded
        if (minLength > 0 && pivots.size() > 1) {
            List<Pivot> filtered = new ArrayList<>();
            Pivot last = null;
            for (Pivot p : pivots) {
                if (last == null || (p.index - last.index) >= minLength) {
                    filtered.add(p);
                    last = p;
                } else {
                    if (highs ? (p.value > last.value) : (p.value < last.value)) {
                        filtered.set(filtered.size() - 1, p);
                        last = p;
                    }
                }
            }
            pivots = filtered;
        }

        return pivots;
    }

    /** Detects regular divergences (bearish: price HH + RSI LH, bullish: price LL + RSI HL). */
    public static List<Divergence> detectRegularDivergences(
            List<Double> close,
            List<Double> rsi,
            int lookback,
            int minLength
    ) {
        if (close == null || rsi == null || close.size() != rsi.size()) return Collections.emptyList();

        List<Pivot> priceHighs = findPivots(close, lookback, true,  minLength);
        List<Pivot> priceLows  = findPivots(close, lookback, false, minLength);
        List<Pivot> rsiHighs   = findPivots(rsi,   lookback, true,  minLength);
        List<Pivot> rsiLows    = findPivots(rsi,   lookback, false, minLength);

        List<Divergence> out = new ArrayList<>();

        // Bearish (match consecutive price highs with nearest RSI highs)
        for (int k = 1; k < priceHighs.size(); k++) {
            Pivot p1 = priceHighs.get(k - 1);
            Pivot p2 = priceHighs.get(k);
            Pivot r1 = nearestPivotAround(rsiHighs, p1.index, lookback);
            Pivot r2 = nearestPivotAround(rsiHighs, p2.index, lookback);
            if (r1 == null || r2 == null || r2.index <= r1.index) continue;

            boolean priceHH = p2.value > p1.value + EPS;
            boolean rsiLH   = r2.value < r1.value - EPS;
            if (priceHH && rsiLH) out.add(new Divergence(DivergenceType.BEARISH_REGULAR, p1, p2, r1, r2));
        }

        // Bullish (match consecutive price lows with nearest RSI lows)
        for (int k = 1; k < priceLows.size(); k++) {
            Pivot p1 = priceLows.get(k - 1);
            Pivot p2 = priceLows.get(k);
            Pivot r1 = nearestPivotAround(rsiLows, p1.index, lookback);
            Pivot r2 = nearestPivotAround(rsiLows, p2.index, lookback);
            if (r1 == null || r2 == null || r2.index <= r1.index) continue;

            boolean priceLL = p2.value < p1.value - EPS;
            boolean rsiHL   = r2.value > r1.value + EPS;
            if (priceLL && rsiHL) out.add(new Divergence(DivergenceType.BULLISH_REGULAR, p1, p2, r1, r2));
        }

        // chronological
        out.sort(Comparator.comparingInt(d -> d.endIndex));
        return out;
    }

    private static Pivot nearestPivotAround(List<Pivot> pivots, int idx, int window) {
        Pivot best = null; int bestDist = Integer.MAX_VALUE;
        for (Pivot p : pivots) {
            int d = Math.abs(p.index - idx);
            if (d <= window && d < bestDist) { best = p; bestDist = d; }
        }
        return best;
    }

    // =============== DRAW HELPERS ============================

    private static double xToPixel(double xCandleCoord, int n, int plotX, int plotW) {
        return plotX + (xCandleCoord / (double) n) * plotW;
    }

    private static int rsiToPixel(double value, int plotY, int plotH) {
        double norm = value / 100.0;
        return plotY + (int) Math.round(plotH - norm * plotH);
    }

    /** Draws divergence slanted lines + labels onto the RSI panel. */
    public static void drawDivergencesOnRsi(Graphics2D g,
                                            List<Divergence> divergences,
                                            List<Double> rsiSeries, // aligned to candles
                                            int n,
                                            int plotX, int plotW,
                                            int rsiY, int rsiH) {
        if (divergences == null || divergences.isEmpty() || rsiSeries == null) return;

        Stroke oldStroke = g.getStroke();
        Font oldFont = g.getFont();
        g.setFont(new Font("SansSerif", Font.BOLD, 20));

        for (Divergence d : divergences) {
            int x1 = (int) xToPixel(d.startIndex + 0.5, n, plotX, plotW);
            int x2 = (int) xToPixel(d.endIndex   + 0.5, n, plotX, plotW);

            Double r1 = safeGet(rsiSeries, d.startIndex);
            Double r2 = safeGet(rsiSeries, d.endIndex);
            if (r1 == null || r2 == null) continue;

            int y1 = rsiToPixel(r1, rsiY, rsiH);
            int y2 = rsiToPixel(r2, rsiY, rsiH);

            Color col = (d.type == DivergenceType.BEARISH_REGULAR) ? Color.RED : new Color(0, 153, 0);
            g.setColor(col);
            g.setStroke(new BasicStroke(2.5f));
            g.drawLine(x1, y1, x2, y2);

            String label = (d.type == DivergenceType.BEARISH_REGULAR) ? "Bearish div" : "Bullish div";
            g.drawString(label, x2 + 6, y2 - 6);
        }

        g.setStroke(oldStroke);
        g.setFont(oldFont);
    }

    private static Double safeGet(List<Double> list, int idx) {
        return (idx >= 0 && idx < list.size()) ? list.get(idx) : null;
    }

    /** Utility: pad/align RSI to length n (filling missing with null). */
    public static List<Double> alignToLength(List<Double> in, int n) {
        List<Double> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add((i < in.size()) ? in.get(i) : null);
        return out;
    }
}
