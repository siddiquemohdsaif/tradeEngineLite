package app.ai.lab.tradeEngineLite.Algos.ResultReact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.*;
import java.util.*;

/**
 * Aggregates trades across ALL symbol folders under ResultReact
 * for a given quarter range and side (SHORT/LONG).
 *
 * Output JSON structure:
 * {
 *   "2020-Jun": {
 *     "no_of_trade": 10,
 *     "sum_pnl": 20.0,
 *     "type": "LONG",
 *     "total_trading_days_use": 86
 *   },
 *   ...
 *   "summary": {
 *     "type": "LONG",
 *     "startQuarter": "2020-Jun",
 *     "endQuarter": "2025-Jun",
 *     "total_trades": 23,
 *     "overall_sum_pnl": 42.0,
 *     "overall_trading_days_use": 181
 *   }
 * }
 */
public class TradeReportQuarters {

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

    public static void main(String[] args) throws Exception {
        // Base folder where all symbol folders (HCLTECH, INFY, MPHASIS, etc.) exist
        Path baseDir = Paths.get("D:\\SpringBoot project\\Trade\\output files\\tradeInfo\\ResultReact");

        String startQuarter = "2020-Jun";
        String endQuarter   = "2025-Jun";
        String type         = "LONG"; // or SHORT

        if (args.length >= 3) {
            baseDir      = Paths.get(args[0]);
            startQuarter = args[1];
            endQuarter   = args[2];
            if (args.length >= 4) {
                type = args[3];
            }
        }

        generateQuarterReport(baseDir, startQuarter, endQuarter, type);
        System.out.println("Quarter report generated in: " + baseDir.toAbsolutePath());
    }

    public static void generateQuarterReport(Path baseDir,
                                             String startQuarter,
                                             String endQuarter,
                                             String typeFilter) throws IOException {

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

                // Inside each symbol folder, read all *.json files except report.json
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

                        JsonNode qNode = root.path("quarterRecord");
                        JsonNode tNode = root.path("trade");
                        JsonNode times = root.path("times");

                        String quarterStr = qNode.path("Quarter").asText(null);
                        String side       = tNode.path("side").asText(null);
                        double pnlPct     = tNode.path("pnl_pct").asDouble(Double.NaN);
                        long entryMs      = times.path("entry_epoch_ms").asLong(0L);
                        long exitMs       = times.path("exit_epoch_ms").asLong(0L);

                        if (quarterStr == null || side == null || Double.isNaN(pnlPct)) {
                            System.err.println("Skip (missing fields): " + jsonFile);
                            continue;
                        }

                        String sideUpper = side.toUpperCase(Locale.ROOT);
                        if (!sideUpper.equals(typeWanted)) {
                            continue; // skip other side
                        }

                        QuarterKey qKey = parseQuarter(quarterStr);
                        if (qKey == null) {
                            System.err.println("Skip (invalid Quarter): " + quarterStr + " in " + jsonFile);
                            continue;
                        }

                        // Check if quarter is within range [startKey, endKey]
                        if (qKey.compareTo(startKey) < 0 || qKey.compareTo(endKey) > 0) {
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
        String fileName = "TradeReportQuarters_" + startQuarter + "_to_" + endQuarter + "_" + typeWanted + ".json";
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

        return new QuarterKey(year, month, mStr.substring(0, 1).toUpperCase() + mStr.substring(1).toLowerCase(Locale.ROOT));
    }

    private static double round3(BigDecimal v) {
        return v.setScale(3, RoundingMode.HALF_UP).doubleValue();
    }

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
}
