package app.ai.lab.tradeEngineLite.Algos.RSI_v1;

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
        AtomicInteger added = new AtomicInteger();

        StreamHistoricalData streamer = new StreamHistoricalData(
                root,
                "01-08-25",
                "12-09-25",
                "NIFTY_100",
                -1,
                new StreamHistoricalData.BlockCallback() {
                    @Override
                    public boolean onBlock(Block block) {
                        logic.onBlock(block);
                        oms.onBlock(block);
                        int i = added.incrementAndGet();
                        if (i%100 == 0) {
                            System.out.println("tick-count: " + i + " time : "+ new Date(block.getTimeStamp()));
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

        //streamer.stream("09:15 am", "03:30 pm");
        streamer.stream_zerodha("hqzF41ciowAM20O4R0nzh98pgYYWl07pk5Pc6vHoD5SaILGcfFXlgh7POqZOA6ZRshDxPDGz9f2vchjpI/26zDx3TE/4AkSjwLL/egySui2XnnvlymDDaQ==", token, 1, true);
        logic.drawGraph(outDir.toString());

        assertTrue(Files.exists(outDir.resolve(token + "_" + name + ".json")));
        assertTrue(Files.exists(outDir.resolve(token + "_" + name + ".png")));
    }








    
    @Test
    void backtestNifty100_1_day() throws IOException {
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
        AtomicInteger added = new AtomicInteger();

        StreamHistoricalData streamer = new StreamHistoricalData(
                root,
                "12-09-24",
                "12-09-25",
                "NIFTY_100",
                -1,
                new StreamHistoricalData.BlockCallback() {
                    @Override
                    public boolean onBlock(Block block) {
                        logic.onBlock(block);
                        oms.onBlock(block);
                        int i = added.incrementAndGet();
                        if (i%100 == 0) {
                            System.out.println("tick-count: " + i + " time : "+ new Date(block.getTimeStamp()));
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

        //streamer.stream("09:15 am", "03:30 pm");
        streamer.stream_zerodha("fxPdaQgautTWwdVRoJJmWXVMsHs0EsUpLjMM1nwVeheRlvsgSwoBXpHFkiCb8lc4QcVEZQbbaN9Y/agv7rF0obKyn7NQZVXa54UY6FRjaRmit1Mi3sxNEg==", token, 1, true);
        logic.drawGraph(outDir.toString());

        assertTrue(Files.exists(outDir.resolve(token + "_" + name + ".json")));
        assertTrue(Files.exists(outDir.resolve(token + "_" + name + ".png")));
    }
}

