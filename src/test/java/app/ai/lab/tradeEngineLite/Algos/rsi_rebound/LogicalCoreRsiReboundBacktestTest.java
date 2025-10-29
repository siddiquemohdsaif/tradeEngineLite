package app.ai.lab.tradeEngineLite.Algos.rsi_rebound;

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
 * Integration test for 20-RSI rebound strategy on HCLTECH (token 1850625).
 * Runs from 01-01-23 to 01-01-25 and renders output using CandleGraphTracker.
 */
class LogicalCoreRsiReboundBacktestTest {

    @Test
    void backtestHclTechRsiRebound() throws IOException {
        Path root = Path.of("D:\\Node Project\\Trading\\IntraDay record\\ticker_historic_data");
        Assumptions.assumeTrue(Files.isDirectory(root), "Test data folder not found: " + root);



        final int token = 738561;     // HCLTECH instrument token
        final String name = "RELIANCE";

        Path outDir = Path.of("D:", "SpringBoot project", "Trade", "output files", "tradeInfo", "RSI_Rebound_v1", name, "graph");
        Files.createDirectories(outDir);

        Files.deleteIfExists(outDir.resolve(token + "_" + name + ".json"));
        Files.deleteIfExists(outDir.resolve(token + "_" + name + ".png"));
        Files.deleteIfExists(outDir.resolve(token + "_" + name + ".bmp"));

        OrderManagementService oms = new OrderManagementService();
        LogicalCore logic = new LogicalCore(token, name, oms);
        AtomicInteger added = new AtomicInteger();

        StreamHistoricalData streamer = new StreamHistoricalData(
                root,
                "01-01-24",
                "07-10-25",
                name,
                -1,
                new StreamHistoricalData.BlockCallback() {
                    @Override
                    public boolean onBlock(Block block) {
                        logic.onBlock(block);
                        oms.onBlock(block);
                        int c = added.incrementAndGet();
                        if (c % 100 == 0) {
                            // System.out.println("tick-count: " + c + "  time: " + new Date(block.getTimeStamp()));
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

        final String ENCTOKEN = "ay+YJQIv/iZDRVrAetpZSMsLIm4yCiD3tCC9arcvEeILAhBB4gpeulO8F1eg1Ewl8WlFw/zV45tAsN18Ko/g8q/bCBusdrEhLyc1BXZ+fsXbrjIBmnJwEw==";

        streamer.stream_zerodha(ENCTOKEN, token, 1, false);

        logic.drawGraph(outDir.toString());

        boolean jsonExists = Files.exists(outDir.resolve(token + "_" + name + ".json"));
        boolean pngExists  = Files.exists(outDir.resolve(token + "_" + name + ".png"));
        boolean bmpExists  = Files.exists(outDir.resolve(token + "_" + name + ".bmp"));

        assertTrue(jsonExists, "Missing volatility JSON");
        assertTrue(pngExists || bmpExists, "Missing chart image (.png or .bmp)");
    }
}
