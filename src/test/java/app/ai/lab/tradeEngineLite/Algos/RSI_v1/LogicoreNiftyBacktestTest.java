package app.ai.lab.tradeEngineLite.Algos.RSI_v1;

import app.ai.lab.tradeEngineLite.BackTest.Engine.HistoricalData.Block;
import app.ai.lab.tradeEngineLite.BackTest.Engine.HistoricalData.StreamHistoricalData;
import app.ai.lab.tradeEngineLite.BackTest.Exchange.OrderManagementService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration style test that performs a backtest on the NIFTY_100 index
 * using {@link StreamHistoricalData}. The test is skipped if the sample data
 * directory is not available.
 */
class LogicoreNiftyBacktestTest {

    @Test
    void backtestNifty100() throws IOException {
        Path root = Path.of("D:\\Node Project\\Trading\\IntraDay record\\ticker_historic_data");
        Assumptions.assumeTrue(Files.isDirectory(root), "Test data folder not found: " + root);

        Path outDir = Path.of("D:", "SpringBoot project", "Trade", "output files");
        Files.createDirectories(outDir);

        final int token = 256265;
        final String name = "NIFTY_100";

        Files.deleteIfExists(outDir.resolve(token + "_" + name + ".json"));
        Files.deleteIfExists(outDir.resolve(token + "_" + name + ".png"));

        OrderManagementService oms = new OrderManagementService();
        Logicore logic = new Logicore(token, name, oms);

        StreamHistoricalData streamer = new StreamHistoricalData(
                root,
                "05-09-25",
                "05-09-25",
                "NIFTY_100",
                -1,
                new StreamHistoricalData.BlockCallback() {
                    @Override
                    public boolean onBlock(Block block) {
                        logic.onBlock(block);
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

        streamer.stream("09:15 am", "03:30 pm");
        logic.drawGraph(outDir.toString());

        assertTrue(Files.exists(outDir.resolve(token + "_" + name + ".json")));
        assertTrue(Files.exists(outDir.resolve(token + "_" + name + ".png")));
    }
}

