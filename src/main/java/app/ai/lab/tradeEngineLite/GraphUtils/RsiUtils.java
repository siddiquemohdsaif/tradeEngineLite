package app.ai.lab.tradeEngineLite.GraphUtils;

import java.awt.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * RSI + Divergence utilities aligned to the TradingView Pine script:
 * - Pivots are computed on the RSI using (lbL, lbR) like ta.pivotlow/ta.pivothigh
 * - Regular & Hidden divergences use the exact comparisons from the provided Pine script
 * - Range filter equals distance (in bars) between consecutive RSI pivots (centers)
 * - Four plot toggles to enable/disable sets
 *
 * Alerts are intentionally NOT implemented.
 */
public final class RsiUtils {

    private RsiUtils() {}

    // ========================= Wilder RSI =========================

    public static final class RsiState {
        private final List<Double> values = new ArrayList<>(); // one per CLOSED candle (nullable until seeded)
        private int period;
        private Double prevClose = null;
        private double avgGain = 0.0;
        private double avgLoss = 0.0;
        private int initCount = 0;
        private boolean initialized = false;

        public RsiState(int period) { this.period = Math.max(2, period); }

        public void reset(int period) {
            this.period = Math.max(2, period);
            values.clear();
            prevClose = null;
            avgGain = 0.0;
            avgLoss = 0.0;
            initCount = 0;
            initialized = false;
        }

        /** Call once per CLOSED candle. Returns RSI for the closed candle, or null until seeded. */
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

    // ========================= Divergence Core =========================

    public enum DivergenceType {
        REGULAR_BULLISH,   // price LL, rsi HL
        HIDDEN_BULLISH,    // price HL, rsi LL
        REGULAR_BEARISH,   // price HH, rsi LH
        HIDDEN_BEARISH     // price LH, rsi HH
    }

    public static final class Pivot {
        public final int index;   // center index of the pivot (i)
        public final double value;
        public Pivot(int i, double v) { index = i; value = v; }
    }

    public static final class Divergence {
        public final DivergenceType type;
        public final int startIndex;     // older RSI pivot center
        public final int endIndex;       // newer RSI pivot center
        // values at RSI pivot centers:
        public final double priceA, priceB;
        public final double rsiA, rsiB;

        public Divergence(DivergenceType type, int startIdx, int endIdx,
                          double priceA, double priceB,
                          double rsiA, double rsiB) {
            this.type = type;
            this.startIndex = startIdx;
            this.endIndex = endIdx;
            this.priceA = priceA;
            this.priceB = priceB;
            this.rsiA = rsiA;
            this.rsiB = rsiB;
        }
    }

    public static final class Config {
        public int lbL = 5;
        public int lbR = 1;
        public int rangeLower = 5;
        public int rangeUpper = 60;

        public boolean plotBull = true;
        public boolean plotHiddenBull = true;
        public boolean plotBear = true;
        public boolean plotHiddenBear = true;

        // Colors (optional; used by draw helpers)
        public Color bearColor = Color.RED;
        public Color bullColor = new Color(0, 128, 0);
        public Color hiddenBullColor = new Color(0, 128, 0, 100);
        public Color hiddenBearColor = new Color(255, 0, 0, 100);

        public Config() {}
    }

    private static final double EPS = 1e-12;

    /** Returns center indices where series[i] is a local low within [i-lbL, i+lbR], strict compare. */
    private static List<Integer> pivotLowsIndices(List<Double> series, int lbL, int lbR) {
        List<Integer> out = new ArrayList<>();
        if (series == null || series.size() < lbL + lbR + 1) return out;
        final int n = series.size();
        for (int i = lbL; i <= n - 1 - lbR; i++) {
            Double v = series.get(i);
            if (v == null) continue;
            boolean ok = true;
            for (int j = i - lbL; j <= i + lbR; j++) {
                if (j == i) continue;
                Double w = series.get(j);
                if (w == null || !(v < w - EPS)) { ok = false; break; }
            }
            if (ok) out.add(i);
        }
        return out;
    }

    /** Returns center indices where series[i] is a local high within [i-lbL, i+lbR], strict compare. */
    private static List<Integer> pivotHighsIndices(List<Double> series, int lbL, int lbR) {
        List<Integer> out = new ArrayList<>();
        if (series == null || series.size() < lbL + lbR + 1) return out;
        final int n = series.size();
        for (int i = lbL; i <= n - 1 - lbR; i++) {
            Double v = series.get(i);
            if (v == null) continue;
            boolean ok = true;
            for (int j = i - lbL; j <= i + lbR; j++) {
                if (j == i) continue;
                Double w = series.get(j);
                if (w == null || !(v > w + EPS)) { ok = false; break; }
            }
            if (ok) out.add(i);
        }
        return out;
    }

    private static boolean inRange(int deltaBars, int rangeLower, int rangeUpper) {
        return deltaBars >= rangeLower && deltaBars <= rangeUpper;
    }

    private static Double val(List<Double> a, int i) {
        return (i >= 0 && i < a.size()) ? a.get(i) : null;
    }

    /**
     * Detect divergences exactly like the Pine script:
     * - Pivots are computed on RSI
     * - Comparisons use values at RSI pivot centers for both RSI and price
     * - Range filter uses spacing between consecutive RSI pivots
     *
     * Inputs must be aligned (one value per candle).
     *
     * @param close Close prices (unused in comparisons but kept for completeness)
     * @param high  High prices
     * @param low   Low prices
     * @param rsi   RSI series
     * @param cfg   Config (lbL, lbR, ranges, toggles)
     * @return list of divergences (filtered by toggles)
     */
    public static List<Divergence> detectDivergences(
            List<Double> close,
            List<Double> high,
            List<Double> low,
            List<Double> rsi,
            Config cfg
    ) {
        List<Divergence> out = new ArrayList<>();
        if (high == null || low == null || rsi == null) return out;
        int n = rsi.size();
        if (high.size() != n || low.size() != n) return out;

        // RSI pivots (centers)
        final List<Integer> plCenters = pivotLowsIndices(rsi, cfg.lbL, cfg.lbR);
        final List<Integer> phCenters = pivotHighsIndices(rsi, cfg.lbL, cfg.lbR);

        // ----- Regular & Hidden Bullish (use RSI pivot lows) -----
        if (!plCenters.isEmpty()) {
            int prev = -1;
            for (int idx = 0; idx < plCenters.size(); idx++) {
                int curr = plCenters.get(idx);
                if (prev >= 0) {
                    int barsBetweenCenters = curr - prev; // == Pine's barssince(plFound[1]) distance
                    if (inRange(barsBetweenCenters, cfg.rangeLower, cfg.rangeUpper)) {
                        Double rPrev = val(rsi, prev);
                        Double rCurr = val(rsi, curr);
                        Double pPrev = val(low, prev);
                        Double pCurr = val(low, curr);
                        if (rPrev != null && rCurr != null && pPrev != null && pCurr != null) {
                            // Regular Bullish: price LL & RSI HL
                            if (cfg.plotBull) {
                                boolean priceLL = pCurr < pPrev - EPS;
                                boolean rsiHL   = rCurr > rPrev + EPS;
                                if (priceLL && rsiHL) {
                                    out.add(new Divergence(
                                            DivergenceType.REGULAR_BULLISH, prev, curr,
                                            pPrev, pCurr, rPrev, rCurr));
                                }
                            }
                            // Hidden Bullish: price HL & RSI LL
                            if (cfg.plotHiddenBull) {
                                boolean priceHL = pCurr > pPrev + EPS;
                                boolean rsiLL   = rCurr < rPrev - EPS;
                                if (priceHL && rsiLL) {
                                    out.add(new Divergence(
                                            DivergenceType.HIDDEN_BULLISH, prev, curr,
                                            pPrev, pCurr, rPrev, rCurr));
                                }
                            }
                        }
                    }
                }
                prev = curr;
            }
        }

        // ----- Regular & Hidden Bearish (use RSI pivot highs) -----
        if (!phCenters.isEmpty()) {
            int prev = -1;
            for (int idx = 0; idx < phCenters.size(); idx++) {
                int curr = phCenters.get(idx);
                if (prev >= 0) {
                    int barsBetweenCenters = curr - prev;
                    if (inRange(barsBetweenCenters, cfg.rangeLower, cfg.rangeUpper)) {
                        Double rPrev = val(rsi, prev);
                        Double rCurr = val(rsi, curr);
                        Double pPrev = val(high, prev);
                        Double pCurr = val(high, curr);
                        if (rPrev != null && rCurr != null && pPrev != null && pCurr != null) {
                            // Regular Bearish: price HH & RSI LH
                            if (cfg.plotBear) {
                                boolean priceHH = pCurr > pPrev + EPS;
                                boolean rsiLH   = rCurr < rPrev - EPS;
                                if (priceHH && rsiLH) {
                                    out.add(new Divergence(
                                            DivergenceType.REGULAR_BEARISH, prev, curr,
                                            pPrev, pCurr, rPrev, rCurr));
                                }
                            }
                            // Hidden Bearish: price LH & RSI HH
                            if (cfg.plotHiddenBear) {
                                boolean priceLH = pCurr < pPrev - EPS;
                                boolean rsiHH   = rCurr > rPrev + EPS;
                                if (priceLH && rsiHH) {
                                    out.add(new Divergence(
                                            DivergenceType.HIDDEN_BEARISH, prev, curr,
                                            pPrev, pCurr, rPrev, rCurr));
                                }
                            }
                        }
                    }
                }
                prev = curr;
            }
        }

        // Chronological like Pine (by newer pivot center)
        out.sort(Comparator.comparingInt(d -> d.endIndex));
        return out;
    }

    // ========================= Draw Helpers (optional) =========================

    /**
     * Draw divergence lines and labels on an RSI pane.
     * @param g       Graphics2D
     * @param divs    divergences (from detectAllLikePine)
     * @param rsi     RSI series
     * @param n       total candle count
     * @param plotX   left x of RSI plot area
     * @param plotW   width of RSI plot area
     * @param rsiY    top y of RSI plot area
     * @param rsiH    height of RSI plot area
     * @param cfg     config for colors
     */
    public static void drawDivergencesOnRsi(Graphics2D g,
                                     List<Divergence> divs,
                                     List<Double> rsi,
                                     int n,
                                     int plotX, int plotW,
                                     int rsiY, int rsiH,
                                     Config cfg) {
        if (divs == null || divs.isEmpty() || rsi == null) return;

        Stroke oldStroke = g.getStroke();
        Font oldFont = g.getFont();
        g.setFont(new Font("SansSerif", Font.BOLD, 18));

        for (Divergence d : divs) {
            Double r1 = val(rsi, d.startIndex);
            Double r2 = val(rsi, d.endIndex);
            if (r1 == null || r2 == null) continue;

            int x1 = (int) (plotX + ((d.startIndex + 0.5) / (double) n) * plotW);
            int x2 = (int) (plotX + ((d.endIndex   + 0.5) / (double) n) * plotW);
            int y1 = rsiToPixel(r1, rsiY, rsiH);
            int y2 = rsiToPixel(r2, rsiY, rsiH);

            Color c = switch (d.type) {
                case REGULAR_BULLISH -> cfg.bullColor;
                case HIDDEN_BULLISH -> cfg.hiddenBullColor;
                case REGULAR_BEARISH -> cfg.bearColor;
                case HIDDEN_BEARISH -> cfg.hiddenBearColor;
            };

            g.setColor(c);
            g.setStroke(new BasicStroke(2.5f));
            g.drawLine(x1, y1, x2, y2);
            g.drawString(labelFor(d.type), x2 + 6, y2 - 6);
        }

        g.setStroke(oldStroke);
        g.setFont(oldFont);
    }

    private static int rsiToPixel(double value, int plotY, int plotH) {
        double norm = Math.max(0.0, Math.min(100.0, value)) / 100.0;
        return plotY + (int) Math.round(plotH - norm * plotH);
    }

    private static String labelFor(DivergenceType t) {
        return switch (t) {
            case REGULAR_BULLISH -> "Bull";
            case HIDDEN_BULLISH  -> "H Bull";
            case REGULAR_BEARISH -> "Bear";
            case HIDDEN_BEARISH  -> "H Bear";
        };
    }

    // ========================= Utilities =========================

    /** Pads/aligns a list to length n (filling missing with null). */
    public static List<Double> alignToLength(List<Double> in, int n) {
        List<Double> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add((in != null && i < in.size()) ? in.get(i) : null);
        return out;
    }
}
