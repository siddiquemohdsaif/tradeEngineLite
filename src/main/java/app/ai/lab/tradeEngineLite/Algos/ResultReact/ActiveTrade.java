package app.ai.lab.tradeEngineLite.Algos.ResultReact;

import app.ai.lab.tradeEngineLite.GraphUtils.CandleGraphTracker;

enum Side {
    LONG, SHORT
}

enum ShortLevel {
    L1, L2, L3, L4
}

/** Simple container for a live trade managed inside strategies */
final class ActiveTrade {
    final Side side;
    final double entryPrice;
    final int openedOnCandleIndex; // index in tracker.candles at entry time
    final int autoExpiryCandles;
    final double targetPct; // e.g. 0.03 for 3%
    final double stopPct; // e.g. 0.03 for 3%

    boolean closed = false;
    boolean logged = false;
    Double exitPrice = null;
    int closedOnCandleIndex = -1;

    ActiveTrade(Side side, double entryPrice, int openedOnCandleIndex,
            int autoExpiryCandles, double targetPct, double stopPct) {
        this.side = side;
        this.entryPrice = entryPrice;
        this.openedOnCandleIndex = openedOnCandleIndex;
        this.autoExpiryCandles = autoExpiryCandles;
        this.targetPct = targetPct;
        this.stopPct = stopPct;
        System.out.println(String.format(
                "ActiveTrade{side=%s, entryPrice=%s, openedOnCandleIndex=%d, autoExpiryCandles=%d, targetPct=%s, stopPct=%s}",
                side, entryPrice, openedOnCandleIndex, autoExpiryCandles, targetPct, stopPct));
    }

    boolean isExpired(int currentCandleIndex, CandleGraphTracker tracker) {
        boolean is_current_date_almost_closed = false;
        // time must be greater equal to 2:55 pm ist to consider expiry on daily candles , as market closes at 3:30 pm ist so it nears closure
        long lastTickTime = tracker.marketGraph.get(tracker.marketGraph.size() -1).time;
        java.util.Calendar cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Kolkata"));
        cal.setTimeInMillis(lastTickTime);
        int hour = cal.get(java.util.Calendar.HOUR_OF_DAY);
        int minute = cal.get(java.util.Calendar.MINUTE);    
        if (hour > 14 || (hour == 14 && minute >= 55)) {
            is_current_date_almost_closed = true;
        }

        return (currentCandleIndex - openedOnCandleIndex) >= (autoExpiryCandles - 1)  && is_current_date_almost_closed;
    }
    
    /** PnL percent (e.g. 0.031 = +3.1%) */
    double pnlPct() {
        if (!closed || exitPrice == null)
            return 0.0;
        if (side == Side.SHORT) {
            return (entryPrice - exitPrice) / entryPrice;
        } else {
            return (exitPrice - entryPrice) / entryPrice;
        }
    }

    /** PnL per 1 unit (absolute) */
    double pnlAbs() {
        if (!closed || exitPrice == null)
            return 0.0;
        if (side == Side.SHORT) {
            return entryPrice - exitPrice;
        } else {
            return exitPrice - entryPrice;
        }
    }
}
