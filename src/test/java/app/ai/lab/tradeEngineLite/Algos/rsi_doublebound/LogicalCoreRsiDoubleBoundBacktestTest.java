package app.ai.lab.tradeEngineLite.Algos.rsi_doublebound;

import app.ai.lab.tradeEngineLite.BackTest.Engine.HistoricalData.Block;
import app.ai.lab.tradeEngineLite.BackTest.Engine.HistoricalData.StreamHistoricalData;
import app.ai.lab.tradeEngineLite.BackTest.Exchange.OrderManagementService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for RSI Double-Bound LONG strategy.
 * Streams historical data and renders CandleGraphTracker output.
 *
 * Notes:
 * - Keeps the same streaming pattern as your RSI_Rebound test.
 * - Writes graph files under .../RSI_DoubleBound_Long_v1/<symbol>/graph
 * - Asserts JSON + image existence after drawGraph.
 */
class LogicalCoreRsiDoubleBoundBacktestTest {

    @Test
    void backtestNiftyRsiDoubleBoundLong() throws IOException {
        // Adjust if your dataset path differs
        Path root = Path.of("D:\\Node Project\\Trading\\IntraDay record\\ticker_historic_data");
        Assumptions.assumeTrue(Files.isDirectory(root), "Test data folder not found: " + root);

        // Instrument under test
        final int token = 81153;     // HCLTECH instrument token
        final String name = "BAJFINANCE";


        // Output directory (mirrors your style; separate strategy namespace)
        Path outDir = Path.of("D:", "SpringBoot project", "Trade", "output files",
                "tradeInfo", "RSI_DoubleBound_Long_v1", name, "graph");
        Files.createDirectories(outDir);

        // Clean previous renders so assertions are meaningful
        Files.deleteIfExists(outDir.resolve(token + "_" + name + ".json"));
        Files.deleteIfExists(outDir.resolve(token + "_" + name + ".png"));
        Files.deleteIfExists(outDir.resolve(token + "_" + name + ".bmp"));

        // Wire OMS + Strategy
        OrderManagementService oms = new OrderManagementService();
        LogicalCore logic = new LogicalCore(token, name, oms);
        AtomicInteger added = new AtomicInteger();

        // Historical streamer (same date range style as your other test)
        StreamHistoricalData streamer = new StreamHistoricalData(
                root,
                "01-01-24",   // from (dd-MM-yy)
                "07-10-25",   // to   (dd-MM-yy)
                name,
                -1,
                new StreamHistoricalData.BlockCallback() {
                    @Override
                    public boolean onBlock(Block block) {
                        logic.onBlock(block);
                        oms.onBlock(block);
                        added.incrementAndGet();
                        return true;
                    }

                    @Override
                    public void onError(Exception e, Path source) {
                        System.err.println("Error: " + e + " at " + source);
                    }

                    @Override
                    public void onEnd() {
                        // no-op
                    }
                }
        );

        // ⚠️ Use a valid enctoken for your environment/dataset, or keep the same one you use elsewhere
        final String ENCTOKEN = "ay+YJQIv/iZDRVrAetpZSMsLIm4yCiD3tCC9arcvEeILAhBB4gpeulO8F1eg1Ewl8WlFw/zV45tAsN18Ko/g8q/bCBusdrEhLyc1BXZ+fsXbrjIBmnJwEw==";

        // Stream & backtest
        streamer.stream_zerodha(ENCTOKEN, token, 1, false);

        // Render graph/volatility JSON via CandleGraphTracker
        logic.drawGraph(outDir.toString());

        // Assert generated artifacts (CandleGraphTracker typically writes <token>_<name>.*)
        boolean jsonExists = Files.exists(outDir.resolve(token + "_" + name + ".json"));
        boolean pngExists  = Files.exists(outDir.resolve(token + "_" + name + ".png"));
        boolean bmpExists  = Files.exists(outDir.resolve(token + "_" + name + ".bmp"));

        assertTrue(jsonExists, "Missing volatility JSON");
        assertTrue(pngExists || bmpExists, "Missing chart image (.png or .bmp)");
    }
}
