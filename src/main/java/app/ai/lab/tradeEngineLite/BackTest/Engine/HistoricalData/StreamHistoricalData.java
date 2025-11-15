package app.ai.lab.tradeEngineLite.BackTest.Engine.HistoricalData;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Year;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class StreamHistoricalData {

    /**
     * Callback to receive decoded Blocks in stream order. Return false to stop
     * early.
     */
    public interface BlockCallback {
        /** @return true to continue streaming; false to stop immediately */
        boolean onBlock(Block block);

        /** Non-fatal errors (we continue to next file/entry). */
        default void onError(Exception e, Path source) {
        }

        /** Called when streaming finishes (normal or early stop). */
        default void onEnd() {
        }
    }

    private static final DateTimeFormatter DMY_2Y = new DateTimeFormatterBuilder()
            .appendPattern("dd-MM-")
            .appendValueReduced(ChronoField.YEAR, 2, 2, Year.of(2000).getValue())
            .toFormatter(Locale.ROOT);
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Kolkata");
    private static final Pattern ZIP_DATE_TAIL = Pattern.compile("_(\\d{2}-\\d{2}-\\d{2})\\.zip$",
            Pattern.CASE_INSENSITIVE);


    private static final int  MAX_ZERODHA_DAYS_PER_CALL = 60;     // inclusive window (e.g., 60 = from..to spans 60 days)
    private static final long CHUNK_THROTTLE_MS          = 10_000; // 10 seconds between chunks

    // STRICT: timestamp_<digits>.bin (case-insensitive)
    private static final Pattern STRICT_TS = Pattern.compile("timestamp_(\\d+)\\.bin", Pattern.CASE_INSENSITIVE);

    private final Path rootDir;
    private final LocalDate startDate;
    private final LocalDate endDate;
    private final String fileCode; // "NIFTY_100" or "SENSEX" or "NIFTY"
    private final long delayMs; // -1 => no delay
    private final BlockCallback callback;

    public StreamHistoricalData(Path rootDir,
            String startDate_dd_MM_yy,
            String endDate_dd_MM_yy,
            String fileCode,
            long delayMs,
            BlockCallback callback) {
        Objects.requireNonNull(rootDir, "rootDir");
        Objects.requireNonNull(startDate_dd_MM_yy, "startDate");
        Objects.requireNonNull(endDate_dd_MM_yy, "endDate");
        Objects.requireNonNull(fileCode, "fileCode");
        Objects.requireNonNull(callback, "callback");
        this.rootDir = rootDir;
        this.startDate = LocalDate.parse(startDate_dd_MM_yy, DMY_2Y);
        this.endDate = LocalDate.parse(endDate_dd_MM_yy, DMY_2Y);
        if (this.endDate.isBefore(this.startDate)) {
            throw new IllegalArgumentException("endDate < startDate");
        }
        this.fileCode = fileCode;
        this.delayMs = delayMs;
        this.callback = callback;
    }

    /** Run the stream on the current thread. */
    public void stream() {
        List<Path> zips = listCandidateZips();
        zips.sort(Comparator
                .comparing((Path p) -> extractDate(p.getFileName().toString()))
                .thenComparing(p -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER));

        outer: for (Path zipPath : zips) {
            try (ZipFile zf = new ZipFile(zipPath.toFile())) {
                List<BinEntry> bins = new ArrayList<>();
                Enumeration<? extends ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) {
                    ZipEntry ze = en.nextElement();
                    if (ze.isDirectory())
                        continue;
                    String name = ze.getName();
                    if (!name.toLowerCase(Locale.ROOT).endsWith(".bin"))
                        continue;

                    long ts = extractTimestampFromEntry(ze, zipPath); // <-- strict + fallback
                    bins.add(new BinEntry(ze, ts));
                }

                bins.sort(Comparator.comparingLong(b -> b.epochMs));

                for (BinEntry be : bins) {
                    byte[] payload;
                    try (InputStream is = zf.getInputStream(be.entry)) {
                        payload = readAllBytes(is);
                    }

                    Optional<Block> maybe = BlockUtils.parseBinToBlock(payload, be.epochMs);
                    if (maybe.isPresent()) {
                        boolean keepGoing = callback.onBlock(maybe.get());
                        if (!keepGoing)
                            break outer;

                        if (delayMs >= 0) {
                            try {
                                Thread.sleep(delayMs);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                break outer;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                callback.onError(e, zipPath);
            }
        }

        try {
            callback.onEnd();
        } catch (Exception ignore) {
        }
    }

    /**
     * Stream Zerodha historical candles as synthetic ticks (O, L, H, C),
     * producing 4 Blocks per candle in that order, chunked into <=60-day ranges.
     *
     * @param enctoken Zerodha session enctoken (without the "enctoken " prefix)
     * @param instrumentId Zerodha instrument token (index token or stock token)
     * @param timeFrameMinutes 1, 3, 5, 15, etc.
     * @param isIndex true -> emit IndexPacket; false -> emit StockPacket
     */
    public void stream_zerodha(String enctoken, int instrumentId, int timeFrameMinutes, boolean isIndex) {
        final HistoricalCandleFetcherZerodha api = new HistoricalCandleFetcherZerodha(enctoken);
    
        // Walk the overall [startDate, endDate] range in inclusive 60-day chunks.
        java.time.LocalDate cursor = startDate;
        try {
            while (!cursor.isAfter(endDate)) {
                // Inclusive chunk end; cap at endDate.
                java.time.LocalDate chunkEnd = cursor.plusDays(MAX_ZERODHA_DAYS_PER_CALL - 1);
                if (chunkEnd.isAfter(endDate)) chunkEnd = endDate;
            
                final String from = cursor.toString();    // yyyy-MM-dd
                final String to   = chunkEnd.toString();  // yyyy-MM-dd
            
                try {
                    final java.util.List<HistoricalCandleFetcherZerodha.Candle> candles =
                            api.fetchCandles(instrumentId, timeFrameMinutes, from, to);
                
                    // Emit in chronological order (chunk order + candle order).
                    for (HistoricalCandleFetcherZerodha.Candle c : candles) {
                        final long baseMs    = parseZerodhaTsToEpochMs(c.timestamp);
                        final long epochSec  = baseMs / 1000L;
                        final double[] ticks = new double[] { c.open, c.low, c.high, c.close };
                    
                        for (int i = 0; i < ticks.length; i++) {
                            final long priceU32 = toU32Price(ticks[i]);
                        
                            Block.PacketData pd;
                            if (isIndex) {
                                Block.IndexPacket ip = new Block.IndexPacket();
                                ip.setToken(instrumentId);
                                ip.setLastTradedPrice(priceU32);
                                ip.setExchangeTimestamp(epochSec);
                                pd = ip;
                            } else {
                                Block.StockPacket sp = new Block.StockPacket();
                                sp.setInstrumentToken(instrumentId);
                                sp.setLastTradedPrice(priceU32);
                                sp.setExchangeTimestamp(epochSec);
                                sp.setVolumeTraded(c.volume);
                                sp.setOpenInterest(c.oi);
                                sp.setMarketDepth(java.util.Collections.emptyList());
                                pd = sp;
                            }
                        
                            Block block = new Block(baseMs + i, java.util.Collections.singletonList(pd));
                            boolean keep = callback.onBlock(block);
                            if (!keep) {
                                try { callback.onEnd(); } catch (Exception ignore) {}
                                return;
                            }
                        
                            if (delayMs >= 0) {
                                try {
                                    Thread.sleep(delayMs);
                                } catch (InterruptedException ie) {
                                    Thread.currentThread().interrupt();
                                    try { callback.onEnd(); } catch (Exception ignore) {}
                                    return;
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    // Non-fatal: report and continue to next chunk.
                    callback.onError(e, rootDir);
                }
            
                // Pause 10s between chunks (but not after the final one).
                if (chunkEnd.isBefore(endDate)) {
                    try {
                        Thread.sleep(CHUNK_THROTTLE_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        try { callback.onEnd(); } catch (Exception ignore) {}
                        return;
                    }
                }
            
                // Next chunk starts the day after this chunk's end (no overlap, no gaps).
                cursor = chunkEnd.plusDays(1);
            }
        } finally {
            try { callback.onEnd(); } catch (Exception ignore) {}
        }
    }


    /**
     * Stream Groww historical candles as synthetic ticks (O, L, H, C),
     * producing 4 Blocks per candle in that order, chunked into <=60-day ranges.
     *
     * @param stockSymbol      Groww symbol (e.g., "INDUSINDBK", "HDFCBANK")
     * @param instrumentId     Zerodha instrument token (index token or stock token)
     * @param timeFrameMinutes Candle interval in minutes (1, 5, 15, 60, 1440)
     * @param isIndex          true -> emit IndexPacket; false -> emit StockPacket
     */
    public void stream_groww(String stockSymbol, int instrumentId, int timeFrameMinutes, boolean isIndex) {
        final HistoricalCandleFetcherGroww api = new HistoricalCandleFetcherGroww();
    
        java.time.LocalDate cursor = startDate;
        try {
            while (!cursor.isAfter(endDate)) {
                java.time.LocalDate chunkEnd = cursor.plusDays(MAX_ZERODHA_DAYS_PER_CALL - 1);
                if (timeFrameMinutes == 1440) {
                    chunkEnd = cursor.plusDays(MAX_ZERODHA_DAYS_PER_CALL*3 - 1);
                }
                if (chunkEnd.isAfter(endDate)) chunkEnd = endDate;
            
                final String fromIst = cursor.toString();   // yyyy-MM-dd
                final String toIst   = chunkEnd.toString(); // yyyy-MM-dd
            
                try {
                    final java.util.List<HistoricalCandleFetcherZerodha.Candle> candles =
                            api.fetchCandlesAsZerodhaPojo(stockSymbol, fromIst, toIst, timeFrameMinutes);
                
                    for (HistoricalCandleFetcherZerodha.Candle c : candles) {
                        final long baseMs   = parseZerodhaTsToEpochMs(c.timestamp); // "yyyy-MM-dd'T'HH:mm:ss+0530"
                    
                        // --- NEW: for daily candles, place the 4 ticks at 09:15, 11:00, 14:00, 15:30 IST ---
                        final long[] tickTimesMs;
                        if (timeFrameMinutes == 1440) {
                            java.time.LocalDate day = java.time.Instant.ofEpochMilli(baseMs)
                                    .atZone(DEFAULT_ZONE)
                                    .toLocalDate();
                        
                            tickTimesMs = new long[] {
                                    java.time.ZonedDateTime.of(day, java.time.LocalTime.of(9, 15),  DEFAULT_ZONE).toInstant().toEpochMilli(),
                                    java.time.ZonedDateTime.of(day, java.time.LocalTime.of(11, 0),  DEFAULT_ZONE).toInstant().toEpochMilli(),
                                    java.time.ZonedDateTime.of(day, java.time.LocalTime.of(14, 0),  DEFAULT_ZONE).toInstant().toEpochMilli(),
                                    java.time.ZonedDateTime.of(day, java.time.LocalTime.of(15, 30), DEFAULT_ZONE).toInstant().toEpochMilli()
                            };
                        } else {
                            // intraday: keep previous behavior (4 ticks spaced by +i ms for determinism)
                            tickTimesMs = new long[] { baseMs, baseMs + 1, baseMs + 2, baseMs + 3 };
                        }
                    
                        final double[] ticks = new double[] { c.open, c.low, c.high, c.close };
                    
                        for (int i = 0; i < ticks.length; i++) {
                            final long tsMs    = tickTimesMs[i];
                            final long epochSec = tsMs / 1000L;
                            final long priceU32 = toU32Price(ticks[i]);
                        
                            Block.PacketData pd;
                            if (isIndex) {
                                Block.IndexPacket ip = new Block.IndexPacket();
                                ip.setToken(instrumentId);
                                ip.setLastTradedPrice(priceU32);
                                ip.setExchangeTimestamp(epochSec);
                                pd = ip;
                            } else {
                                Block.StockPacket sp = new Block.StockPacket();
                                sp.setInstrumentToken(instrumentId);
                                sp.setLastTradedPrice(priceU32);
                                sp.setExchangeTimestamp(epochSec);
                                sp.setVolumeTraded(c.volume);
                                sp.setOpenInterest(c.oi); // 0L via adapter
                                sp.setMarketDepth(java.util.Collections.emptyList());
                                pd = sp;
                            }
                        
                            Block block = new Block(tsMs, java.util.Collections.singletonList(pd));
                            boolean keep = callback.onBlock(block);
                            if (!keep) {
                                try { callback.onEnd(); } catch (Exception ignore) {}
                                return;
                            }
                        
                            if (delayMs >= 0) {
                                try { Thread.sleep(delayMs); }
                                catch (InterruptedException ie) {
                                    Thread.currentThread().interrupt();
                                    try { callback.onEnd(); } catch (Exception ignore) {}
                                    return;
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    // Non-fatal: report and continue to next chunk.
                    callback.onError(e, rootDir);
                }
            
                if (chunkEnd.isBefore(endDate)) {
                    try { Thread.sleep(CHUNK_THROTTLE_MS); }
                    catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        try { callback.onEnd(); } catch (Exception ignore) {}
                        return;
                    }
                }
            
                cursor = chunkEnd.plusDays(1);
            }
        } finally {
            try { callback.onEnd(); } catch (Exception ignore) {}
        }
    }

    
    // ---- helpers (keep inside StreamHistoricalData) ----
    
    // Zerodha timestamps look like "2025-09-05T09:15:00+0530" (offset without colon).
    private static long parseZerodhaTsToEpochMs(String ts) {
        java.time.format.DateTimeFormatter F =
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ");
        return java.time.ZonedDateTime.parse(ts, F).toInstant().toEpochMilli();
    }
    
    // Use paise as the wire unit by default (×100). Adjust if your payload expects a different scale.
    private static final int PRICE_SCALE = 100;
    private static long toU32Price(double price) {
        long scaled = Math.round(price * PRICE_SCALE);
        return (scaled < 0) ? 0 : scaled; // clamp negative just in case
    }


    /** Core implementation (optionally time-filtered). */
    public void stream(String startTime, String endTime) {
        LocalTime start = parseTimeFlexible(startTime);
        LocalTime end = parseTimeFlexible(endTime);
        ZoneId zone = DEFAULT_ZONE;

        List<Path> zips = listCandidateZips();
        zips.sort(Comparator
                .comparing((Path p) -> extractDate(p.getFileName().toString()))
                .thenComparing(p -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER));

        outer:
        for (Path zipPath : zips) {
            try (ZipFile zf = new ZipFile(zipPath.toFile())) {
                List<BinEntry> bins = new ArrayList<>();
                Enumeration<? extends ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) {
                    ZipEntry ze = en.nextElement();
                    if (ze.isDirectory()) continue;
                    String name = ze.getName();
                    if (!name.toLowerCase(Locale.ROOT).endsWith(".bin")) continue;

                    long ts = extractTimestampFromEntry(ze, zipPath);
                    // --- NEW: filter by time-of-day window before reading payload ---
                    if (!isWithinWindow(ts, start, end, zone)) continue;

                    bins.add(new BinEntry(ze, ts));
                }

                bins.sort(Comparator.comparingLong(b -> b.epochMs));

                for (BinEntry be : bins) {
                    byte[] payload;
                    try (InputStream is = zf.getInputStream(be.entry)) {
                        payload = readAllBytes(is);
                    }

                    Optional<Block> maybe = BlockUtils.parseBinToBlock(payload, be.epochMs);
                    if (maybe.isPresent()) {
                        boolean keepGoing = callback.onBlock(maybe.get());
                        if (!keepGoing) break outer;

                        if (delayMs >= 0) {
                            try { Thread.sleep(delayMs); }
                            catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                break outer;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                callback.onError(e, zipPath);
            }
        }

        try { callback.onEnd(); } catch (Exception ignore) {}
    }

    // Add to StreamHistoricalData
    public void streamParallelAcrossZips(int workers) {
        final List<Path> zips = listCandidateZips();
        zips.sort(Comparator
                .comparing((Path p) -> extractDate(p.getFileName().toString()))
                .thenComparing(p -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER));

        final int n = zips.size();
        if (n == 0) {
            callback.onEnd();
            return;
        }

        final java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors
                .newFixedThreadPool(Math.max(1, workers));
        final java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean(false);

        // one queue per zip; producers fill, dispatcher drains in zip order
        final Object END = new Object();
        final java.util.concurrent.BlockingQueue<Object>[] queues = new java.util.concurrent.BlockingQueue[n];
        for (int i = 0; i < n; i++)
            queues[i] = new java.util.concurrent.LinkedBlockingQueue<>();

        // producer task per zip
        for (int i = 0; i < n; i++) {
            final int zipIdx = i;
            final Path zipPath = zips.get(i);
            pool.submit(() -> {
                try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(zipPath.toFile())) {
                    // collect and sort .bin entries by strict timestamp
                    java.util.List<java.util.zip.ZipEntry> entries = new java.util.ArrayList<>();
                    for (java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries(); en
                            .hasMoreElements();) {
                        var ze = en.nextElement();
                        if (ze.isDirectory())
                            continue;
                        if (!ze.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".bin"))
                            continue;
                        entries.add(ze);
                    }
                    entries.sort(java.util.Comparator.comparingLong(e -> extractTimestampFromEntry(e, zipPath)));

                    for (java.util.zip.ZipEntry be : entries) {
                        if (stop.get())
                            break;
                        byte[] payload;
                        try (java.io.InputStream is = zf.getInputStream(be)) {
                            payload = readAllBytes(is);
                        }
                        long ts = extractTimestampFromEntry(be, zipPath);
                        var maybe = BlockUtils.parseBinToBlock(payload, ts);
                        // even on parse failure we just skip (like single-thread version)
                        maybe.ifPresent(block -> queues[zipIdx].offer(block));
                    }
                } catch (Exception e) {
                    callback.onError(e, zipPath);
                } finally {
                    // signal end of this zip
                    queues[zipIdx].offer(END);
                }
            });
        }

        // single-threaded dispatcher: drains queues in sorted zip order, preserving
        // determinism
        try {
            for (int i = 0; i < n && !stop.get(); i++) {
                while (!stop.get()) {
                    Object item = queues[i].take(); // blocks until block or END
                    if (item == END)
                        break;
                    Block b = (Block) item;
                    boolean keep = callback.onBlock(b);
                    if (!keep) {
                        stop.set(true);
                        break;
                    }
                    if (delayMs >= 0) {
                        try {
                            Thread.sleep(delayMs);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            stop.set(true);
                            break;
                        }
                    }
                }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdownNow();
            try {
                callback.onEnd();
            } catch (Exception ignore) {
            }
        }
    }

    // ---------- internals ----------

    private List<Path> listCandidateZips() {
        if (!Files.isDirectory(rootDir))
            return Collections.emptyList();

        List<Path> out = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(rootDir, "*.zip")) {
            for (Path p : ds) {
                String fn = p.getFileName().toString();
                // must contain _<fileCode>_ before date tail
                // e.g. ..._260617_NIFTY_100_25-08-25.zip
                // ..._256265_NIFTY_29-08-25.zip
                if (!fn.toUpperCase(Locale.ROOT).contains("_" + fileCode.toUpperCase(Locale.ROOT) + "_")) {
                    continue;
                }
                LocalDate d = extractDate(fn);
                if (d == null)
                    continue;
                if (!d.isBefore(startDate) && !d.isAfter(endDate)) {
                    out.add(p);
                }
            }
        } catch (IOException e) {
            callback.onError(e, rootDir);
        }
        return out;
    }

    private static LocalDate extractDate(String filename) {
        Matcher m = ZIP_DATE_TAIL.matcher(filename);
        if (!m.find())
            return null;
        String ddMMyy = m.group(1);
        try {
            return LocalDate.parse(ddMMyy, DMY_2Y);
        } catch (Exception ignore) {
            return null;
        }
    }

    // Flexible time parser: accepts "09:15 am", "9:15 AM", "15:30", etc.
    private static LocalTime parseTimeFlexible(String text) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("time is blank");
        text = text.trim();

        DateTimeFormatter h12 = new DateTimeFormatterBuilder()
                .parseCaseInsensitive()
                .appendPattern("h:mm a")
                .toFormatter(Locale.ROOT);

        DateTimeFormatter h24 = DateTimeFormatter.ofPattern("H:mm", Locale.ROOT);

        try { return LocalTime.parse(text, h12); } catch (Exception ignore) {}
        try { return LocalTime.parse(text, h24); } catch (Exception ignore) {}

        // Allow "HHmm" like 0915
        if (text.matches("\\d{3,4}")) {
            String s = text.length() == 3 ? "0" + text : text;
            String hh = s.substring(0, 2);
            String mm = s.substring(2, 4);
            return LocalTime.of(Integer.parseInt(hh), Integer.parseInt(mm));
        }

        throw new IllegalArgumentException("Unrecognized time format: " + text);
    }

    /** True if epochMs (in given zone) falls within [start,end], inclusive. Supports wrap (e.g., 22:00–02:00). */
    private static boolean isWithinWindow(long epochMs, LocalTime start, LocalTime end, ZoneId zone) {
        if (start == null || end == null) return true; // no filter
        LocalTime t = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalTime();
    
        if (!end.isBefore(start)) { // normal same-day window
            return !t.isBefore(start) && !t.isAfter(end);
        } else { // wraps midnight
            return !t.isBefore(start) || !t.isAfter(end);
        }
    }

    /**
     * Strictly extract timestamp from a ZIP entry named like
     * ".../timestamp_<millis>.bin".
     * Fallbacks:
     * 1) Use ZipEntry#getTime() if present (>0)
     * 2) Use the ZIP file's last modified time
     */
    private static long extractTimestampFromEntry(ZipEntry entry, Path zipPath) {
        // get only the leaf name (entry may include folders)
        String leaf = entry.getName();
        int slash = Math.max(leaf.lastIndexOf('/'), leaf.lastIndexOf('\\'));
        if (slash >= 0)
            leaf = leaf.substring(slash + 1);

        Matcher m = STRICT_TS.matcher(leaf);
        if (m.find()) {
            try {
                return Long.parseLong(m.group(1));
            } catch (NumberFormatException ignore) {
            }
        }

        long t = entry.getTime();
        if (t > 0)
            return t;

        try {
            return Files.getLastModifiedTime(zipPath).toMillis();
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot extract timestamp for entry: " + entry.getName() +
                    " in zip: " + zipPath.getFileName(), e);
        }
    }

    private static byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(64 * 1024);
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) != -1) {
            bos.write(buf, 0, r);
        }
        return bos.toByteArray();
    }

    private static final class BinEntry {
        final ZipEntry entry;
        final long epochMs;

        BinEntry(ZipEntry entry, long epochMs) {
            this.entry = entry;
            this.epochMs = epochMs;
        }
    }
}
