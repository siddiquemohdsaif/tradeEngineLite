package app.ai.lab.tradeEngineLite.BackTest.Exchange;

import app.ai.lab.tradeEngineLite.BackTest.Engine.HistoricalData.Block;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class VirtualExchangeTest {

    static class TestVirtualExchange extends VirtualExchange {
        final List<Integer> processedTokens = new ArrayList<>();
        @Override
        public void instrumentPriceFeed(int instrumentId, double priceLtp, double priceAsk, double priceBid) {
            processedTokens.add(instrumentId);
            super.instrumentPriceFeed(instrumentId, priceLtp, priceAsk, priceBid);
        }
    }

    @Test
    void onBlockProcessesOnlyActiveInstruments() {
        TestVirtualExchange exchange = new TestVirtualExchange();
        exchange.placeOrder(new VirtualExchange.Order(1, VirtualExchange.OrderType.BUY_M, 0.0, 0.0));

        Block block = new Block();
        block.setTimeStamp(0L);
        List<Block.PacketData> packets = new ArrayList<>();

        Block.IndexPacket p1 = new Block.IndexPacket();
        p1.setToken(1);
        p1.setLastTradedPrice(10000); // 100.00
        packets.add(p1);

        Block.IndexPacket p2 = new Block.IndexPacket();
        p2.setToken(2);
        p2.setLastTradedPrice(20000); // 200.00
        packets.add(p2);

        block.setInfo(packets);

        exchange.onBlock(block);

        assertEquals(List.of(1), exchange.processedTokens);
    }
}
