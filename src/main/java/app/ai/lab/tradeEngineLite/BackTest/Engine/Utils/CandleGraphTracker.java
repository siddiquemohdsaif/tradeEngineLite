package app.ai.lab.tradeEngineLite.BackTest.Engine.Utils;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.*;
import javax.imageio.ImageIO;

/**
 * Direct port of the Rust CandleGraphTracker to Java for Spring Boot usage.
 * - Add market ticks via addMarketData(timeMs, price)
 * - Call drawCandleGraph(outputDir) to render a large PNG/BMP and save a JSON with volatility info.
 */
public class CandleGraphTracker {

    // ===== Models =====

    public static final class CandleVolatilityInfo {
        @JsonProperty public long timestamp;
        @JsonProperty public String candleId;
        @JsonProperty public double volatility;
        @JsonProperty public double volatilityIndex;

        public CandleVolatilityInfo(long timestamp, String candleId, double volatility, double volatilityIndex) {
            this.timestamp = timestamp;
            this.candleId = candleId;
            this.volatility = volatility;
            this.volatilityIndex = volatilityIndex;
        }
    }

    public static final class MarketPoint {
        public long time;   // ms since epoch
        public double price;

        public MarketPoint(long time, double price) {
            this.time = time;
            this.price = price;
        }
    }

    public enum CandleType { Green, Red }
    public enum WaveType { Crest, Trough }

    public static final class Wave {
        public WaveType waveType;
        public long timestamp;          // peak value bar time (ms)
        public double price;            // peak value bar price (MA value)
        public long recordTimestamp;    // ms

        public Wave(WaveType waveType, long timestamp, double price, long recordTimestamp) {
            this.waveType = waveType;
            this.timestamp = timestamp;
            this.price = price;
            this.recordTimestamp = recordTimestamp;
        }
    }

    public static final class BollingerBands {
        public double middleBand;
        public double upperBand;
        public double lowerBand;

        public BollingerBands() { }
        public BollingerBands(double middleBand, double upperBand, double lowerBand) {
            this.middleBand = middleBand;
            this.upperBand = upperBand;
            this.lowerBand = lowerBand;
        }
    }

    public static final class Candle {
        public int tickCount;
        public long timestamp;          // candle start (ms)
        public String candleId;         // "hh:mma" in IST, lowercased
        public double high;
        public double low;
        public double open;
        public double close;
        public CandleType candleType;
        public double vix;
        public double totalLength;
        public double completePercent;
        public double mavg60, mavg30, mavg10, mavg5, mavg3;
        public double volatility;       // per-tick candle volatility (body + 0.5*vix)
        public double volatilityIndex;  // avg of last N candle.volatility
        public BollingerBands bollinger = new BollingerBands(0, 0, 0);

        public Candle(int tickCount, long timestamp, String candleId, double price,
                      double completePercent, double m60, double m30, double m10, double m5, double m3,
                      double volatilityIndex) {
            this.tickCount = tickCount;
            this.timestamp = timestamp;
            this.candleId = candleId;
            this.high = price;
            this.low = price;
            this.open = price;
            this.close = price;
            this.candleType = CandleType.Green;
            this.vix = 0.0;
            this.totalLength = 0.0;
            this.completePercent = completePercent;
            this.mavg60 = m60;
            this.mavg30 = m30;
            this.mavg10 = m10;
            this.mavg5 = m5;
            this.mavg3 = m3;
            this.volatility = 0.0;
            this.volatilityIndex = volatilityIndex;
        }
    }

    // ===== Tracker fields =====

    public int id;
    public String tradingsymbol;
    public long candleTimeFrameMs = 900_000; // 15m default (same as Rust default)
    public final List<Candle> candles = new ArrayList<>();
    public final List<MarketPoint> marketGraph = new ArrayList<>();
    public final List<Wave> waves30 = new ArrayList<>();
    public final List<Wave> waves60 = new ArrayList<>();

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter ID_FMT = DateTimeFormatter.ofPattern("hh:mma").withLocale(Locale.ENGLISH);

    public CandleGraphTracker(int id, String tradingsymbol) {
        this.id = id;
        this.tradingsymbol = tradingsymbol;
    }

    // ===== Public API =====

    /** Add a market tick (ms since epoch, price). */
    public void addMarketData(long timeMs, double price) {
        marketGraph.add(new MarketPoint(timeMs, price));
        updateCandles(timeMs, price);
        updateWaves(); // includes Bollinger updates
    }

    /** Render chart and save a JSON with volatility info. */
    public void drawCandleGraph(String outputDir) throws IOException {
        if (!outputDir.endsWith(File.separator)) {
            outputDir += File.separator;
        }
        String baseName = this.id + "_" + this.tradingsymbol;

        // Save volatility JSON (pretty)
        saveVolatilityIndex(outputDir + baseName + ".json");

        // --- Prepare image ---
        final int width = 2560 * 8;   // 20480
        final int height = 1440 * 2;  // 2880

        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);

            if (candles.isEmpty()) {
                writeImageWithFallback(new File(outputDir + baseName + ".png"), img);
                return;
            }

            double maxPrice = candles.stream().mapToDouble(c -> c.high).max().orElse(1d);
            double minPrice = candles.stream().mapToDouble(c -> c.low).min().orElse(0d);
            if (maxPrice == minPrice) {
                maxPrice += 1.0;
                minPrice -= 1.0;
            }

            // Margins & plot area
            int marginLeft = 120;
            int marginRight = 40;
            int marginTop = 80;
            int marginBottom = 140;
            int plotX = marginLeft;
            int plotY = marginTop;
            int plotW = width - marginLeft - marginRight;
            int plotH = height - marginTop - marginBottom;

            // Title
            g.setColor(Color.BLACK);
            g.setFont(g.getFont().deriveFont(Font.BOLD, 48f));
            g.drawString("Candle Graph", marginLeft, 60);

            // Axes
            g.setColor(new Color(220, 220, 220));
            g.drawRect(plotX, plotY, plotW, plotH);

            // Y ticks
            g.setFont(g.getFont().deriveFont(Font.PLAIN, 20f));
            g.setColor(Color.GRAY);
            int yTicks = 10;
            for (int i = 0; i <= yTicks; i++) {
                double t = i / (double) yTicks;
                int y = plotY + (int) Math.round(plotH - t * plotH);
                g.setColor(new Color(235, 235, 235));
                g.drawLine(plotX, y, plotX + plotW, y);
                g.setColor(Color.DARK_GRAY);
                double price = minPrice + (maxPrice - minPrice) * t;
                String label = String.format(Locale.ENGLISH, "%.2f", price);
                g.drawString(label, 10, y + 6);
            }

            // X labels (candleId) — draw sparsely to avoid crowding
            int n = candles.size();
            int every = Math.max(1, n / 30);
            g.setColor(Color.DARK_GRAY);
            for (int i = 0; i < n; i += every) {
                double x = xToPixel(i + 0.5, n, plotX, plotW);
                g.drawLine((int) x, plotY + plotH, (int) x, plotY + plotH + 6);
                String label = candles.get(i).candleId;
                drawCentered(g, label, (int) x, plotY + plotH + 28);
            }

            // Draw candles (wicks + body)
            Color green = new Color(76, 175, 80);
            Color red = new Color(223, 81, 76);

            for (int i = 0; i < n; i++) {
                Candle c = candles.get(i);
                double cx = i + 0.5;
                double xMid = xToPixel(cx, n, plotX, plotW);

                // Wick
                g.setColor(c.candleType == CandleType.Green ? green : red);
                int yLow = yToPixel(c.low, minPrice, maxPrice, plotY, plotH);
                int yHigh = yToPixel(c.high, minPrice, maxPrice, plotY, plotH);
                g.drawLine((int) xMid, yLow, (int) xMid, yHigh);

                // Body
                double top = Math.max(c.open, c.close);
                double bottom = Math.min(c.open, c.close);
                int yTop = yToPixel(top, minPrice, maxPrice, plotY, plotH);
                int yBottom = yToPixel(bottom, minPrice, maxPrice, plotY, plotH);

                int bodyW = Math.max(2, (int) Math.round(plotW / (double) n * 0.6));
                int xLeft = (int) xMid - bodyW / 2;
                int hBody = Math.max(1, yBottom - yTop);

                g.fillRect(xLeft, yTop, bodyW, hBody);
            }

            // Moving averages
            // Colors mirror your Rust code (approx)
            Color m60 = new Color(0, 165, 83);
            Color m30 = new Color(255, 0, 0);
            Color m10 = new Color(233, 8, 140);
            Color m5  = new Color(0, 175, 237);
            Color m3  = new Color(50, 50, 50);

            drawLineSeries(g, candles, n, plotX, plotW, plotY, plotH, minPrice, maxPrice, c -> c.mavg3, 2f, m3);
            drawLineSeries(g, candles, n, plotX, plotW, plotY, plotH, minPrice, maxPrice, c -> c.mavg5, 2f, m5);
            drawLineSeries(g, candles, n, plotX, plotW, plotY, plotH, minPrice, maxPrice, c -> c.mavg10, 2f, m10);
            drawLineSeries(g, candles, n, plotX, plotW, plotY, plotH, minPrice, maxPrice, c -> c.mavg60, 2f, m60);
            drawLineSeries(g, candles, n, plotX, plotW, plotY, plotH, minPrice, maxPrice, c -> c.mavg30, 2f, m30);

            // Waves (30)
            g.setColor(m30);
            for (Wave w : waves30) {
                int idx = indexOfTimestamp(candles, w.timestamp);
                if (idx >= 0) {
                    double cx = idx + 0.5;
                    int x = (int) xToPixel(cx, n, plotX, plotW);
                    int y = yToPixel(w.price, minPrice, maxPrice, plotY, plotH);
                    fillCircle(g, x, y, 6);
                }
                int recIdx = indexOfTimestamp(candles, w.recordTimestamp);
                if (recIdx >= 0) {
                    double cx = recIdx + 0.5;
                    int x = (int) xToPixel(cx, n, plotX, plotW);
                    int y = yToPixel(candles.get(recIdx).mavg30, minPrice, maxPrice, plotY, plotH);
                    g.setColor(Color.BLACK);
                    fillCircle(g, x, y, 6);
                    g.setColor(m30);
                }
            }

            // Waves (60)
            g.setColor(m60);
            for (Wave w : waves60) {
                int idx = indexOfTimestamp(candles, w.timestamp);
                if (idx >= 0) {
                    double cx = idx + 0.5;
                    int x = (int) xToPixel(cx, n, plotX, plotW);
                    int y = yToPixel(w.price, minPrice, maxPrice, plotY, plotH);
                    fillCircle(g, x, y, 6);
                }
                int recIdx = indexOfTimestamp(candles, w.recordTimestamp);
                if (recIdx >= 0) {
                    double cx = recIdx + 0.5;
                    int x = (int) xToPixel(cx, n, plotX, plotW);
                    int y = yToPixel(candles.get(recIdx).mavg60, minPrice, maxPrice, plotY, plotH);
                    g.setColor(Color.BLACK);
                    fillCircle(g, x, y, 6);
                    g.setColor(m60);
                }
            }

            // Bollinger (lines + filled band)
            List<Double> bbM = new ArrayList<>(n);
            List<Double> bbU = new ArrayList<>(n);
            List<Double> bbL = new ArrayList<>(n);
            for (Candle c : candles) {
                bbM.add(c.bollinger.middleBand);
                bbU.add(c.bollinger.upperBand);
                bbL.add(c.bollinger.lowerBand);
            }

            g.setColor(Color.BLACK);
            drawSimpleLine(g, bbM, n, plotX, plotW, plotY, plotH, minPrice, maxPrice, 2f, Color.BLACK);
            drawSimpleLine(g, bbU, n, plotX, plotW, plotY, plotH, minPrice, maxPrice, 2f, Color.BLACK);
            drawSimpleLine(g, bbL, n, plotX, plotW, plotY, plotH, minPrice, maxPrice, 2f, Color.BLACK);

            // Fill band (upper -> lower reversed)
            if (bbU.size() == bbL.size() && !bbU.isEmpty()) {
                Composite old = g.getComposite();
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.2f));
                g.setColor(Color.BLACK);

                Path2D poly = new Path2D.Double();
                boolean started = false;
                for (int i = 0; i < n; i++) {
                    double x = xToPixel(i + 0.5, n, plotX, plotW);
                    int y = yToPixel(bbU.get(i), minPrice, maxPrice, plotY, plotH);
                    if (!started) { poly.moveTo(x, y); started = true; }
                    else poly.lineTo(x, y);
                }
                for (int i = n - 1; i >= 0; i--) {
                    double x = xToPixel(i + 0.5, n, plotX, plotW);
                    int y = yToPixel(bbL.get(i), minPrice, maxPrice, plotY, plotH);
                    poly.lineTo(x, y);
                }
                poly.closePath();
                g.fill(poly);
                g.setComposite(old);
            }

            // Simple legend
            int lx = plotX + 20;
            int ly = plotY + 20;
            drawLegendEntry(g, lx, ly, m3,  "MA3");   ly += 28;
            drawLegendEntry(g, lx, ly, m5,  "MA5");   ly += 28;
            drawLegendEntry(g, lx, ly, m10, "MA10");  ly += 28;
            drawLegendEntry(g, lx, ly, m30, "MA30");  ly += 28;
            drawLegendEntry(g, lx, ly, m60, "MA60");  ly += 28;
            drawLegendEntry(g, lx, ly, Color.BLACK, "BB (M/U/L)"); ly += 28;

        } finally {
            g.dispose();
        }

        // Write as BMP if available; fallback to PNG.
        File bmp = new File(outputDir + baseName + ".bmp");
        if (!writeImageWithFallback(bmp, img)) {
            writeImageWithFallback(new File(outputDir + baseName + ".png"), img);
        }
    }

    /** Persist per-candle volatility and volatility index as JSON. */
    public void saveVolatilityIndex(String filePath) throws IOException {
        List<CandleVolatilityInfo> out = new ArrayList<>();
        for (Candle c : candles) {
            out.add(new CandleVolatilityInfo(c.timestamp, c.candleId, c.volatility, c.volatilityIndex));
        }
        ObjectMapper om = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        om.writeValue(new File(filePath), out);
    }

    // ===== Core logic (ported) =====

    private void updateCandles(long timeMs, double price) {
        long frame = candleTimeFrameMs;
        long candleTs = (timeMs / frame) * frame;

        // IST "hh:mma"
        String candleId = Instant.ofEpochMilli(candleTs).atZone(IST).format(ID_FMT).toLowerCase(Locale.ENGLISH);

        double completePercent = Math.round(((timeMs - candleTs) / (double) frame) * 100.0);

        if (!candles.isEmpty()) {
            Candle last = candles.get(candles.size() - 1);
            if (last.timestamp == candleTs) {
                // Update existing candle
                if (price > last.high) last.high = price;
                if (price < last.low) last.low = price;

                last.close = price;
                last.totalLength = last.high - last.low;
                last.candleType = last.close >= last.open ? CandleType.Green : CandleType.Red;
                last.vix = last.high - last.low;
                last.completePercent = completePercent;
                last.tickCount += 1;

                double body = Math.abs(last.close - last.open);
                last.volatility = body + 0.5 * last.vix;
            } else {
                // New candle
                createNewCandle(candleTs, candleId, price, completePercent);
            }
        } else {
            // First candle
            createNewCandle(candleTs, candleId, price, completePercent);
        }

        // Compute moving averages for the last candle
        int lastIdx = candles.size() - 1;
        int[] periods = {60, 30, 10, 5, 3};
        for (int p : periods) {
            int n = Math.min(p, candles.size());
            double sum = 0.0;
            for (int i = candles.size() - n; i < candles.size(); i++) {
                sum += candles.get(i).close;
            }
            double avg = sum / n;
            Candle cur = candles.get(lastIdx);
            switch (p) {
                case 60 -> cur.mavg60 = avg;
                case 30 -> cur.mavg30 = avg;
                case 10 -> cur.mavg10 = avg;
                case 5  -> cur.mavg5 = avg;
                case 3  -> cur.mavg3 = avg;
            }
        }
    }

    private void createNewCandle(long candleTs, String candleId, double price, double completePercent) {
        int count = candles.size();

        double volIndex;
        if (count < 5) {
            volIndex = 0.0;
        } else {
            int n = Math.min(30, count);
            double sum = 0.0;
            for (int i = count - n; i < count; i++) sum += candles.get(i).volatility;
            volIndex = sum / n;
        }

        if (!candles.isEmpty()) {
            Candle last = candles.get(candles.size() - 1);
            candles.add(new Candle(
                    1, candleTs, candleId, price, completePercent,
                    last.mavg60, last.mavg30, last.mavg10, last.mavg5, last.mavg3,
                    volIndex
            ));
        } else {
            candles.add(new Candle(
                    1, candleTs, candleId, price, completePercent,
                    price, price, price, price, price,
                    0.0
            ));
        }
    }

    private void updateWaves() {
        if (candles.size() < 5) return;
        evaluateWave(30, waves30);
        evaluateWave(60, waves60);
        updateBollingerBands(20, 2.0);
    }

    private void evaluateWave(int mavgPeriod, List<Wave> waves) {
        int last = candles.size() - 1;

        // slope over last 5
        double slope;
        if (mavgPeriod == 30) {
            slope = (candles.get(last).mavg30 - candles.get(last - 4).mavg30) / 5.0;
        } else {
            slope = (candles.get(last).mavg60 - candles.get(last - 4).mavg60) / 5.0;
        }

        // Don't add if last wave < 5 minutes ago
        if (!waves.isEmpty()) {
            Wave lw = waves.get(waves.size() - 1);
            long fiveMin = 5 * 60 * 1000L;
            if (candles.get(last).timestamp - lw.timestamp < fiveMin) {
                return;
            }
        }

        if (waves.isEmpty()) {
            if (slope > 0.1) {
                // min of last 5 MAs -> Trough
                double bestPrice = Double.POSITIVE_INFINITY;
                long bestTs = 0L;
                for (int i = last - 4; i <= last; i++) {
                    double v = (mavgPeriod == 30) ? candles.get(i).mavg30 : candles.get(i).mavg60;
                    if (v < bestPrice) { bestPrice = v; bestTs = candles.get(i).timestamp; }
                }
                waves.add(new Wave(WaveType.Trough, bestTs, bestPrice, candles.get(last).timestamp));
            } else if (slope < -0.1) {
                // max of last 5 MAs -> Crest
                double bestPrice = Double.NEGATIVE_INFINITY;
                long bestTs = 0L;
                for (int i = last - 4; i <= last; i++) {
                    double v = (mavgPeriod == 30) ? candles.get(i).mavg30 : candles.get(i).mavg60;
                    if (v > bestPrice) { bestPrice = v; bestTs = candles.get(i).timestamp; }
                }
                waves.add(new Wave(WaveType.Crest, bestTs, bestPrice, candles.get(last).timestamp));
            }
        } else {
            Wave prev = waves.get(waves.size() - 1);
            if (slope > 0.1 && prev.waveType == WaveType.Crest) {
                // switch to Trough at min of last 5
                double bestPrice = Double.POSITIVE_INFINITY;
                long bestTs = 0L;
                for (int i = last - 4; i <= last; i++) {
                    double v = (mavgPeriod == 30) ? candles.get(i).mavg30 : candles.get(i).mavg60;
                    if (v < bestPrice) { bestPrice = v; bestTs = candles.get(i).timestamp; }
                }
                waves.add(new Wave(WaveType.Trough, bestTs, bestPrice, candles.get(last).timestamp));
            } else if (slope < -0.1 && prev.waveType == WaveType.Trough) {
                // switch to Crest at max of last 5
                double bestPrice = Double.NEGATIVE_INFINITY;
                long bestTs = 0L;
                for (int i = last - 4; i <= last; i++) {
                    double v = (mavgPeriod == 30) ? candles.get(i).mavg30 : candles.get(i).mavg60;
                    if (v > bestPrice) { bestPrice = v; bestTs = candles.get(i).timestamp; }
                }
                waves.add(new Wave(WaveType.Crest, bestTs, bestPrice, candles.get(last).timestamp));
            }
        }
    }

    private void updateBollingerBands(int period, double stdDevFactor) {
        int count = candles.size();
        if (count < period) return;

        int start = count - period;
        double sum = 0.0;
        double sumSq = 0.0;

        for (int i = start; i < count; i++) {
            double close = candles.get(i).close;
            sum += close;
            sumSq += close * close;
        }

        double middle = sum / period;
        double meanSq = sumSq / period;
        double variance = meanSq - (middle * middle);
        double stdDev = Math.sqrt(Math.max(0.0, variance));

        double upper = middle + stdDevFactor * stdDev;
        double lower = middle - stdDevFactor * stdDev;

        Candle last = candles.get(count - 1);
        last.bollinger = new BollingerBands(middle, upper, lower);
    }

    // ===== Helper draw/math =====

    private static void drawLegendEntry(Graphics2D g, int x, int y, Color color, String label) {
        g.setColor(color);
        g.fillRect(x, y - 12, 24, 6);
        g.setColor(Color.BLACK);
        g.drawString(label, x + 30, y);
    }

    private static void fillCircle(Graphics2D g, int cx, int cy, int r) {
        g.fillOval(cx - r, cy - r, r * 2, r * 2);
    }

    private static int indexOfTimestamp(List<Candle> list, long ts) {
        for (int i = 0; i < list.size(); i++) if (list.get(i).timestamp == ts) return i;
        return -1;
    }

    private static double xToPixel(double xCandleCoord, int n, int plotX, int plotW) {
        return plotX + (xCandleCoord / (double) n) * plotW;
    }

    private static int yToPixel(double price, double min, double max, int plotY, int plotH) {
        double t = (price - min) / (max - min);
        return plotY + (int) Math.round(plotH - t * plotH);
    }

    private static void drawCentered(Graphics2D g, String text, int cx, int y) {
        FontMetrics fm = g.getFontMetrics();
        int w = fm.stringWidth(text);
        g.drawString(text, cx - w / 2, y);
    }

    private static void drawLineSeries(
            Graphics2D g,
            List<Candle> candles,
            int n,
            int plotX, int plotW, int plotY, int plotH,
            double minPrice, double maxPrice,
            java.util.function.ToDoubleFunction<Candle> valueExtractor,
            float strokeWidth,
            Color color
    ) {
        List<Double> series = new ArrayList<>(candles.size());
        for (Candle c : candles) series.add(valueExtractor.applyAsDouble(c));
        drawSimpleLine(g, series, n, plotX, plotW, plotY, plotH, minPrice, maxPrice, strokeWidth, color);
    }

    private static void drawSimpleLine(
            Graphics2D g,
            List<Double> series,
            int n,
            int plotX, int plotW, int plotY, int plotH,
            double minPrice, double maxPrice,
            float strokeWidth,
            Color color
    ) {
        Stroke old = g.getStroke();
        g.setStroke(new BasicStroke(strokeWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(color);

        Path2D path = new Path2D.Double();
        boolean started = false;
        for (int i = 0; i < series.size(); i++) {
            Double v = series.get(i);
            if (v == null) continue;
            double x = xToPixel(i + 0.5, n, plotX, plotW);
            int y = yToPixel(v, minPrice, maxPrice, plotY, plotH);
            if (!started) { path.moveTo(x, y); started = true; }
            else { path.lineTo(x, y); }
        }
        g.draw(path);
        g.setStroke(old);
    }

    private static boolean writeImageWithFallback(File file, BufferedImage img) {
        String ext = getExt(file.getName());
        try {
            boolean ok = ImageIO.write(img, ext, file);
            if (ok) return true;
        } catch (Exception ignore) { }
        // fallback to PNG
        try {
            File png = file.getName().toLowerCase(Locale.ENGLISH).endsWith(".png")
                    ? file
                    : new File(file.getParentFile(), stripExt(file.getName()) + ".png");
            return ImageIO.write(img, "png", png);
        } catch (IOException e) {
            return false;
        }
    }

    private static String getExt(String name) {
        int i = name.lastIndexOf('.');
        return (i >= 0 && i + 1 < name.length()) ? name.substring(i + 1) : "png";
    }

    private static String stripExt(String name) {
        int i = name.lastIndexOf('.');
        return (i >= 0) ? name.substring(0, i) : name;
    }

    // ===== Optional convenience setters =====

    public void setCandleTimeFrameMs(long candleTimeFrameMs) {
        this.candleTimeFrameMs = candleTimeFrameMs;
    }

    // ===== Example Spring Boot wiring =====
    // @Service
    // public static class CandleService {
    //     private final CandleGraphTracker tracker = new CandleGraphTracker(1, "NIFTY");
    //     public void onTick(long epochMs, double price) { tracker.addMarketData(epochMs, price); }
    //     public void render(String dir) throws IOException { tracker.drawCandleGraph(dir); }
    // }
}
