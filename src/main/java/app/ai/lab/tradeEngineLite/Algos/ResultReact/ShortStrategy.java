package app.ai.lab.tradeEngineLite.Algos.ResultReact;

import app.ai.lab.tradeEngineLite.GraphUtils.CandleGraphTracker;

interface ShortStrategy {
    /** Called every tick; may open a trade while entry-validity lasts. */
    void onTick(long ts, double price);

    /** Called when a new *daily* candle starts (tickCount == 1). */
    void onNewCandle();

    /** Give strategy a view to the tracker to read EMAs/BB etc. */
    void setTracker(CandleGraphTracker tracker);

    /** Returns the active managed trade (if any). */
    ActiveTrade getActiveTrade();

    /** Whether entry window (validity in trading candles) is still open. */
    boolean isEntryWindowOpen();

    /** Clean up when we must cancel/ignore shorts (e.g., priceScore <= -8). */
    void cancel();
}
