package app.ai.lab.tradeEngineLite.GraphUtils;

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
 * - Polished axes:
 *    * Y-axis uses “nice” round ticks (1–2–5 × 10^k) and adds N minor lines between majors (default 9).
 *    * X-axis draws vertical grid lines at each labeled tick (or a custom every-k-candles cadence).
 * - Optional RSI:
 *    * Enable via constructor overload with rsiPeriod.
 *    * RSI computed from candle CLOSES, appended when a new candle starts (i.e., previous candle closes).
 *    * If enabled, renders an RSI panel below the candles, sharing the same timeline.
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
    public long candleTimeFrameMs = 900_000; // 15m default
    public final List<Candle> candles = new ArrayList<>();
    public final List<MarketPoint> marketGraph = new ArrayList<>();
    public final List<Wave> waves30 = new ArrayList<>();
    public final List<Wave> waves60 = new ArrayList<>();

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter ID_FMT = DateTimeFormatter.ofPattern("hh:mma").withLocale(Locale.ENGLISH);

    // ===== Y/X axis polish knobs =====
    private int targetYTicks = 10;             // desired number of MAJOR Y ticks
    private int yMinorBetweenMajors = 9;       // draw this many MINOR lines between majors (default 9 => 10 slices)
    private double yPadFractionOfMid = 0.10;   // ±10% of mid-price as padding
    private Integer xGridEveryCandles = null;  // if set, draw vertical grid every K candles; else uses label spacing

    // ===== Grid customization (NEW) =====
    private Color gridMajorColor = new Color(200, 200, 200);
    private Color gridMinorColor = new Color(230, 230, 230);
    private Color axesBorderColor = new Color(220, 220, 220);
    private float gridMajorStroke = 1.0f;
    private float gridMinorStroke = 1.0f;

    // ===== RSI state (optional) =====
    // RSI grid (5% step) style
    private boolean rsiShow5PctGrid = true;
    private Color rsiGridMajorColor = new Color(210, 210, 210); // for 0,10,20,...,100
    private Color rsiGridMinorColor = new Color(235, 235, 235); // for 5,15,25,....
    private float rsiGridMajorStroke = 1.25f;
    private float rsiGridMinorStroke = 1.0f;
    private boolean rsiEnabled = false;
    private int rsiPeriod = 14;
    private Double rsiPrevClose = null;
    private double rsiAvgGain = 0.0;
    private double rsiAvgLoss = 0.0;
    private int rsiInitCount = 0;     // number of diffs accumulated toward initial period
    private boolean rsiInitialized = false;
    private final List<Double> rsiValues = new ArrayList<>(); // aligned to candles: index i -> RSI for candle i (may be null)

    // RSI render knobs
    private Color rsiLineColor = new Color(33, 150, 243);    // blue-ish
    private Color rsiLevelColor = new Color(200, 200, 200);  // 30/70 guides
    private float rsiLineStroke = 2.0f;

    public CandleGraphTracker(int id, String tradingsymbol) {
        this.id = id;
        this.tradingsymbol = tradingsymbol;
    }

    public CandleGraphTracker(int id, String tradingsymbol, long candleTimeFrameSeconds) {
        this(id, tradingsymbol);
        this.candleTimeFrameMs = candleTimeFrameSeconds * 1000L;
    }

    /** Enable RSI via constructor overloading. */
    public CandleGraphTracker(int id, String tradingsymbol, long candleTimeFrameSeconds, int rsiPeriod) {
        this(id, tradingsymbol, candleTimeFrameSeconds);
        enableRSI(rsiPeriod);
    }

    // ===== Public API =====

    /** Add a market tick (ms since epoch, price). */
    public void addMarketData(long timeMs, double price) {
        marketGraph.add(new MarketPoint(timeMs, price));
        updateCandles(timeMs, price);
        updateWaves(); // includes Bollinger updates
    }

    public Double getRSILatest(){
        if (rsiValues.size() < 2) {
            return null;
        }
        return rsiValues.get(rsiValues.size()-2);
    }

    /** Render chart and save a JSON with volatility info. Also draws RSI panel if enabled. */
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

        boolean hasRSIToDraw = rsiEnabled; // draw panel regardless of whether latest points are null; timeline still shared

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

            // Raw data bounds
            double dataMax = candles.stream().mapToDouble(c -> c.high).max().orElse(1d);
            double dataMin = candles.stream().mapToDouble(c -> c.low).min().orElse(0d);
            if (dataMax == dataMin) {
                dataMax += 1.0;
                dataMin -= 1.0;
            }

            // Add symmetric padding based on mid-price (default 10%)
            double padAbs = (dataMax - dataMin) * yPadFractionOfMid;
            double range = dataMax - dataMin;
            if (padAbs < range * 0.05) padAbs = range * 0.05; // ensure some minimum padding
            double paddedMin = dataMin - padAbs;
            double paddedMax = dataMax + padAbs;

            // Compute nice min/max/step for Y axis
            NiceScale yscale = new NiceScale(paddedMin, paddedMax, targetYTicks);
            double yMinPlot = yscale.niceMin;
            double yMaxPlot = yscale.niceMax;
            double yStep    = yscale.tickSpacing;

            // Margins
            int marginLeft = 120;
            int marginRight = 40;
            int marginTop = 80;
            int marginBottom = 140;

            // Panel layout
            int gapBetweenPanels = hasRSIToDraw ? 40 : 0;
            int rsiPanelHeight = hasRSIToDraw ? Math.min(640, (int)(height * 0.30)) : 0;

            // Plot areas
            int plotX = marginLeft;
            int plotW = width - marginLeft - marginRight;

            int candleAreaTop = marginTop;
            int candleAreaHeight = height - marginTop - marginBottom - rsiPanelHeight - gapBetweenPanels;
            int plotY = candleAreaTop;
            int plotH = candleAreaHeight;

            int rsiY = plotY + plotH + gapBetweenPanels;
            int rsiH = rsiPanelHeight;

            // Title
            g.setColor(Color.BLACK);
            g.setFont(g.getFont().deriveFont(Font.BOLD, 48f));
            g.drawString("Candle Graph", marginLeft, 60);

            // Axes border (candles)
            g.setColor(axesBorderColor);
            g.drawRect(plotX, plotY, plotW, plotH);

            // ---- Y major ticks + labels (candles)
            g.setFont(g.getFont().deriveFont(Font.PLAIN, 20f));
            int majorCount = (int)Math.round((yMaxPlot - yMinPlot) / yStep);

            Stroke oldStroke = g.getStroke();
            // Major grid
            g.setStroke(new BasicStroke(gridMajorStroke));
            for (int k = 0; k <= majorCount; k++) {
                double value = yMinPlot + k * yStep;
                int y = yToPixel(value, yMinPlot, yMaxPlot, plotY, plotH);
                // Major grid line
                g.setColor(gridMajorColor);
                g.drawLine(plotX, y, plotX + plotW, y);
                // Label
                g.setColor(Color.DARK_GRAY);
                String label = formatTickLabel(value, yStep);
                g.drawString(label, 10, y + 6);
            }

            // ---- Y minor grid lines (candles)
            if (yMinorBetweenMajors > 0) {
                g.setStroke(new BasicStroke(gridMinorStroke));
                g.setColor(gridMinorColor); // lighter than major grids
                for (int k = 0; k < majorCount; k++) {
                    double base = yMinPlot + k * yStep;
                    for (int j = 1; j <= yMinorBetweenMajors; j++) {
                        double v = base + (yStep * (j / (double)(yMinorBetweenMajors + 1)));
                        int y = yToPixel(v, yMinPlot, yMaxPlot, plotY, plotH);
                        g.drawLine(plotX, y, plotX + plotW, y);
                    }
                }
            }
            g.setStroke(oldStroke);

            // X labels cadence — draw sparsely to avoid crowding
            int n = candles.size();
            int labelEvery = Math.max(1, n / 30);

            // ---- X vertical grid lines (extend across both panels if RSI enabled)
            int xEvery = (xGridEveryCandles != null && xGridEveryCandles > 0) ? xGridEveryCandles : labelEvery;
            g.setColor(gridMajorColor);
            g.setStroke(new BasicStroke(gridMajorStroke));
            int gridTop = plotY;
            int gridBottom = hasRSIToDraw ? (rsiY + rsiH) : (plotY + plotH);
            for (int i = 0; i < n; i += xEvery) {
                double x = xToPixel(i + 0.5, n, plotX, plotW);
                g.drawLine((int) x, gridTop, (int) x, gridBottom);
            }
            g.setStroke(oldStroke);

            // ---- X tick marks + labels
            g.setColor(Color.DARK_GRAY);
            int labelBaseY = hasRSIToDraw ? (rsiY + rsiH + 28) : (plotY + plotH + 28);
            for (int i = 0; i < n; i += labelEvery) {
                double x = xToPixel(i + 0.5, n, plotX, plotW);
                int tickTop = hasRSIToDraw ? (rsiY + rsiH) : (plotY + plotH);
                g.drawLine((int) x, tickTop, (int) x, tickTop + 6);
                String label = candles.get(i).candleId;
                drawCentered(g, label, (int) x, labelBaseY);
            }

            // ---- Candles (wicks + body)
            Color green = new Color(76, 175, 80);
            Color red   = new Color(223, 81, 76);

            for (int i = 0; i < n; i++) {
                Candle c = candles.get(i);
                double cx = i + 0.5;
                double xMid = xToPixel(cx, n, plotX, plotW);

                // Wick
                g.setColor(c.candleType == CandleType.Green ? green : red);
                int yLow  = yToPixel(c.low,  yMinPlot, yMaxPlot, plotY, plotH);
                int yHigh = yToPixel(c.high, yMinPlot, yMaxPlot, plotY, plotH);
                g.drawLine((int) xMid, yLow, (int) xMid, yHigh);

                // Body
                double top = Math.max(c.open, c.close);
                double bottom = Math.min(c.open, c.close);
                int yTop = yToPixel(top,    yMinPlot, yMaxPlot, plotY, plotH);
                int yBot = yToPixel(bottom, yMinPlot, yMaxPlot, plotY, plotH);

                int bodyW = Math.max(2, (int) Math.round(plotW / (double) n * 0.6));
                int xLeft = (int) xMid - bodyW / 2;
                int hBody = Math.max(1, yBot - yTop);

                g.fillRect(xLeft, yTop, bodyW, hBody);
            }

            // ---- Moving averages
            Color m60 = new Color(0, 165, 83);
            Color m30 = new Color(255, 0, 0);
            Color m10 = new Color(233, 8, 140);
            Color m5  = new Color(0, 175, 237);
            Color m3  = new Color(50, 50, 50);

            drawLineSeries(g, candles, n, plotX, plotW, plotY, plotH, yMinPlot, yMaxPlot, c -> c.mavg3,  2f, m3);
            drawLineSeries(g, candles, n, plotX, plotW, plotY, plotH, yMinPlot, yMaxPlot, c -> c.mavg5,  2f, m5);
            drawLineSeries(g, candles, n, plotX, plotW, plotY, plotH, yMinPlot, yMaxPlot, c -> c.mavg10, 2f, m10);
            drawLineSeries(g, candles, n, plotX, plotW, plotY, plotH, yMinPlot, yMaxPlot, c -> c.mavg60, 2f, m60);
            drawLineSeries(g, candles, n, plotX, plotW, plotY, plotH, yMinPlot, yMaxPlot, c -> c.mavg30, 2f, m30);

            // ---- Waves (30)
            g.setColor(m30);
            for (Wave w : waves30) {
                int idx = indexOfTimestamp(candles, w.timestamp);
                if (idx >= 0) {
                    double cx = idx + 0.5;
                    int x = (int) xToPixel(cx, n, plotX, plotW);
                    int y = yToPixel(w.price, yMinPlot, yMaxPlot, plotY, plotH);
                    fillCircle(g, x, y, 6);
                }
                int recIdx = indexOfTimestamp(candles, w.recordTimestamp);
                if (recIdx >= 0) {
                    double cx = recIdx + 0.5;
                    int x = (int) xToPixel(cx, n, plotX, plotW);
                    int y = yToPixel(candles.get(recIdx).mavg30, yMinPlot, yMaxPlot, plotY, plotH);
                    g.setColor(Color.BLACK);
                    fillCircle(g, x, y, 6);
                    g.setColor(m30);
                }
            }

            // ---- Waves (60)
            g.setColor(m60);
            for (Wave w : waves60) {
                int idx = indexOfTimestamp(candles, w.timestamp);
                if (idx >= 0) {
                    double cx = idx + 0.5;
                    int x = (int) xToPixel(cx, n, plotX, plotW);
                    int y = yToPixel(w.price, yMinPlot, yMaxPlot, plotY, plotH);
                    fillCircle(g, x, y, 6);
                }
                int recIdx = indexOfTimestamp(candles, w.recordTimestamp);
                if (recIdx >= 0) {
                    double cx = recIdx + 0.5;
                    int x = (int) xToPixel(cx, n, plotX, plotW);
                    int y = yToPixel(candles.get(recIdx).mavg60, yMinPlot, yMaxPlot, plotY, plotH);
                    g.setColor(Color.BLACK);
                    fillCircle(g, x, y, 6);
                    g.setColor(m60);
                }
            }

            // ---- Bollinger (lines + filled band)
            List<Double> bbM = new ArrayList<>(n);
            List<Double> bbU = new ArrayList<>(n);
            List<Double> bbL = new ArrayList<>(n);
            for (Candle c : candles) {
                bbM.add(c.bollinger.middleBand);
                bbU.add(c.bollinger.upperBand);
                bbL.add(c.bollinger.lowerBand);
            }

            g.setColor(Color.BLACK);
            drawSimpleLine(g, bbM, n, plotX, plotW, plotY, plotH, yMinPlot, yMaxPlot, 2f, Color.BLACK);
            drawSimpleLine(g, bbU, n, plotX, plotW, plotY, plotH, yMinPlot, yMaxPlot, 2f, Color.BLACK);
            drawSimpleLine(g, bbL, n, plotX, plotW, plotY, plotH, yMinPlot, yMaxPlot, 2f, Color.BLACK);

            // Fill band (upper -> lower reversed)
            if (bbU.size() == bbL.size() && !bbU.isEmpty()) {
                Composite old = g.getComposite();
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.2f));
                g.setColor(Color.BLACK);

                Path2D poly = new Path2D.Double();
                boolean started = false;
                for (int i = 0; i < n; i++) {
                    double x = xToPixel(i + 0.5, n, plotX, plotW);
                    int y = yToPixel(bbU.get(i), yMinPlot, yMaxPlot, plotY, plotH);
                    if (!started) { poly.moveTo(x, y); started = true; }
                    else poly.lineTo(x, y);
                }
                for (int i = n - 1; i >= 0; i--) {
                    double x = xToPixel(i + 0.5, n, plotX, plotW);
                    int y = yToPixel(bbL.get(i), yMinPlot, yMaxPlot, plotY, plotH);
                    poly.lineTo(x, y);
                }
                poly.closePath();
                g.fill(poly);
                g.setComposite(old);
            }

            // ---- Legend (top-left of candle panel)
            int lx = plotX + 20;
            int ly = plotY + 20;
            drawLegendEntry(g, lx, ly, m3,  "MA3");   ly += 28;
            drawLegendEntry(g, lx, ly, m5,  "MA5");   ly += 28;
            drawLegendEntry(g, lx, ly, m10, "MA10");  ly += 28;
            drawLegendEntry(g, lx, ly, m30, "MA30");  ly += 28;
            drawLegendEntry(g, lx, ly, m60, "MA60");  ly += 28;
            drawLegendEntry(g, lx, ly, Color.BLACK, "BB (M/U/L)"); ly += 28;

            // ================= RSI PANEL =================
            if (hasRSIToDraw) {
                // Border
                g.setColor(axesBorderColor);
                g.drawRect(plotX, rsiY, plotW, rsiH);
            
                // --- Horizontal grid every 5 (0..100)
                Stroke oldStroke2 = g.getStroke();
                for (int v = 0; v <= 100; v += 5) {
                    int yLine = rsiToPixel(v, rsiY, rsiH);
                    boolean isMajor = (v % 10 == 0);
                
                    // g.setStroke(new BasicStroke(isMajor ? rsiGridMajorStroke : rsiGridMinorStroke));
                    // g.setColor(isMajor ? rsiGridMajorColor : rsiGridMinorColor);
                    // g.drawLine(plotX, yLine, plotX + plotW, yLine);

                    if (v == 50) {
                        g.setColor(new Color(150, 150, 150));
                        g.setStroke(new BasicStroke(rsiGridMajorStroke*1.5f));

                    }else if (v == 20 || v == 80) {
                        g.setColor(new Color(220, 0, 0));
                        g.setStroke(new BasicStroke(rsiGridMajorStroke));
                    }else {
                        g.setColor(new Color(230, 230, 230));
                        g.setStroke(new BasicStroke(rsiGridMinorStroke));
                    }
                    g.drawLine(plotX, yLine, plotX + plotW, yLine);

                    // label the majors (every 10%) to avoid clutter
                    if (isMajor) {
                        g.setColor(Color.DARK_GRAY);
                        g.setFont(g.getFont().deriveFont(Font.PLAIN, 16f));
                        String lbl = String.valueOf(v);
                        g.drawString(lbl, 10, yLine + 5); // left margin labels
                    }
                }
                g.setStroke(oldStroke2);
            
                // Emphasize the classic 30/70 levels over the grid (optional)
                int rsi30y = rsiToPixel(0, rsiY, rsiH);
                int rsi70y = rsiToPixel(100, rsiY, rsiH);
                oldStroke = g.getStroke();
                g.setStroke(new BasicStroke(rsiGridMajorStroke * 2));
                g.setColor(new Color(100,100,100));
                g.drawLine(plotX, rsi30y, plotX + plotW, rsi30y);
                g.drawLine(plotX, rsi70y, plotX + plotW, rsi70y);
                g.setStroke(oldStroke);
            
                // RSI line (aligned to candle indices)
                List<Double> rsiSeriesAligned = rsiAlignedToCandles(n);
                drawSimpleLine(g, rsiSeriesAligned, n, plotX, plotW, rsiY, rsiH, 0.0, 100.0, rsiLineStroke, rsiLineColor);
            
                // RSI legend
                drawLegendEntry(g, plotX + 20, rsiY + 24, rsiLineColor, "RSI(" + rsiPeriod + ")");
            }

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
                if (price < last.low)  last.low  = price;

                last.close = price;
                last.totalLength = last.high - last.low;
                last.candleType = last.close >= last.open ? CandleType.Green : CandleType.Red;
                last.vix = last.high - last.low;
                last.completePercent = completePercent;
                last.tickCount += 1;

                double body = Math.abs(last.close - last.open);
                last.volatility = body + 0.5 * last.vix;
            } else {
                // New candle is starting => previous candle just CLOSED.
                // If RSI enabled, compute RSI on the previous candle CLOSE and align to its index.
                if (rsiEnabled) maybeAddRsiOnCandleClose();

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

        // Ensure RSI vector stays aligned: append a placeholder for the NEW (open) candle.
        if (rsiEnabled) {
            // We only compute RSI for closed candles. The newly opened candle has no RSI yet.
            // So keep rsiValues size == candles size by adding a null placeholder for this candle.
            while (rsiValues.size() < candles.size()) {
                rsiValues.add(null);
            }
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

    // ===== RSI helpers =====

    /** Enable RSI computation and drawing. Safe to call at construction. */
    public void enableRSI(int period) {
        this.rsiEnabled = true;
        this.rsiPeriod = Math.max(2, period);
        // keep existing state if re-enabled; call resetRSI() first if needed
    }

    /** Optional: clear RSI state if you want to restart computation. */
    public void resetRSI() {
        rsiPrevClose = null;
        rsiAvgGain = 0.0;
        rsiAvgLoss = 0.0;
        rsiInitCount = 0;
        rsiInitialized = false;
        rsiValues.clear();
        // Pre-fill to current candles size with nulls to keep alignment
        for (int i = 0; i < candles.size(); i++) rsiValues.add(null);
    }

    /** Called exactly when a new candle starts -> previous candle is closed. */
    private void maybeAddRsiOnCandleClose() {
        if (!rsiEnabled || candles.isEmpty()) return;

        Candle prev = candles.get(candles.size() - 1);
        double close = prev.close;

        // First close initializes prevClose and produces a null RSI (no diff yet)
        if (rsiPrevClose == null) {
            rsiPrevClose = close;
            ensureRsiAlignmentForClosedIndex(candles.size() - 1, null);
            return;
        }

        double change = close - rsiPrevClose;
        double gain = Math.max(change, 0.0);
        double loss = Math.max(-change, 0.0);

        if (!rsiInitialized) {
            // Accumulate initial period-1 diffs; on reaching period, emit first RSI
            rsiAvgGain += gain;
            rsiAvgLoss += loss;
            rsiInitCount += 1;

            if (rsiInitCount >= rsiPeriod) {
                rsiAvgGain /= rsiPeriod;
                rsiAvgLoss /= rsiPeriod;
                rsiInitialized = true;
                double rsi = computeCurrentRsi(rsiAvgGain, rsiAvgLoss);
                ensureRsiAlignmentForClosedIndex(candles.size() - 1, rsi);
            } else {
                ensureRsiAlignmentForClosedIndex(candles.size() - 1, null);
            }
        } else {
            // Wilder smoothing
            rsiAvgGain = ((rsiAvgGain * (rsiPeriod - 1)) + gain) / rsiPeriod;
            rsiAvgLoss = ((rsiAvgLoss * (rsiPeriod - 1)) + loss) / rsiPeriod;
            double rsi = computeCurrentRsi(rsiAvgGain, rsiAvgLoss);
            ensureRsiAlignmentForClosedIndex(candles.size() - 1, rsi);
        }

        rsiPrevClose = close;
    }

    private static double computeCurrentRsi(double avgGain, double avgLoss) {
        if (avgLoss == 0.0) return 100.0;
        double rs = avgGain / avgLoss;
        return 100.0 - (100.0 / (1.0 + rs));
    }

    /** Ensure rsiValues[iClosed] is set, expanding/null-filling as needed to align with candles. */
    private void ensureRsiAlignmentForClosedIndex(int closedIndex, Double value) {
        while (rsiValues.size() < candles.size()) rsiValues.add(null);
        // closedIndex should be within bounds (last candle before new one was appended)
        if (closedIndex >= 0 && closedIndex < rsiValues.size()) {
            rsiValues.set(closedIndex, value);
        }
    }

    /** Build a series length=n (candles) with RSI values/nulls aligned to indices. */
    private List<Double> rsiAlignedToCandles(int n) {
        if (!rsiEnabled) return Collections.nCopies(n, null);
        List<Double> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Double v = (i < rsiValues.size()) ? rsiValues.get(i) : null;
            out.add(v);
        }
        return out;
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

    private static int rsiToPixel(double value, int plotY, int plotH) {
        // RSI is 0..100
        double norm = value / 100.0;
        return plotY + (int) Math.round(plotH - norm * plotH);
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
            double minVal, double maxVal,
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
            int y = (maxVal == minVal)
                    ? plotY + plotH / 2
                    : (int) Math.round(plotY + plotH - ((v - minVal) / (maxVal - minVal)) * plotH);
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

    public void setCandleTimeFrameSeconds(long seconds) {
        this.candleTimeFrameMs = seconds * 1000L;
    }

    public void setTargetYTicks(int targetYTicks) {
        this.targetYTicks = Math.max(2, targetYTicks);
    }

    public void setYAxisPaddingFraction(double fraction) {
        this.yPadFractionOfMid = Math.max(0.0, fraction);
    }

    /** How many minor lines to draw BETWEEN major Y ticks (default 9 => 10 slices). */
    public void setYMinorTicksBetweenMajors(int count) {
        this.yMinorBetweenMajors = Math.max(0, count);
    }

    /** Force vertical X-grid every K candles. If null/<=0, uses the label cadence. */
    public void setXGridEveryCandles(Integer k) {
        this.xGridEveryCandles = (k != null && k > 0) ? k : null;
    }

    /** Customize grid colors & thickness. */
    public void setGridStyle(Color majorColor, float majorStrokeWidth,
                             Color minorColor, float minorStrokeWidth,
                             Color borderColor) {
        if (majorColor != null) this.gridMajorColor = majorColor;
        if (minorColor != null) this.gridMinorColor = minorColor;
        if (borderColor != null) this.axesBorderColor = borderColor;
        if (majorStrokeWidth > 0) this.gridMajorStroke = majorStrokeWidth;
        if (minorStrokeWidth > 0) this.gridMinorStroke = minorStrokeWidth;
    }

    /** Customize RSI line & levels. */
    public void setRsiStyle(Color lineColor, float lineStrokeWidth, Color levelColor) {
        if (lineColor != null) this.rsiLineColor = lineColor;
        if (lineStrokeWidth > 0) this.rsiLineStroke = lineStrokeWidth;
        if (levelColor != null) this.rsiLevelColor = levelColor;
    }

    // ===== “Nice” axis helper =====

    // Produces round min/max and tick spacing like 60,70,80 or 100,120,140...
    private static final class NiceScale {
        final double niceMin, niceMax, tickSpacing;
        final int ticks;

        NiceScale(double dataMin, double dataMax, int targetTicks) {
            if (dataMax < dataMin) { double t = dataMin; dataMin = dataMax; dataMax = t; }
            double range = dataMax - dataMin;
            if (range == 0) { range = Math.abs(dataMax) > 0 ? Math.abs(dataMax) * 0.1 : 1.0; }
            double rawStep = range / Math.max(2, targetTicks - 1);
            double step = niceNum(rawStep, true);
            double nMin = Math.floor(dataMin / step) * step;
            double nMax = Math.ceil (dataMax / step) * step;
            this.niceMin = nMin;
            this.niceMax = nMax;
            this.tickSpacing = step;
            this.ticks = (int)Math.round((nMax - nMin) / step);
        }

        private static double niceNum(double x, boolean round) {
            double exp = Math.floor(Math.log10(x));
            double f = x / Math.pow(10.0, exp);
            double nf;
            if (round) {
                if (f < 1.5)      nf = 1.0;
                else if (f < 3.0) nf = 2.0;
                else if (f < 7.0) nf = 5.0;
                else              nf = 10.0;
            } else {
                if (f <= 1.0)     nf = 1.0;
                else if (f <= 2.) nf = 2.0;
                else if (f <= 5.) nf = 5.0;
                else              nf = 10.0;
            }
            return nf * Math.pow(10.0, exp);
        }
    }

    private static String formatTickLabel(double value, double step) {
        if (step >= 1) return String.format(Locale.ENGLISH, "%.0f", value);
        int decimals = Math.max(0, (int)Math.ceil(-Math.log10(step)) + 1);
        return String.format(Locale.ENGLISH, "%." + decimals + "f", value);
    }

    // ===== Example Spring Boot wiring =====
    // @Service
    // public static class CandleService {
    //     private final CandleGraphTracker tracker = new CandleGraphTracker(1, "NIFTY", 60L, 14);
    //     public void onTick(long epochMs, double price) { tracker.addMarketData(epochMs, price); }
    //     public void render(String dir) throws IOException { tracker.drawCandleGraph(dir); }
    // }
}
