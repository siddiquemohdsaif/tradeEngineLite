package app.ai.lab.tradeEngineLite.Algos.ResultReact;

public class LongL4_PointOrEMA5 extends BaseLongStrategy {
    public LongL4_PointOrEMA5(double currentDateClosePrice, double priceScore, double baselineClose) {
        super(1.03 * currentDateClosePrice, /*entryValidity*/15, /*autoExpiry*/30, /*TP*/0.10, /*SL*/0.04, priceScore, baselineClose);
    }

    @Override public void onTick(long ts, double price) {
        if (isEntryWindowOpen() && getActiveTrade() == null) {
            if (price <= longPoint && passesCrashAndEMAFilter(price)) {
                tryOpenLong(price);
            } else {
                var prev = prevCandle();
                if (prev != null && prev.maValues != null && prev.maValues.length >= 5) {
                    double ema5_prev = prev.maValues[4];
                    if (price >= ema5_prev && passesCrashAndEMAFilter(price)) {
                        tryOpenLong(ema5_prev);
                    }
                }
            }
        }
        manageActiveLong(price);
    }
}
