package app.ai.lab.tradeEngineLite.BackTest.Engine.HistoricalData;

import app.ai.lab.tradeEngineLite.BackTest.Engine.HistoricalData.HistoricalCandleFetcherZerodha.Candle;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class HistoricalCandleFetcherGrowwTest {

    // Example NSE stock code used in your Node example
    private final String stockCode = "BANKBARODA";

    @Test
    public void testFetchCandles() throws Exception {
        HistoricalCandleFetcherGroww fetcher = new HistoricalCandleFetcherGroww();

        int intervalMinutes = 1;
        String fromIst = "2025-09-05 09:15:00"; // IST
        String toIst   = "2025-09-05 15:30:00"; // IST

        List<HistoricalCandleFetcherGroww.Candle> candles =
                fetcher.fetchCandles(stockCode, fromIst, toIst, intervalMinutes);

        assertNotNull(candles, "Candles list should not be null");
        assertFalse(candles.isEmpty(), "Candles list should not be empty");

        HistoricalCandleFetcherGroww.Candle first = candles.get(0);
        System.out.println("epochSec: " + first.epochSec);
        System.out.println("O: " + first.open + ", H: " + first.high + ", L: " + first.low + ", C: " + first.close + ", V: " + first.volume);
        System.out.println("IST: " + first.ist);
    }

    @Test
    public void testGetPriceAtDaily() throws Exception {
        HistoricalCandleFetcherGroww fetcher = new HistoricalCandleFetcherGroww();

        Optional<HistoricalCandleFetcherGroww.Candle> daily =
                fetcher.getPriceAt("2025-09-05", stockCode); // IST date

        assertTrue(daily.isPresent(), "Daily candle should be present for the given date");
        HistoricalCandleFetcherGroww.Candle c = daily.get();

        assertNotNull(c.epochSec);
        System.out.println("Daily -> O:" + c.open + " H:" + c.high + " L:" + c.low + " C:" + c.close + " V:" + c.volume + " ts:" + c.epochSec);
    }

    @Test
    public void testFetchCandlesAsZerodhaShape() throws Exception {
        HistoricalCandleFetcherGroww fetcher = new HistoricalCandleFetcherGroww();

        String fromIst = "2025-09-05 09:15:00";
        String toIst   = "2025-09-05 15:30:00";

        HistoricalCandleFetcherGroww.ZerodhaLikeResponse out =
                fetcher.fetchCandlesAsZerodhaShape(stockCode, fromIst, toIst, 1);

        assertNotNull(out);
        assertEquals("success", out.status);
        assertNotNull(out.data);
        assertNotNull(out.data.candles);
        assertFalse(out.data.candles.isEmpty(), "Zerodha-shaped candles should not be empty");

        // Validate one row shape: ["yyyy-MM-dd'T'HH:mm:ss+0530", o, h, l, c, vol, 0]
        List<Object> row = out.data.candles.get(0);
        assertEquals(7, row.size(), "Each candle row must have 7 fields like Zerodha");
        assertTrue(row.get(0) instanceof String, "Timestamp should be a String in Zerodha format");
        assertTrue(row.get(6) instanceof Number, "OI should be a number");
        assertEquals(0L, ((Number) row.get(6)).longValue(), "OI must be forced to 0");

        System.out.println("Zerodha-like TS: " + row.get(0));
        System.out.println("O:" + row.get(1) + " H:" + row.get(2) + " L:" + row.get(3) + " C:" + row.get(4) + " V:" + row.get(5) + " OI:" + row.get(6));
    }

    @Test
    public void testFetchCandlesAsZerodhaPojo() throws Exception {
        HistoricalCandleFetcherGroww fetcher = new HistoricalCandleFetcherGroww();

        String fromIst = "2025-09-05";
        String toIst   = "2025-09-05";

        List<Candle> candles =
                fetcher.fetchCandlesAsZerodhaPojo(stockCode, fromIst, toIst, 1);

        assertNotNull(candles);
        assertFalse(candles.isEmpty(), "Zerodha-POJO candles should not be empty");

        Candle first = candles.get(0);
        assertNotNull(first.timestamp, "Timestamp must be set in Zerodha format");
        assertEquals(0L, first.oi, "OI must be 0");

        System.out.println("TS: " + first.timestamp);
        System.out.println("O:" + first.open + " H:" + first.high + " L:" + first.low + " C:" + first.close + " V:" + first.volume + " OI:" + first.oi);
    }

    @Test
    public void testSaveCandlesToJsonFile() throws Exception {
        HistoricalCandleFetcherGroww fetcher = new HistoricalCandleFetcherGroww();

        String fromIst = "2025-09-05 09:15:00";
        String toIst   = "2025-09-05 15:30:00";
        int intervalMinutes = 1;

        List<HistoricalCandleFetcherGroww.Candle> candles =
                fetcher.fetchCandles(stockCode, fromIst, toIst, intervalMinutes);

        assertNotNull(candles);
        assertFalse(candles.isEmpty(), "Candles list should not be empty");

        // Prepare output path (mirroring your Zerodha test style)
        String fileName = stockCode + "_" + fromIst.replace(" ", "_") + "_" + toIst.replace(" ", "_") + "_m" + intervalMinutes + ".json";
        Path outputPath = Path.of("D:", "SpringBoot project", "Trade", "output files", "HistoricalCandles", fileName);
        Files.createDirectories(outputPath.getParent());

        // Pretty JSON
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        mapper.writeValue(new File(outputPath.toString()), candles);

        System.out.println("✅ JSON saved to: " + outputPath);
        assertTrue(Files.exists(outputPath), "JSON file should be created");
    }
}
