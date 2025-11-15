package app.ai.lab.tradeEngineLite.Algos.ResultReact;

import app.ai.lab.tradeEngineLite.GraphUtils.CandleGraphTracker.Candle;

public class LongL2_PointOrEMA10 extends BaseLongStrategy {
    public LongL2_PointOrEMA10(double currentDateClosePrice, double priceScore, double baselineClose) {
        super(1.02 * currentDateClosePrice, /*entryValidity*/10, /*autoExpiry*/15, /*TP*/0.05, /*SL*/0.03, priceScore, baselineClose);
    }

    @Override public void onTick(long ts, double price) {
        if (isEntryWindowOpen() && getActiveTrade() == null) {
            if (price <= longPoint && passesCrashAndEMAFilter(price)) {
                tryOpenLong(price);
            } else {
                Candle prev = prevCandle();
                if (prev != null && prev.maValues != null && prev.maValues.length >= 4) {
                    double ema10_prev = prev.maValues[3]; // [200,50,20,10,5,3]
                    if (price <= ema10_prev && passesCrashAndEMAFilter(price)) {
                        tryOpenLong(ema10_prev);
                    }
                }
            }
        }
        manageActiveLong(price);
    }
}
