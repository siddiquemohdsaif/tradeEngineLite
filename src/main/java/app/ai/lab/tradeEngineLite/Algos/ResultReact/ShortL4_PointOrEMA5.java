package app.ai.lab.tradeEngineLite.Algos.ResultReact;

import app.ai.lab.tradeEngineLite.GraphUtils.CandleGraphTracker;

public class ShortL4_PointOrEMA5 extends BaseShortStrategy {
    public ShortL4_PointOrEMA5(double currentDateClosePrice) {
        super(0.97 * currentDateClosePrice, /*entryValidity*/10, /*autoExpiry*/30, /*TP*/0.10, /*SL*/0.04);
    }

    @Override public void onTick(long ts, double price) {
        System.out.println("ShortL4_PointOrEMA5:onTick ts=" + ts + " price=" + price);
        if (isEntryWindowOpen() && getActiveTrade() == null) {
            if (price >= shortPoint) {
                System.out.println("ShortL4_PointOrEMA5:onTick trying short at point " + shortPoint);
                tryOpenShort(price);
            } else {
                System.out.println("ShortL4_PointOrEMA5:onTick checking EMA5 condition at price " + price);
                var prev = prevCandle();
                if (prev != null && prev.maValues != null && prev.maValues.length >= 5) {
                    System.out.println("ShortL4_PointOrEMA5:onTick prev EMA5=" + prev.maValues[4]);
                    double ema5_prev = prev.maValues[4];
                    if (price >= ema5_prev) {
                        tryOpenShort(ema5_prev);  // short at point even if EMA5 triggered
                    }
                }
            }
        }
        manageActiveShort(price);
    }
}
