package app.ai.lab.tradeEngineLite.Algos.ema_20_v1;

import app.ai.lab.tradeEngineLite.Algos.ema_20_v1.LogicalCore;
import app.ai.lab.tradeEngineLite.BackTest.Engine.HistoricalData.Block;
import app.ai.lab.tradeEngineLite.BackTest.Engine.HistoricalData.StreamHistoricalData;
import app.ai.lab.tradeEngineLite.BackTest.Exchange.OrderManagementService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for EMA-20 "hit" strategy on HCLTECH (token 1850625).
 * Runs from 01-01-23 to 01-01-25 and renders output using CandleGraphTracker.
 */
class LogicalCoreHclTechBacktestTest {

    @Test
    void backtestHclTech_2023_to_2025() throws IOException {
        // Adjust this if your on-disk path differs (kept consistent with your other tests)
        Path root = Path.of("D:\\Node Project\\Trading\\IntraDay record\\ticker_historic_data");
        Assumptions.assumeTrue(Files.isDirectory(root), "Test data folder not found: " + root);

        Path outDir = Path.of("D:", "SpringBoot project", "Trade", "output files");
        Files.createDirectories(outDir);

        final int token = 1850625;     // HCLTECH instrument token
        final String name = "HCLTECH";

        // Clean previous outputs
        Files.deleteIfExists(outDir.resolve(token + "_" + name + ".json"));
        Files.deleteIfExists(outDir.resolve(token + "_" + name + ".png"));
        Files.deleteIfExists(outDir.resolve(token + "_" + name + ".bmp"));

        OrderManagementService oms = new OrderManagementService();
        LogicalCore logic = new LogicalCore(token, name, oms);
        AtomicInteger added = new AtomicInteger();

        StreamHistoricalData streamer = new StreamHistoricalData(
                root,
                "01-01-23",  // dd-MM-yy
                "01-01-25",
                name,
                -1,
                new StreamHistoricalData.BlockCallback() {
                    @Override
                    public boolean onBlock(Block block) {
                        logic.onBlock(block);
                        oms.onBlock(block);
                        int c = added.incrementAndGet();
                        if (c % 100 == 0) {
                            System.out.println("tick-count: " + c + "  time: " + new Date(block.getTimeStamp()));
                        }
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

        // ⚠️ Use a valid Zerodha enctoken. Reuse the one from your other tests or inject via env/secret.
        final String ENCTOKEN = "JrWrXlIwpEiAuwDBr5tr96MjIQ6y0DljShHFKysZbBM20E1nEtouv4jptXdZuFGgtl5F6QSIKHEFiqxDhR/KWSLwuwZnEDqKM+lNhrd9GS6WnfyIle81bg==";

        // Stream 1-minute candles as ticks; isIndex=false for an equity symbol
        streamer.stream_zerodha(ENCTOKEN, token, 1, false);

        // Render chart + volatility JSON
        logic.drawGraph(outDir.toString());

        // Verify outputs (tracker writes .bmp if possible, else .png)
        boolean jsonExists = Files.exists(outDir.resolve(token + "_" + name + ".json"));
        boolean pngExists  = Files.exists(outDir.resolve(token + "_" + name + ".png"));
        boolean bmpExists  = Files.exists(outDir.resolve(token + "_" + name + ".bmp"));

        assertTrue(jsonExists, "Missing volatility JSON");
        assertTrue(pngExists || bmpExists, "Missing chart image (.png or .bmp)");
    }
}
