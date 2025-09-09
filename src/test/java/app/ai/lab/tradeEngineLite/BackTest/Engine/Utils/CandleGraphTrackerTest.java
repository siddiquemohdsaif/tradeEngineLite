package app.ai.lab.tradeEngineLite.BackTest.Engine.Utils;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CandleGraphTrackerTest {

    @Test
    void generatesCandleGraphFromTicks() throws IOException {
        System.setProperty("java.awt.headless", "true");

        // Fixed output directory on Windows
        Path outDir = Path.of("D:", "SpringBoot project", "Trade", "output files");
        Files.createDirectories(outDir); // make sure it exists

        // (Optional) start clean so assertions don't pass from stale files
        Files.deleteIfExists(outDir.resolve("1_TEST.json"));
        Files.deleteIfExists(outDir.resolve("1_TEST.png"));

        long start = 1757166616450L;
        long end = start + 10_000_000L; // 1 second range
        long tickDuration = 100L;
        double startPrice = 100.0;

        RandomTickGenerator gen = new RandomTickGenerator(start, end, tickDuration,
                -1, startPrice, 1.0, 1.0);

        List<RandomTickGenerator.TickData> ticks = gen.generateAll();
        int expectedCount = (int) ((end - start) / tickDuration) + 1;
        assertEquals(expectedCount, ticks.size(), "tick count");

        // Print ticks
        for (RandomTickGenerator.TickData t : ticks) {
            System.out.printf("tick @ %d ms -> price=%.4f%n", t.timestamp, t.price);
        }

        CandleGraphTracker tracker = new CandleGraphTracker(1, "TEST");
        for (RandomTickGenerator.TickData t : ticks) {
            tracker.addMarketData(t.timestamp, t.price);
        }

        // Save outputs to the fixed directory
        tracker.drawCandleGraph(outDir.toString());

        Path json = outDir.resolve("1_TEST.json");
        Path img  = outDir.resolve("1_TEST.png");
        assertTrue(Files.exists(json), "volatility json");
        assertTrue(Files.exists(img), "graph image");

        // Print generated candles
        for (CandleGraphTracker.Candle c : tracker.candles) {
            System.out.printf("candle %s -> O=%.4f H=%.4f L=%.4f C=%.4f%n",
                    c.candleId, c.open, c.high, c.low, c.close);
        }
    }
}

