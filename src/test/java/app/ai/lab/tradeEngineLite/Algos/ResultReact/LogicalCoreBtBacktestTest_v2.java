package app.ai.lab.tradeEngineLite.Algos.ResultReact;

import app.ai.lab.tradeEngineLite.BackTest.Engine.HistoricalData.StreamHistoricalData;
import app.ai.lab.tradeEngineLite.BackTest.Engine.HistoricalData.Block;
import app.ai.lab.tradeEngineLite.BackTest.Exchange.OrderManagementService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LogicalCoreBtBacktestTest_v2 {

    @Test
    void backtest_all_qualifying_quarters_generates_ticks() throws Exception {
        Path dataRoot = Path.of("D:\\Node Project\\Trading\\IntraDay record\\ticker_historic_data");
        Assumptions.assumeTrue(Files.isDirectory(dataRoot), "Test data folder not found: " + dataRoot);

        boolean useGroww = true;
        final int token = 1510401; // AXISBANK
        final String name = "AXISBANK";
        final String ENCTOKEN = "8lW4cK0WbBaaKtdR8gpjbOnPb0pBdUZuwqqcsO9j3eZLL2+A3HH1HDnFffzMVOyqj5OYAUiVf4b3TIE7ogRcaiulBB+HzoyR6x6o28PQcrTsXvcrfhTYbQ==";

        String pathJson = "D:\\Node Project\\webscrap\\ms-events\\data\\analyser\\performance\\AXISBANK.json";
        List<QuarterRecord> all = QuarterRecord.loadHistoricalsQuarters(pathJson);
        Assumptions.assumeTrue(!all.isEmpty(), "No quarter records loaded from " + pathJson);

        List<QuarterRecord> candidates = all.stream()
                .filter(QuarterRecord::hasBothFinalScores)
                .filter(q -> q.startEndWindow4Months() != null)
                .toList();

        Assumptions.assumeTrue(!candidates.isEmpty(), "No qualifying quarters with both final scores & parsable date.");

        AtomicInteger totalStreams = new AtomicInteger(0);
        AtomicInteger totalTicks = new AtomicInteger(0);

        for (int i = 0; i < candidates.size(); i++) {
            QuarterRecord q = candidates.get(i);
            String[] win = q.startEndWindow4Months();
            String start = win[0], end = win[1];

            System.out.println("== " + (i + 1) + "/" + candidates.size()
                    + " :: " + q.getQuarter()
                    + " | dateRaw=" + q.getDateTimeRaw()
                    + " | window=" + start + " -> " + end);

            OrderManagementService oms = new OrderManagementService();

            LogicalCore_bt core;
            try {
                // If your constructor still needs QuarterRecord non-null, we pass q.
                core = new LogicalCore_bt(token, name, oms, q);
            } catch (NullPointerException npe) {
                System.out.println("Skipping (LogicalCore_bt not null-safe): " + q.getQuarter());
                continue;
            }

            AtomicInteger tickCounter = new AtomicInteger();

            StreamHistoricalData streamer = new StreamHistoricalData(
                    dataRoot,
                    start,
                    end,
                    name,
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
                            System.err.println("Error: " + e + " at " + source);
                        }

                        @Override
                        public void onEnd() {
                        }
                    });

            try {
                if (useGroww) {
                    streamer.stream_groww(name, token, 1440, false);
                } else {
                    streamer.stream_zerodha(ENCTOKEN, token, 1, false);
                }
                int ticks = tickCounter.get();
                System.out.println("   -> finished ticks=" + ticks);
                if (ticks > 0) {
                    totalStreams.incrementAndGet();
                    totalTicks.addAndGet(ticks);
                }
            } catch (Exception e) {
                System.err.println("Stream failed for " + q.getQuarter() + " (" + start + "->" + end + "): " + e);
            }
        }

        System.out
                .println("=== Summary: successful streams=" + totalStreams.get() + ", total ticks=" + totalTicks.get());
        assertTrue(totalStreams.get() > 0, "No successful streams produced ticks.");
    }
}
