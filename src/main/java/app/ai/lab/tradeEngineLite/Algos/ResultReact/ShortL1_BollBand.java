package app.ai.lab.tradeEngineLite.Algos.ResultReact;

import app.ai.lab.tradeEngineLite.GraphUtils.CandleGraphTracker;

public class ShortL1_BollBand extends BaseShortStrategy {
    public ShortL1_BollBand() {
        super(Double.NaN, /*entryValidity*/10, /*autoExpiry*/10, /*TP*/0.03, /*SL*/0.03);
    }

    @Override public void onTick(long ts, double price) {
        // Entry window only, and if not already in trade
        if (isEntryWindowOpen() && getActiveTrade() == null) {
            var prev = prevCandle();
            if (prev != null) {
                double prevUpper = prev.bollinger.upperBand;
                // "when price goes above previous day's bollinger high band point it short"
                if (price > prevUpper) {
                    tryOpenShort(prevUpper); // take entry price as prevUpper
                }
            }
        }
        manageActiveShort(price);
    }
}
