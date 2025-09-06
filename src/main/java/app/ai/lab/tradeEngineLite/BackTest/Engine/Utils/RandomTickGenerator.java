package app.ai.lab.tradeEngineLite.BackTest.Engine.Utils;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.function.Consumer;

/**
 * Utility that produces pseudo random tick data for back testing.
 * <p>
 * Configuration options:
 * <ul>
 *   <li>startTime / endTime - inclusive time range for generated ticks (epoch millis)</li>
 *   <li>tickDurationMs - gap between successive ticks in milliseconds</li>
 *   <li>speedMs - delay in milliseconds between emitted ticks. Use {@code -1} for no delay</li>
 *   <li>startPrice - first tick price</li>
 *   <li>variationPercent - price change in percent applied each tick (e.g. 0.01 for 0.01%)</li>
 *   <li>upSideToDownSideProbabilityRatio - ratio determining how often the price moves up vs down</li>
 * </ul>
 *
 * Prices move by a fixed percentage of the current price on each tick. The
 * direction is chosen randomly according to the supplied probability ratio.
 */
public class RandomTickGenerator implements Iterator<RandomTickGenerator.TickData> {

    /** Immutable tick representation. */
    public static final class TickData {
        public final long timestamp;
        public final double price;

        public TickData(long timestamp, double price) {
            this.timestamp = timestamp;
            this.price = price;
        }
    }

    private final long endTime;
    private final long tickDurationMs;
    private final long speedMs;
    private final double variationFraction;
    private final double upProbability;
    private final Random random = new Random();

    private long currentTime;
    private double currentPrice;

    /**
     * Create a new generator.
     *
     * @param startTime  start timestamp in epoch milliseconds
     * @param endTime    end timestamp (inclusive) in epoch milliseconds
     * @param tickDurationMs duration between ticks in milliseconds
     * @param speedMs    delay between emitted ticks. Use -1 for no delay
     * @param startPrice starting price
     * @param variationPercent percentage movement applied each tick (e.g. 0.01 for 0.01%)
     * @param upSideToDownSideProbabilityRatio ratio of upward to downward moves
     */
    public RandomTickGenerator(long startTime,
                               long endTime,
                               long tickDurationMs,
                               long speedMs,
                               double startPrice,
                               double variationPercent,
                               double upSideToDownSideProbabilityRatio) {
        this.currentTime = startTime;
        this.endTime = endTime;
        this.tickDurationMs = tickDurationMs;
        this.speedMs = speedMs;
        this.currentPrice = startPrice;
        this.variationFraction = variationPercent / 100.0; // convert percentage to fraction
        double ratio = Math.max(upSideToDownSideProbabilityRatio, 0.0);
        this.upProbability = ratio / (ratio + 1.0);
    }

    @Override
    public boolean hasNext() {
        return currentTime <= endTime;
    }

    @Override
    public TickData next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }

        TickData tick = new TickData(currentTime, currentPrice);

        // Prepare state for next tick
        currentTime += tickDurationMs;
        double change = currentPrice * variationFraction;
        if (random.nextDouble() < upProbability) {
            currentPrice += change;
        } else {
            currentPrice -= change;
        }

        if (speedMs >= 0) {
            try {
                Thread.sleep(speedMs);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        return tick;
    }

    /**
     * Generate all ticks and return them as a list. Any configured delay will be
     * honoured during generation.
     */
    public List<TickData> generateAll() {
        List<TickData> ticks = new ArrayList<>();
        while (hasNext()) {
            ticks.add(next());
        }
        return ticks;
    }

    /**
     * Stream ticks to the provided consumer. Generation stops when the end time
     * is reached.
     */
    public void forEachTick(Consumer<TickData> consumer) {
        while (hasNext()) {
            consumer.accept(next());
        }
    }
}

