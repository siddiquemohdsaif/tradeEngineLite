package app.ai.lab.tradeEngineLite.Algos.RSI_v1;

import app.ai.lab.tradeEngineLite.BackTest.Engine.HistoricalData.Block;
import app.ai.lab.tradeEngineLite.BackTest.Exchange.OrderManagementService;
import app.ai.lab.tradeEngineLite.BackTest.Exchange.VirtualExchange;
import app.ai.lab.tradeEngineLite.GraphUtils.CandleGraphTracker;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * RSI based backtest logic that relies on {@link CandleGraphTracker} for both
 * RSI calculation and candle graph generation.
 *
 * <p>Trading rules:</p>
 * <ul>
 *     <li>Buy when RSI falls to 25 or below.</li>
 *     <li>Sell when RSI rises to 60 or above, or when price moves +1% in
 *     profit or -0.5% in loss from entry.</li>
 * </ul>
 */
public class Logicore {

    private final int instrumentId;
    private final String name;
    private final OrderManagementService oms;
    private final CandleGraphTracker tracker;

    private boolean inPosition = false;
    private double entryPrice = 0.0;
    private long entryTime = 0L;
    private double totalProfit = 0.0;

    /**
     * Create a new Logicore strategy for the given instrument.
     *
     * @param instrumentId market token
     * @param name         human readable symbol/name
     * @param oms          order manager used to route orders
     */
    public Logicore(int instrumentId, String name, OrderManagementService oms) {
        this(instrumentId, name, oms, 14);
    }

    /**
     * Package private constructor used in tests to override RSI period.
     */
    Logicore(int instrumentId, String name, OrderManagementService oms, int rsiPeriod) {
        this.instrumentId = instrumentId;
        this.name = name;
        this.oms = oms;
        this.tracker = new CandleGraphTracker(instrumentId, name, 86400);
        this.tracker.enableRSI(rsiPeriod);
    }

    /**
     * Supply a historical data block. Relevant index packets are fed into
     * the tracker and trading logic is evaluated.
     */
    public void onBlock(Block block) {
        if (block.getInfo() == null) return;

        for (Block.PacketData pd : block.getInfo()) {
            if (pd instanceof Block.IndexPacket ip && ip.getToken() == instrumentId) {
                long ts = block.getTimeStamp();
                double price = ip.getLastTradedPrice() / 100.0;

                // update graph/RSI
                tracker.addMarketData(ts, price);

                Double rsi = tracker.getRSILatest();
                if (rsi == null) continue;

                if (!inPosition) {
                    if (rsi <= 30.0) {
                        oms.createOrder(instrumentId, VirtualExchange.OrderType.BUY_M);
                        inPosition = true;
                        entryPrice = price;
                        entryTime = ts;
                    }
                } else {
                    boolean targetHit = price >= entryPrice * 1.01;   // +1%
                    boolean stopHit = price <= entryPrice * 0.995;    // -0.5%
                    if (rsi >= 60.0 || targetHit || stopHit) {
                        oms.createOrder(instrumentId, VirtualExchange.OrderType.SELL_M);
                        inPosition = false;
                        double pnl = price - entryPrice;
                        totalProfit += pnl;
                        long exitTime = ts;
                        saveTradeLog(entryTime, exitTime, pnl);
                    }
                }
            }
        }
    }

    /** Write trade info to the configured output directory. */
    private void saveTradeLog(long start, long end, double pnl) {
        try {
            Path dir = Path.of("D:", "SpringBoot project", "Trade", "output files",
                    "tradeInfo", "RSI_v1");
            Files.createDirectories(dir);
            String fileName = start + "_" + String.format("%.2f", pnl) + "_" + (end - start) + ".json";
            Path file = dir.resolve(fileName);

            Map<String, Object> info = new HashMap<>();
            info.put("instrumentId", instrumentId);
            info.put("entryTime", start);
            info.put("exitTime", end);
            info.put("pnl", pnl);

            ObjectMapper mapper = new ObjectMapper();
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(info);
            Files.writeString(file, json);
        } catch (IOException e) {
            // For backtests we can print stack trace; failures here shouldn't abort processing.
            e.printStackTrace();
        }
    }

    /** Render candle graph (and RSI panel) to the given output directory. */
    public void drawGraph(String outputDir) throws IOException {
        tracker.drawCandleGraph(outputDir);
    }

    /** Convenience method using default output path as per requirements. */
    public void drawGraph() throws IOException {
        drawGraph(Path.of("D:", "SpringBoot project", "Trade", "output files").toString());
    }

    public double getTotalProfit() { return totalProfit; }

    public boolean isInPosition() { return inPosition; }
}

