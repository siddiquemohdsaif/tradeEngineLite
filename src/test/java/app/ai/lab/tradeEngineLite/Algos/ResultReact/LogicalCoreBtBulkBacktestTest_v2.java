package app.ai.lab.tradeEngineLite.Algos.ResultReact;

import app.ai.lab.tradeEngineLite.BackTest.Engine.HistoricalData.Block;
import app.ai.lab.tradeEngineLite.BackTest.Engine.HistoricalData.StreamHistoricalData;
import app.ai.lab.tradeEngineLite.BackTest.Exchange.OrderManagementService;
import app.ai.lab.tradeEngineLite.Utils.CompanyInfo;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LogicalCoreBtBulkBacktestTest_v2 {

    // === CONFIG ===
    // Hybrid logic:
    //  - For dates <= CROSSOVER_DATE -> use Groww
    //  - For dates  > CROSSOVER_DATE -> use Zerodha
    //
    // Example:
    //   CROSSOVER_DATE_STR = "31-03-25"
    //   Window = Feb–Apr 2025
    //   => Groww for Feb + Mar, Zerodha for Apr
    private static final String CROSSOVER_DATE_STR = "31-03-25"; // dd-MM-yy
    private static final DateTimeFormatter DDMMYY =
            DateTimeFormatter.ofPattern("dd-MM-yy", Locale.ENGLISH);
    private static final LocalDate GROWW_ZERODHA_CROSSOVER =
            LocalDate.parse(CROSSOVER_DATE_STR, DDMMYY);

    private static final int GROWW_INTERVAL_MIN = 1440; // 1, 5, 15, 60, 1440
    private static final int ZERODHA_INTERVAL_MIN = 1;  // typical 1-minute stream
    private static final boolean IS_INDEX = false;      // all below are equity stocks

    // Zerodha token / enctoken
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

    @Test
    void bulk_backtest_all_symbols_across_qualifying_quarters() throws Exception {
        Assumptions.assumeTrue(Files.isDirectory(DATA_ROOT), "Test data folder not found: " + DATA_ROOT);

        // Grand totals
        AtomicInteger grandStreams = new AtomicInteger(0);
        AtomicInteger grandTicks = new AtomicInteger(0);
        Map<String, Integer> perSymbolTicks = new LinkedHashMap<>();
        Map<String, Integer> perSymbolStreams = new LinkedHashMap<>();

        int processedSymbols = 0;
        for (String nse : NSE_SYMBOLS) {
            processedSymbols++;
            SymbolOutcome outcome = backtestOneSymbol(nse);
            if (outcome == null) {
                System.out.println("[SKIP] " + nse + " (no quarters / token / file / filtered)");
                continue;
            }
            perSymbolTicks.put(nse, outcome.totalTicks);
            perSymbolStreams.put(nse, outcome.successfulStreams);
            grandTicks.addAndGet(outcome.totalTicks);
            grandStreams.addAndGet(outcome.successfulStreams);

            System.out.printf(Locale.ROOT,
                    "== [%d/%d] %s -> streams=%d, ticks=%d%n",
                    processedSymbols, NSE_SYMBOLS.length, nse, outcome.successfulStreams, outcome.totalTicks);
        }

        // Pretty summary
        System.out.println("\n=== PER-SYMBOL SUMMARY ===");
        for (String nse : perSymbolTicks.keySet()) {
            System.out.printf(Locale.ROOT, "%-12s streams=%-4d ticks=%-8d%n",
                    nse, perSymbolStreams.getOrDefault(nse, 0), perSymbolTicks.getOrDefault(nse, 0));
        }

        System.out.println("\n=== GRAND SUMMARY ===");
        System.out.println("Total successful streams = " + grandStreams.get());
        System.out.println("Total ticks             = " + grandTicks.get());

        assertTrue(grandStreams.get() > 0, "No successful streams produced ticks across all symbols.");
    }

    private SymbolOutcome backtestOneSymbol(String nse) {
        try {
            // Resolve Zerodha token (string -> int) via your CompanyInfo API
            String tokenStr = CompanyInfo.getZerodhaInstrumentFromNse(nse);
            if (tokenStr == null || tokenStr.isBlank()) return null;
            int token;
            try {
                token = Integer.parseInt(tokenStr);
            } catch (NumberFormatException nfe) {
                System.err.println("[WARN] Invalid token for " + nse + ": " + tokenStr);
                return null;
            }

            // Load quarters JSON for symbol
            String pathJson = PERFORMANCE_JSON_DIR + "\\" + nse + ".json";
            List<QuarterRecord> all = QuarterRecord.loadHistoricalsQuarters(pathJson);
            if (all == null || all.isEmpty()) return null;

            // Base filters (scores + valid window)
            List<QuarterRecord> candidates = all.stream()
                    .filter(QuarterRecord::hasBothFinalScores)
                    .filter(q -> q.startEndWindow4Months() != null)
                    .filter(q -> QUARTER_ALLOW_FILTER.isEmpty()
                            || QUARTER_ALLOW_FILTER.contains(q.getQuarter()))
                    .toList();

            if (candidates.isEmpty()) {
                System.out.println("   [" + nse + "] No quarters matching QUARTER_ALLOW_FILTER=" + QUARTER_ALLOW_FILTER);
                return null;
            }

            AtomicInteger totalStreams = new AtomicInteger(0);
            AtomicInteger totalTicks = new AtomicInteger(0);

            for (int i = 0; i < candidates.size(); i++) {
                QuarterRecord q = candidates.get(i);
                String[] win = q.startEndWindow4Months();   // or startEndWindow3Months() if you prefer 3-month window
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
                LogicalCore_bt_v2 core;
                try {
                    core = new LogicalCore_bt_v2(token, nse, oms, q);
                } catch (NullPointerException npe) {
                    System.out.println("   -> Skipping (LogicalCore_bt_v2 not null-safe). Quarter: " + q.getQuarter());
                    continue;
                }

                AtomicInteger tickCounter = new AtomicInteger();

                // Shared callback for both Groww and Zerodha segments
                StreamHistoricalData.BlockCallback cb = new StreamHistoricalData.BlockCallback() {
                    @Override
                    public boolean onBlock(Block block) {
                        core.onBlock(block);
                        oms.onBlock(block);
                        tickCounter.incrementAndGet();
                        return true;
                    }

                    @Override
                    public void onError(Exception e, Path source) {
                        System.err.println("   -> Error: " + e + " at " + source);
                    }

                    @Override
                    public void onEnd() {
                        // no-op
                    }
                };

                // ==== Segment 1: Groww (up to and including CROSSOVER_DATE) ====
                if (!startDate.isAfter(GROWW_ZERODHA_CROSSOVER)) {
                    LocalDate growwEnd = endDate.isBefore(GROWW_ZERODHA_CROSSOVER)
                            ? endDate
                            : GROWW_ZERODHA_CROSSOVER;

                    String segStart = startDate.format(DDMMYY);
                    String segEnd = growwEnd.format(DDMMYY);

                    int before = tickCounter.get();
                    try {
                        StreamHistoricalData streamer = new StreamHistoricalData(
                                DATA_ROOT,
                                segStart,
                                segEnd,
                                nse,
                                -1,
                                cb
                        );
                        System.out.println("      [GROWW] " + segStart + " -> " + segEnd);
                        streamer.stream_groww(nse, token, GROWW_INTERVAL_MIN, IS_INDEX);
                    } catch (Exception e) {
                        System.err.printf(Locale.ROOT,
                                "   -> Groww stream failed for %s (%s -> %s): %s%n",
                                q.getQuarter(), segStart, segEnd, e);
                    }
                    int after = tickCounter.get();
                    int segTicks = after - before;
                    if (segTicks > 0) {
                        totalStreams.incrementAndGet();
                        totalTicks.addAndGet(segTicks);
                        System.out.println("      [GROWW] finished ticks=" + segTicks);
                    }
                }

                // ==== Segment 2: ZIP one second tick data (after CROSSOVER_DATE) ====
                if (endDate.isAfter(GROWW_ZERODHA_CROSSOVER)) {
                    LocalDate zStart = startDate.isAfter(GROWW_ZERODHA_CROSSOVER)
                            ? startDate
                            : GROWW_ZERODHA_CROSSOVER.plusDays(1); // day after crossover
                    if (!zStart.isAfter(endDate)) {
                        String segStart = zStart.format(DDMMYY);
                        String segEnd = endDate.format(DDMMYY);

                        int before = tickCounter.get();
                        try {
                            StreamHistoricalData streamer = new StreamHistoricalData(
                                    DATA_ROOT,
                                    segStart,
                                    segEnd,
                                    "NIFTY_100",
                                    -1,
                                    cb
                            );
                            System.out.println("      [ZERODHA] " + segStart + " -> " + segEnd);
                            // streamer.stream_zerodha(ENCTOKEN, token, ZERODHA_INTERVAL_MIN, IS_INDEX);
                            streamer.stream("09:15 am", "03:30 pm");  // using full-fidelity ZIP data for every second ticks data of 100+ stocks
                        } catch (Exception e) {
                            System.err.printf(Locale.ROOT,
                                    "   -> Zerodha stream failed for %s (%s -> %s): %s%n",
                                    q.getQuarter(), segStart, segEnd, e);
                        }
                        int after = tickCounter.get();
                        int segTicks = after - before;
                        if (segTicks > 0) {
                            totalStreams.incrementAndGet();
                            totalTicks.addAndGet(segTicks);
                            System.out.println("      [ZERODHA] finished ticks=" + segTicks);
                        }
                    }
                }
            }

            return new SymbolOutcome(totalStreams.get(), totalTicks.get());

        } catch (Throwable t) {
            System.err.println("[ERROR] backtestOneSymbol failed for " + nse + ": " + t);
            return null;
        }
    }

    private static class SymbolOutcome {
        final int successfulStreams;
        final int totalTicks;

        SymbolOutcome(int successfulStreams, int totalTicks) {
            this.successfulStreams = successfulStreams;
            this.totalTicks = totalTicks;
        }
    }
}
