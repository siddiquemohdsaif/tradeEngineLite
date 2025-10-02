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
 * - Call drawCandleGraph(outputDir) to render a large PNG/BMP and save a JSON
 * with volatility info.
 * - Polished axes:
 * * Y-axis uses “nice” round ticks (1–2–5 × 10^k) and adds N minor lines
 * between majors (default 9).
 * * X-axis draws vertical grid lines at each labeled tick (or a custom
 * every-k-candles cadence).
 * - Optional RSI:
 * * Enable via constructor overloading with rsiPeriod.
 * * RSI computed from candle CLOSES, appended when a new candle starts (i.e.,
 * previous candle closes).
 * * If enabled, renders an RSI panel below the candles, sharing the same
 * timeline.
 *
 * - Moving Averages (NEW):
 * * Configurable periods and type (SIMPLE/EXPONENTIAL).
 * * Defaults to SIMPLE on [60, 30, 20, 5, 3].
 * * Waves are computed on the two LARGEST configured MA periods.
 */
public class CandleGraphTracker {

    // ===== Models =====

    public static final class CandleVolatilityInfo {
        @JsonProperty
        public long timestamp;
        @JsonProperty
        public String candleId;
        @JsonProperty
        public double volatility;
        @JsonProperty
        public double volatilityIndex;

        public CandleVolatilityInfo(long timestamp, String candleId, double volatility, double volatilityIndex) {
            this.timestamp = timestamp;
            this.candleId = candleId;
            this.volatility = volatility;
            this.volatilityIndex = volatilityIndex;
        }
    }

    public static final class MarketPoint {
        public long time;
        public double price;

        public MarketPoint(long time, double price) {
            this.time = time;
            this.price = price;
        }
    }

    public enum CandleType {
        Green, Red
    }

    public enum WaveType {
        Crest, Trough
    }

    public static final class Wave {
        public WaveType waveType;
        public long timestamp;
        public double price;
        public long recordTimestamp;

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

        public BollingerBands() {
        }

        public BollingerBands(double middleBand, double upperBand, double lowerBand) {
            this.middleBand = middleBand;
            this.upperBand = upperBand;
            this.lowerBand = lowerBand;
        }
    }

    public static final class Candle {
        public int tickCount;
        public long timestamp;
        public String candleId;
        public double high, low, open, close;
        public CandleType candleType;
        public double vix, totalLength, completePercent, volatility, volatilityIndex;
        public BollingerBands bollinger = new BollingerBands(0, 0, 0);
        public double[] maValues;

        public Candle(int tickCount, long timestamp, String candleId, double price,
                double completePercent, double volatilityIndex, double[] maSeed) {
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
            this.volatility = 0.0;
            this.volatilityIndex = volatilityIndex;
            this.maValues = (maSeed == null) ? null : Arrays.copyOf(maSeed, maSeed.length);
        }
    }

    // ===== Tracker fields =====
    public int id;
    public String tradingsymbol;
    public long candleTimeFrameMs = 900_000; // 15m default
    public final List<Candle> candles = new ArrayList<>();
    public final List<MarketPoint> marketGraph = new ArrayList<>();

    // === MA config
    public enum MAType {
        SIMPLE, EXPONENTIAL
    }

    private int[] maPeriods = new int[] { 60, 30, 10, 5, 3 };
    private MAType maType = MAType.SIMPLE;
    private int wavePrimaryIdx = 0, waveSecondaryIdx = 1;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter ID_FMT = DateTimeFormatter.ofPattern("hh:mma").withLocale(Locale.ENGLISH);

    // Axes/grid knobs
    private int targetYTicks = 10;
    private int yMinorBetweenMajors = 9;
    private double yPadFractionOfMid = 0.10;
    private Integer xGridEveryCandles = null;
    private Color gridMajorColor = new Color(200, 200, 200);
    private Color gridMinorColor = new Color(230, 230, 230);
    private Color axesBorderColor = new Color(220, 220, 220);
    private float gridMajorStroke = 1.0f;
    private float gridMinorStroke = 1.0f;

    // Day separator
    private Color daySeparatorColor = new Color(200, 200, 200);
    private float daySeparatorStroke = 1.5f;
    private Font daySeparatorFont = new Font("SansSerif", Font.BOLD, 20);
    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("dd-MM-yy").withLocale(Locale.ENGLISH);

    // ===== RSI state (moved to RsiUtils) // >>> CHANGED
    private boolean rsiEnabled = false;
    private int rsiPeriod = 14;
    private Color rsiLineColor = new Color(33, 150, 243);
    private Color rsiLevelColor = new Color(200, 200, 200);
    private float rsiLineStroke = 2.0f;

    // RSI grid knobs
    private boolean rsiShow5PctGrid = true;
    private Color rsiGridMajorColor = new Color(210, 210, 210);
    private Color rsiGridMinorColor = new Color(235, 235, 235);
    private float rsiGridMajorStroke = 1.25f;
    private float rsiGridMinorStroke = 1.0f;

    // NEW: externalized RSI engine + divergences
    private RsiUtils.RsiState rsiState = null; // >>> NEW
    private boolean rsiDivergenceEnabled = false; // >>> NEW
    private List<RsiUtils.Divergence> rsiDivergences = new ArrayList<>(); // >>> NEW

    // Waves
    private final List<Wave> wavesPrimary = new ArrayList<>();
    private final List<Wave> wavesSecondary = new ArrayList<>();

    // MA colors
    private Color[] MA_PALETTE = new Color[] {
            new Color(0, 165, 83), new Color(255, 0, 0), new Color(233, 8, 140), new Color(0, 175, 237),
            new Color(50, 50, 50), new Color(128, 0, 128), new Color(255, 165, 0), new Color(0, 128, 255)
    };

    public CandleGraphTracker(int id, String tradingsymbol) {
        this.id = id;
        this.tradingsymbol = tradingsymbol;
        recomputeWaveIndices();
    }

    public CandleGraphTracker(int id, String tradingsymbol, long candleTimeFrameSeconds) {
        this(id, tradingsymbol);
        this.candleTimeFrameMs = candleTimeFrameSeconds * 1000L;
    }

    public CandleGraphTracker(int id, String tradingsymbol, long candleTimeFrameSeconds,
            int[] maPeriods, MAType maType) {
        this(id, tradingsymbol, candleTimeFrameSeconds);
        setMovingAverageConfig(maPeriods, maType);
    }

    // ===== Public API =====
    public void setMovingAverageConfig(int[] periods, MAType type) {
        if (periods == null || periods.length == 0)
            throw new IllegalArgumentException("maPeriods must have at least 1");
        for (int p : periods)
            if (p <= 0)
                throw new IllegalArgumentException("MA period must be > 0");
        this.maPeriods = Arrays.copyOf(periods, periods.length);
        this.maType = (type == null) ? MAType.SIMPLE : type;
        recomputeWaveIndices();
        for (Candle c : candles) {
            if (c.maValues == null || c.maValues.length != maPeriods.length) {
                double[] seed = new double[maPeriods.length];
                Arrays.fill(seed, c.close);
                c.maValues = seed;
            }
        }
    }

    public void addMarketData(long timeMs, double price) {
        marketGraph.add(new MarketPoint(timeMs, price));
        updateCandles(timeMs, price);
        updateWavesAndBands();
    }

    public Double getRSILatest() {
        if (!rsiEnabled || rsiState == null)
            return null; // >>> CHANGED
        List<Double> v = rsiState.getValues();
        if (v.size() < 2)
            return null;
        return v.get(v.size() - 1); // last closed candle's RSI
    }

    public void drawCandleGraph(String outputDir) throws IOException {
        if (!outputDir.endsWith(File.separator))
            outputDir += File.separator;
        String baseName = this.id + "_" + this.tradingsymbol;

        saveVolatilityIndex(outputDir + baseName + ".json");

        final int width = 2560 * 8;
        final int height = 1440 * 2;
        boolean hasRSIToDraw = rsiEnabled;

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

            // data bounds
            double dataMax = candles.stream().mapToDouble(c -> c.high).max().orElse(1d);
            double dataMin = candles.stream().mapToDouble(c -> c.low).min().orElse(0d);
            if (dataMax == dataMin) {
                dataMax += 1.0;
                dataMin -= 1.0;
            }

            double padAbs = (dataMax - dataMin) * yPadFractionOfMid;
            double range = dataMax - dataMin;
            if (padAbs < range * 0.05)
                padAbs = range * 0.05;
            double paddedMin = dataMin - padAbs;
            double paddedMax = dataMax + padAbs;

            NiceScale yscale = new NiceScale(paddedMin, paddedMax, targetYTicks);
            double yMinPlot = yscale.niceMin, yMaxPlot = yscale.niceMax, yStep = yscale.tickSpacing;

            int marginLeft = 120, marginRight = 40, marginTop = 80, marginBottom = 140;
            int gapBetweenPanels = hasRSIToDraw ? 40 : 0;
            int rsiPanelHeight = hasRSIToDraw ? Math.min(640, (int) (height * 0.30)) : 0;

            int plotX = marginLeft, plotW = width - marginLeft - marginRight;
            int candleAreaTop = marginTop;
            int candleAreaHeight = height - marginTop - marginBottom - rsiPanelHeight - gapBetweenPanels;
            int plotY = candleAreaTop, plotH = candleAreaHeight;
            int rsiY = plotY + plotH + gapBetweenPanels, rsiH = rsiPanelHeight;

            // Title
            g.setColor(Color.BLACK);
            g.setFont(g.getFont().deriveFont(Font.BOLD, 48f));
            g.drawString("Candle Graph", marginLeft, 60);

            // Axes border (candles)
            g.setColor(axesBorderColor);
            g.drawRect(plotX, plotY, plotW, plotH);

            // Y major grid + labels
            g.setFont(g.getFont().deriveFont(Font.PLAIN, 20f));
            int majorCount = (int) Math.round((yMaxPlot - yMinPlot) / yStep);
            Stroke oldStroke = g.getStroke();

            g.setStroke(new BasicStroke(gridMajorStroke));
            for (int k = 0; k <= majorCount; k++) {
                double value = yMinPlot + k * yStep;
                int y = yToPixel(value, yMinPlot, yMaxPlot, plotY, plotH);
                g.setColor(gridMajorColor);
                g.drawLine(plotX, y, plotX + plotW, y);
                g.setColor(Color.DARK_GRAY);
                String label = formatTickLabel(value, yStep);
                g.drawString(label, 10, y + 6);
            }
            if (yMinorBetweenMajors > 0) {
                g.setStroke(new BasicStroke(gridMinorStroke));
                g.setColor(gridMinorColor);
                for (int k = 0; k < majorCount; k++) {
                    double base = yMinPlot + k * yStep;
                    for (int j = 1; j <= yMinorBetweenMajors; j++) {
                        double v = base + (yStep * (j / (double) (yMinorBetweenMajors + 1)));
                        int y = yToPixel(v, yMinPlot, yMaxPlot, plotY, plotH);
                        g.drawLine(plotX, y, plotX + plotW, y);
                    }
                }
            }
            g.setStroke(oldStroke);

            int n = candles.size();
            int labelEvery = Math.max(1, n / 30);

            // X vertical grid across both panels
            g.setColor(gridMajorColor);
            g.setStroke(new BasicStroke(gridMajorStroke));
            int gridTop = plotY;
            int gridBottom = hasRSIToDraw ? (rsiY + rsiH) : (plotY + plotH);
            int xEvery = (xGridEveryCandles != null && xGridEveryCandles > 0) ? xGridEveryCandles : labelEvery;
            for (int i = 0; i < n; i += xEvery) {
                double x = xToPixel(i + 0.5, n, plotX, plotW);
                g.drawLine((int) x, gridTop, (int) x, gridBottom);
            }
            g.setStroke(oldStroke);

            // Day separators + labels
            List<Integer> dayStarts = new ArrayList<>();
            LocalDate lastDate = null;
            for (int i = 0; i < n; i++) {
                long ts = candles.get(i).timestamp;
                LocalDate d = Instant.ofEpochMilli(ts).atZone(IST).toLocalDate();
                if (lastDate == null || !d.equals(lastDate)) {
                    dayStarts.add(i);
                    lastDate = d;
                }
            }
            if (dayStarts.size() >= 2) {
                Stroke prevStroke = g.getStroke();
                Font prevFont = g.getFont();
                g.setStroke(new BasicStroke(daySeparatorStroke));
                g.setFont(daySeparatorFont);
                g.setColor(daySeparatorColor);

                for (int idx : dayStarts) {
                    double x = xToPixel(idx + 0.5, n, plotX, plotW);
                    g.drawLine((int) x, gridTop, (int) x, gridBottom);
                    String dayLabel = DAY_FMT.format(Instant.ofEpochMilli(candles.get(idx).timestamp).atZone(IST));
                    int labelY = (hasRSIToDraw ? (rsiY + rsiH + 28) : (plotY + plotH + 28)) + 22;
                    g.setColor(Color.DARK_GRAY);
                    drawCentered(g, dayLabel, (int) x, labelY);
                    g.setColor(daySeparatorColor);
                }
                g.setStroke(prevStroke);
                g.setFont(prevFont);
            }

            if (candleTimeFrameMs < 86400_000) {
                g.setColor(Color.DARK_GRAY);
                int labelBaseY = hasRSIToDraw ? (rsiY + rsiH + 28) : (plotY + plotH + 28);
                for (int i = 0; i < n; i += labelEvery) {
                    double x = xToPixel(i + 0.5, n, plotX, plotW);
                    int tickTop = hasRSIToDraw ? (rsiY + rsiH) : (plotY + plotH);
                    g.drawLine((int) x, tickTop, (int) x, tickTop + 6);
                    String label = candles.get(i).candleId;
                    drawCentered(g, label, (int) x, labelBaseY);
                }
            }

            // Candles
            Color green = new Color(76, 175, 80);
            Color red = new Color(223, 81, 76);
            for (int i = 0; i < n; i++) {
                Candle c = candles.get(i);
                double xMid = xToPixel(i + 0.5, n, plotX, plotW);

                g.setColor(c.candleType == CandleType.Green ? green : red);
                int yLow = yToPixel(c.low, yMinPlot, yMaxPlot, plotY, plotH);
                int yHigh = yToPixel(c.high, yMinPlot, yMaxPlot, plotY, plotH);
                g.drawLine((int) xMid, yLow, (int) xMid, yHigh);

                double top = Math.max(c.open, c.close);
                double bottom = Math.min(c.open, c.close);
                int yTop = yToPixel(top, yMinPlot, yMaxPlot, plotY, plotH);
                int yBot = yToPixel(bottom, yMinPlot, yMaxPlot, plotY, plotH);

                int bodyW = Math.max(2, (int) Math.round(plotW / (double) n * 0.6));
                int xLeft = (int) xMid - bodyW / 2;
                int hBody = Math.max(1, yBot - yTop);
                g.fillRect(xLeft, yTop, bodyW, hBody);
            }

            // MAs
            for (int mi = 0; mi < maPeriods.length; mi++) {
                final int idx = mi;
                Color color = MA_PALETTE[mi % MA_PALETTE.length];
                drawLineSeries(g, candles, n, plotX, plotW, plotY, plotH, yMinPlot, yMaxPlot,
                        c -> (c.maValues != null && idx < c.maValues.length) ? c.maValues[idx] : Double.NaN,
                        2f, color);
            }

            // Waves
            if (maPeriods.length >= 2) {
                Color waveColorP = MA_PALETTE[wavePrimaryIdx % MA_PALETTE.length];
                g.setColor(waveColorP);
                for (Wave w : wavesPrimary) {
                    int idx = indexOfTimestamp(candles, w.timestamp);
                    if (idx >= 0) {
                        int x = (int) xToPixel(idx + 0.5, n, plotX, plotW);
                        int y = yToPixel(w.price, yMinPlot, yMaxPlot, plotY, plotH);
                        fillCircle(g, x, y, 6);
                    }
                    int recIdx = indexOfTimestamp(candles, w.recordTimestamp);
                    if (recIdx >= 0) {
                        int x = (int) xToPixel(recIdx + 0.5, n, plotX, plotW);
                        int y = yToPixel(candles.get(recIdx).maValues[wavePrimaryIdx], yMinPlot, yMaxPlot, plotY,
                                plotH);
                        g.setColor(Color.BLACK);
                        fillCircle(g, x, y, 6);
                        g.setColor(waveColorP);
                    }
                }
                Color waveColorS = MA_PALETTE[waveSecondaryIdx % MA_PALETTE.length];
                g.setColor(waveColorS);
                for (Wave w : wavesSecondary) {
                    int idx = indexOfTimestamp(candles, w.timestamp);
                    if (idx >= 0) {
                        int x = (int) xToPixel(idx + 0.5, n, plotX, plotW);
                        int y = yToPixel(w.price, yMinPlot, yMaxPlot, plotY, plotH);
                        fillCircle(g, x, y, 6);
                    }
                    int recIdx = indexOfTimestamp(candles, w.recordTimestamp);
                    if (recIdx >= 0) {
                        int x = (int) xToPixel(recIdx + 0.5, n, plotX, plotW);
                        int y = yToPixel(candles.get(recIdx).maValues[waveSecondaryIdx], yMinPlot, yMaxPlot, plotY,
                                plotH);
                        g.setColor(Color.BLACK);
                        fillCircle(g, x, y, 6);
                        g.setColor(waveColorS);
                    }
                }
            }

            // Bollinger
            List<Double> bbM = new ArrayList<>(n), bbU = new ArrayList<>(n), bbL = new ArrayList<>(n);
            for (Candle c : candles) {
                bbM.add(c.bollinger.middleBand);
                bbU.add(c.bollinger.upperBand);
                bbL.add(c.bollinger.lowerBand);
            }
            g.setColor(Color.BLACK);
            drawSimpleLine(g, bbM, n, plotX, plotW, plotY, plotH, yMinPlot, yMaxPlot, 2f, Color.BLACK);
            drawSimpleLine(g, bbU, n, plotX, plotW, plotY, plotH, yMinPlot, yMaxPlot, 2f, Color.BLACK);
            drawSimpleLine(g, bbL, n, plotX, plotW, plotY, plotH, yMinPlot, yMaxPlot, 2f, Color.BLACK);
            if (!bbU.isEmpty()) {
                Composite old = g.getComposite();
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.2f));
                g.setColor(Color.BLACK);
                Path2D poly = new Path2D.Double();
                boolean started = false;
                for (int i = 0; i < n; i++) {
                    double x = xToPixel(i + 0.5, n, plotX, plotW);
                    int y = yToPixel(bbU.get(i), yMinPlot, yMaxPlot, plotY, plotH);
                    if (!started) {
                        poly.moveTo(x, y);
                        started = true;
                    } else
                        poly.lineTo(x, y);
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

            // Legend
            int lx = plotX + 20, ly = plotY + 20;
            for (int mi = 0; mi < maPeriods.length; mi++) {
                Color color = MA_PALETTE[mi % MA_PALETTE.length];
                String label = (maType == MAType.EXPONENTIAL ? "EMA" : "SMA") + maPeriods[mi];
                drawLegendEntry(g, lx, ly, color, label);
                ly += 28;
            }
            drawLegendEntry(g, lx, ly, Color.BLACK, "BB (M/U/L)");
            ly += 28;

            // ================= RSI PANEL =================
            if (hasRSIToDraw) {
                // Border
                g.setColor(axesBorderColor);
                g.drawRect(plotX, rsiY, plotW, rsiH);

                // Grid lines (0..100)
                Stroke oldStroke2 = g.getStroke();
                for (int v = 0; v <= 100; v += 5) {
                    int yLine = rsiToPixel(v, rsiY, rsiH);
                    boolean isMajor = (v % 10 == 0);
                    if (v == 50) {
                        g.setColor(new Color(150, 150, 150));
                        g.setStroke(new BasicStroke(rsiGridMajorStroke * 1.5f));
                    } else if (v == 20 || v == 80) {
                        g.setColor(new Color(220, 0, 0));
                        g.setStroke(new BasicStroke(rsiGridMajorStroke));
                    } else {
                        g.setColor(new Color(230, 230, 230));
                        g.setStroke(new BasicStroke(rsiGridMinorStroke));
                    }
                    g.drawLine(plotX, yLine, plotX + plotW, yLine);
                    if (isMajor) {
                        g.setColor(Color.DARK_GRAY);
                        g.setFont(g.getFont().deriveFont(Font.PLAIN, 16f));
                        g.drawString(String.valueOf(v), 10, yLine + 5);
                    }
                }
                g.setStroke(oldStroke2);

                // 0 and 100 bold
                int rsi0y = rsiToPixel(0, rsiY, rsiH);
                int rsi100y = rsiToPixel(100, rsiY, rsiH);
                oldStroke = g.getStroke();
                g.setStroke(new BasicStroke(rsiGridMajorStroke * 2));
                g.setColor(new Color(100, 100, 100));
                g.drawLine(plotX, rsi0y, plotX + plotW, rsi0y);
                g.drawLine(plotX, rsi100y, plotX + plotW, rsi100y);
                g.setStroke(oldStroke);

                // RSI series
                List<Double> rsiSeries = (rsiState != null) ? rsiState.getValues() : Collections.emptyList(); // CLOSED
                                                                                                              // candles
                                                                                                              // only
                List<Double> rsiAligned = RsiUtils.alignToLength(rsiSeries, n);
                drawSimpleLine(g, rsiAligned, n, plotX, plotW, rsiY, rsiH, 0.0, 100.0, rsiLineStroke, rsiLineColor);

                // Label
                drawLegendEntry(g, plotX + 20, rsiY + 24, rsiLineColor, "RSI(" + rsiPeriod + ")");

                // Draw divergences (if enabled) // >>> NEW
                if (rsiDivergenceEnabled && !rsiDivergences.isEmpty()) {
                    RsiUtils.drawDivergencesOnRsi(g, rsiDivergences, rsiAligned, n, plotX, plotW, rsiY, rsiH);
                }
            }
        } finally {
            g.dispose();
        }

        File bmp = new File(outputDir + baseName + ".bmp");
        if (!writeImageWithFallback(bmp, img)) {
            writeImageWithFallback(new File(outputDir + baseName + ".png"), img);
        }
    }

    public void saveVolatilityIndex(String filePath) throws IOException {
        List<CandleVolatilityInfo> out = new ArrayList<>();
        for (Candle c : candles)
            out.add(new CandleVolatilityInfo(c.timestamp, c.candleId, c.volatility, c.volatilityIndex));
        ObjectMapper om = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        om.writeValue(new File(filePath), out);
    }

    // ===== Core logic =====
    private void updateCandles(long timeMs, double price) {
        long frame = candleTimeFrameMs;
        long candleTs = (timeMs / frame) * frame;
        String candleId = Instant.ofEpochMilli(candleTs).atZone(IST).format(ID_FMT).toLowerCase(Locale.ENGLISH);
        double completePercent = Math.round(((timeMs - candleTs) / (double) frame) * 100.0);

        if (!candles.isEmpty()) {
            Candle last = candles.get(candles.size() - 1);
            if (last.timestamp == candleTs) {
                // Update existing candle
                if (price > last.high)
                    last.high = price;
                if (price < last.low)
                    last.low = price;
                last.close = price;
                last.totalLength = last.high - last.low;
                last.candleType = last.close >= last.open ? CandleType.Green : CandleType.Red;
                last.vix = last.high - last.low;
                last.completePercent = completePercent;
                last.tickCount += 1;

                double body = Math.abs(last.close - last.open);
                last.volatility = body + 0.5 * last.vix;
            } else {
                // Previous candle closed
                if (rsiEnabled)
                    maybeAddRsiOnCandleClose(); // >>> CHANGED (delegates to RsiUtils)
                createNewCandle(candleTs, candleId, price, completePercent);
            }
        } else {
            createNewCandle(candleTs, candleId, price, completePercent);
        }

        computeMAForLastCandle();
    }

    private void createNewCandle(long candleTs, String candleId, double price, double completePercent) {
        int count = candles.size();
        double volIndex;
        if (count < 5)
            volIndex = 0.0;
        else {
            int n = Math.min(30, count);
            double sum = 0.0;
            for (int i = count - n; i < count; i++)
                sum += candles.get(i).volatility;
            volIndex = sum / n;
        }
        double[] seed;
        if (!candles.isEmpty() && candles.get(count - 1).maValues != null
                && candles.get(count - 1).maValues.length == maPeriods.length) {
            seed = Arrays.copyOf(candles.get(count - 1).maValues, maPeriods.length);
        } else {
            seed = new double[maPeriods.length];
            Arrays.fill(seed, price);
        }
        candles.add(new Candle(1, candleTs, candleId, price, completePercent, volIndex, seed));
    }

    private void updateWavesAndBands() {
        if (candles.size() < 5) {
            updateBollingerBands(20, 2.0);
            return;
        }
        if (maPeriods.length >= 2) {
            evaluateWaveForMA(wavePrimaryIdx, wavesPrimary);
            evaluateWaveForMA(waveSecondaryIdx, wavesSecondary);
        }
        updateBollingerBands(20, 2.0);
    }

    private void computeMAForLastCandle() {
        if (candles.isEmpty())
            return;
        int lastIdx = candles.size() - 1;
        Candle cur = candles.get(lastIdx);
        if (cur.maValues == null || cur.maValues.length != maPeriods.length) {
            cur.maValues = new double[maPeriods.length];
            Arrays.fill(cur.maValues, cur.close);
        }
        for (int i = 0; i < maPeriods.length; i++) {
            int period = maPeriods[i];
            if (maType == MAType.SIMPLE) {
                int n = Math.min(period, candles.size());
                double sum = 0.0;
                for (int k = candles.size() - n; k < candles.size(); k++)
                    sum += candles.get(k).close;
                cur.maValues[i] = sum / n;
            } else {
                double k = 2.0 / (period + 1.0);
                if (lastIdx == 0) {
                    cur.maValues[i] = cur.close;
                } else if (lastIdx < period - 1) {
                    int n = lastIdx + 1;
                    double sum = 0.0;
                    for (int kx = 0; kx <= lastIdx; kx++)
                        sum += candles.get(kx).close;
                    cur.maValues[i] = sum / n;
                } else if (lastIdx == period - 1) {
                    double sum = 0.0;
                    for (int kx = 0; kx < period; kx++)
                        sum += candles.get(kx).close;
                    cur.maValues[i] = sum / period;
                } else {
                    double prevEma = candles.get(lastIdx - 1).maValues[i];
                    cur.maValues[i] = (cur.close * k) + (prevEma * (1.0 - k));
                }
            }
        }
    }

    private void evaluateWaveForMA(int maIdx, List<Wave> waves) {
        int last = candles.size() - 1;
        double vLast = candles.get(last).maValues[maIdx];
        double vPrev5 = candles.get(last - 4).maValues[maIdx];
        double slope = (vLast - vPrev5) / 5.0;

        if (!waves.isEmpty()) {
            Wave lw = waves.get(waves.size() - 1);
            long fiveMin = 5 * 60 * 1000L;
            if (candles.get(last).timestamp - lw.timestamp < fiveMin)
                return;
        }
        if (waves.isEmpty()) {
            if (slope > 0.1) {
                double bestPrice = Double.POSITIVE_INFINITY;
                long bestTs = 0L;
                for (int i = last - 4; i <= last; i++) {
                    double v = candles.get(i).maValues[maIdx];
                    if (v < bestPrice) {
                        bestPrice = v;
                        bestTs = candles.get(i).timestamp;
                    }
                }
                waves.add(new Wave(WaveType.Trough, bestTs, bestPrice, candles.get(last).timestamp));
            } else if (slope < -0.1) {
                double bestPrice = Double.NEGATIVE_INFINITY;
                long bestTs = 0L;
                for (int i = last - 4; i <= last; i++) {
                    double v = candles.get(i).maValues[maIdx];
                    if (v > bestPrice) {
                        bestPrice = v;
                        bestTs = candles.get(i).timestamp;
                    }
                }
                waves.add(new Wave(WaveType.Crest, bestTs, bestPrice, candles.get(last).timestamp));
            }
        } else {
            Wave prev = waves.get(waves.size() - 1);
            if (slope > 0.1 && prev.waveType == WaveType.Crest) {
                double bestPrice = Double.POSITIVE_INFINITY;
                long bestTs = 0L;
                for (int i = last - 4; i <= last; i++) {
                    double v = candles.get(i).maValues[maIdx];
                    if (v < bestPrice) {
                        bestPrice = v;
                        bestTs = candles.get(i).timestamp;
                    }
                }
                waves.add(new Wave(WaveType.Trough, bestTs, bestPrice, candles.get(last).timestamp));
            } else if (slope < -0.1 && prev.waveType == WaveType.Trough) {
                double bestPrice = Double.NEGATIVE_INFINITY;
                long bestTs = 0L;
                for (int i = last - 4; i <= last; i++) {
                    double v = candles.get(i).maValues[maIdx];
                    if (v > bestPrice) {
                        bestPrice = v;
                        bestTs = candles.get(i).timestamp;
                    }
                }
                waves.add(new Wave(WaveType.Crest, bestTs, bestPrice, candles.get(last).timestamp));
            }
        }
    }

    private void updateBollingerBands(int period, double stdDevFactor) {
        int count = candles.size();
        if (count < period)
            return;
        int start = count - period;
        double sum = 0.0, sumSq = 0.0;
        for (int i = start; i < count; i++) {
            double c = candles.get(i).close;
            sum += c;
            sumSq += c * c;
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

    // ===== RSI: enable/reset + compute on candle close (via RsiUtils) // >>>
    public void enableRSI(int period) {
        enableRSI(period, false);
    }

    public void enableRSI(int period, boolean divergence) { // >>> NEW
        this.rsiEnabled = true;
        this.rsiPeriod = Math.max(2, period);
        this.rsiDivergenceEnabled = divergence;
        if (this.rsiState == null)
            this.rsiState = new RsiUtils.RsiState(this.rsiPeriod);
        else
            this.rsiState.reset(this.rsiPeriod);
        this.rsiDivergences.clear();
    }

    public void resetRSI() { // >>> CHANGED
        if (rsiState != null)
            rsiState.reset(rsiPeriod);
        rsiDivergences.clear();
    }

    /** Called exactly when a new candle starts -> previous candle is closed. */
    private void maybeAddRsiOnCandleClose() { // >>> CHANGED
        if (!rsiEnabled)
            return;
        Candle prev = candles.get(candles.size() - 1);
        if (rsiState == null)
            rsiState = new RsiUtils.RsiState(rsiPeriod);
        rsiState.onClose(prev.close); // appends null or RSI for this closed candle

        // If divergence enabled, recompute full set (cheap for typical sizes)
        if (rsiDivergenceEnabled) {
            // Collect closes and aligned rsi
            int n = candles.size();
            List<Double> closes = new ArrayList<>(n);
            for (int i = 0; i < n; i++)
                closes.add(candles.get(i).close);
            List<Double> rsiAligned = RsiUtils.alignToLength(rsiState.getValues(), n);
            // Zerodha-like: lookback=5, minLength=5
            rsiDivergences = RsiUtils.detectRegularDivergences(closes, rsiAligned, 5, 5);
        }
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
        for (int i = 0; i < list.size(); i++)
            if (list.get(i).timestamp == ts)
                return i;
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
        double norm = value / 100.0;
        return plotY + (int) Math.round(plotH - norm * plotH);
    }

    private static void drawCentered(Graphics2D g, String text, int cx, int y) {
        FontMetrics fm = g.getFontMetrics();
        int w = fm.stringWidth(text);
        g.drawString(text, cx - w / 2, y);
    }

    private static void drawLineSeries(
            Graphics2D g, List<Candle> candles, int n,
            int plotX, int plotW, int plotY, int plotH,
            double minPrice, double maxPrice,
            java.util.function.ToDoubleFunction<Candle> valueExtractor,
            float strokeWidth, Color color) {
        List<Double> series = new ArrayList<>(candles.size());
        for (Candle c : candles)
            series.add(valueExtractor.applyAsDouble(c));
        drawSimpleLine(g, series, n, plotX, plotW, plotY, plotH, minPrice, maxPrice, strokeWidth, color);
    }

    private static void drawSimpleLine(
            Graphics2D g, List<Double> series, int n,
            int plotX, int plotW, int plotY, int plotH,
            double minVal, double maxVal,
            float strokeWidth, Color color) {
        Stroke old = g.getStroke();
        g.setStroke(new BasicStroke(strokeWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(color);
        Path2D path = new Path2D.Double();
        boolean started = false;
        for (int i = 0; i < series.size(); i++) {
            Double v = series.get(i);
            if (v == null || Double.isNaN(v))
                continue;
            double x = xToPixel(i + 0.5, n, plotX, plotW);
            int y = (maxVal == minVal)
                    ? plotY + plotH / 2
                    : (int) Math.round(plotY + plotH - ((v - minVal) / (maxVal - minVal)) * plotH);
            if (!started) {
                path.moveTo(x, y);
                started = true;
            } else {
                path.lineTo(x, y);
            }
        }
        g.draw(path);
        g.setStroke(old);
    }

    // ===== Optional setters =====
    private static boolean writeImageWithFallback(File file, BufferedImage img) {
        String ext = getExt(file.getName());
        try {
            boolean ok = ImageIO.write(img, ext, file);
            if (ok)
                return true;
        } catch (Exception ignore) {
        }
        try {
            File png = file.getName().toLowerCase(Locale.ENGLISH).endsWith(".png") ? file
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

    public void setYMinorTicksBetweenMajors(int count) {
        this.yMinorBetweenMajors = Math.max(0, count);
    }

    public void setXGridEveryCandles(Integer k) {
        this.xGridEveryCandles = (k != null && k > 0) ? k : null;
    }

    public void setGridStyle(Color majorColor, float majorStrokeWidth, Color minorColor, float minorStrokeWidth,
            Color borderColor) {
        if (majorColor != null)
            this.gridMajorColor = majorColor;
        if (minorColor != null)
            this.gridMinorColor = minorColor;
        if (borderColor != null)
            this.axesBorderColor = borderColor;
        if (majorStrokeWidth > 0)
            this.gridMajorStroke = majorStrokeWidth;
        if (minorStrokeWidth > 0)
            this.gridMinorStroke = minorStrokeWidth;
    }

    /** Customize RSI line & levels. */
    public void setRsiStyle(Color lineColor, float lineStrokeWidth, Color levelColor) {
        if (lineColor != null)
            this.rsiLineColor = lineColor;
        if (lineStrokeWidth > 0)
            this.rsiLineStroke = lineStrokeWidth;
        if (levelColor != null)
            this.rsiLevelColor = levelColor;
    }

    /** Customize day separator style (color & thickness). */
    public void setDaySeparatorStyle(Color color, float strokeWidth) {
        if (color != null)
            this.daySeparatorColor = color;
        if (strokeWidth > 0)
            this.daySeparatorStroke = strokeWidth;
    }

    /** Customize day separator font. */
    public void setDaySeparatorFont(Font font) {
        if (font != null)
            this.daySeparatorFont = font;
    }

    /** Customize MaPalette. */
    public void modifyMaPalette(Color[] maPalette) {
        this.MA_PALETTE = maPalette;
    }



    // ===== Nice scale =====
    private static final class NiceScale {
        final double niceMin, niceMax, tickSpacing;
        final int ticks;

        NiceScale(double dataMin, double dataMax, int targetTicks) {
            if (dataMax < dataMin) {
                double t = dataMin;
                dataMin = dataMax;
                dataMax = t;
            }
            double range = dataMax - dataMin;
            if (range == 0) {
                range = Math.abs(dataMax) > 0 ? Math.abs(dataMax) * 0.1 : 1.0;
            }
            double rawStep = range / Math.max(2, targetTicks - 1);
            double step = niceNum(rawStep, true);
            double nMin = Math.floor(dataMin / step) * step;
            double nMax = Math.ceil(dataMax / step) * step;
            this.niceMin = nMin;
            this.niceMax = nMax;
            this.tickSpacing = step;
            this.ticks = (int) Math.round((nMax - nMin) / step);
        }

        private static double niceNum(double x, boolean round) {
            double exp = Math.floor(Math.log10(x));
            double f = x / Math.pow(10.0, exp);
            double nf;
            if (round) {
                if (f < 1.5)
                    nf = 1.0;
                else if (f < 3.0)
                    nf = 2.0;
                else if (f < 7.0)
                    nf = 5.0;
                else
                    nf = 10.0;
            } else {
                if (f <= 1.0)
                    nf = 1.0;
                else if (f <= 2.)
                    nf = 2.0;
                else if (f <= 5.)
                    nf = 5.0;
                else
                    nf = 10.0;
            }
            return nf * Math.pow(10.0, exp);
        }
    }

    private static String formatTickLabel(double value, double step) {
        if (step >= 1)
            return String.format(Locale.ENGLISH, "%.0f", value);
        int decimals = Math.max(0, (int) Math.ceil(-Math.log10(step)) + 1);
        return String.format(Locale.ENGLISH, "%." + decimals + "f", value);
    }

    // ===== Internal helpers =====

    private void recomputeWaveIndices() {
        // pick two largest periods by value
        if (maPeriods.length == 1) {
            wavePrimaryIdx = 0;
            waveSecondaryIdx = 0;
            return;
        }
        // build index list sorted by period desc
        Integer[] idxs = new Integer[maPeriods.length];
        for (int i = 0; i < maPeriods.length; i++)
            idxs[i] = i;
        Arrays.sort(idxs, (a, b) -> Integer.compare(maPeriods[b], maPeriods[a]));
        wavePrimaryIdx = idxs[0];
        waveSecondaryIdx = idxs[1];
    }

    // ===== Example Spring Boot wiring =====
    // @Service
    // public static class CandleService {
    // // default SIMPLE [60,30,20,5,3] & RSI 14
    // private final CandleGraphTracker tracker = new CandleGraphTracker(1, "NIFTY",
    // 60L, 14);
    // // or:
    // // private final CandleGraphTracker tracker = new CandleGraphTracker(1,
    // "NIFTY", 60L,
    // // new int[]{200,50,20,10,3}, MAType.EXPONENTIAL, 14);
    // public void onTick(long epochMs, double price) {
    // tracker.addMarketData(epochMs, price); }
    // public void render(String dir) throws IOException {
    // tracker.drawCandleGraph(dir); }
    // }
}
