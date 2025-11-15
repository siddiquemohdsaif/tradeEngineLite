package app.ai.lab.tradeEngineLite.Algos.ResultReact;

import app.ai.lab.tradeEngineLite.GraphUtils.CandleGraphTracker;

public class ShortL3_PointOrEMA5 extends BaseShortStrategy {
    public ShortL3_PointOrEMA5(double currentDateClosePrice) {
        super(0.97 * currentDateClosePrice, /*entryValidity*/10, /*autoExpiry*/30, /*TP*/0.07, /*SL*/0.04);
    }

    @Override public void onTick(long ts, double price) {
        if (isEntryWindowOpen() && getActiveTrade() == null) {
            if (price >= shortPoint) {
                tryOpenShort(price);
            } else {
                var prev = prevCandle();
                if (prev != null && prev.maValues != null && prev.maValues.length >= 5) {
                    double ema5_prev = prev.maValues[4];
                    if (price >= ema5_prev) {
                        tryOpenShort(ema5_prev);
                    }
                }
            }
        }
        manageActiveShort(price);
    }
}
