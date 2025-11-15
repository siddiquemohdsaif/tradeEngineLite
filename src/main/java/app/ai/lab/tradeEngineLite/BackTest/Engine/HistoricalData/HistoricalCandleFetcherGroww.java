package app.ai.lab.tradeEngineLite.BackTest.Engine.HistoricalData;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * HistoricalCandleFetcherGroww
 *
 * Groww delayed chart API:
 * https://groww.in/v1/api/charting_service/v2/chart/delayed/exchange/NSE/segment/CASH/{SYMBOL}
 * ?endTimeInMillis={epochMs}&intervalInMinutes={n}&startTimeInMillis={epochMs}
 *
 * Candles payload is an array-of-arrays:
 * [ epochSec, open, high, low, close, volume ]
 *
 * Public methods:
 * - fetchCandles(stockCode, startIst, endIst, intervalMin) -> List<Candle>
 * (Groww native)
 * - getPriceAt(dateOrDateTimeIst, stockCode) -> Optional<Candle> (daily)
 * - fetchCandlesAsZerodhaShape(...) -> ZerodhaLikeResponse (arrays like
 * Zerodha)
 * - fetchCandlesAsZerodhaPojo(...) ->
 * List<HistoricalCandleFetcherZerodha.Candle>
 */

public class HistoricalCandleFetcherGroww {

    // ===== Time & formatters =====
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter DATE_ONLY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    // "2024-02-22T09:15:00+0530"
    private static final DateTimeFormatter ZERODHA_TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ")
            .withZone(IST);

    private final RestTemplate restTemplate;

    public HistoricalCandleFetcherGroww() {
        this.restTemplate = new RestTemplate();
    }

    // ===================== Public API =====================

    /**
     * Fetch historical candles between two IST strings with custom interval.
     * 
     * @param stockCode         e.g., "INDUSINDBK"
     * @param startIst          "yyyy-MM-dd" or "yyyy-MM-dd HH:mm:ss" (IST)
     * @param endIst            same format (IST)
     * @param intervalInMinutes integer > 0 (1,5,15,60,1440)
     */
    public List<Candle> fetchCandles(String stockCode, String startIst, String endIst, int intervalInMinutes)
            throws Exception {
        Objects.requireNonNull(stockCode, "stockCode");
        long startMs = parseStartIstToEpochMillis(startIst);
        long endMs   = parseEndIstToEpochMillis(endIst);

        if (intervalInMinutes <= 0)
            throw new IllegalArgumentException("intervalInMinutes must be > 0");
        if (endMs < startMs)
            throw new IllegalArgumentException("endIst must be >= startIst");

        String url = buildUrl(stockCode, startMs, endMs, intervalInMinutes);

        System.out.println("url=" + url);

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.set("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        headers.set("Origin", "https://groww.in");
        headers.set("Referer", "https://groww.in/charts/stocks");

        ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(headers), ApiResponse.class);

        ApiResponse body = resp.getBody();

        System.out.println("resp=" + resp.getBody().candles.size());

        if (resp.getStatusCode().is2xxSuccessful() && body != null) {
            return (body.candles != null) ? body.candles : Collections.emptyList();
        }
        throw new RuntimeException("Groww API error: HTTP " + resp.getStatusCodeValue());
    }

    /**
     * Get the **daily** candle for the given IST date/datetime.
     * Internally queries interval=1440 within that IST day window.
     * 
     * @param dateOrDateTimeIst "yyyy-MM-dd" or "yyyy-MM-dd HH:mm:ss" (IST)
     * @param stockCode         e.g., "INDUSINDBK"
     */
    public Optional<Candle> getPriceAt(String dateOrDateTimeIst, String stockCode) throws Exception {
        Objects.requireNonNull(stockCode, "stockCode");
        long baseMs = parseIstToEpochMillis(dateOrDateTimeIst);

        long startOfDayMs = startOfIstDay(baseMs);
        long endMs = startOfDayMs + (1439L * 60 * 1000); // daily window

        String url = buildUrl(stockCode, startOfDayMs, endMs, 1440);

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.set("User-Agent", "java");

        ResponseEntity<ApiResponse> resp = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(headers), ApiResponse.class);
        ApiResponse body = resp.getBody();
        if (!resp.getStatusCode().is2xxSuccessful() || body == null || body.candles == null) {
            return Optional.empty();
        }

        // Match by IST date
        String targetDate = epochSecToDateIST(startOfDayMs / 1000);
        for (Candle c : body.candles) {
            if (c.epochSec == null)
                continue;
            if (epochSecToDateIST(c.epochSec).equals(targetDate)) {
                return Optional.of(c);
            }
        }
        return Optional.empty();
    }

    /**
     * Return Zerodha-shaped response:
     * {
     * "status": "success",
     * "data": { "candles": [ ["yyyy-MM-dd'T'HH:mm:ss+0530", o,h,l,c, vol, 0], ... ]
     * }
     * }
     */
    public ZerodhaLikeResponse fetchCandlesAsZerodhaShape(
            String stockCode, String startIst, String endIst, int intervalInMinutes) throws Exception {

        List<Candle> growwCandles = fetchCandles(stockCode, startIst, endIst, intervalInMinutes);
        List<List<Object>> zCandles = new ArrayList<>(growwCandles.size());

        for (Candle c : growwCandles) {
            if (c.epochSec == null)
                continue;
            String ts = ZERODHA_TS_FMT.format(Instant.ofEpochSecond(c.epochSec).atZone(IST));
            zCandles.add(Arrays.asList(
                    ts, // "2024-02-22T09:15:00+0530"
                    c.open, // open
                    c.high, // high
                    c.low, // low
                    c.close, // close
                    c.volume, // volume
                    0L // oi -> forced zero
            ));
        }

        ZerodhaLikeResponse out = new ZerodhaLikeResponse();
        out.status = "success";
        out.data = new ZerodhaLikeResponse.Data();
        out.data.candles = zCandles;
        return out;
    }

    /**
     * Convert Groww candles to your existing Zerodha Candle POJO
     * (app.ai.lab.tradeEngineLite.BackTest.Engine.HistoricalData.HistoricalCandleFetcherZerodha.Candle).
     */
    public List<HistoricalCandleFetcherZerodha.Candle> fetchCandlesAsZerodhaPojo(
            String stockCode, String startIst, String endIst, int intervalInMinutes) throws Exception {

        List<Candle> growwCandles = fetchCandles(stockCode, startIst, endIst, intervalInMinutes);
        List<HistoricalCandleFetcherZerodha.Candle> out = new ArrayList<>(growwCandles.size());

        for (Candle c : growwCandles) {
            if (c.epochSec == null)
                continue;
            String ts = ZERODHA_TS_FMT.format(Instant.ofEpochSecond(c.epochSec).atZone(IST));

            HistoricalCandleFetcherZerodha.Candle z = new HistoricalCandleFetcherZerodha.Candle();
            z.timestamp = ts;
            z.open = c.open;
            z.high = c.high;
            z.low = c.low;
            z.close = c.close;
            z.volume = c.volume;
            z.oi = 0L;
            out.add(z);
        }
        return out;
    }

    // ===================== Models & Deserializer =====================

    public static class ApiResponse {
        @JsonDeserialize(using = CandleArrayDeserializer.class)
        public List<Candle> candles;
    }

    /**
     * Groww candle:
     * [ epochSec, open, high, low, close, volume ]
     */
    public static class Candle {
        public Long epochSec; // seconds
        public double open;
        public double high;
        public double low;
        public double close;
        public long volume;

        // Convenience (filled by deserializer)
        public String ist; // ISO-8601 IST string
    }

    /** Deserializes {"candles":[[ts, o, h, l, c, v], ...]} into List<Candle> */
    public static class CandleArrayDeserializer extends JsonDeserializer<List<Candle>> {
        @Override
        public List<Candle> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            JsonNode node = p.getCodec().readTree(p);

            JsonNode arr = node.isArray() ? node : node.get("candles");
            if (arr == null || !arr.isArray())
                return Collections.emptyList();

            List<Candle> out = new ArrayList<>(arr.size());
            for (JsonNode n : arr) {
                if (!n.isArray() || n.size() < 6)
                    continue;

                Candle c = new Candle();
                c.epochSec = asLongSafe(n.get(0)); // seconds
                c.open = n.get(1).asDouble();
                c.high = n.get(2).asDouble();
                c.low = n.get(3).asDouble();
                c.close = n.get(4).asDouble();
                c.volume = n.get(5).asLong();

                if (c.epochSec != null) {
                    c.ist = ZonedDateTime.ofInstant(Instant.ofEpochSecond(c.epochSec), IST).toString();
                }
                out.add(c);
            }
            return out;
        }

        private Long asLongSafe(JsonNode node) {
            if (node == null || node.isNull())
                return null;
            if (node.isNumber())
                return node.longValue();
            try {
                return Long.parseLong(node.asText());
            } catch (Exception e) {
                return null;
            }
        }
    }

    // ===================== Helpers (IST parsing) =====================

    // --- Utility: detect formats ---
    private static boolean isDateOnly(String s) {
        return s != null && s.trim().matches("^\\d{4}-\\d{2}-\\d{2}$");
    }

    private static boolean isDateTime(String s) {
        return s != null && s.trim().matches("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}$");
    }

    /**
     * Parse for range START: date-only => 00:00:00.000 IST; datetime => exact IST.
     */
    public static long parseStartIstToEpochMillis(String s) {
        String t = requireNonEmpty("start date", s).trim();
        if (isDateOnly(t)) {
            LocalDate d = LocalDate.parse(t, DATE_ONLY);
            return ZonedDateTime.of(d, LocalTime.MIDNIGHT, IST).toInstant().toEpochMilli();
        }
        if (isDateTime(t)) {
            LocalDateTime dt = LocalDateTime.parse(t, DATE_TIME);
            return ZonedDateTime.of(dt, IST).toInstant().toEpochMilli();
        }
        throw new IllegalArgumentException("start date must be \"yyyy-MM-dd\" or \"yyyy-MM-dd HH:mm:ss\" (IST)");
    }

    /**
     * Parse for range END: date-only => 23:59:59.999 IST; datetime => exact IST.
     */
    public static long parseEndIstToEpochMillis(String s) {
        String t = requireNonEmpty("end date", s).trim();
        if (isDateOnly(t)) {
            LocalDate d = LocalDate.parse(t, DATE_ONLY);
            long start = ZonedDateTime.of(d, LocalTime.MIDNIGHT, IST).toInstant().toEpochMilli();
            return endOfIstDay(start);
        }
        if (isDateTime(t)) {
            LocalDateTime dt = LocalDateTime.parse(t, DATE_TIME);
            return ZonedDateTime.of(dt, IST).toInstant().toEpochMilli();
        }
        throw new IllegalArgumentException("end date must be \"yyyy-MM-dd\" or \"yyyy-MM-dd HH:mm:ss\" (IST)");
    }

    /**
     * Accepts "yyyy-MM-dd" or "yyyy-MM-dd HH:mm:ss" (24h), returns epoch millis for
     * IST local time.
     */
    public static long parseIstToEpochMillis(String s) {
        String t = requireNonEmpty("date string", s).trim();
        if (t.matches("^\\d{4}-\\d{2}-\\d{2}$")) {
            LocalDate d = LocalDate.parse(t, DATE_ONLY);
            return ZonedDateTime.of(d, LocalTime.MIDNIGHT, IST).toInstant().toEpochMilli();
        }
        if (t.matches("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}$")) {
            LocalDateTime dt = LocalDateTime.parse(t, DATE_TIME);
            return ZonedDateTime.of(dt, IST).toInstant().toEpochMilli();
        }
        throw new IllegalArgumentException("date must be \"yyyy-MM-dd\" or \"yyyy-MM-dd HH:mm:ss\" (IST)");
    }

    /** Start-of-day (00:00:00 IST) for arbitrary epoch millis. */
    public static long startOfIstDay(long epochMs) {
        ZonedDateTime z = Instant.ofEpochMilli(epochMs).atZone(IST);
        ZonedDateTime start = z.toLocalDate().atStartOfDay(IST);
        return start.toInstant().toEpochMilli();
    }

    
    /**
     * End-of-day (23:59:59.999 IST) for arbitrary epoch millis.
     */
    public static long endOfIstDay(long epochMs) {
        ZonedDateTime z = Instant.ofEpochMilli(epochMs).atZone(IST);
        // next day at 00:00:00 IST, then minus 1 millisecond
        ZonedDateTime nextStart = z.toLocalDate().plusDays(1).atStartOfDay(IST);
        return nextStart.toInstant().toEpochMilli() - 1L;
    }

    /** Format epoch seconds into "YYYY-MM-DD" by IST. */
    public static String epochSecToDateIST(long epochSec) {
        return Instant.ofEpochSecond(epochSec).atZone(IST).toLocalDate().format(DATE_ONLY);
    }

    private static String requireNonEmpty(String name, String v) {
        if (v == null || v.trim().isEmpty())
            throw new IllegalArgumentException(name + " is required");
        return v;
    }

    private static String buildUrl(String stockCode, long startMs, long endMs, int intervalInMinutes) {
        String encoded = URLEncoder.encode(stockCode, StandardCharsets.UTF_8);
        return "https://groww.in/v1/api/charting_service/v2/chart/delayed/exchange/NSE/segment/CASH/" + encoded
                + "?endTimeInMillis=" + endMs
                + "&intervalInMinutes=" + intervalInMinutes
                + "&startTimeInMillis=" + startMs;
    }

    // ===================== Zerodha-like DTO for array output =====================

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ZerodhaLikeResponse {
        public String status;
        public Data data;

        public static class Data {
            public List<List<Object>> candles;
        }
    }
}
