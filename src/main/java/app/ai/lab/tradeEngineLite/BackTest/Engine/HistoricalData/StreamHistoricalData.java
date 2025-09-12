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
