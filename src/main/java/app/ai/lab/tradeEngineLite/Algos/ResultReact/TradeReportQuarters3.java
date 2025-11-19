package app.ai.lab.tradeEngineLite.Algos.ResultReact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;

// JFreeChart imports
import org.jfree.chart.ChartFactory;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.ChartUtils;
import org.jfree.data.category.DefaultCategoryDataset;

/**
 * Aggregates trades across ALL symbol folders under ResultReact
 * for a given quarter range and side (SHORT/LONG),
 * with optional date-range exclusions based on quarterRecord.dateTimeRaw.
 *
 * exclude.json behaviour:
 *  - For LONG  : ranges are EXCLUSION zones (no trades counted in those dates)
 *  - For SHORT : ranges are INCLUSION zones (only trades in those dates are counted)
 *
 * Also builds a day-wise "active_trades" graph and saves it as PNG:
 *   ActiveTrades_<startQuarter>_to_<endQuarter>_<TYPE>.png
 */
public class TradeReportQuarters3 {

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
        Path baseDir = Paths.get("D:\\SpringBoot project\\Trade\\output files\\tradeInfo\\ResultReact_futv3");

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
        if (exclusionsFile != null && Files.exists(exclusionsFile)) {
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

        // NEW: day-wise active trades map
        // For all trades that pass filters (side, quarter range, dateRange logic)
        Map<LocalDate, Integer> dailyActiveTrades = new TreeMap<>();

                // For trading-days distribution statistics (per trade)
        List<Long> tradingDaysPerTrade = new ArrayList<>();

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

                        // --- dateTimeRaw-based filter ---
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

                        // Collect per-trade trading days for statistics
                        tradingDaysPerTrade.add(days);

                        // Aggregate by quarter
                        QuarterAgg agg = aggMap.computeIfAbsent(qKey, k -> new QuarterAgg(k.toLabel(), typeWanted));
                        agg.tradeCount++;
                        agg.sumPnl = agg.sumPnl.add(BigDecimal.valueOf(pnlPct));
                        agg.totalTradingDays += days;

                        // --- NEW: accumulate day-wise active trades ---
                        if (entryMs > 0L && exitMs > 0L && exitMs >= entryMs) {
                            LocalDate startDate = Instant.ofEpochMilli(entryMs)
                                    .atZone(ZoneId.systemDefault())
                                    .toLocalDate();
                            LocalDate endDate = Instant.ofEpochMilli(exitMs)
                                    .atZone(ZoneId.systemDefault())
                                    .toLocalDate();

                            for (LocalDate d = startDate; !d.isAfter(endDate); d = d.plusDays(1)) {
                                dailyActiveTrades.merge(d, 1, Integer::sum);
                            }
                        }
                    }
                }
            }
        }

        // Build final JSON (quarter-wise summary)
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

        // NEW: trading days distribution stats
        Map<String, Object> tradingDaysStats = computeTradingDaysStats(tradingDaysPerTrade);
        summary.put("trading_days_used", tradingDaysStats);


        out.put("summary", summary);

        // Output file name for JSON
        String baseLabel = "TradeReportQuarters_" + startQuarter + "_to_" + endQuarter + "_" + typeWanted + "_";
        String fileName = baseLabel + ".json";
        Path outFile = baseDir.resolve(fileName);

        ObjectWriter writer = mapper.writer(new DefaultPrettyPrinter());
        writer.writeValue(outFile.toFile(), out);

        System.out.println("Written JSON: " + outFile.toAbsolutePath());

        // --- NEW: create and save active-trades graph ---
        if (!dailyActiveTrades.isEmpty()) {
            createActiveTradesChart(dailyActiveTrades, baseDir, startQuarter, endQuarter, typeWanted);
        } else {
            System.out.println("No daily active trade data to plot (map empty).");
        }
    }

    // ===== Helpers =====

        // Compute avg, median, 90th and 70th percentile over per-trade trading days
    private static Map<String, Object> computeTradingDaysStats(List<Long> daysList) {
        Map<String, Object> stats = new LinkedHashMap<>();
        if (daysList == null || daysList.isEmpty()) {
            stats.put("avg", 0.0);
            stats.put("median", 0.0);
            stats.put("90_percentile", 0.0);
            stats.put("70_percentile", 0.0);
            return stats;
        }

        // Sort a copy
        List<Long> sorted = new ArrayList<>(daysList);
        Collections.sort(sorted);

        int n = sorted.size();

        // Sum for average
        long sum = 0L;
        for (Long v : sorted) {
            sum += (v == null ? 0L : v);
        }
        double avg = BigDecimal.valueOf(sum)
                .divide(BigDecimal.valueOf(n), 3, RoundingMode.HALF_UP)
                .doubleValue();

        // Median
        double median;
        if (n % 2 == 1) {
            median = sorted.get(n / 2);
        } else {
            long a = sorted.get(n / 2 - 1);
            long b = sorted.get(n / 2);
            median = BigDecimal.valueOf(a + b)
                    .divide(BigDecimal.valueOf(2), 3, RoundingMode.HALF_UP)
                    .doubleValue();
        }

        double p90 = percentile(sorted, 90);
        double p70 = percentile(sorted, 70);

        stats.put("avg", avg);
        stats.put("median", median);
        stats.put("90_percentile", p90);
        stats.put("70_percentile", p70);

        return stats;
    }

    // Simple percentile function on sorted list (ascending), returns double
    private static double percentile(List<Long> sorted, int percentile) {
        if (sorted == null || sorted.isEmpty()) return 0.0;
        if (percentile <= 0) return sorted.get(0);
        if (percentile >= 100) return sorted.get(sorted.size() - 1);

        int n = sorted.size();
        double rank = (percentile / 100.0) * n;
        int idx = (int) Math.ceil(rank) - 1;
        if (idx < 0) idx = 0;
        if (idx >= n) idx = n - 1;
        return sorted.get(idx);
    }

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

    // === NEW: chart creation for daily active trades ===
    private static void createActiveTradesChart(Map<LocalDate, Integer> dailyActiveTrades,
                                                Path baseDir,
                                                String startQuarter,
                                                String endQuarter,
                                                String typeWanted) {
        try {
            DefaultCategoryDataset dataset = new DefaultCategoryDataset();
            String seriesKey = "Active Trades";

            // X-axis category as date string (yyyy-MM-dd)
            for (Map.Entry<LocalDate, Integer> e : dailyActiveTrades.entrySet()) {
                LocalDate date = e.getKey();
                Integer count = e.getValue();
                String label = date.toString(); // e.g. 2024-11-14
                dataset.addValue(count, seriesKey, label);
            }

            String chartTitle = "Daily Active Trades (" + typeWanted + ") " +
                    startQuarter + " to " + endQuarter;
            JFreeChart chart = ChartFactory.createLineChart(
                    chartTitle,
                    "Date",
                    "Active Trades",
                    dataset
            );

            int width = 16000*8;
            int height = 1000;

            String pngName = "ActiveTrades_" + startQuarter + "_to_" + endQuarter + "_" + typeWanted + "_.png";
            Path outPng = baseDir.resolve(pngName);

            ChartUtils.saveChartAsPNG(outPng.toFile(), chart, width, height);
            System.out.println("Written Active Trades PNG: " + outPng.toAbsolutePath());
        } catch (Exception e) {
            System.err.println("Failed to create/save active trades chart: " + e.getMessage());
            e.printStackTrace(System.err);
        }
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
