package app.ai.lab.tradeEngineLite.Algos.rsi_rebound;

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
 * 20-RSI rebound strategy (15m)
 *
 * Rules
 *  1) Detect a CLOSED candle with RSI < 20
 *  2) If within NEXT 12 CLOSED candles RSI >= 50, SHORT on that candle’s first tick (market)
 *  3) TP: +1% (price <= entry * (1 - 0.01))
 *  4) SL: -1% (price >= entry * (1 + 0.01))
 *
 * Persists ENTRY/EXIT & DAILY_STATUS logs to JSON (like EMA strategy).
 */
public class LogicalCore {

    // ==== Public state (mirrors your EMA class style) ====
    public enum TradeState { WARMUP, ARMED_WAIT_REBOUND, NOT_ARMED, IN_POSITION }
    public volatile TradeState state = TradeState.WARMUP;
    public volatile String stateNote = "";

    public volatile boolean tradeLoggingEnabled = true;
    public volatile boolean dailyStatusLoggingEnabled = true;

    // ==== Config ====
    private static final int RSI_PERIOD = 14;
    private static final int CANDLE_TIME_SEC = 900; // 15min
    private static final int MAX_REBOUND_WINDOW = 24;
    private static final double ENTRY_RSI_DROP = 20.0;
    private static final double REBOUND_RSI_THRESHOLD = 50.0;
    private static final double TP_PCT = 0.02;  // 1%
    private static final double SL_PCT = 0.005;  // 1%

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

    // RSI arming
    private int rsiDropCandleIdx = -1;     // index (in candles list) of drop <20
    private boolean rsiBelow20Armed = false;

    // daily log guard
    private long lastDailyLogCandleTs = Long.MIN_VALUE;

    public LogicalCore(int instrumentId, String name, OrderManagementService oms) {
        this.instrumentId = instrumentId;
        this.name = name;
        this.oms = oms;
        this.tracker = new CandleGraphTracker(instrumentId, name, CANDLE_TIME_SEC);
        this.tracker.enableRSI(RSI_PERIOD);

        // initial state
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

                // Detect closed-candle boundary: first tick of a new forming candle
                if (n >= 2) {
                    Candle latest = tracker.candles.get(n - 1); // forming candle
                    if (latest.tickCount == 1) {
                        System.out.println("tickCount:" + 1);

                        // last closed candle ended at index n-2
                        onNewClosedCandle(n-1, latest.timestamp);
                    }
                }

                if (!inPosition) {
                    maybeEnterShort(price, ts, n);
                } else {
                    maybeExit(price, ts);
                }
            }
        }
    }

    private void onNewClosedCandle(int n, long formingCandleTs) {
        // Closed candle RSI is at index n-2
        List<Double> rsi = tracker.getRSIValues();
        if (rsi.size() < n-1) return;

        double closedRsi = nullSafe(rsi.get(n - 2));

        // Gate 1: arm if RSI < 20
        if (!rsiBelow20Armed && closedRsi < ENTRY_RSI_DROP) {
            System.out.println("rsiBelow20Armed:" + closedRsi);

            rsiBelow20Armed = true;
            rsiDropCandleIdx = n - 2;
            if (!inPosition) {
                state = TradeState.ARMED_WAIT_REBOUND;
                stateNote = "RSI < 20 detected. Waiting up to " + MAX_REBOUND_WINDOW + " candles for RSI >= 50.";
                System.out.println("state:" + state + " stateNote:"+ stateNote);

            }
        }

        // Disarm if window exceeded
        if (rsiBelow20Armed) {
            int candlesSinceDrop = (n - 2) - rsiDropCandleIdx;
            if (candlesSinceDrop > MAX_REBOUND_WINDOW) {
                rsiBelow20Armed = false;
                if (!inPosition) {
                    state = TradeState.NOT_ARMED;
                    stateNote = "Window exceeded without rebound >= 50. Disarmed.";
                    System.out.println("state:" + state + " stateNote:"+ stateNote);
                }
            }
        }

        // Daily (per-closed-candle) status JSON
        maybeDailyStatusLog(formingCandleTs);
    }

    private void maybeEnterShort(double price, long ts, int n) {
        if (!rsiBelow20Armed) {
            if (state != TradeState.WARMUP && state != TradeState.NOT_ARMED) {
                state = TradeState.NOT_ARMED;
                stateNote = "Not armed; waiting for RSI < 20.";
            }
            return;
        }

        List<Double> rsi = tracker.getRSIValues();
        if (rsi.size() < n-1) return;
        double closedRsi = nullSafe(rsi.get(n - 2));

        // Entry when we observe RSI rebound >= 50 within window
        int candlesSinceDrop = (n - 2) - rsiDropCandleIdx;
        if (closedRsi >= REBOUND_RSI_THRESHOLD && candlesSinceDrop <= MAX_REBOUND_WINDOW) {
            // Enter short
            entryPrice = price;
            entryTime = ts;
            stopLossPx = entryPrice * (1.0 + SL_PCT);
            targetPx   = entryPrice * (1.0 - TP_PCT);
            inPosition = true;

            oms.createOrder(instrumentId, VirtualExchange.OrderType.SELL_M);

            // state
            state = TradeState.IN_POSITION;
            stateNote = "Entered SHORT after RSI rebound >= 50. Managing SL/TP.";

            // log entry
            saveTradeLog("ENTRY", Map.of(
                    "entryPrice", entryPrice,
                    "entryTime", entryTime,
                    "rsiDropCandleIdx", rsiDropCandleIdx,
                    "reboundRsi", closedRsi,
                    "candlesSinceDrop", candlesSinceDrop,
                    "stopLossPx", stopLossPx,
                    "targetPx", targetPx
            ));

            // reset arming
            rsiBelow20Armed = false;
            rsiDropCandleIdx = -1;
        } else {
            // still armed; waiting
            if (!inPosition) {
                state = TradeState.ARMED_WAIT_REBOUND;
                stateNote = "Armed; waiting RSI >= 50 within window. Elapsed=" + candlesSinceDrop;
            }
        }
    }

    private void maybeExit(double price, long ts) {
        if (!inPosition) return;

        String reason = null;
        if (price >= stopLossPx) {
            reason = "SL_1pct";
        } else if (price <= targetPx) {
            reason = "TP_1pct";
        }

        if (reason != null) {
            oms.createOrder(instrumentId, VirtualExchange.OrderType.BUY_M);
            double pnl = entryPrice - price; // short: entry - exit
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

            // reset
            inPosition = false;
            entryPrice = 0.0;
            entryTime = 0L;
            stopLossPx = 0.0;
            targetPx = 0.0;

            // back to armed/not armed depending on current arming flag
            if (rsiBelow20Armed) {
                state = TradeState.ARMED_WAIT_REBOUND;
                stateNote = "Exited; still armed from prior drop. Waiting RSI >= 50.";
            } else {
                state = TradeState.NOT_ARMED;
                stateNote = "Exited; not armed.";
            }
        }
    }

    // ==== Logging to JSON (mirrors your EMA writer) ====
    private void saveTradeLog(String type, Map<String, Object> extra) {
        if (!tradeLoggingEnabled) return;

        try {
            Path dir = Path.of("D:", "SpringBoot project", "Trade", "output files",
                    "tradeInfo", "RSI_Rebound_v1", name);
            Files.createDirectories(dir);

            long ts = Objects.equals(type, "ENTRY") ? entryTime
                    : (extra != null && extra.get("exitTime") instanceof Number ? ((Number) extra.get("exitTime")).longValue() : System.currentTimeMillis());

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
                    "entryDrop", ENTRY_RSI_DROP,
                    "rebound", REBOUND_RSI_THRESHOLD,
                    "maxWindow", MAX_REBOUND_WINDOW,
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
                    "tradeInfo", "RSI_Rebound_v1", name, "daily-logs");
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
            info.put("armedFromDrop", rsiBelow20Armed);
            info.put("rsiDropCandleIdx", rsiDropCandleIdx);
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
    private static double nullSafe(Double v) { return v == null ? Double.NaN : v; }

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
