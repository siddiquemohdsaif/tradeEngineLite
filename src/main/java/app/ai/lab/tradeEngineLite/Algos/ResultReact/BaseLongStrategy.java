package app.ai.lab.tradeEngineLite.Algos.ResultReact;

import app.ai.lab.tradeEngineLite.GraphUtils.CandleGraphTracker;
import app.ai.lab.tradeEngineLite.GraphUtils.CandleGraphTracker.Candle;

abstract class BaseLongStrategy implements LongStrategy {
    protected CandleGraphTracker tracker;
    protected final double longPoint;          // as per level spec when applicable
    protected final int entryValidityCandles;  // max candles to wait for entry
    protected final int autoExpiryCandles;     // force-close after this many candles
    protected final double targetPct;          // TP
    protected final double stopPct;            // SL
    protected int candlesElapsed = 0;
    protected ActiveTrade activeTrade;

    // For priceScore-based crash/EMA gating
    protected final double priceScore;
    protected final double baselineClose; // record.currentDateClosePrice

    protected BaseLongStrategy(
            double longPoint,
            int entryValidityCandles,
            int autoExpiryCandles,
            double targetPct,
            double stopPct,
            double priceScore,
            double baselineClose
    ) {
        this.longPoint = longPoint;
        this.entryValidityCandles = entryValidityCandles;
        this.autoExpiryCandles = autoExpiryCandles;
        this.targetPct = targetPct;
        this.stopPct = stopPct;
        this.priceScore = priceScore;
        this.baselineClose = baselineClose;
    }

    @Override public void setTracker(CandleGraphTracker t) { this.tracker = t; }
    @Override public ActiveTrade getActiveTrade() { return activeTrade; }
    @Override public boolean isEntryWindowOpen() { return activeTrade == null && candlesElapsed < entryValidityCandles; }
    @Override public void onNewCandle() { candlesElapsed++; }
    @Override public void cancel() { activeTrade = null; candlesElapsed = entryValidityCandles; }

    protected Candle prevCandle() {
        int n = tracker.candles.size();
        return (n >= 2) ? tracker.candles.get(n - 2) : null;
    }

    protected Candle lastCandle() {
        int n = tracker.candles.size();
        return (n >= 1) ? tracker.candles.get(n - 1) : null;
    }

    protected void tryOpenLong(double price) {
        if (activeTrade != null) return;
        int n = tracker.candles.size();
        activeTrade = new ActiveTrade(Side.LONG, price, n - 1, autoExpiryCandles, targetPct, stopPct);
    }

    /** Manage TP/SL/Expiry each tick */
    protected void manageActiveLong(double price) {
        if (activeTrade == null || activeTrade.closed) return;
        double entry = activeTrade.entryPrice;

        // For long: TP hit if price >= entry * (1 + targetPct)
        boolean tp = price >= entry * (1.0 + activeTrade.targetPct);
        // SL hit if price <= entry * (1 - stopPct)
        boolean sl = price <= entry * (1.0 - activeTrade.stopPct);
        int n = tracker.candles.size();

        double exitPrice;
        if (tp) {
            exitPrice = entry * (1.0 + activeTrade.targetPct);
        } else if (sl) {
            exitPrice = entry * (1.0 - activeTrade.stopPct);
        } else {
            exitPrice = price;
        }

        if (tp || sl || activeTrade.isExpired(n - 1, tracker)) {
            System.out.println(String.format(
                "Closing LONG trade: entry=%.2f price=%.2f tp=%b sl=%b expired=%b pnlPct=%.4f pnlAbs=%.2f",
                entry, price, tp, sl,
                activeTrade.isExpired(n - 1, tracker),
                activeTrade.pnlPct(),
                activeTrade.pnlAbs()
            ));
            activeTrade.closed = true;
            activeTrade.exitPrice = exitPrice;
            activeTrade.closedOnCandleIndex = n - 1;
        }
    }

    // ===== priceScore crash/EMA gating =====
    // If priceScore < 8 -> no extra constraints (always true).
    // If >= 8 -> require (crash% from baselineClose) AND cross below prev EMA(20 / 50).
    protected boolean passesCrashAndEMAFilter(double price) {
        Candle prev = prevCandle();
        if (prev == null) return false;

        if (priceScore >= 12.0) {
            double needCrash = 0.04; // 4%
            boolean crashed = price <= baselineClose * (1.0 - needCrash);
            double ema50 = (prev.maValues != null && prev.maValues.length >= 2) ? prev.maValues[1] : Double.NaN; // [200,50,20,10,5,3]
            boolean belowEMA = !Double.isNaN(ema50) && price <= ema50;
            return crashed && belowEMA;
        } else if (priceScore >= 10.0) {
            double needCrash = 0.03; // 3%
            boolean crashed = price <= baselineClose * (1.0 - needCrash);
            double ema50 = (prev.maValues != null && prev.maValues.length >= 2) ? prev.maValues[1] : Double.NaN;
            boolean belowEMA = !Double.isNaN(ema50) && price <= ema50;
            return crashed && belowEMA;
        } else if (priceScore >= 8.0) {
            double needCrash = 0.02; // 2%
            boolean crashed = price <= baselineClose * (1.0 - needCrash);
            double ema20 = (prev.maValues != null && prev.maValues.length >= 3) ? prev.maValues[2] : Double.NaN;
            boolean belowEMA = !Double.isNaN(ema20) && price <= ema20;
            return crashed && belowEMA;
        } else {
            return true; // no gating for priceScore < 8
        }
    }
}
