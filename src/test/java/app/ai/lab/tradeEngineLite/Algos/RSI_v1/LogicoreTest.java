package app.ai.lab.tradeEngineLite.Algos.RSI_v1;

import app.ai.lab.tradeEngineLite.BackTest.Engine.HistoricalData.Block;
import app.ai.lab.tradeEngineLite.BackTest.Exchange.OrderManagementService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sanity test for the RSI strategy using synthetic data. The RSI period is set
 * to 2 so that a trade is generated quickly.
 */
class LogicoreTest {

    private static Block mkBlock(long ts, double price) {
        Block.IndexPacket ip = new Block.IndexPacket();
        ip.setToken(1);
        ip.setLastTradedPrice((long) (price * 100));
        Block b = new Block();
        b.setTimeStamp(ts);
        b.setInfo(List.of(ip));
        return b;
    }

    @Test
    void testSimpleRsiStrategy() {
        OrderManagementService oms = new OrderManagementService();
        Logicore logic = new Logicore(1, "TEST", oms, 2);

        long t = 0L;
        long step = 300_000L; // 5 minutes
        double[] closes = {100.0, 95.0, 90.0, 85.0, 86.0};
        for (double p : closes) {
            logic.onBlock(mkBlock(t, p));
            t += step;
        }

        assertFalse(logic.isInPosition(), "Position should be closed");
        assertEquals(1.0, logic.getTotalProfit(), 1e-6);
    }
}

