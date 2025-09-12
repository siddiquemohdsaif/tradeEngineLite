package app.ai.lab.tradeEngineLite.BackTest.Engine.HistoricalData;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import app.ai.lab.tradeEngineLite.BackTest.Engine.HistoricalData.Block.PacketData;
import app.ai.lab.tradeEngineLite.BackTest.Engine.HistoricalData.Block.StockPacket;
import app.ai.lab.tradeEngineLite.GraphUtils.CandleGraphTracker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamHistoricalDataTest {
        
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS z").withZone(IST);

    @Test
    void smokeStream_FirstFewBlocks_Nifty100() {
        // Adjust path if needed:
        Path root = Path.of("D:\\Node Project\\Trading\\IntraDay record\\ticker_historic_data");
        Assumptions.assumeTrue(Files.isDirectory(root), "Test data folder not found: " + root);

        AtomicInteger seen = new AtomicInteger();

        StreamHistoricalData streamer = new StreamHistoricalData(
                root,
                "03-09-25",
                "05-09-25",
                "NIFTY_100",
                -1, // no delay during tests
                new StreamHistoricalData.BlockCallback() {
                    @Override
                    public boolean onBlock(Block block) {
                        int n = seen.incrementAndGet();
                        // Basic visibility
                        // System.out.printf("Block #%d @ %d, packets=%d%n",
                        //         n, block.getTimeStamp(),
                        //         block.getInfo() != null ? block.getInfo().size() : 0);

                        // Stop early to keep tests fast:
                        return n < 200000;
                    }

                    @Override
                    public void onError(Exception e, Path source) {
                        System.err.println("Error: " + e + " at " + source);
                    }

                    @Override
                    public void onEnd() {
                        System.out.println("Stream ended. Total blocks: " + seen.get());
                    }
                }
        );

        streamer.stream();
        assertTrue(seen.get() >= 0, "Should see zero or more blocks without hard failure");
    }

    @Test
    void smokeStream_Sensex_WithDelay() {
        Path root = Path.of("D:\\Node Project\\Trading\\IntraDay record\\ticker_historic_data");
        Assumptions.assumeTrue(Files.isDirectory(root), "Test data folder not found: " + root);

        AtomicInteger seen = new AtomicInteger();

        StreamHistoricalData streamer = new StreamHistoricalData(
                root,
                "20-08-25",
                "05-09-25",
                "SENSEX",
                1, // 1 ms small delay just to exercise the path
                new StreamHistoricalData.BlockCallback() {
                    @Override
                    public boolean onBlock(Block block) {
                        return seen.incrementAndGet() < 10; // keep it short
                    }
                }
        );

        streamer.stream();
        assertTrue(seen.get() >= 0);
    }



    
    @Test
    void smokeStream_FirstFewBlocks_Nifty100_printIndexPackets() {
        Path root = Path.of("D:\\Node Project\\Trading\\IntraDay record\\ticker_historic_data");
        Assumptions.assumeTrue(Files.isDirectory(root), "Test data folder not found: " + root);

        AtomicInteger indexSeen = new AtomicInteger();

        StreamHistoricalData streamer = new StreamHistoricalData(
                root,
                "05-09-25",
                "05-09-25",
                "NIFTY_100",
                -1, // no delay
                new StreamHistoricalData.BlockCallback() {
                    @Override
                    public boolean onBlock(Block block) {
                        if (block.getInfo() == null || block.getInfo().isEmpty()) return true;

                        long blockTs = block.getTimeStamp();
                        String blockTsStr = FMT.format(Instant.ofEpochMilli(blockTs));

                        for (Block.PacketData pd : block.getInfo()) {
                            if (pd instanceof Block.IndexPacket ip) {
                                int n = indexSeen.incrementAndGet();

                                if (ip.getToken() != 256265  ) {
                                    return true;
                                }
                                System.out.printf(
                                        "IndexPkt #%d  blockTs=%d (%s)  token=%d  ltp=%d  o=%d  h=%d  l=%d  c=%d  chg=%d  exTs=%d %n",
                                        n,
                                        blockTs, blockTsStr,
                                        ip.getToken(),
                                        ip.getLastTradedPrice(),
                                        ip.getOpenPrice(),
                                        ip.getHighPrice(),
                                        ip.getLowPrice(),
                                        ip.getClosePrice(),
                                        ip.getPriceChange(),
                                        ip.getExchangeTimestamp()
                                );
                            }
                        }
                        return true; // continue streaming
                    }

                    @Override
                    public void onError(Exception e, Path source) {
                        System.err.println("Error: " + e + " at " + source);
                    }

                    @Override
                    public void onEnd() {
                        System.out.println("Stream ended. Total index packets printed: " + indexSeen.get());
                    }
                }
        );

        streamer.stream();
        assertTrue(indexSeen.get() >= 0, "Should see zero or more index packets without hard failure");
    }



    @Test
    void smokeStream_FirstFewBlocks_Nifty100_candleIndexPackets() throws IOException {
        Path root = Path.of("D:\\Node Project\\Trading\\IntraDay record\\ticker_historic_data");
        Assumptions.assumeTrue(Files.isDirectory(root), "Test data folder not found: " + root);


        Path outDir = Path.of("D:", "SpringBoot project", "Trade", "output files");
        Files.createDirectories(outDir);

        final int token = 256265;      // NIFTY_100 index token
        final String name = "NIFTY_100";

        Files.deleteIfExists(outDir.resolve(token + "_" + name + ".json"));
        Files.deleteIfExists(outDir.resolve(token + "_" + name + ".png"));

        CandleGraphTracker tracker = new CandleGraphTracker(token, name);
        AtomicInteger added = new AtomicInteger();

        StreamHistoricalData streamer = new StreamHistoricalData(
                root,
                "04-09-25",
                "05-09-25",
                "NIFTY_100",
                -1, // no delay
                new StreamHistoricalData.BlockCallback() {
                     @Override public boolean onBlock(Block block) {
                        if (block.getInfo() == null) return true;
                        for (Block.PacketData pd : block.getInfo()) {
                            if (pd instanceof Block.IndexPacket ip && ip.getToken() == token) {
                                
                                long ts = block.getTimeStamp();
                                double ltp = ip.getLastTradedPrice()/100.0;
                                tracker.addMarketData(ts, ltp);
                                added.incrementAndGet();

                                long blockTs = block.getTimeStamp();
                                String blockTsStr = FMT.format(Instant.ofEpochMilli(blockTs));
                                System.out.printf(
                                        "IndexPkt #%d  blockTs=%d (%s)  token=%d  ltp=%d  o=%d  h=%d  l=%d  c=%d  chg=%d  exTs=%d %n",
                                        added.get(),
                                        blockTs, blockTsStr,
                                        ip.getToken(),
                                        ip.getLastTradedPrice(),
                                        ip.getOpenPrice(),
                                        ip.getHighPrice(),
                                        ip.getLowPrice(),
                                        ip.getClosePrice(),
                                        ip.getPriceChange(),
                                        ip.getExchangeTimestamp()
                                );
                            }
                        }
                        return true;
                    }

                    @Override public void onError(Exception e, Path source) {
                        System.err.println("Error: " + e + " at " + source);
                    }

                    @Override public void onEnd() {
                        System.out.println("Stream ended. Total ticks added: " + added.get());
                    }
                }
        );

        streamer.stream("09:15 am", "03:30 pm"); // Asia/Kolkata by default
        tracker.drawCandleGraph(outDir.toString());

        assertTrue(added.get() > 0, "No index ticks captured for NIFTY_100");
        assertTrue(Files.exists(outDir.resolve(token + "_" + name + ".json")));
        assertTrue(Files.exists(outDir.resolve(token + "_" + name + ".png")));
    }

}
