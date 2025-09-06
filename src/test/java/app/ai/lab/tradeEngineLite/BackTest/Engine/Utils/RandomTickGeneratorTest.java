package app.ai.lab.tradeEngineLite.BackTest.Engine.Utils;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RandomTickGeneratorTest {

    @Test
    void generatesExpectedTicksWithoutVariation() {
        long start = 0L;
        long end = 1_000L; // 1 second range
        long tickDuration = 100L;
        double startPrice = 100.0;

        RandomTickGenerator gen = new RandomTickGenerator(start, end, tickDuration,
                -1, startPrice, 0.0, 1.0);

        List<RandomTickGenerator.TickData> ticks = gen.generateAll();

        int expectedCount = (int) ((end - start) / tickDuration) + 1;
        assertEquals(expectedCount, ticks.size(), "tick count");
        assertEquals(start, ticks.get(0).timestamp);
        assertEquals(end, ticks.get(ticks.size() - 1).timestamp);
        // price should remain constant when variation is 0
        for (RandomTickGenerator.TickData t : ticks) {
            assertEquals(startPrice, t.price, 0.0);
        }
    }
}

