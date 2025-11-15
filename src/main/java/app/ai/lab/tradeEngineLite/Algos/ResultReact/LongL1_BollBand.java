package app.ai.lab.tradeEngineLite.Algos.ResultReact;

public class LongL1_BollBand extends BaseLongStrategy {
    public LongL1_BollBand(double priceScore, double baselineClose) {
        super(Double.NaN, /*entryValidity*/10, /*autoExpiry*/10, /*TP*/0.03, /*SL*/0.03, priceScore, baselineClose);
    }

    @Override public void onTick(long ts, double price) {
        if (isEntryWindowOpen() && getActiveTrade() == null) {
            var prev = prevCandle();
            if (prev != null) {
                double prevLower = prev.bollinger.lowerBand;
                if (price < prevLower && passesCrashAndEMAFilter(price)) {
                    tryOpenLong(prevLower);
                }
            }
        }
        manageActiveLong(price);
    }
}
