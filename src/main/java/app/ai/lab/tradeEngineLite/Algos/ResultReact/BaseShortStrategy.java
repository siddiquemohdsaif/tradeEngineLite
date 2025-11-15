package app.ai.lab.tradeEngineLite.Algos.ResultReact;

import app.ai.lab.tradeEngineLite.GraphUtils.CandleGraphTracker;
import app.ai.lab.tradeEngineLite.GraphUtils.CandleGraphTracker.Candle;

abstract class BaseShortStrategy implements ShortStrategy {
    protected CandleGraphTracker tracker;
    protected final double shortPoint;         // as per level spec when applicable
    protected final int entryValidityCandles;  // max candles to wait for entry
    protected final int autoExpiryCandles;     // force-close after this many candles
    protected final double targetPct;          // TP
    protected final double stopPct;            // SL
    protected int candlesElapsed = 0;
    protected ActiveTrade activeTrade;

    protected BaseShortStrategy(double shortPoint, int entryValidityCandles,
                                int autoExpiryCandles, double targetPct, double stopPct) {
        this.shortPoint = shortPoint;
        this.entryValidityCandles = entryValidityCandles;
        this.autoExpiryCandles = autoExpiryCandles;
        this.targetPct = targetPct;
        this.stopPct = stopPct;
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

    protected void tryOpenShort(double price) {
        if (activeTrade != null) return;
        int n = tracker.candles.size();
        activeTrade = new ActiveTrade(Side.SHORT, price, n - 1, autoExpiryCandles, targetPct, stopPct);
    }

    /** Manage TP/SL/Expiry each tick */
    protected void manageActiveShort(double price) {
        if (activeTrade == null || activeTrade.closed) return;
        double entry = activeTrade.entryPrice;
        // For short: TP hit if price <= entry * (1 - targetPct)
        boolean tp = price <= entry * (1.0 - activeTrade.targetPct);
        // SL hit if price >= entry * (1 + stopPct)
        boolean sl = price >= entry * (1.0 + activeTrade.stopPct);
        int n = tracker.candles.size();

        double exitPrice;
        if (tp) {
            exitPrice = entry * (1.0 - activeTrade.targetPct);
        } else if (sl) {
            exitPrice = entry * (1.0 + activeTrade.stopPct);
        }else {
            exitPrice = price;
        }

        if (tp || sl || activeTrade.isExpired(n - 1,  tracker)) {
            System.out.println();String.format(
                "Closing SHORT trade: entry=%.2f price=%.2f tp=%b sl=%b expired=%b pnlPct=%.4f pnlAbs=%.2f",
                entry, price, tp, sl,
                activeTrade.isExpired(n - 1, tracker),
                activeTrade.pnlPct(),
                activeTrade.pnlAbs()
            );
            activeTrade.closed = true;
            activeTrade.exitPrice = exitPrice;
            activeTrade.closedOnCandleIndex = n - 1;
        }
    }
}
