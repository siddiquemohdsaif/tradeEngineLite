package app.ai.lab.tradeEngineLite.BackTest.Engine.HistoricalData;

import app.ai.lab.tradeEngineLite.BackTest.Engine.HistoricalData.HistoricalCandleFetcherZerodha.Candle;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class HistoricalCandleFetcherTest {

    private final String enctoken = "fZrhqbu1m+n11c5GdFMAzQVXKv+ZvD+tkWEVHjZsozozCH6Op4QEFDjps5ahUs3GOZQea86u6icXM1XOg2jdINbugX7yTvkgYOVbwMZk3QcNjpC9X2MZvA==";

    @Test
    public void testFetchCandles() throws Exception {
        HistoricalCandleFetcherZerodha fetcher = new HistoricalCandleFetcherZerodha(enctoken);

        int instrumentId = 256265; // NIFTY 50 (example)
        int timeFrame = 15;
        String from = "2025-09-05";
        String to = "2025-09-06";

        List<Candle> candles = fetcher.fetchCandles(instrumentId, timeFrame, from, to);

        assertNotNull(candles);
        assertFalse(candles.isEmpty(), "Candles list should not be empty");

        Candle first = candles.get(0);
        System.out.println("Timestamp: " + first.timestamp);
        System.out.println("Open: " + first.open + ", Close: " + first.close);
    }

    @Test
    public void testSaveCandlesToJsonFile() throws Exception {
        HistoricalCandleFetcherZerodha fetcher = new HistoricalCandleFetcherZerodha(enctoken);

        int instrumentId = 256265;
        int timeFrame = 1;
        String from = "2025-09-05";
        String to = "2025-09-06";

        List<Candle> candles = fetcher.fetchCandles(instrumentId, timeFrame, from, to);

        assertNotNull(candles);
        assertFalse(candles.isEmpty(), "Candles list should not be empty");

        // Prepare output path
        String fileName = instrumentId + "_" + from + "_" + to + ".json";
        Path outputPath = Path.of("D:", "SpringBoot project", "Trade", "output files", "HistoricalCandles", fileName);
        Files.createDirectories(outputPath.getParent());

        // Write JSON using pretty print
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        mapper.writeValue(new File(outputPath.toString()), candles);

        System.out.println("✅ JSON saved to: " + outputPath);
        assertTrue(Files.exists(outputPath), "JSON file should be created");
    }
}
