package app.ai.lab.tradeEngineLite.Algos.ResultReact;

import app.ai.lab.tradeEngineLite.GraphUtils.CandleGraphTracker;
import app.ai.lab.tradeEngineLite.GraphUtils.CandleGraphTracker.Candle;

public class ShortL2_PointOrEMA10 extends BaseShortStrategy {
    public ShortL2_PointOrEMA10(double currentDateClosePrice) {
        super(0.98 * currentDateClosePrice, /*entryValidity*/10, /*autoExpiry*/15, /*TP*/0.05, /*SL*/0.03);
    }

    @Override public void onTick(long ts, double price) {
        if (isEntryWindowOpen() && getActiveTrade() == null) {
            if (price >= shortPoint) {
                tryOpenShort(price);
            } else {
                Candle prev = prevCandle();
                if (prev != null && prev.maValues != null && prev.maValues.length >= 4) {
                    double ema10_prev = prev.maValues[3]; // periods: [200,50,20,10,5,3]
                    if (price >= ema10_prev) {
                        tryOpenShort(ema10_prev);
                    }
                }
            }
        }
        manageActiveShort(price);
    }
}
