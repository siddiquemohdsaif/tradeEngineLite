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

public class TradeReportMaker {

    public static void main(String[] args) throws Exception {
        // Default to your INFY folder if not passed
        Path dir = (args.length > 0)
                ? Paths.get(args[0])
                : Paths.get("D:\\SpringBoot project\\Trade\\output files\\tradeInfo\\ResultReact\\MPHASIS");

        generateReport(dir);
        System.out.println("Report written: " + dir.resolve("report.json"));
    }

    public static void generateReport(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) {
            throw new IllegalArgumentException("Not a directory: " + folder);
        }

        ObjectMapper mapper = new ObjectMapper();

        // LinkedHashMap to keep insertion order
        Map<String, Object> rootOut = new LinkedHashMap<>();
        Map<String, Object> perQuarter = new LinkedHashMap<>();

        BigDecimal shortSum = BigDecimal.ZERO;
        BigDecimal longSum  = BigDecimal.ZERO;

        // To ensure unique keys if any duplicate "Quarter" appears
        Map<String, Integer> quarterCounts = new HashMap<>();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder, "*.json")) {
            for (Path p : stream) {
                if (p.getFileName().toString().equalsIgnoreCase("report.json")) continue;

                JsonNode node;
                try {
                    node = mapper.readTree(p.toFile());
                } catch (Exception e) {
                    System.err.println("Skip (unreadable): " + p + " :: " + e.getMessage());
                    continue;
                }

                JsonNode qNode = node.path("quarterRecord");
                JsonNode tNode = node.path("trade");

                String quarter = qNode.path("Quarter").asText(null);
                String side    = tNode.path("side").asText(null);       // "SHORT" / "LONG"
                double pnlPct  = tNode.path("pnl_pct").asDouble(Double.NaN);

                if (quarter == null || side == null || Double.isNaN(pnlPct)) {
                    System.err.println("Skip (missing fields): " + p);
                    continue;
                }

                // Sums
                BigDecimal pnl = BigDecimal.valueOf(pnlPct);
                if ("SHORT".equalsIgnoreCase(side)) {
                    shortSum = shortSum.add(pnl);
                } else if ("LONG".equalsIgnoreCase(side)) {
                    longSum = longSum.add(pnl);
                }

                // Ensure unique quarter key if duplicates exist
                String key = quarter;
                int count = quarterCounts.getOrDefault(quarter, 0);
                if (count > 0) key = quarter + " (#" + (count + 1) + ")";
                quarterCounts.put(quarter, count + 1);

                Map<String, Object> one = new LinkedHashMap<>();
                one.put("type", side.toUpperCase(Locale.ROOT));
                one.put("pnl_pct", round3(pnl));
                one.put("file_name", p.getFileName().toString());

                perQuarter.put(key, one);
            }
        }

        BigDecimal total = shortSum.add(longSum);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("SHORT_SUM", round3(shortSum));
        summary.put("LONG_SUM",  round3(longSum));
        summary.put("TOTAL",     round3(total));

        rootOut.putAll(perQuarter);
        rootOut.put("summary", summary);

        ObjectWriter writer = mapper.writer(new DefaultPrettyPrinter());
        writer.writeValue(folder.resolve("report.json").toFile(), rootOut);
    }

    private static double round3(BigDecimal v) {
        return v.setScale(3, RoundingMode.HALF_UP).doubleValue();
    }
}
