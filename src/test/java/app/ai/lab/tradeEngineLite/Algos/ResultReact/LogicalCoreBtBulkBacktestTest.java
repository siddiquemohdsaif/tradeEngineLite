package app.ai.lab.tradeEngineLite.Algos.ResultReact;

import app.ai.lab.tradeEngineLite.BackTest.Engine.HistoricalData.Block;
import app.ai.lab.tradeEngineLite.BackTest.Engine.HistoricalData.StreamHistoricalData;
import app.ai.lab.tradeEngineLite.BackTest.Exchange.OrderManagementService;
import app.ai.lab.tradeEngineLite.Utils.CompanyInfo;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LogicalCoreBtBulkBacktestTest {

    // === CONFIG ===
    private static final boolean USE_GROWW = true;          // false -> Zerodha streaming
    private static final int GROWW_INTERVAL_MIN = 1440;     // 1, 5, 15, 60, 1440
    private static final int ZERODHA_INTERVAL_MIN = 1;      // if you flip USE_GROWW = false
    private static final boolean IS_INDEX = false;          // all below are equity stocks

    // For Zerodha-only runs; ignored when USE_GROWW = true
    private static final String ENCTOKEN =
            "8lW4cK0WbBaaKtdR8gpjbOnPb0pBdUZuwqqcsO9j3eZLL2+A3HH1HDnFffzMVOyqj5OYAUiVf4b3TIE7ogRcaiulBB+HzoyR6x6o28PQcrTsXvcrfhTYbQ==";

    // Root folders
    private static final Path DATA_ROOT = Path.of("D:\\Node Project\\Trading\\IntraDay record\\ticker_historic_data");
    private static final String PERFORMANCE_JSON_DIR = "D:\\Node Project\\webscrap\\ms-events\\data\\analyser\\performance";

    // === BULK LIST ===
    private static final String[] NSE_SYMBOLS = {
"HINDALCO", "OIL", "IDBI", "INDUSTOWER", "GAIL", "FEDERALBNK", "IOB",
  "SHRIRAMFIN", "DRREDDY", "BAJAJHLDNG", "ZYDUSLIFE", "AUROPHARMA", "LUPIN",
  "SAIL", "MUTHOOTFIN", "TORNTPOWER", "JSL", "HEROMOTOCO", "CIPLA", "IRFC",
  "AMBUJACEM", "SUZLON", "YESBANK", "NHPC", "ABCAPITAL", "ASHOKLEY", "UPL",
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
                System.out.println("[SKIP] " + nse + " (no quarters / token / file)");
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

            List<QuarterRecord> candidates = all.stream()
                    .filter(QuarterRecord::hasBothFinalScores)
                    .filter(q -> q.startEndWindow4Months() != null)
                    .toList();
            if (candidates.isEmpty()) return null;

            AtomicInteger totalStreams = new AtomicInteger(0);
            AtomicInteger totalTicks = new AtomicInteger(0);

            for (int i = 0; i < candidates.size(); i++) {
                QuarterRecord q = candidates.get(i);
                String[] win = q.startEndWindow4Months();
                if (win == null || win.length < 2) continue;

                String start = win[0], end = win[1];
                System.out.printf(Locale.ROOT,
                        "   [%s] %d/%d :: %s | dateRaw=%s | window=%s -> %s%n",
                        nse, (i + 1), candidates.size(), q.getQuarter(), q.getDateTimeRaw(), start, end);

                OrderManagementService oms = new OrderManagementService();
                LogicalCore_bt core;
                try {
                    core = new LogicalCore_bt(token, nse, oms, q);
                } catch (NullPointerException npe) {
                    System.out.println("   -> Skipping (LogicalCore_bt not null-safe). Quarter: " + q.getQuarter());
                    continue;
                }

                AtomicInteger tickCounter = new AtomicInteger();

                StreamHistoricalData streamer = new StreamHistoricalData(
                        DATA_ROOT,
                        start,
                        end,
                        nse,
                        -1,
                        new StreamHistoricalData.BlockCallback() {
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
                            public void onEnd() { /* no-op */ }
                        });

                try {
                    if (USE_GROWW) {
                        streamer.stream_groww(nse, token, GROWW_INTERVAL_MIN, IS_INDEX);
                    } else {
                        streamer.stream_zerodha(ENCTOKEN, token, ZERODHA_INTERVAL_MIN, IS_INDEX);
                    }
                    int ticks = tickCounter.get();
                    System.out.println("      finished ticks=" + ticks);
                    if (ticks > 0) {
                        totalStreams.incrementAndGet();
                        totalTicks.addAndGet(ticks);
                    }
                } catch (Exception e) {
                    System.err.printf(Locale.ROOT,
                            "   -> Stream failed for %s (%s -> %s): %s%n", q.getQuarter(), start, end, e);
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
