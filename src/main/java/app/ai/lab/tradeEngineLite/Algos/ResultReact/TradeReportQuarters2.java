package app.ai.lab.tradeEngineLite.Algos.ResultReact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;

/**
 * Aggregates trades across ALL symbol folders under ResultReact
 * for a given quarter range and side (SHORT/LONG),
 * with optional date-range exclusions based on quarterRecord.dateTimeRaw.
 *
 * exclude.json behaviour:
 *  - For LONG  : ranges are EXCLUSION zones (no trades counted in those dates)
 *  - For SHORT : ranges are INCLUSION zones (only trades in those dates are counted)
 */
public class TradeReportQuarters2 {

    private static final long MILLIS_PER_DAY = 86_400_000L;

    private static final Map<String, Integer> MONTH_MAP = new HashMap<>();
    static {
        MONTH_MAP.put("JAN", 1);
        MONTH_MAP.put("FEB", 2);
        MONTH_MAP.put("MAR", 3);
        MONTH_MAP.put("APR", 4);
        MONTH_MAP.put("MAY", 5);
        MONTH_MAP.put("JUN", 6);
        MONTH_MAP.put("JUL", 7);
        MONTH_MAP.put("AUG", 8);
        MONTH_MAP.put("SEP", 9);
        MONTH_MAP.put("OCT", 10);
        MONTH_MAP.put("NOV", 11);
        MONTH_MAP.put("DEC", 12);
    }

    // dateTimeRaw patterns:
    //  "02/11/2023 01:43 pm"  OR  "02/11/2023"
    private static final DateTimeFormatter DATE_TIME_FMT =
            new DateTimeFormatterBuilder()
                    .parseCaseInsensitive()
                    .appendPattern("dd/MM/uuuu hh:mm a")
                    .toFormatter(Locale.ENGLISH);

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("dd/MM/uuuu", Locale.ENGLISH);

    public static void main(String[] args) throws Exception {
        // Base folder where all symbol folders (HCLTECH, INFY, MPHASIS, etc.) exist
        Path baseDir = Paths.get("D:\\SpringBoot project\\Trade\\output files\\tradeInfo\\ResultReact_small");

        String startQuarter = "2020-Jun";
        String endQuarter   = "2025-Jun";
        String type         = "LONG"; // or SHORT

        // Default exclusions file
        Path exclusionsFile = Paths.get(
                "D:\\SpringBoot project\\Trade\\output files\\tradeInfo\\ResultReact\\exclude.json"
        );

        // CLI:
        // arg0 = baseDir
        // arg1 = startQuarter
        // arg2 = endQuarter
        // arg3 = type (LONG/SHORT)
        // arg4 = exclusions.json (optional)
        if (args.length >= 3) {
            baseDir      = Paths.get(args[0]);
            startQuarter = args[1];
            endQuarter   = args[2];
        }
        if (args.length >= 4) {
            type = args[3];
        }
        if (args.length >= 5) {
            exclusionsFile = Paths.get(args[4]);
        }

        List<DateRange> exclusions = Collections.emptyList();
        if (exclusionsFile != null) {
            exclusions = loadExclusionsFromJson(exclusionsFile);
        }

        generateQuarterReport(baseDir, startQuarter, endQuarter, type, exclusions);
        System.out.println("Quarter report generated in: " + baseDir.toAbsolutePath());
    }

    // --- Old signature kept for compatibility (no exclusions) ---
    public static void generateQuarterReport(Path baseDir,
                                             String startQuarter,
                                             String endQuarter,
                                             String typeFilter) throws IOException {
        generateQuarterReport(baseDir, startQuarter, endQuarter, typeFilter, Collections.emptyList());
    }

    // --- New signature with date-range "exclude/include" logic ---
    public static void generateQuarterReport(Path baseDir,
                                             String startQuarter,
                                             String endQuarter,
                                             String typeFilter,
                                             List<DateRange> exclusionRanges) throws IOException {

        if (!Files.isDirectory(baseDir)) {
            throw new IllegalArgumentException("Base directory does not exist: " + baseDir);
        }

        String typeWanted = typeFilter == null ? "" : typeFilter.trim().toUpperCase(Locale.ROOT);
        if (!"SHORT".equals(typeWanted) && !"LONG".equals(typeWanted)) {
            throw new IllegalArgumentException("type must be SHORT or LONG, got: " + typeFilter);
        }

        QuarterKey startKey = parseQuarter(startQuarter);
        QuarterKey endKey   = parseQuarter(endQuarter);
        if (startKey == null || endKey == null) {
            throw new IllegalArgumentException("Invalid quarter format. Use like 2020-Jun");
        }

        if (startKey.compareTo(endKey) > 0) {
            throw new IllegalArgumentException("startQuarter must be <= endQuarter");
        }

        ObjectMapper mapper = new ObjectMapper();

        // Use TreeMap to keep quarters sorted
        Map<QuarterKey, QuarterAgg> aggMap = new TreeMap<>();

        // Walk through each symbol folder (HCLTECH, INFY, etc.)
        try (DirectoryStream<Path> symbolDirs = Files.newDirectoryStream(baseDir)) {
            for (Path symbolDir : symbolDirs) {
                if (!Files.isDirectory(symbolDir)) continue;

                // Inside each symbol folder, read all *.json files except report.json and our own reports
                try (DirectoryStream<Path> jsonFiles =
                             Files.newDirectoryStream(symbolDir, "*.json")) {

                    for (Path jsonFile : jsonFiles) {
                        String fileName = jsonFile.getFileName().toString();
                        if ("report.json".equalsIgnoreCase(fileName)) continue;
                        if (fileName.startsWith("TradeReportQuarters_")) continue;

                        JsonNode root;
                        try {
                            root = mapper.readTree(jsonFile.toFile());
                        } catch (Exception ex) {
                            System.err.println("Skip unreadable file: " + jsonFile + " :: " + ex.getMessage());
                            continue;
                        }

                        JsonNode qNode  = root.path("quarterRecord");
                        JsonNode tNode  = root.path("trade");
                        JsonNode times  = root.path("times");

                        String quarterStr  = qNode.path("Quarter").asText(null);
                        String side        = tNode.path("side").asText(null);
                        double pnlPct      = tNode.path("pnl_pct").asDouble(Double.NaN);
                        long entryMs       = times.path("entry_epoch_ms").asLong(0L);
                        long exitMs        = times.path("exit_epoch_ms").asLong(0L);
                        String dateTimeRaw = qNode.path("dateTimeRaw").asText(null);

                        if (quarterStr == null || side == null || Double.isNaN(pnlPct)) {
                            System.err.println("Skip (missing fields): " + jsonFile);
                            continue;
                        }

                        // side filter
                        String sideUpper = side.toUpperCase(Locale.ROOT);
                        if (!sideUpper.equals(typeWanted)) {
                            continue;
                        }

                        // Parse quarter for range filter
                        QuarterKey qKey = parseQuarter(quarterStr);
                        if (qKey == null) {
                            System.err.println("Skip (invalid Quarter): " + quarterStr + " in " + jsonFile);
                            continue;
                        }

                        // Quarter range [startKey, endKey]
                        if (qKey.compareTo(startKey) < 0 || qKey.compareTo(endKey) > 0) {
                            continue;
                        }

                        // --- NEW: dateTimeRaw-based filter ---
                        // LONG  -> exclude trades inside ranges
                        // SHORT -> include only trades inside ranges
                        LocalDate tradeDate = parseDateTimeRawToDate(dateTimeRaw);
                        if (!shouldKeepTrade(tradeDate, typeWanted, exclusionRanges)) {
                            // filtered out by date logic
                            continue;
                        }

                        // Compute trading days for this trade
                        long days = 0L;
                        if (entryMs > 0 && exitMs > 0 && exitMs >= entryMs) {
                            days = (exitMs - entryMs) / MILLIS_PER_DAY;
                        }

                        // Aggregate
                        QuarterAgg agg = aggMap.computeIfAbsent(qKey, k -> new QuarterAgg(k.toLabel(), typeWanted));
                        agg.tradeCount++;
                        agg.sumPnl = agg.sumPnl.add(BigDecimal.valueOf(pnlPct));
                        agg.totalTradingDays += days;
                    }
                }
            }
        }

        // Build final JSON
        Map<String, Object> out = new LinkedHashMap<>();
        int totalTrades = 0;
        long totalDays = 0L;
        BigDecimal overallSumPnl = BigDecimal.ZERO;

        for (Map.Entry<QuarterKey, QuarterAgg> e : aggMap.entrySet()) {
            QuarterAgg qa = e.getValue();
            Map<String, Object> qObj = new LinkedHashMap<>();
            qObj.put("no_of_trade", qa.tradeCount);
            qObj.put("sum_pnl", round3(qa.sumPnl)); // numeric; interpret as percentage points
            qObj.put("type", qa.type);
            qObj.put("total_trading_days_use", qa.totalTradingDays);

            out.put(qa.quarterLabel, qObj);

            totalTrades += qa.tradeCount;
            totalDays   += qa.totalTradingDays;
            overallSumPnl = overallSumPnl.add(qa.sumPnl);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("type", typeWanted);
        summary.put("startQuarter", startQuarter);
        summary.put("endQuarter", endQuarter);
        summary.put("total_trades", totalTrades);
        summary.put("overall_sum_pnl", round3(overallSumPnl));
        summary.put("overall_trading_days_use", totalDays);

        out.put("summary", summary);

        // Output file name
        String fileName = "TradeReportQuarters_" + startQuarter + "_to_" + endQuarter + "_" + typeWanted + "_.json";
        Path outFile = baseDir.resolve(fileName);

        ObjectWriter writer = mapper.writer(new DefaultPrettyPrinter());
        writer.writeValue(outFile.toFile(), out);

        System.out.println("Written: " + outFile.toAbsolutePath());
    }

    // ===== Helpers =====

    private static QuarterKey parseQuarter(String s) {
        if (s == null) return null;
        String trimmed = s.trim();
        if (trimmed.isEmpty()) return null;

        String[] parts = trimmed.split("-");
        if (parts.length != 2) return null;

        String yStr = parts[0].trim();
        String mStr = parts[1].trim().toUpperCase(Locale.ROOT);

        int year;
        try {
            year = Integer.parseInt(yStr);
        } catch (NumberFormatException e) {
            return null;
        }

        Integer month = MONTH_MAP.get(mStr);
        if (month == null) return null;

        String monLabel = mStr.substring(0, 1).toUpperCase(Locale.ROOT)
                + mStr.substring(1).toLowerCase(Locale.ROOT);

        return new QuarterKey(year, month, monLabel);
    }

    private static double round3(BigDecimal v) {
        return v.setScale(3, RoundingMode.HALF_UP).doubleValue();
    }

    // Parse dateTimeRaw which may be "dd/MM/yyyy hh:mm a" or "dd/MM/yyyy"
    private static LocalDate parseDateTimeRawToDate(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;

        // Try with time
        try {
            LocalDateTime dt = LocalDateTime.parse(s, DATE_TIME_FMT);
            return dt.toLocalDate();
        } catch (DateTimeParseException ignored) {}

        // Try date-only
        try {
            return LocalDate.parse(s, DATE_FMT);
        } catch (DateTimeParseException ignored) {}

        return null;
    }

    // Decide whether to keep a trade based on side + date ranges.
    // LONG  -> ranges used as EXCLUDE (no trading in those periods)
    // SHORT -> ranges used as INCLUDE (only trading in those periods)
    private static boolean shouldKeepTrade(LocalDate date,
                                           String typeWanted,
                                           List<DateRange> ranges) {
        if (date == null || ranges == null || ranges.isEmpty()) {
            // No date info or no ranges -> don't filter anything
            return true;
        }

        boolean inRange = isInAnyRange(date, ranges);

        if ("LONG".equals(typeWanted)) {
            // Exclude periods that are in ranges
            return !inRange;
        } else if ("SHORT".equals(typeWanted)) {
            // Include only periods that are in ranges
            return inRange;
        }

        // Fallback (should not happen because we validate typeWanted)
        return true;
    }

    private static boolean isInAnyRange(LocalDate date, List<DateRange> ranges) {
        if (date == null || ranges == null || ranges.isEmpty()) return false;
        for (DateRange r : ranges) {
            if (r.contains(date)) return true;
        }
        return false;
    }

    // Load exclusions from JSON file:
    // [
    //   { "from": "29/10/2021", "to": "29/06/2022" },
    //   { "from": "30/10/2024", "to": "27/02/2025" }
    // ]
    private static List<DateRange> loadExclusionsFromJson(Path p) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        List<DateRange> out = new ArrayList<>();

        JsonNode root = mapper.readTree(p.toFile());
        if (!root.isArray()) {
            System.err.println("Exclusions file is not an array: " + p);
            return out;
        }

        for (JsonNode n : root) {
            String fromStr = n.path("from").asText(null);
            String toStr   = n.path("to").asText(null);

            if (fromStr == null || toStr == null) continue;

            LocalDate from = parseDateTimeRawToDate(fromStr);
            LocalDate to   = parseDateTimeRawToDate(toStr);
            if (from == null || to == null) continue;

            if (to.isBefore(from)) {
                // swap if user accidentally reversed
                LocalDate tmp = from;
                from = to;
                to = tmp;
            }

            out.add(new DateRange(from, to));
        }

        System.out.println("Loaded " + out.size() + " exclusion ranges from " + p);
        return out;
    }

    // ===== Inner helper classes =====

    private static class QuarterKey implements Comparable<QuarterKey> {
        final int year;
        final int month;      // 1-12
        final String monStr;  // "Jun", "Sep", etc.

        QuarterKey(int year, int month, String monStr) {
            this.year = year;
            this.month = month;
            this.monStr = monStr;
        }

        String toLabel() {
            return year + "-" + monStr;
        }

        @Override
        public int compareTo(QuarterKey o) {
            int cmp = Integer.compare(this.year, o.year);
            if (cmp != 0) return cmp;
            return Integer.compare(this.month, o.month);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof QuarterKey)) return false;
            QuarterKey that = (QuarterKey) o;
            return year == that.year && month == that.month;
        }

        @Override
        public int hashCode() {
            return Objects.hash(year, month);
        }
    }

    private static class QuarterAgg {
        final String quarterLabel;
        final String type;
        int tradeCount = 0;
        BigDecimal sumPnl = BigDecimal.ZERO;
        long totalTradingDays = 0L;

        QuarterAgg(String quarterLabel, String type) {
            this.quarterLabel = quarterLabel;
            this.type = type;
        }
    }

    // Inclusive date range
    private static class DateRange {
        final LocalDate from;
        final LocalDate to;

        DateRange(LocalDate from, LocalDate to) {
            this.from = from;
            this.to = to;
        }

        boolean contains(LocalDate d) {
            return (d.isEqual(from) || d.isAfter(from))
                    && (d.isEqual(to) || d.isBefore(to));
        }
    }
}
