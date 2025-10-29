package app.ai.lab.tradeEngineLite.Algos.rsi_doublebound;

import app.ai.lab.tradeEngineLite.BackTest.Engine.HistoricalData.Block;
import app.ai.lab.tradeEngineLite.BackTest.Exchange.OrderManagementService;
import app.ai.lab.tradeEngineLite.BackTest.Exchange.VirtualExchange;
import app.ai.lab.tradeEngineLite.GraphUtils.CandleGraphTracker;
import app.ai.lab.tradeEngineLite.GraphUtils.CandleGraphTracker.Candle;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * RSI Double-Bound LONG Strategy (15m)
 *
 * Sequence:
 *  (1) A closed candle with RSI < 20                -> "drop" armed and window starts
 *  (2) Later, a closed candle with RSI >= 50        -> rebound confirmed
 *  (3) Later, a closed candle with RSI < 40         -> pullback armed
 *  Entry:
 *   - Only if within 75 closed candles from the (1) drop
 *   - Current RSI < 40 (on last closed candle)
 *   - Current tick price < min(close) of the last 100 closed candles
 *  Exit:
 *   - Target: +2% (price >= entry * 1.02)
 *   - Stop  : -0.5% (price <= entry * 0.995)
 *
 * Persists ENTRY/EXIT & DAILY_STATUS logs to JSON (mirrors your EMA/RSI class style).
 */
public class LogicalCore {

    // ==== Public state ====
    public enum TradeState { WARMUP, ARMED_DROP, ARMED_REBOUND, ARMED_PULLBACK, NOT_ARMED, IN_POSITION }
    public volatile TradeState state = TradeState.WARMUP;
    public volatile String stateNote = "";

    public volatile boolean tradeLoggingEnabled = true;
    public volatile boolean dailyStatusLoggingEnabled = true;

    // ==== Config ====
    private static final int RSI_PERIOD = 14;
    private static final int CANDLE_TIME_SEC = 900; // 15m
    private static final int MAX_WINDOW_FROM_DROP = 75; // closed candles
    private static final int LOOKBACK_MIN_CLOSE = 100;  // closed candles to compute min(close)

    private static final double DROP_BELOW = 20.0;
    private static final double REBOUND_AT_LEAST = 50.0;
    private static final double PULLBACK_BELOW = 40.0;

    private static final double TP_PCT = 0.02;   // +2%
    private static final double SL_PCT = 0.005;  // -0.5%

    // ==== Wiring ====
    private final int instrumentId;
    private final String name;
    private final OrderManagementService oms;
    private final CandleGraphTracker tracker;

    // ==== Runtime ====
    private boolean inPosition = false;
    private double entryPrice = 0.0;
    private long entryTime = 0L;

    private double stopLossPx = 0.0;
    private double targetPx = 0.0;

    private double totalProfit = 0.0;

    // RSI path arming
    private int dropIdx = -1;           // index (in candles list) where RSI < 20 was seen (closed)
    private boolean sawDrop = false;    // step (1)
    private boolean sawRebound = false; // step (2)
    private boolean pullbackArmed = false; // step (3) armed for entry

    // daily log guard
    private long lastDailyLogCandleTs = Long.MIN_VALUE;

    public LogicalCore(int instrumentId, String name, OrderManagementService oms) {
        this.instrumentId = instrumentId;
        this.name = name;
        this.oms = oms;
        this.tracker = new CandleGraphTracker(instrumentId, name, CANDLE_TIME_SEC);
        this.tracker.enableRSI(RSI_PERIOD);

        state = TradeState.WARMUP;
        stateNote = "Waiting for enough RSI history.";
    }

    public void onBlock(Block block) {
        if (block.getInfo() == null) return;

        for (Block.PacketData pd : block.getInfo()) {
            if (pd instanceof Block.StockPacket ip && ip.getInstrumentToken() == instrumentId) {
                final long ts = block.getTimeStamp();
                final double price = ip.getLastTradedPrice() / 100.0;

                tracker.addMarketData(ts, price);

                final int n = tracker.candles.size();
                if (n < RSI_PERIOD + 2) {
                    if (!inPosition) {
                        state = TradeState.WARMUP;
                        stateNote = "Waiting for sufficient RSI seed (" + (RSI_PERIOD + 2) + " candles).";
                    }
                    continue;
                }

                // Detect closed-candle boundary: first tick of the newly forming candle
                if (n >= 2) {
                    Candle forming = tracker.candles.get(n - 1);
                    if (forming.tickCount == 1) {
                        // last closed candle index is (n-2)
                        onNewClosedCandle(n - 1, forming.timestamp);
                    }
                }

                if (!inPosition) {
                    maybeEnterLong(price, ts, n);
                } else {
                    maybeExit(price, ts);
                }
            }
        }
    }

    private void onNewClosedCandle(int formingIndex, long formingCandleTs) {
        // formingIndex == n-1 here; closed candle is n-2
        final int n = formingIndex + 1;
        List<Double> rsi = tracker.getRSIValues();
        if (rsi.size() < n - 1) return;

        final int closedIdx = n - 2;
        double closedRsi = ns(rsi.get(closedIdx));

        // Step (1): drop < 20 -> set drop index and arm window
        if (!sawDrop && closedRsi < DROP_BELOW) {
            sawDrop = true;
            sawRebound = false;
            pullbackArmed = false;
            dropIdx = closedIdx;

            state = TradeState.ARMED_DROP;
            stateNote = "RSI drop < " + DROP_BELOW + " detected at idx=" + dropIdx + ". Waiting for rebound >= " + REBOUND_AT_LEAST + ".";
        }

        // If we armed, check window expiry relative to dropIdx
        if (sawDrop) {
            int candlesSinceDrop = closedIdx - dropIdx;
            if (candlesSinceDrop > MAX_WINDOW_FROM_DROP) {
                // Window over -> disarm everything
                resetArming();
                if (!inPosition) {
                    state = TradeState.NOT_ARMED;
                    stateNote = "Window exceeded without valid sequence. Disarmed.";
                }
            }
        }

        // Step (2): rebound >= 50 (must occur after drop and within window)
        if (sawDrop && !sawRebound) {
            int candlesSinceDrop = closedIdx - dropIdx;
            if (candlesSinceDrop >= 0 && candlesSinceDrop <= MAX_WINDOW_FROM_DROP && closedRsi >= REBOUND_AT_LEAST) {
                sawRebound = true;
                state = TradeState.ARMED_REBOUND;
                stateNote = "Rebound >= " + REBOUND_AT_LEAST + " confirmed. Waiting for pullback < " + PULLBACK_BELOW + ".";
            }
        }

        // Step (3): pullback < 40 (after rebound and within window) -> ready to look for entry
        if (sawDrop && sawRebound && !pullbackArmed) {
            int candlesSinceDrop = closedIdx - dropIdx;
            if (candlesSinceDrop >= 0 && candlesSinceDrop <= MAX_WINDOW_FROM_DROP && closedRsi < PULLBACK_BELOW) {
                pullbackArmed = true;
                state = TradeState.ARMED_PULLBACK;
                stateNote = "Pullback < " + PULLBACK_BELOW + " seen. Entry check armed.";
            }
        }

        // Daily per-closed-candle status JSON
        maybeDailyStatusLog(formingCandleTs);
    }

    private void maybeEnterLong(double price, long ts, int n) {
        if (!pullbackArmed) {
            // keep user-friendly state
            if (!sawDrop && state != TradeState.WARMUP && state != TradeState.NOT_ARMED) {
                state = TradeState.NOT_ARMED;
                stateNote = "Waiting for RSI < " + DROP_BELOW + " to arm.";
            }
            return;
        }

        // We need RSI of last closed candle and min(close) across last 100 closed candles
        List<Double> rsi = tracker.getRSIValues();
        if (rsi.size() < n - 1) return;
        int lastClosedIdx = n - 2;
        double closedRsi = ns(rsi.get(lastClosedIdx));

        // Guard the 75-candle window from drop point
        if (!sawDrop || dropIdx < 0) return;
        int candlesSinceDrop = lastClosedIdx - dropIdx;
        if (candlesSinceDrop < 0 || candlesSinceDrop > MAX_WINDOW_FROM_DROP) {
            resetArming();
            state = TradeState.NOT_ARMED;
            stateNote = "Window exceeded before entry.";
            return;
        }

        // Must still be below 40 at the last closed candle
        if (!(closedRsi < PULLBACK_BELOW)) {
            // Still armed; keep waiting
            state = TradeState.ARMED_PULLBACK;
            stateNote = "Armed: waiting for rsi<" + PULLBACK_BELOW + " & price<min(last " + LOOKBACK_MIN_CLOSE + " closes).";
            return;
        }

        // Price condition: current tick price < min(close) of last 100 closed candles
        if (tracker.candles.size() < 2) return;
        int closedEnd = lastClosedIdx; // inclusive
        int closedStart = Math.max(0, closedEnd - (LOOKBACK_MIN_CLOSE - 1));
        double minClose = Double.POSITIVE_INFINITY;

        // NOTE: using Candle.close (closed candle). Your CandleGraphTracker should populate this.
        for (int i = closedStart; i <= closedEnd; i++) {
            Candle c = tracker.candles.get(i);
            // Only use *closed* candles: indices <= lastClosedIdx are closed.
            // c.close is assumed to be the close price for that candle.
            minClose = Math.min(minClose, c.close);
        }

        if (Double.isInfinite(minClose)) return;

        if (price < minClose) {
            // ENTER LONG
            entryPrice = price;
            entryTime = ts;
            targetPx = entryPrice * (1.0 + TP_PCT);
            stopLossPx = entryPrice * (1.0 - SL_PCT);
            inPosition = true;

            oms.createOrder(instrumentId, VirtualExchange.OrderType.BUY_M);

            state = TradeState.IN_POSITION;
            stateNote = "Entered LONG on double-bound pullback. Managing SL/TP.";

            saveTradeLog("ENTRY", Map.of(
                    "entryPrice", entryPrice,
                    "entryTime", entryTime,
                    "dropIdx", dropIdx,
                    "candlesSinceDrop", candlesSinceDrop,
                    "lastClosedRsi", closedRsi,
                    "minCloseLookback", minClose,
                    "stopLossPx", stopLossPx,
                    "targetPx", targetPx
            ));

            // Reset the arming chain after entry so a new cycle can begin post-exit
            resetArming();
        } else {
            // Still armed but price condition not met yet
            state = TradeState.ARMED_PULLBACK;
            stateNote = "Armed: waiting price < min(last " + LOOKBACK_MIN_CLOSE + " closes). Now=" + price + ", minClose=" + minClose;
        }
    }

    private void maybeExit(double price, long ts) {
        if (!inPosition) return;

        String reason = null;
        if (price <= stopLossPx) {
            reason = "SL_0.5pct";
        } else if (price >= targetPx) {
            reason = "TP_2pct";
        }

        if (reason != null) {
            oms.createOrder(instrumentId, VirtualExchange.OrderType.SELL_M);
            double pnl = price - entryPrice; // long: exit - entry
            totalProfit += pnl;

            saveTradeLog("EXIT", Map.of(
                    "reason", reason,
                    "exitTime", ts,
                    "exitPrice", price,
                    "stopLossPx", stopLossPx,
                    "targetPx", targetPx,
                    "pnl", pnl,
                    "pnlCumulative", totalProfit
            ));

            // Clear position & risk
            inPosition = false;
            entryPrice = 0.0;
            entryTime = 0L;
            stopLossPx = 0.0;
            targetPx = 0.0;

            // After exit, go back to NOT_ARMED (a fresh cycle must form)
            state = TradeState.NOT_ARMED;
            stateNote = "Exited; waiting for new RSI < " + DROP_BELOW + " drop.";
        }
    }

    private void resetArming() {
        sawDrop = false;
        sawRebound = false;
        pullbackArmed = false;
        dropIdx = -1;
    }

    // ==== Logging (same style as your other strategies) ====
    private void saveTradeLog(String type, Map<String, Object> extra) {
        if (!tradeLoggingEnabled) return;

        try {
            Path dir = Path.of("D:", "SpringBoot project", "Trade", "output files",
                    "tradeInfo", "RSI_DoubleBound_Long_v1", name);
            Files.createDirectories(dir);

            long ts = Objects.equals(type, "ENTRY") ? entryTime
                    : (extra != null && extra.get("exitTime") instanceof Number
                       ? ((Number) extra.get("exitTime")).longValue()
                       : System.currentTimeMillis());

            String dateStr = DateTimeFormatter.ofPattern("yyyy-MM-dd")
                    .withZone(ZoneId.of("Asia/Kolkata"))
                    .format(Instant.ofEpochMilli(ts));

            String base = ts + "_" + type.toLowerCase(Locale.ENGLISH) + "_" + dateStr + "_" + name;
            String fileName = base;
            if ("EXIT".equals(type) && extra != null && extra.get("pnl") != null) {
                fileName += "_" + extra.get("pnl");
            }
            fileName += ".json";

            Path file = dir.resolve(fileName);

            Map<String, Object> info = new LinkedHashMap<>();
            info.put("type", type);
            info.put("instrumentId", instrumentId);
            info.put("symbol", name);
            info.put("inPosition", inPosition);
            info.put("entryTime", entryTime);
            info.put("entryPrice", entryPrice);
            info.put("stopLossPx", stopLossPx);
            info.put("targetPx", targetPx);
            info.put("totalProfit", totalProfit);
            info.put("state", state.toString());
            info.put("stateNote", stateNote);
            info.put("config", Map.of(
                    "rsiPeriod", RSI_PERIOD,
                    "dropBelow", DROP_BELOW,
                    "reboundAtLeast", REBOUND_AT_LEAST,
                    "pullbackBelow", PULLBACK_BELOW,
                    "maxWindowFromDrop", MAX_WINDOW_FROM_DROP,
                    "lookbackMinClose", LOOKBACK_MIN_CLOSE,
                    "tpPct", TP_PCT,
                    "slPct", SL_PCT
            ));
            if (extra != null) info.putAll(extra);

            ObjectMapper mapper = new ObjectMapper();
            Files.writeString(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(info));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /** Daily one-shot status log (once per forming candle start). */
    private void maybeDailyStatusLog(long formingCandleTs) {
        if (!dailyStatusLoggingEnabled) return;
        if (formingCandleTs == lastDailyLogCandleTs) return;
        lastDailyLogCandleTs = formingCandleTs;

        try {
            Path dir = Path.of("D:", "SpringBoot project", "Trade", "output files",
                    "tradeInfo", "RSI_DoubleBound_Long_v1", name, "daily-logs");
            Files.createDirectories(dir);
            String fileName = "daily_" + formingCandleTs + "_" + name + ".json";
            Path file = dir.resolve(fileName);

            Map<String, Object> info = new LinkedHashMap<>();
            info.put("type", "DAILY_STATUS");
            info.put("candleTime", formingCandleTs);
            info.put("instrumentId", instrumentId);
            info.put("symbol", name);
            info.put("state", state.toString());
            info.put("stateNote", stateNote);
            info.put("inPosition", inPosition);
            info.put("arming", Map.of(
                    "sawDrop", sawDrop,
                    "sawRebound", sawRebound,
                    "pullbackArmed", pullbackArmed,
                    "dropIdx", dropIdx
            ));
            info.put("riskTargets", Map.of(
                    "stopLossPx", stopLossPx,
                    "targetPx", targetPx
            ));
            info.put("pnlCumulative", totalProfit);

            ObjectMapper mapper = new ObjectMapper();
            Files.writeString(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(info));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // ==== Utilities ====
    private static double ns(Double v) { return v == null ? Double.NaN : v; }

    // ==== External helpers ====
    public void drawGraph(String outDir) throws IOException {
        tracker.drawCandleGraph(outDir);
    }

    public boolean isInPosition() { return inPosition; }
    public double getTotalProfit() { return totalProfit; }
    public TradeState getState() { return state; }

    public void setTradeLoggingEnabled(boolean enabled) { this.tradeLoggingEnabled = enabled; }
    public void setDailyStatusLoggingEnabled(boolean enabled) { this.dailyStatusLoggingEnabled = enabled; }
}




