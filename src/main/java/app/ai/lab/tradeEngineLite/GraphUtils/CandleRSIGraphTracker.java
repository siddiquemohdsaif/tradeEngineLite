package app.ai.lab.tradeEngineLite.GraphUtils;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Locale;

/**
 * Combines {@link CandleGraphTracker} and {@link RSIGraphTracker} to produce a single
 * chart containing a candlestick view on top and an RSI line graph below with a
 * shared time axis.
 */
public class CandleRSIGraphTracker {

    private final CandleGraphTracker candleTracker;
    private final RSIGraphTracker rsiTracker;
    private long candleTimeFrameMs;

    public CandleRSIGraphTracker(int id, String tradingsymbol) {
        this(id, tradingsymbol, 900); // default 15 minutes
    }

    public CandleRSIGraphTracker(int id, String tradingsymbol, long candleTimeFrameSeconds) {
        this.candleTracker = new CandleGraphTracker(id, tradingsymbol, candleTimeFrameSeconds);
        this.rsiTracker = new RSIGraphTracker(id, tradingsymbol);
        this.candleTimeFrameMs = candleTimeFrameSeconds * 1000L;
    }

    public void addMarketData(long timeMs, double price) {
        candleTracker.addMarketData(timeMs, price);
        rsiTracker.addMarketData(timeMs, price);
    }

    public void setCandleTimeFrameSeconds(long seconds) {
        candleTracker.setCandleTimeFrameSeconds(seconds);
        this.candleTimeFrameMs = seconds * 1000L;
    }

    /**
     * Draw a combined candle + RSI graph and save supporting JSON files.
     */
    public void drawCandleRSIGraph(String outputDir) throws IOException {
        if (!outputDir.endsWith(File.separator)) outputDir += File.separator;
        String base = candleTracker.id + "_" + candleTracker.tradingsymbol;

        // Persist JSON data for candles and RSI
        candleTracker.saveVolatilityIndex(outputDir + base + ".json");
        rsiTracker.saveRsiJson(outputDir + base + "_rsi.json");

        final int width = 2560;
        final int height = 1440;
        final int margin = 50;

        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);

            if (candleTracker.candles.isEmpty() || rsiTracker.rsiSeries.isEmpty()) {
                writeImageWithFallback(new File(outputDir + base + "_combo.png"), img);
                return;
            }

            int plotX = margin;
            int plotW = width - 2 * margin;

            int candlePlotY = margin;
            int candlePlotH = (int) (height * 0.6);
            int rsiPlotY = candlePlotY + candlePlotH + margin;
            int rsiPlotH = height - rsiPlotY - margin;
            int axisY = rsiPlotY + rsiPlotH;

            g.setColor(Color.GRAY);
            g.drawRect(plotX, candlePlotY, plotW, candlePlotH);
            g.drawRect(plotX, rsiPlotY, plotW, rsiPlotH);

            long startTime = Math.min(candleTracker.marketGraph.get(0).time, rsiTracker.market.get(0).time);
            long endTime = Math.max(candleTracker.marketGraph.get(candleTracker.marketGraph.size() - 1).time,
                    rsiTracker.market.get(rsiTracker.market.size() - 1).time);
            if (endTime == startTime) endTime += 1;

            // --- Candle bounds ---
            double dataMax = candleTracker.candles.stream().mapToDouble(c -> c.high).max().orElse(1d);
            double dataMin = candleTracker.candles.stream().mapToDouble(c -> c.low).min().orElse(0d);
            if (dataMax == dataMin) { dataMax += 1; dataMin -= 1; }
            double pad = (dataMax - dataMin) * 0.10;
            double paddedMin = dataMin - pad;
            double paddedMax = dataMax + pad;

            // Draw candles
            for (CandleGraphTracker.Candle c : candleTracker.candles) {
                long tsStart = c.timestamp;
                long tsEnd = tsStart + candleTimeFrameMs;
                int x1 = timeToX(tsStart, startTime, endTime, plotX, plotW);
                int x2 = timeToX(tsEnd, startTime, endTime, plotX, plotW);
                int w = Math.max(1, x2 - x1);

                int openY = priceToY(c.open, paddedMin, paddedMax, candlePlotY, candlePlotH);
                int closeY = priceToY(c.close, paddedMin, paddedMax, candlePlotY, candlePlotH);
                int highY = priceToY(c.high, paddedMin, paddedMax, candlePlotY, candlePlotH);
                int lowY = priceToY(c.low, paddedMin, paddedMax, candlePlotY, candlePlotH);

                int bodyTop = Math.min(openY, closeY);
                int bodyH = Math.max(1, Math.abs(openY - closeY));
                Color col = c.candleType == CandleGraphTracker.CandleType.Green ? Color.GREEN : Color.RED;
                g.setColor(col);
                g.fillRect(x1, bodyTop, w, bodyH);
                g.setColor(col.darker());
                g.drawLine(x1 + w / 2, highY, x1 + w / 2, lowY);
            }

            // Draw RSI level lines
            g.setColor(new Color(220, 220, 220));
            int y30 = rsiToY(30, rsiPlotY, rsiPlotH);
            int y70 = rsiToY(70, rsiPlotY, rsiPlotH);
            g.drawLine(plotX, y30, plotX + plotW, y30);
            g.drawLine(plotX, y70, plotX + plotW, y70);

            // Draw RSI line
            g.setColor(Color.BLUE);
            Path2D path = new Path2D.Double();
            for (int i = 0; i < rsiTracker.rsiSeries.size(); i++) {
                RSIGraphTracker.RSIPoint p = rsiTracker.rsiSeries.get(i);
                int x = timeToX(p.time, startTime, endTime, plotX, plotW);
                int y = rsiToY(p.rsi, rsiPlotY, rsiPlotH);
                if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }
            g.draw(path);

            // Shared X axis
            g.setColor(Color.BLACK);
            g.drawLine(plotX, axisY, plotX + plotW, axisY);

            // Time labels every ~10 candles
            int step = Math.max(1, candleTracker.candles.size() / 10);
            g.setFont(new Font("SansSerif", Font.PLAIN, 12));
            for (int i = 0; i < candleTracker.candles.size(); i += step) {
                CandleGraphTracker.Candle c = candleTracker.candles.get(i);
                int x = timeToX(c.timestamp + candleTimeFrameMs / 2, startTime, endTime, plotX, plotW);
                g.drawLine(x, axisY, x, axisY - 5);
                g.drawString(c.candleId, x - 20, axisY + 15);
            }
        } finally {
            g.dispose();
        }

        writeImageWithFallback(new File(outputDir + base + "_combo.png"), img);
    }

    private static int timeToX(long time, long start, long end, int plotX, int plotW) {
        double frac = (double) (time - start) / (double) (end - start);
        return plotX + (int) Math.round(frac * plotW);
    }

    private static int priceToY(double price, double min, double max, int plotY, int plotH) {
        double frac = (price - min) / (max - min);
        return plotY + plotH - (int) Math.round(frac * plotH);
    }

    private static int rsiToY(double rsi, int plotY, int plotH) {
        double frac = rsi / 100.0;
        return plotY + plotH - (int) Math.round(frac * plotH);
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
