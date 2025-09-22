package app.ai.lab.tradeEngineLite.Algos.ema_20_v1;

import app.ai.lab.tradeEngineLite.BackTest.Engine.HistoricalData.Block;
import app.ai.lab.tradeEngineLite.BackTest.Exchange.OrderManagementService;
import app.ai.lab.tradeEngineLite.BackTest.Exchange.VirtualExchange;
import app.ai.lab.tradeEngineLite.GraphUtils.CandleGraphTracker;
import app.ai.lab.tradeEngineLite.GraphUtils.CandleGraphTracker.MAType;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.awt.Color;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 20-EMA "hit" long strategy (daily candles).
 *
 * Core rules:
 *  1) Trend filter: EMA(20) strictly increasing for the last 7 CLOSED candles
 *     (i.e., 7 values in a row, each > the previous).
 *  2) Extra filter: for EACH of the last 7 CLOSED candles, that candle's CLOSE
 *     must be > the PREVIOUS candle's EMA(20).
 *  3) Today's OPEN must be ABOVE yesterday's EMA(20); otherwise NO TRADE today.
 *  4) Entry trigger (intraday): price <= (yesterday's EMA(20) * 0.999)  // 0.1% below
 *  5) Targets:
 *        - Take-profit when price >= yesterday's Upper Bollinger(20, 2), at ANY time; OR
 *        - +5% profit, but ONLY after holding at least 3 FULL candles (days).
 *  6) Stop loss: -3% from entry.
 *  7) Timeout: Square-off after 20 FULL candles have elapsed since entry.
 *
 * Notes:
 *  - This class expects you to feed ticks in order through onBlock(...).
 *  - Uses CandleGraphTracker with EMA stack including period 20 and Bollinger(20,2).
 *  - All EMA/Bollinger reads are from CLOSED candles unless specified (i.e., "yesterday").
 *
 * NEW:
 *  - Public trade state (enum) reflecting current mode of the strategy.
 *  - Optional once-per-day status logging (toggle via dailyStatusLoggingEnabled).
 */
public class LogicalCore {

    // ===== Public state (NEW) =====
    public enum TradeState { WARMUP, NOT_ELIGIBLE, ELIGIBLE_WAITING_TRIGGER, IN_POSITION }
    /** Current external state snapshot. Updated on new candle, entry/exit. */
    public volatile TradeState state = TradeState.WARMUP;
    /** Helpful note describing why a state is set (e.g., which gate failed). */
    public volatile String stateNote = "";

    /** Enable/disable entry/exit trade logs. (Default true) */
    public volatile boolean tradeLoggingEnabled = true;
    /** Enable/disable once-per-day status log. (Default true) */
    public volatile boolean dailyStatusLoggingEnabled = true;

    // ===== Tunables =====
    private static final int    LOOKBACK_EMA_TREND = 7;       // 7 closed candles
    private static final long   CANDLE_SECONDS     = 86_400;  // daily candles
    private static final double ENTRY_BAND         = 0.001;   // 0.1% below prev EMA20
    private static final double TP_PCT             = 0.05;    // +5%
    private static final double SL_PCT             = 0.03;    // -3%
    private static final int    TP_DELAY_CANDLES   = 3;       // wait 3 full candles for +5% TP
    private static final int    TIMEOUT_CANDLES    = 20;      // square-off after 20 full candles

    // ===== Wiring =====
    private final int instrumentId;
    private final String name;
    private final OrderManagementService oms;
    private final CandleGraphTracker tracker;

    private final int[] maPeriods = new int[]{200, 50, 20, 10, 5, 3};
    private final int ema20Index;

    // ===== State =====
    private boolean inPosition = false;
    private double entryPrice = 0.0;
    private long entryTime = 0L;
    private int entryCandleIdx = -1;

    private double stopLossPx = 0.0;
    private double targetPctPx = 0.0;
    private double targetBollUpperPrev = Double.NaN;

    private double totalProfit = 0.0;

    // Per-day eligibility gate (recomputed when a new candle starts)
    private long lastSeenCandleTs = Long.MIN_VALUE;
    private boolean todayEligibleForEntry = false;
    private double prevEma20ForDay = Double.NaN;

    // Optional flags (for logging/debug)
    private boolean lastTrendOk = false;
    private boolean lastOpenOk = false;
    private boolean lastCloseAbovePrevEmaOk = false;

    // Daily status log guard (NEW) — ensures exactly one log per candle/day
    private long lastDailyLogCandleTs = Long.MIN_VALUE;

    public LogicalCore(int instrumentId, String name, OrderManagementService oms) {
        this.instrumentId = instrumentId;
        this.name = name;
        this.oms = oms;

        // Daily timeframe, EMA config including 20
        this.tracker = new CandleGraphTracker(instrumentId, name, CANDLE_SECONDS, maPeriods, MAType.EXPONENTIAL);

        // Optional: nicer palette for rendering later
        this.tracker.modifyMaPalette(new Color[]{
                new Color(238,101,46,255), // orange
                new Color(0, 165, 83),     // green
                new Color(0, 128, 255),    // blue
                new Color(233, 8, 140),    // magenta
                new Color(255, 0, 0),      // red
                new Color(50, 50, 50)      // dark gray
        });

        int idx = -1;
        for (int i = 0; i < maPeriods.length; i++) if (maPeriods[i] == 20) { idx = i; break; }
        if (idx < 0) throw new IllegalStateException("EMA(20) not configured!");
        this.ema20Index = idx;
    }

    /** Feed blocks in timestamp order. */
    public void onBlock(Block block) {
        if (block.getInfo() == null) return;

        for (Block.PacketData pd : block.getInfo()) {
            if (pd instanceof Block.StockPacket ip && ip.getInstrumentToken() == instrumentId) {
                final long ts = block.getTimeStamp();
                final double price = ip.getLastTradedPrice() / 100.0;

                tracker.addMarketData(ts, price);

                final int n = tracker.candles.size();
                if (n < 21) {
                    // Not enough data to evaluate → WARMUP unless already in a trade
                    if (!inPosition) {
                        state = TradeState.WARMUP;
                        stateNote = "Waiting for sufficient history (need >= 21 candles with EMA).";
                    }
                    continue;
                }

                // Detect new daily candle to recompute eligibility & daily log
                long curCandleTs = tracker.candles.get(n - 1).timestamp;
                if (curCandleTs != lastSeenCandleTs) {
                    lastSeenCandleTs = curCandleTs;
                    recomputeTodayEligibility(curCandleTs);
                }

                // Keep live state in sync
                if (!inPosition) {
                    maybeEnter(price, ts);
                } else {
                    state = TradeState.IN_POSITION; // reflect live trade
                    stateNote = "Managing position: SL/TP/Timeout checks active.";
                    maybeExit(price, ts);
                }
            }
        }
    }

    /** Recompute daily entry gate on the first tick of a new candle. */
    private void recomputeTodayEligibility(long curCandleTs) {
        final int n = tracker.candles.size();
        if (n < 2) {
            todayEligibleForEntry = false;
            prevEma20ForDay = Double.NaN;
            lastTrendOk = false;
            lastOpenOk = false;
            lastCloseAbovePrevEmaOk = false;
            if (!inPosition) {
                state = TradeState.WARMUP;
                stateNote = "Waiting for sufficient history (need >= 21 candles with EMA).";
            }
            // Daily log (once) even during warmup, if desired
            maybeDailyStatusLog(curCandleTs);
            return;
        }

        CandleGraphTracker.Candle cur  = tracker.candles.get(n - 1); // today's forming candle
        CandleGraphTracker.Candle prev = tracker.candles.get(n - 2); // yesterday (closed)

        Double prevEma = (prev.maValues != null && ema20Index < prev.maValues.length)
                ? prev.maValues[ema20Index] : null;

        // Require EMA(20) to be established reasonably
        if (prevEma == null || Double.isNaN(prevEma) || n < 21) {
            todayEligibleForEntry = false;
            prevEma20ForDay = Double.NaN;
            lastTrendOk = false;
            lastOpenOk = false;
            lastCloseAbovePrevEmaOk = false;

            if (!inPosition) {
                state = TradeState.WARMUP;
                stateNote = "EMA20 not ready or history < 21 candles.";
            }
            maybeDailyStatusLog(curCandleTs);
            return;
        }

        lastTrendOk = isEma20StrictlyIncreasing(LOOKBACK_EMA_TREND);
        lastOpenOk = cur.open > prevEma;
        lastCloseAbovePrevEmaOk = isCloseAbovePrevEma20ForLast(LOOKBACK_EMA_TREND);

        todayEligibleForEntry = (lastTrendOk && lastOpenOk && lastCloseAbovePrevEmaOk);
        prevEma20ForDay = prevEma;

        // Update public state if not currently in a position
        if (!inPosition) {
            if (todayEligibleForEntry) {
                state = TradeState.ELIGIBLE_WAITING_TRIGGER;
                stateNote = "Eligible today, waiting intraday pullback to ~0.1% below prev EMA20.";
            } else {
                state = TradeState.NOT_ELIGIBLE;
                stateNote = buildNotEligibleReason();
            }
        } else {
            state = TradeState.IN_POSITION; // carry through if already long
            stateNote = "Managing position: SL/TP/Timeout checks active.";
        }

        // Once-per-day status log
        maybeDailyStatusLog(curCandleTs);
    }

    /** Trend filter: last k CLOSED candles' EMA20 strictly increasing. */
    private boolean isEma20StrictlyIncreasing(int k) {
        final int n = tracker.candles.size();
        if ((n - 1) < (k + 1)) return false; // at least k+1 closed to compare

        int end = n - 2;           // most recent CLOSED
        int start = end - k + 1;   // inclusive
        if (start < 0) return false;

        double last = Double.NaN;
        for (int i = start; i <= end; i++) {
            CandleGraphTracker.Candle c = tracker.candles.get(i);
            if (c.maValues == null || ema20Index >= c.maValues.length) return false;
            double v = c.maValues[ema20Index];
            if (Double.isNaN(v)) return false;
            if (!Double.isNaN(last) && !(v > last)) return false; // strict
            last = v;
        }
        return true;
    }

    /**
     * Extra rule: for the last k CLOSED candles, each candle's CLOSE must be
     * greater than the PREVIOUS candle's EMA(20).
     */
    private boolean isCloseAbovePrevEma20ForLast(int k) {
        final int n = tracker.candles.size();
        if (n < k + 2) return false;

        int endIdx = n - 2;            // most recent CLOSED candle
        int startIdx = endIdx - k + 1; // inclusive
        if (startIdx <= 0) return false;

        for (int i = startIdx; i <= endIdx; i++) {
            CandleGraphTracker.Candle cur  = tracker.candles.get(i);
            CandleGraphTracker.Candle prev = tracker.candles.get(i - 1);

            if (prev.maValues == null || ema20Index >= prev.maValues.length) return false;
            double prevEma20 = prev.maValues[ema20Index];
            if (Double.isNaN(prevEma20)) return false;

            if (!(cur.close > prevEma20)) return false;
        }
        return true;
    }

    /** Entry trigger evaluation (intraday). */
    private void maybeEnter(double price, long ts) {
        if (!todayEligibleForEntry || Double.isNaN(prevEma20ForDay)) return;

        // Intraday trigger: price <= prev EMA20 * (1 - 0.1%)
        double triggerPx = prevEma20ForDay * (1.0 - ENTRY_BAND);
        if (price <= triggerPx) {
            final int n = tracker.candles.size();
            if (n < 2) return;

            CandleGraphTracker.Candle prev = tracker.candles.get(n - 2);

            // Capture state
            this.entryPrice = price;
            this.entryTime = ts;
            this.entryCandleIdx = n - 1; // index of TODAY (forming candle)
            this.stopLossPx = entryPrice * (1.0 - SL_PCT);
            this.targetPctPx = entryPrice * (1.0 + TP_PCT);
            this.targetBollUpperPrev = (prev.bollinger != null) ? prev.bollinger.upperBand : Double.NaN;

            // Place BUY
            oms.createOrder(instrumentId, VirtualExchange.OrderType.BUY_M);
            inPosition = true;

            // Update public state immediately
            state = TradeState.IN_POSITION;
            stateNote = "Entered long. Managing SL/TP.";

            // Log entry
            saveTradeLog("ENTRY", Map.of(
                    "triggerPx", triggerPx,
                    "prevEma20", prevEma20ForDay,
                    "prevUpperBB", targetBollUpperPrev,
                    "trendOk", lastTrendOk,
                    "openOk", lastOpenOk,
                    "closeAbovePrevEmaOk", lastCloseAbovePrevEmaOk
            ));
        }
    }

    /** Exit logic: SL, Bollinger TP (anytime), +5% TP (after delay), Timeout. */
    private void maybeExit(double price, long ts) {
        String reason = null;

        // Full candles since entry candle (do not count the entry candle itself)
        int daysHeld = Math.max(0, tracker.candles.size() - 1 - entryCandleIdx);

        // 1) Stop loss
        if (price <= stopLossPx) reason = "SL_3pct";

        // 2) Bollinger target (yesterday's upper band) can trigger anytime
        if (reason == null && !Double.isNaN(targetBollUpperPrev) && price >= targetBollUpperPrev) {
            reason = "TP_BB";
        }

        // 3) +5% target only after TP_DELAY_CANDLES full candles
        if (reason == null && daysHeld >= TP_DELAY_CANDLES && price >= targetPctPx) {
            reason = "TP_5pct";
        }

        // 4) Timeout after TIMEOUT_CANDLES full candles
        if (reason == null && daysHeld >= TIMEOUT_CANDLES) {
            reason = "TIMEOUT_20D";
        }

        if (reason != null) {
            oms.createOrder(instrumentId, VirtualExchange.OrderType.SELL_M);
            double pnl = price - entryPrice;
            totalProfit += pnl;

            saveTradeLog("EXIT", Map.of(
                    "reason", reason,
                    "exitTime", ts,
                    "exitPrice", price,
                    "daysHeld", daysHeld,
                    "stopLossPx", stopLossPx,
                    "targetPctPx", targetPctPx,
                    "targetBollUpperPrev", targetBollUpperPrev,
                    "pnl", pnl
            ));

            // Reset position state
            inPosition = false;
            entryPrice = 0.0;
            entryTime = 0L;
            entryCandleIdx = -1;
            stopLossPx = 0.0;
            targetPctPx = 0.0;
            targetBollUpperPrev = Double.NaN;

            // Reflect post-exit mode immediately (based on today's eligibility)
            if (todayEligibleForEntry) {
                state = TradeState.ELIGIBLE_WAITING_TRIGGER;
                stateNote = "Exited; still eligible today. Waiting for trigger.";
            } else {
                state = TradeState.NOT_ELIGIBLE;
                stateNote = buildNotEligibleReason();
            }
        }
    }

    // ===== Logging =====

    private void saveTradeLog(String type, Map<String, Object> extra) {
        if (!tradeLoggingEnabled) return;
        try {
            Path dir = Path.of("D:", "SpringBoot project", "Trade", "output files",
                    "tradeInfo", "EMA20_v1");
            Files.createDirectories(dir);
            String stamp = (Objects.equals(type, "ENTRY") ? String.valueOf(entryTime) : String.valueOf(System.currentTimeMillis()));
            String fileName = type.toLowerCase(Locale.ENGLISH) + "_" + stamp + "_" + name + ".json";
            Path file = dir.resolve(fileName);

            Map<String, Object> info = new LinkedHashMap<>();
            info.put("type", type);
            info.put("instrumentId", instrumentId);
            info.put("symbol", name);
            info.put("inPosition", inPosition);
            info.put("entryTime", entryTime);
            info.put("entryPrice", entryPrice);
            info.put("totalProfit", totalProfit);
            info.put("state", state.toString());
            info.put("stateNote", stateNote);
            info.put("eligibility", Map.of(
                    "trendOk", lastTrendOk,
                    "openOk", lastOpenOk,
                    "closeAbovePrevEmaOk", lastCloseAbovePrevEmaOk,
                    "prevEma20ForDay", prevEma20ForDay
            ));
            if (extra != null) info.putAll(extra);

            ObjectMapper mapper = new ObjectMapper();
            Files.writeString(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(info));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /** NEW: daily one-shot status log. */
    private void maybeDailyStatusLog(long candleTs) {
        if (!dailyStatusLoggingEnabled) return;
        if (candleTs == lastDailyLogCandleTs) return; // already logged for this candle/day
        lastDailyLogCandleTs = candleTs;

        try {
            Path dir = Path.of("D:", "SpringBoot project", "Trade", "output files",
                    "tradeInfo", "EMA20_v1", "daily-logs");
            Files.createDirectories(dir);
            String fileName = "daily_" + candleTs + "_" + name + ".json";
            Path file = dir.resolve(fileName);

            Map<String, Object> info = new LinkedHashMap<>();
            info.put("type", "DAILY_STATUS");
            info.put("candleTime", candleTs);
            info.put("instrumentId", instrumentId);
            info.put("symbol", name);
            info.put("state", state.toString());
            info.put("stateNote", stateNote);
            info.put("inPosition", inPosition);
            info.put("eligibility", Map.of(
                    "eligibleToday", todayEligibleForEntry,
                    "trendOk", lastTrendOk,
                    "openOk", lastOpenOk,
                    "closeAbovePrevEmaOk", lastCloseAbovePrevEmaOk,
                    "prevEma20ForDay", prevEma20ForDay
            ));
            info.put("riskTargets", Map.of(
                    "stopLossPx", stopLossPx,
                    "targetPctPx", targetPctPx,
                    "prevUpperBB", targetBollUpperPrev
            ));
            info.put("pnlCumulative", totalProfit);

            ObjectMapper mapper = new ObjectMapper();
            Files.writeString(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(info));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private String buildNotEligibleReason() {
        if (!lastTrendOk) return "Trend gate failed: EMA20 not strictly rising for last " + LOOKBACK_EMA_TREND + " closed candles.";
        if (!lastOpenOk)  return "Open gate failed: today's OPEN <= yesterday's EMA20.";
        if (!lastCloseAbovePrevEmaOk) return "Close gate failed: not all of last " + LOOKBACK_EMA_TREND + " closes were > previous candle's EMA20.";
        return "Not eligible for unspecified reason.";
    }

    // ===== Optional helpers =====

    /** Render chart (EMA + BB etc.) using the tracker painter. */
    public void drawGraph(String outputDir) throws IOException {
        tracker.drawCandleGraph(outputDir);
    }

    public void drawGraph() throws IOException {
        drawGraph(Path.of("D:", "SpringBoot project", "Trade", "output files").toString());
    }

    public boolean isInPosition() { return inPosition; }
    public double getTotalProfit() { return totalProfit; }
    public TradeState getState() { return state; }

    // Convenience setters (optional)
    public void setTradeLoggingEnabled(boolean enabled) { this.tradeLoggingEnabled = enabled; }
    public void setDailyStatusLoggingEnabled(boolean enabled) { this.dailyStatusLoggingEnabled = enabled; }
}
