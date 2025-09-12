package app.ai.lab.tradeEngineLite.GraphUtils;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Simple tracker that computes the Relative Strength Index (RSI) from incoming
 * market prices and can render the resulting series as a PNG image.  The API is
 * deliberately similar to {@link CandleGraphTracker}: feed ticks via
 * {@link #addMarketData(long, double)} and finally call
 * {@link #drawRSIGraph(String)} to write a JSON file containing the RSI values
 * and a PNG chart that mimics the basic RSI indicator seen on platforms like
 * Zerodha.
 */
public class RSIGraphTracker {

    /** Container for raw market data. */
    public static final class MarketPoint {
        @JsonProperty public long time;
        @JsonProperty public double price;
        public MarketPoint(long time, double price) {
            this.time = time;
            this.price = price;
        }
    }

    /** One computed RSI value. */
    public static final class RSIPoint {
        @JsonProperty public long time;
        @JsonProperty public double rsi;
        public RSIPoint(long time, double rsi) {
            this.time = time;
            this.rsi = rsi;
        }
    }

    public final int id;
    public final String tradingsymbol;
    public final List<MarketPoint> market = new ArrayList<>();
    public final List<RSIPoint> rsiSeries = new ArrayList<>();

    private final int period = 14; // standard RSI period
    private double avgGain = 0.0;
    private double avgLoss = 0.0;
    private boolean initialized = false;

    public RSIGraphTracker(int id, String tradingsymbol) {
        this.id = id;
        this.tradingsymbol = tradingsymbol;
    }

    /** Add a new price tick (time in ms since epoch, price). */
    public void addMarketData(long timeMs, double price) {
        market.add(new MarketPoint(timeMs, price));
        if (market.size() < 2) return; // need at least one diff

        double change = price - market.get(market.size() - 2).price;
        double gain = Math.max(change, 0.0);
        double loss = Math.max(-change, 0.0);

        if (!initialized) {
            // Build initial averages over the first 'period' differences
            avgGain += gain;
            avgLoss += loss;
            if (market.size() - 1 == period) {
                avgGain /= period;
                avgLoss /= period;
                initialized = true;
                double rsi = computeRsi();
                rsiSeries.add(new RSIPoint(timeMs, rsi));
            }
            return;
        }

        avgGain = ((avgGain * (period - 1)) + gain) / period;
        avgLoss = ((avgLoss * (period - 1)) + loss) / period;
        double rsi = computeRsi();
        rsiSeries.add(new RSIPoint(timeMs, rsi));
    }

    private double computeRsi() {
        if (avgLoss == 0) return 100.0; // no losses => max RSI
        double rs = avgGain / avgLoss;
        return 100.0 - (100.0 / (1.0 + rs));
    }

    /** Render the RSI series as a PNG and save a JSON file with the values. */
    public void drawRSIGraph(String outputDir) throws IOException {
        if (!outputDir.endsWith(File.separator)) outputDir += File.separator;
        String baseName = this.id + "_" + this.tradingsymbol + "_rsi";

        // Save JSON with RSI values
        saveRsiJson(outputDir + baseName + ".json");

        final int width = 2560;
        final int height = 640;
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);

            if (rsiSeries.size() < 2) {
                writeImageWithFallback(new File(outputDir + baseName + ".png"), img);
                return;
            }

            int margin = 50;
            int plotX = margin;
            int plotY = margin;
            int plotW = width - 2 * margin;
            int plotH = height - 2 * margin;

            // Bounding box
            g.setColor(Color.GRAY);
            g.drawRect(plotX, plotY, plotW, plotH);

            // Common RSI levels at 30 and 70
            g.setColor(new Color(220, 220, 220));
            int y30 = yToPixel(30, plotY, plotH);
            int y70 = yToPixel(70, plotY, plotH);
            g.drawLine(plotX, y30, plotX + plotW, y30);
            g.drawLine(plotX, y70, plotX + plotW, y70);

            // Draw RSI line
            g.setColor(Color.BLUE);
            Path2D path = new Path2D.Double();
            for (int i = 0; i < rsiSeries.size(); i++) {
                double x = xToPixel(i, rsiSeries.size() - 1, plotX, plotW);
                int y = yToPixel(rsiSeries.get(i).rsi, plotY, plotH);
                if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }
            g.draw(path);
        } finally {
            g.dispose();
        }

        writeImageWithFallback(new File(outputDir + baseName + ".png"), img);
    }

    public void saveRsiJson(String path) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        mapper.writeValue(new File(path), rsiSeries);
    }

    private static double xToPixel(double idx, double maxIdx, int plotX, int plotW) {
        if (maxIdx == 0) return plotX;
        return plotX + (idx / maxIdx) * plotW;
    }

    private static int yToPixel(double value, int plotY, int plotH) {
        // RSI is always 0..100. 0 is bottom, 100 is top.
        double norm = value / 100.0;
        return (int) Math.round(plotY + plotH - norm * plotH);
    }

    private static boolean writeImageWithFallback(File file, BufferedImage img) {
        String ext = getExt(file.getName());
        try {
            boolean ok = ImageIO.write(img, ext, file);
            if (ok) return true;
        } catch (Exception ignore) { }
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
}

