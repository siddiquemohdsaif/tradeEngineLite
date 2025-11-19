package app.ai.lab.tradeEngineLite.Algos.ResultReact;

import app.ai.lab.tradeEngineLite.BackTest.Engine.HistoricalData.Block;
import app.ai.lab.tradeEngineLite.BackTest.Engine.HistoricalData.StreamHistoricalData;
import app.ai.lab.tradeEngineLite.BackTest.Exchange.OrderManagementService;
import app.ai.lab.tradeEngineLite.Utils.CompanyInfo;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LogicalCoreBtBulkBacktestTest_v3 {

    // === CONFIG ===
    //  - For dates <= CROSSOVER_DATE -> use Groww
    //  - For dates  > CROSSOVER_DATE -> use ZIP-based Zerodha data (all 200+ stocks in one pass)
    private static final String CROSSOVER_DATE_STR = "31-03-25"; // dd-MM-yy
    private static final DateTimeFormatter DDMMYY =
            DateTimeFormatter.ofPattern("dd-MM-yy", Locale.ENGLISH);
    private static final LocalDate GROWW_ZERODHA_CROSSOVER =
            LocalDate.parse(CROSSOVER_DATE_STR, DDMMYY);

    private static final int GROWW_INTERVAL_MIN = 1440; // 1, 5, 15, 60, 1440
    private static final int ZERODHA_INTERVAL_MIN = 1;  // not used in ZIP mode but kept for reference
    private static final boolean IS_INDEX = false;

    // Zerodha token / enctoken (only if you also use live Zerodha stream; not needed for ZIP-only)
    @SuppressWarnings("unused")
    private static final String ENCTOKEN =
            "WjfxiHG/cwQzwv7lvDcg7KOpFnoR0JrTRcPtIZ+ivA3Uy2UEHWHlUuA9e5yLZou4nR3lUN4X4YiGwNgKFJeqig06owbhhJWd5NMw545GzRTOIcs61hNNng==";

    // Root folders
    private static final Path DATA_ROOT = Path.of("D:\\Node Project\\Trading\\IntraDay record\\ticker_historic_data");
    private static final String PERFORMANCE_JSON_DIR = "D:\\Node Project\\webscrap\\ms-events\\data\\analyser\\performance";

    // Quarter filter:
    // - If empty => all quarters are allowed
    // - If non-empty => ONLY quarters in this set are traded/backtested
    private static final Set<String> QUARTER_ALLOW_FILTER = new HashSet<>(Arrays.asList(
            "2025-Mar",
            "2025-Jun"
    ));

    // === BULK LIST ===
    private static final String[] NSE_SYMBOLS = {
            "RELIANCE", "TCS", "HDFCBANK", "BHARTIARTL", "ICICIBANK", "INFY", "SBIN", "HINDUNILVR", "ITC",
            "LICI", "BAJFINANCE", "LT", "HCLTECH", "SUNPHARMA", "MARUTI", "M&M", "KOTAKBANK", "WIPRO",
            "ULTRACEMCO", "ONGC", "AXISBANK", "NTPC", "TITAN", "BAJAJFINSV", "ADANIENT", "POWERGRID",
            "HAL", "DMART", "BAJAJ-AUTO", "ADANIPORTS", "COALINDIA", "JSWSTEEL", "ASIANPAINT",
            "NESTLEIND", "BEL", "ETERNAL", "TRENT", "SIEMENS", "HINDZINC", "VBL", "ADANIPOWER", "DLF",
            "IOC", "LTIM", "VEDL", "INDIGO", "TATASTEEL", "GRASIM", "DIVISLAB", "ADANIGREEN", "JIOFIN",
            "EICHERMOT", "SBILIFE", "TECHM", "PIDILITIND", "PFC"
    };

    // === SUPPORTING TYPES FOR SHARED ZIP RUN ===

    /**
     * One logical backtest context = (symbol, token, one QuarterRecord)
     * Shared between Groww segment and ZIP segment.
     */
    private static class QuarterContext {
        final String nse;
        final int token;
        final QuarterRecord quarter;
        final OrderManagementService oms;
        final LogicalCore_bt_v3 core;

        // Full logical window for this quarter (before split into Groww / Zerodha parts)
        final LocalDate windowStartDate;
        final LocalDate windowEndDate;

        // Count of ticks actually processed (Groww + ZIP)
        final AtomicInteger tickCounter = new AtomicInteger(0);

        QuarterContext(String nse,
                       int token,
                       QuarterRecord quarter,
                       OrderManagementService oms,
                       LogicalCore_bt_v3 core,
                       LocalDate windowStartDate,
                       LocalDate windowEndDate) {
            this.nse = nse;
            this.token = token;
            this.quarter = quarter;
            this.oms = oms;
            this.core = core;
            this.windowStartDate = windowStartDate;
            this.windowEndDate = windowEndDate;
        }
    }

    /**
     * A ZIP segment ties one QuarterContext to a specific sub-window
     * [startEpochMs, endEpochMs] that should be fed via ZIP ticks.
     */
    private static class ZipSegment {
        final QuarterContext ctx;
        final long startEpochMs;
        final long endEpochMs;

        ZipSegment(QuarterContext ctx, long startEpochMs, long endEpochMs) {
            this.ctx = ctx;
            this.startEpochMs = startEpochMs;
            this.endEpochMs = endEpochMs;
        }
    }

    @Test
    void bulk_backtest_all_symbols_across_qualifying_quarters_sharedZip() throws Exception {
        Assumptions.assumeTrue(Files.isDirectory(DATA_ROOT), "Test data folder not found: " + DATA_ROOT);

        // All contexts keyed by symbol -> list of quarter contexts
        Map<String, List<QuarterContext>> symbolContexts = new LinkedHashMap<>();

        // All ZIP segments across ALL symbols/quarters
        List<ZipSegment> zipSegments = new ArrayList<>();

        // Track global ZIP date range (min start, max end across all segments)
        LocalDate globalZipStart = null;
        LocalDate globalZipEnd = null;

        // === STAGE 1: Build contexts + run GROWW segment per context ===

        int processedSymbols = 0;
        for (String nse : NSE_SYMBOLS) {
            processedSymbols++;
            String tokenStr = CompanyInfo.getZerodhaInstrumentFromNse(nse);
            if (tokenStr == null || tokenStr.isBlank()) {
                System.out.println("[SKIP] " + nse + " (no Zerodha token)");
                continue;
            }

            final int token;
            try {
                token = Integer.parseInt(tokenStr);
            } catch (NumberFormatException nfe) {
                System.err.println("[WARN] Invalid token for " + nse + ": " + tokenStr);
                continue;
            }

            String pathJson = PERFORMANCE_JSON_DIR + "\\" + nse + ".json";
            List<QuarterRecord> all = QuarterRecord.loadHistoricalsQuarters(pathJson);
            if (all == null || all.isEmpty()) {
                System.out.println("[SKIP] " + nse + " (no quarters JSON)");
                continue;
            }

            List<QuarterRecord> candidates = all.stream()
                    .filter(QuarterRecord::hasBothFinalScores)
                    .filter(q -> q.startEndWindow4Months() != null)
                    .filter(q -> QUARTER_ALLOW_FILTER.isEmpty()
                            || QUARTER_ALLOW_FILTER.contains(q.getQuarter()))
                    .toList();

            if (candidates.isEmpty()) {
                System.out.println("   [" + nse + "] No quarters matching QUARTER_ALLOW_FILTER=" + QUARTER_ALLOW_FILTER);
                continue;
            }

            List<QuarterContext> ctxList = new ArrayList<>();

            for (int i = 0; i < candidates.size(); i++) {
                QuarterRecord q = candidates.get(i);
                String[] win = q.startEndWindow4Months();
                if (win == null || win.length < 2) continue;

                String startStr = win[0];
                String endStr = win[1];

                LocalDate startDate;
                LocalDate endDate;
                try {
                    startDate = LocalDate.parse(startStr, DDMMYY);
                    endDate = LocalDate.parse(endStr, DDMMYY);
                } catch (Exception e) {
                    System.err.println("   [" + nse + "] Invalid dd-MM-yy window: " + Arrays.toString(win));
                    continue;
                }

                if (!startDate.isBefore(endDate)) {
                    System.err.println("   [" + nse + "] startDate >= endDate, skipping: " + startStr + " -> " + endStr);
                    continue;
                }

                System.out.printf(Locale.ROOT,
                        "   [%s] %d/%d :: %s | dateRaw=%s | window=%s -> %s | crossover=%s%n",
                        nse, (i + 1), candidates.size(), q.getQuarter(), q.getDateTimeRaw(),
                        startStr, endStr, CROSSOVER_DATE_STR);

                OrderManagementService oms = new OrderManagementService();
                LogicalCore_bt_v3 core;
                try {
                    core = new LogicalCore_bt_v3(token, nse, oms, q);
                } catch (NullPointerException npe) {
                    System.out.println("   -> Skipping (LogicalCore_bt_v3 not null-safe). Quarter: " + q.getQuarter());
                    continue;
                }

                QuarterContext ctx = new QuarterContext(nse, token, q, oms, core, startDate, endDate);
                ctxList.add(ctx);

                // ==== GROWW SEGMENT FOR THIS CONTEXT (if any) ====
                if (!startDate.isAfter(GROWW_ZERODHA_CROSSOVER)) {
                    LocalDate growwEnd = endDate.isBefore(GROWW_ZERODHA_CROSSOVER)
                            ? endDate
                            : GROWW_ZERODHA_CROSSOVER;

                    runGrowwSegment(ctx, startDate, growwEnd);
                }

                // ==== ZIP SEGMENT FOR THIS CONTEXT (if any) ====
                if (endDate.isAfter(GROWW_ZERODHA_CROSSOVER)) {
                    LocalDate zStart = startDate.isAfter(GROWW_ZERODHA_CROSSOVER)
                            ? startDate
                            : GROWW_ZERODHA_CROSSOVER.plusDays(1); // day after crossover

                    if (!zStart.isAfter(endDate)) {
                        // We will convert this (zStart..endDate) to epochMs range inside addZipSegment
                        ZipSegment seg = addZipSegment(ctx, zStart, endDate);
                        zipSegments.add(seg);

                        // Update global ZIP range
                        if (globalZipStart == null || zStart.isBefore(globalZipStart)) {
                            globalZipStart = zStart;
                        }
                        if (globalZipEnd == null || endDate.isAfter(globalZipEnd)) {
                            globalZipEnd = endDate;
                        }
                    }
                }
            }

            if (!ctxList.isEmpty()) {
                symbolContexts.put(nse, ctxList);
            }

            System.out.printf(Locale.ROOT,
                    "== [%d/%d] %s contexts=%d%n",
                    processedSymbols, NSE_SYMBOLS.length, nse, ctxList.size());
        }

        // === STAGE 2: SINGLE ZIP STREAM FOR ALL SYMBOLS/QUARTERS ===
        if (globalZipStart != null && globalZipEnd != null && !zipSegments.isEmpty()) {
            System.out.println("\n=== RUNNING SHARED ZIP STREAM ===");
            System.out.println("Global ZIP range: " +
                    globalZipStart.format(DDMMYY) + " -> " + globalZipEnd.format(DDMMYY));

            runSharedZipStream(zipSegments, globalZipStart, globalZipEnd);
        } else {
            System.out.println("\n=== NO ZIP SEGMENTS NEEDED (all windows <= crossover or filtered out) ===");
        }

        // === STAGE 3: AGGREGATE STATS (PER SYMBOL + GRAND TOTAL) ===
        AtomicInteger grandStreams = new AtomicInteger(0);
        AtomicInteger grandTicks = new AtomicInteger(0);

        System.out.println("\n=== PER-SYMBOL SUMMARY ===");
        for (Map.Entry<String, List<QuarterContext>> e : symbolContexts.entrySet()) {
            String nse = e.getKey();
            List<QuarterContext> ctxList = e.getValue();

            int symbolStreams = 0;
            int symbolTicks = 0;

            for (QuarterContext ctx : ctxList) {
                int ticks = ctx.tickCounter.get();
                if (ticks > 0) {
                    symbolStreams++; // count this quarter as "one successful stream"
                    symbolTicks += ticks;
                }
            }

            if (symbolTicks > 0) {
                System.out.printf(Locale.ROOT,
                        "%-12s streams=%-4d ticks=%-8d%n",
                        nse, symbolStreams, symbolTicks);
            }

            grandStreams.addAndGet(symbolStreams);
            grandTicks.addAndGet(symbolTicks);
        }

        System.out.println("\n=== GRAND SUMMARY ===");
        System.out.println("Total successful streams = " + grandStreams.get());
        System.out.println("Total ticks             = " + grandTicks.get());

        assertTrue(grandStreams.get() > 0,
                "No successful streams produced ticks across all symbols.");
    }

    /**
     * Run the Groww segment for one QuarterContext in [startDate, endDate] (inclusive).
     */
    private void runGrowwSegment(QuarterContext ctx, LocalDate startDate, LocalDate endDate) {
        String segStart = startDate.format(DDMMYY);
        String segEnd = endDate.format(DDMMYY);

        System.out.println("      [GROWW] " + ctx.nse + " " + segStart + " -> " + segEnd);

        StreamHistoricalData.BlockCallback cb = new StreamHistoricalData.BlockCallback() {
            @Override
            public boolean onBlock(Block block) {
                try {
                    ctx.core.onBlock(block);
                    ctx.oms.onBlock(block);
                    ctx.tickCounter.incrementAndGet();
                } catch (Exception ex) {
                    System.err.println("   [GROWW onBlock error] " + ex);
                }
                return true;
            }

            @Override
            public void onError(Exception e, Path source) {
                System.err.println("   -> Groww error for " + ctx.nse + ": " + e + " at " + source);
            }

            @Override
            public void onEnd() {
                // no-op
            }
        };

        try {
            StreamHistoricalData streamer = new StreamHistoricalData(
                    DATA_ROOT,
                    segStart,
                    segEnd,
                    ctx.nse,
                    -1,
                    cb
            );
            streamer.stream_groww(ctx.nse, ctx.token, GROWW_INTERVAL_MIN, IS_INDEX);
        } catch (Exception e) {
            System.err.printf(Locale.ROOT,
                    "   -> Groww stream failed for %s (%s -> %s): %s%n",
                    ctx.quarter.getQuarter(), segStart, segEnd, e);
        }
    }

    /**
     * Create a ZipSegment for one QuarterContext and a date range [zStart..zEnd] (inclusive).
     * Converts to epochMs at local midnight; your Block epochMs must be comparable.
     */
    private ZipSegment addZipSegment(QuarterContext ctx,
                                     LocalDate zStart,
                                     LocalDate zEnd) {
        // Convert to epochMs based on your recording timezone.
        // Assuming ticks are in Asia/Kolkata; adjust ZoneId if needed.
        ZoneId zone = ZoneId.of("Asia/Kolkata");

        long startEpochMs = zStart.atStartOfDay(zone).toInstant().toEpochMilli();
        long endEpochMs = zEnd.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1; // inclusive day

        System.out.println("      [ZIP] " + ctx.nse + " " +
                zStart.format(DDMMYY) + " -> " + zEnd.format(DDMMYY) +
                "  (epoch " + startEpochMs + " -> " + endEpochMs + ")");

        return new ZipSegment(ctx, startEpochMs, endEpochMs);
    }

    /**
     * Single ZIP stream:
     *  - Stream NIFTY_100 ZIP data once from globalZipStart..globalZipEnd
     *  - For each tick, route to all QuarterContexts whose:
     *      - token matches
     *      - epochMs is within that context's ZIP window
     */
    private void runSharedZipStream(List<ZipSegment> zipSegments,
                                    LocalDate globalZipStart,
                                    LocalDate globalZipEnd) {

        // Shared callback that broadcasts every block to all segments
        StreamHistoricalData.BlockCallback cb = new StreamHistoricalData.BlockCallback() {
            @Override
            public boolean onBlock(Block block) {
                try {
                    long epochMs = block.getTimeStamp(); // your existing timestamp

                    for (ZipSegment seg : zipSegments) {
                        // still respect the quarter-specific ZIP window
                        if (epochMs < seg.startEpochMs || epochMs > seg.endEpochMs) {
                            continue;
                        }

                        QuarterContext ctx = seg.ctx;
                        try {
                            // every matching tick goes to this quarter's core + OMS
                            ctx.core.onBlock(block);
                            ctx.oms.onBlock(block);
                            ctx.tickCounter.incrementAndGet();
                        } catch (Exception ex) {
                            System.err.println("   [ZIP onBlock error] " + ex +
                                    " for " + ctx.nse +
                                    " quarter=" + ctx.quarter.getQuarter());
                        }
                    }
                } catch (Exception exOuter) {
                    System.err.println("   [ZIP onBlock outer error] " + exOuter);
                }
                return true;
            }

            @Override
            public void onError(Exception e, Path source) {
                System.err.println("   -> ZIP error: " + e + " at " + source);
            }

            @Override
            public void onEnd() {
                // no-op
            }
        };

        String segStart = globalZipStart.format(DDMMYY);
        String segEnd = globalZipEnd.format(DDMMYY);

        try {
            StreamHistoricalData streamer = new StreamHistoricalData(
                    DATA_ROOT,
                    segStart,
                    segEnd,
                    "NIFTY_100", // or whatever "combined" name you use
                    -1,
                    cb
            );
            System.out.println("      [ZIP STREAM] NIFTY_100 " + segStart + " -> " + segEnd);
            streamer.stream("09:15 am", "03:30 pm");
        } catch (Exception e) {
            System.err.printf(Locale.ROOT,
                    "   -> Shared ZIP stream failed (%s -> %s): %s%n",
                    segStart, segEnd, e);
        }
    }
}
