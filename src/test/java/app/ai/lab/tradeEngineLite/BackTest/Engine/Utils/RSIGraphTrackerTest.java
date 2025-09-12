package app.ai.lab.tradeEngineLite.BackTest.Engine.Utils;

import org.junit.jupiter.api.Test;

import app.ai.lab.tradeEngineLite.GraphUtils.RSIGraphTracker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RSIGraphTrackerTest {

    @Test
    void generatesRSIGraphFromTicks() throws IOException {
        System.setProperty("java.awt.headless", "true");

        Path outDir = Path.of("D:", "SpringBoot project", "Trade", "output files");
        Files.createDirectories(outDir);
        Files.deleteIfExists(outDir.resolve("1_TEST_rsi.json"));
        Files.deleteIfExists(outDir.resolve("1_TEST_rsi.png"));

        long start = 1757166616450L;
        long end = start + 10_000_000L;
        long tickDuration = 100L;
        double startPrice = 100.0;

        RandomTickGenerator gen = new RandomTickGenerator(start, end, tickDuration,
                -1, startPrice, 1.0, 1.0);
        List<RandomTickGenerator.TickData> ticks = gen.generateAll();

        RSIGraphTracker tracker = new RSIGraphTracker(1, "TEST");
        for (RandomTickGenerator.TickData t : ticks) {
            tracker.addMarketData(t.timestamp, t.price);
        }

        tracker.drawRSIGraph(outDir.toString());

        assertTrue(Files.exists(outDir.resolve("1_TEST_rsi.json")));
        assertTrue(Files.exists(outDir.resolve("1_TEST_rsi.png")));
    }
}
