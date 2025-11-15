package app.ai.lab.tradeEngineLite.Algos.ResultReact;

import app.ai.lab.tradeEngineLite.BackTest.Engine.HistoricalData.Block;
import app.ai.lab.tradeEngineLite.BackTest.Exchange.OrderManagementService;
import app.ai.lab.tradeEngineLite.GraphUtils.CandleGraphTracker;
import app.ai.lab.tradeEngineLite.GraphUtils.CandleGraphTracker.MAType;

import java.awt.Color;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

public class LogicalCore_bt {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter BASE_FMT = DateTimeFormatter.ofPattern("dd/MM/yy hh:mm a", Locale.ENGLISH);

    private final int instrumentId;
    private final String name;
    private final OrderManagementService oms;
    private final CandleGraphTracker tracker;

    private final QuarterRecord record;
    private final double performanceScore; // sign * abs_sqrt_x
    private final double priceScore;
    private final LocalDate startTradeDate; // next day of result date

    // strategy for shorts (selected by performanceScore bands)
    private ShortStrategy shortStrategy;

    // strategy for longs (selected by performanceScore bands)
    private LongStrategy longStrategy;

    // gate to start acting once we reach the startTradeDate
    private boolean tradingWindowOpened = false;

    public LogicalCore_bt(int instrumentId, String name, OrderManagementService oms, QuarterRecord record) {
        this.instrumentId = instrumentId;
        this.name = name;
        this.oms = oms;
        this.record = record;

        // Tracker: daily candles
        int[] maPeriods = new int[] { 200, 50, 20, 10, 5, 3 };
        MAType maType = MAType.EXPONENTIAL;
        this.tracker = new CandleGraphTracker(instrumentId, name, 86_400, maPeriods, maType);
        Color[] MA_PALETTE = new Color[] {
                new Color(238, 101, 46, 255), // orange
                new Color(0, 165, 83), // green
                new Color(255, 0, 0), // red
                new Color(233, 8, 140), // magenta
                new Color(0, 128, 255), // blue
                new Color(50, 50, 50) // dark gray
        };
        this.tracker.modifyMaPalette(MA_PALETTE);

        // Scores
        var perf = record.getPerformance();
        this.performanceScore = (perf != null && perf.getFinalPerformanceScore() != null)
                ? perf.getFinalPerformanceScore().getScore()
                : 0.0;
        this.priceScore = (perf != null && perf.getFinalPriceScore() != null)
                ? perf.getFinalPriceScore().getScore()
                : 0.0;

        // next trading date from result date
        LocalDate resDate = Dates.parseQuarterDate(record.getDateTimeRaw());
        this.startTradeDate = Dates.nextTradingDate(resDate);

        // // Select short strategy by performanceScore (ignore all shorts if priceScore
        // <=
        // // -8)
        // if (priceScore <= -8.0) {
        // this.shortStrategy = null; // hard ignore
        // } else {
        // this.shortStrategy = pickShortStrategy(performanceScore,
        // record.getCurrentDateClosePrice());
        // System.out.println("shortStrategy : " +
        // shortStrategy.getClass().getSimpleName());
        // if (this.shortStrategy != null) {
        // this.shortStrategy.setTracker(this.tracker);
        // }
        // }

        // Choose side: negative -> short, non-negative -> long
        if (performanceScore < 0) {
            if (priceScore <= -8.0) {
                this.shortStrategy = null; // hard ignore shorts
            } else {
                this.shortStrategy = pickShortStrategy(performanceScore, record.getCurrentDateClosePrice());
                System.out.println("shortStrategy : "
                        + (shortStrategy == null ? "none" : shortStrategy.getClass().getSimpleName()));
                if (this.shortStrategy != null)
                    this.shortStrategy.setTracker(this.tracker);
            }
            this.longStrategy = null;
        } else {
            this.shortStrategy = null;
            this.longStrategy = pickLongStrategy(performanceScore, record.getCurrentDateClosePrice());
            System.out.println(
                    "longStrategy : " + (longStrategy == null ? "none" : longStrategy.getClass().getSimpleName()));
            if (this.longStrategy != null)
                this.longStrategy.setTracker(this.tracker);
        }
    }

    private ShortStrategy pickShortStrategy(double perfScore, Double currentDateClose) {
        System.out.println("perfScore : " + perfScore + "  currentDateClose" + currentDateClose);
        if (currentDateClose == null)
            return null;
        if (perfScore > -5 && perfScore < 0)
            return new ShortL1_BollBand();
        if (perfScore > -7 && perfScore <= -5)
            return new ShortL2_PointOrEMA10(currentDateClose);
        if (perfScore > -9 && perfScore <= -7)
            return new ShortL3_PointOrEMA5(currentDateClose);
        if (perfScore <= -9)
            return new ShortL4_PointOrEMA5(currentDateClose);
        // Not a short bucket -> (Long side not implemented yet)
        return null;
    }

    private LongStrategy pickLongStrategy(double perfScore, Double currentDateClose) {
        if (currentDateClose == null)
            return null;

        // Long buckets:
        if (perfScore < 5 && perfScore >= 0) {
            return new LongL1_BollBand(priceScore, currentDateClose);
        }
        if (perfScore < 7 && perfScore >= 5) {
            return new LongL2_PointOrEMA10(currentDateClose, priceScore, currentDateClose);
        }
        if (perfScore < 9 && perfScore >= 7) {
            return new LongL3_PointOrEMA5(currentDateClose, priceScore, currentDateClose);
        }
        if (perfScore >= 9) {
            return new LongL4_PointOrEMA5(currentDateClose, priceScore, currentDateClose);
        }
        return null;
    }

    /**
     * Feed historical block; updates candles and drives strategy.
     */
    public void onBlock(Block block) {
        if (block.getInfo() == null)
            return;

        for (Block.PacketData pd : block.getInfo()) {
            if (pd instanceof Block.StockPacket ip && ip.getInstrumentToken() == instrumentId) {
                long ts = block.getTimeStamp();
                double price = ip.getLastTradedPrice() / 100.0;

                // update candles
                tracker.addMarketData(ts, price);

                // Open trading window when the current candle's date >= startTradeDate
                var last = tracker.candles.get(tracker.candles.size() - 1);
                LocalDate candleDate = Instant.ofEpochMilli(last.timestamp)
                        .atZone(java.time.ZoneId.of("Asia/Kolkata")).toLocalDate();

                // System.out.println("candleDate : " + candleDate + " startTradeDate : " +
                // startTradeDate);
                if (!tradingWindowOpened
                        && (candleDate.isAfter(startTradeDate) || candleDate.isEqual(startTradeDate))) {
                    tradingWindowOpened = true;
                }

                // // Fire per-tick logic only once we’re past result date
                // if (tradingWindowOpened && shortStrategy != null) {
                //     // detect first tick of new daily candle to advance entry-validity counters
                //     if (last.tickCount == 1) {
                //         shortStrategy.onNewCandle();
                //         // Also, if there is an active trade, you can flush PnL/OMS per candle here if
                //         // you want.
                //         maybeFlushAndCloseIfNeeded();
                //     }

                //     shortStrategy.onTick(ts, price);
                //     // If trade got opened/closed, push to OMS
                //     syncWithOMSIfNeeded(price);

                //     logIfClosed();
                // }

                if (tradingWindowOpened) {
                    boolean newDaily = (last.tickCount == 1);

                    if (shortStrategy != null) {
                        if (newDaily) {
                            shortStrategy.onNewCandle();
                            maybeFlushAndCloseIfNeeded();
                        }
                        shortStrategy.onTick(ts, price);
                        syncWithOMSIfNeeded(price);
                        logIfClosed();
                    } else if (longStrategy != null) {
                        if (newDaily) {
                            longStrategy.onNewCandle();
                            maybeFlushAndCloseIfNeeded();
                        }
                        longStrategy.onTick(ts, price);
                        syncWithOMSIfNeeded(price);
                        logIfClosed();
                    }
                }

            }
        }
    }


    private ActiveTrade currentActiveTrade() {
        if (shortStrategy != null && shortStrategy.getActiveTrade() != null) return shortStrategy.getActiveTrade();
        if (longStrategy  != null && longStrategy.getActiveTrade()  != null) return longStrategy.getActiveTrade();
        return null;
    }


    private void syncWithOMSIfNeeded(double price) {
        ActiveTrade t = currentActiveTrade();
        if (t == null) return;

        // Entry fill (same heuristic you used)
        if (!t.closed && t.entryPrice == price && t.closedOnCandleIndex == -1) {
            // TODO: placeOrder(... t.side ...)
        }
        if (t.closed && t.exitPrice != null) {
            // TODO: closeOrder(... t.side ...)
        }
    }

    private void maybeFlushAndCloseIfNeeded() {
        ActiveTrade t = currentActiveTrade();
        if (t == null || !t.closed) return;
        // emit per-candle summaries if desired
    }

    /** If a trade just closed and hasn't been logged yet, write the trade log. */
    private void logIfClosed() {
        ActiveTrade t = currentActiveTrade();
        if (t == null || !t.closed || t.logged) return;

        long startTs = 0L, endTs = 0L;
        if (t.openedOnCandleIndex >= 0 && t.openedOnCandleIndex < tracker.candles.size()) {
            startTs = tracker.candles.get(t.openedOnCandleIndex).timestamp;
        }
        if (t.closedOnCandleIndex >= 0 && t.closedOnCandleIndex < tracker.candles.size()) {
            endTs = tracker.candles.get(t.closedOnCandleIndex).timestamp;
        } else {
            endTs = tracker.candles.get(tracker.candles.size() - 1).timestamp;
        }

        double pnlToSave = t.pnlPct() * 100.0; // percent
        saveTradeLog(startTs, endTs, pnlToSave);
        t.logged = true;
    }

    
    /** Format epoch millis to "dd/MM/yy hh:mm (am/pm)" in IST */
    private static String fmtIstAmPmParen(long epochMillis) {
        ZonedDateTime zdt = Instant.ofEpochMilli(epochMillis).atZone(IST);
        String s = zdt.format(BASE_FMT); // e.g., "30/10/25 09:15 AM"
        int sp = s.lastIndexOf(' ');
        if (sp > 0 && sp < s.length() - 1) {
            String pre = s.substring(0, sp); // "30/10/25 09:15"
            String ampm = s.substring(sp + 1).toLowerCase(Locale.ENGLISH); // "am"/"pm"
            return pre + " (" + ampm + ")"; // "30/10/25 09:15 (am)"
        }
        return s; // fallback
    }

    /**
     * Write trade info to JSON with IST timestamps, strategy type (short/long class),
     * and nested QuarterRecord. Works for both Short & Long strategies.
     */
    private void saveTradeLog(long start, long end, double pnl) {
        try {
            // Resolve active strategy + trade (prefer the one that actually has a trade)
            String strategyType = "NONE";
            ActiveTrade t = null;
        
            if (shortStrategy != null && shortStrategy.getActiveTrade() != null) {
                strategyType = shortStrategy.getClass().getSimpleName();
                t = shortStrategy.getActiveTrade();
            } else if (longStrategy != null && longStrategy.getActiveTrade() != null) {
                strategyType = longStrategy.getClass().getSimpleName();
                t = longStrategy.getActiveTrade();
            } else if (shortStrategy != null) {
                // Fallback: no active trade but shortStrategy exists
                strategyType = shortStrategy.getClass().getSimpleName();
            } else if (longStrategy != null) {
                // Fallback: no active trade but longStrategy exists
                strategyType = longStrategy.getClass().getSimpleName();
            }
        
            // Build payload
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("instrumentId", instrumentId);
            info.put("symbol", name);
        
            // Times (raw + formatted IST)
            Map<String, Object> times = new LinkedHashMap<>();
            times.put("entry_epoch_ms", start);
            times.put("exit_epoch_ms", end);
            times.put("entry_ist", fmtIstAmPmParen(start));
            times.put("exit_ist", fmtIstAmPmParen(end));
            info.put("times", times);
        
            // Strategy / trade meta
            Map<String, Object> strat = new LinkedHashMap<>();
            strat.put("strategyType", strategyType);
            if (t != null) {
                strat.put("side", t.side.toString());            // LONG / SHORT
                strat.put("entryPrice", t.entryPrice);
                strat.put("exitPrice", t.exitPrice);
                strat.put("targetPct", t.targetPct);             // e.g., 0.05 => 5%
                strat.put("stopPct", t.stopPct);
                strat.put("openedOnCandleIndex", t.openedOnCandleIndex);
                strat.put("closedOnCandleIndex", t.closedOnCandleIndex);
                strat.put("autoExpiryCandles", t.autoExpiryCandles);
                strat.put("pnl_pct", t.pnlPct() * 100.0);        // convenience
                strat.put("pnl_abs_per_unit", t.pnlAbs());
            }
            info.put("trade", strat);
        
            // PnL summary (keeps your original 'pnl' meaning intact)
            info.put("pnl_to_save", pnl);
        
            // Embed full QuarterRecord (nested JSON via Jackson)
            info.put("quarterRecord", record);
        
            // Output path & write
            Path dir = Path.of("D:", "SpringBoot project", "Trade", "output files", "tradeInfo", "ResultReact", name);
            Files.createDirectories(dir);
        
            // Filename: <start>_<pnl>_<durationMs>.json  (kept same)
            String fileName = start + "_" + String.format(Locale.ENGLISH, "%.2f", pnl) + "_" + (end - start) + ".json";
            Path file = dir.resolve(fileName);
        
            ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
            String json = mapper.writeValueAsString(info);
            Files.writeString(file, json);
        } catch (Exception e) {
            e.printStackTrace(); // Backtest context: don't abort pipeline
        }
    }


}
