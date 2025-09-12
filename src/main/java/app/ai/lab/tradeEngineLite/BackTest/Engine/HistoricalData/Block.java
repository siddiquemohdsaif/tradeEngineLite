package app.ai.lab.tradeEngineLite.BackTest.Engine.HistoricalData;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.ArrayList;
import java.util.List;

public class Block {

    @JsonProperty("time_stamp")
    private long timeStamp; // u128 in Rust; ms epoch fits comfortably in long
    private List<PacketData> info = new ArrayList<>();

    public Block() {}

    public Block(long timeStamp, List<PacketData> info) {
        this.timeStamp = timeStamp;
        this.info = info;
    }

    public long getTimeStamp() { return timeStamp; }
    public void setTimeStamp(long timeStamp) { this.timeStamp = timeStamp; }

    public List<PacketData> getInfo() { return info; }
    public void setInfo(List<PacketData> info) { this.info = info; }

    // ----- Packet type marker
    // 👇 Tell Jackson how to pick the subtype using existing JSON property "type"
    @JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "type",
        visible = true
    )
    @JsonSubTypes({
        @JsonSubTypes.Type(value = StockPacket.class, name = "stock"),
        @JsonSubTypes.Type(value = IndexPacket.class,  name = "index")
    })
    public interface PacketData {}

    // ----- MarketDepthEntry (each entry is 12 bytes in the wire format:
    // 4 (quantity) + 4 (price) + 2 (orders) + 2 (padding/unused))
    public static class MarketDepthEntry {
        private long quantity; // u32
        private long price;    // u32
        private int orders;    // u16

        public MarketDepthEntry() {}
        public MarketDepthEntry(long quantity, long price, int orders) {
            this.quantity = quantity;
            this.price = price;
            this.orders = orders;
        }

        public long getQuantity() { return quantity; }
        public void setQuantity(long quantity) { this.quantity = quantity; }

        public long getPrice() { return price; }
        public void setPrice(long price) { this.price = price; }

        public int getOrders() { return orders; }
        public void setOrders(int orders) { this.orders = orders; }
    }

    // ----- StockPacket (length = 184 bytes)
    public static class StockPacket implements PacketData {
        @JsonProperty("type")
        private final String type = "stock";

        private long instrumentToken;     // u32
        private long lastTradedPrice;     // u32
        private long lastTradedQuantity;  // u32
        private long avgTradedPrice;      // u32
        private long volumeTraded;        // u32
        private long totalBuyQuantity;    // u32
        private long totalSellQuantity;   // u32
        private long openPrice;           // u32
        private long highPrice;           // u32
        private long lowPrice;            // u32
        private long closePrice;          // u32
        private long lastTradedTimestamp; // u32
        private long openInterest;        // u32
        private long openInterestDayHigh; // u32
        private long openInterestDayLow;  // u32
        private long exchangeTimestamp;   // u32
        private List<MarketDepthEntry> marketDepth = new ArrayList<>(); // 10 entries

        public StockPacket() {}

        // Getters/Setters
        public String getType() { return type; }

        public long getInstrumentToken() { return instrumentToken; }
        public void setInstrumentToken(long instrumentToken) { this.instrumentToken = instrumentToken; }

        public long getLastTradedPrice() { return lastTradedPrice; }
        public void setLastTradedPrice(long lastTradedPrice) { this.lastTradedPrice = lastTradedPrice; }

        public long getLastTradedQuantity() { return lastTradedQuantity; }
        public void setLastTradedQuantity(long lastTradedQuantity) { this.lastTradedQuantity = lastTradedQuantity; }

        public long getAvgTradedPrice() { return avgTradedPrice; }
        public void setAvgTradedPrice(long avgTradedPrice) { this.avgTradedPrice = avgTradedPrice; }

        public long getVolumeTraded() { return volumeTraded; }
        public void setVolumeTraded(long volumeTraded) { this.volumeTraded = volumeTraded; }

        public long getTotalBuyQuantity() { return totalBuyQuantity; }
        public void setTotalBuyQuantity(long totalBuyQuantity) { this.totalBuyQuantity = totalBuyQuantity; }

        public long getTotalSellQuantity() { return totalSellQuantity; }
        public void setTotalSellQuantity(long totalSellQuantity) { this.totalSellQuantity = totalSellQuantity; }

        public long getOpenPrice() { return openPrice; }
        public void setOpenPrice(long openPrice) { this.openPrice = openPrice; }

        public long getHighPrice() { return highPrice; }
        public void setHighPrice(long highPrice) { this.highPrice = highPrice; }

        public long getLowPrice() { return lowPrice; }
        public void setLowPrice(long lowPrice) { this.lowPrice = lowPrice; }

        public long getClosePrice() { return closePrice; }
        public void setClosePrice(long closePrice) { this.closePrice = closePrice; }

        public long getLastTradedTimestamp() { return lastTradedTimestamp; }
        public void setLastTradedTimestamp(long lastTradedTimestamp) { this.lastTradedTimestamp = lastTradedTimestamp; }

        public long getOpenInterest() { return openInterest; }
        public void setOpenInterest(long openInterest) { this.openInterest = openInterest; }

        public long getOpenInterestDayHigh() { return openInterestDayHigh; }
        public void setOpenInterestDayHigh(long openInterestDayHigh) { this.openInterestDayHigh = openInterestDayHigh; }

        public long getOpenInterestDayLow() { return openInterestDayLow; }
        public void setOpenInterestDayLow(long openInterestDayLow) { this.openInterestDayLow = openInterestDayLow; }

        public long getExchangeTimestamp() { return exchangeTimestamp; }
        public void setExchangeTimestamp(long exchangeTimestamp) { this.exchangeTimestamp = exchangeTimestamp; }

        public List<MarketDepthEntry> getMarketDepth() { return marketDepth; }
        public void setMarketDepth(List<MarketDepthEntry> marketDepth) { this.marketDepth = marketDepth; }
    }

    // ----- IndexPacket (length = 32 bytes)
    public static class IndexPacket implements PacketData {
        @JsonProperty("type")
        private final String type = "index";

        private long token;             // u32
        private long lastTradedPrice;   // u32
        private long highPrice;         // u32
        private long lowPrice;          // u32
        private long openPrice;         // u32
        private long closePrice;        // u32
        private long priceChange;       // u32
        private long exchangeTimestamp; // u32

        public IndexPacket() {}

        public String getType() { return type; }

        public long getToken() { return token; }
        public void setToken(long token) { this.token = token; }

        public long getLastTradedPrice() { return lastTradedPrice; }
        public void setLastTradedPrice(long lastTradedPrice) { this.lastTradedPrice = lastTradedPrice; }

        public long getHighPrice() { return highPrice; }
        public void setHighPrice(long highPrice) { this.highPrice = highPrice; }

        public long getLowPrice() { return lowPrice; }
        public void setLowPrice(long lowPrice) { this.lowPrice = lowPrice; }

        public long getOpenPrice() { return openPrice; }
        public void setOpenPrice(long openPrice) { this.openPrice = openPrice; }

        public long getClosePrice() { return closePrice; }
        public void setClosePrice(long closePrice) { this.closePrice = closePrice; }

        public long getPriceChange() { return priceChange; }
        public void setPriceChange(long priceChange) { this.priceChange = priceChange; }

        public long getExchangeTimestamp() { return exchangeTimestamp; }
        public void setExchangeTimestamp(long exchangeTimestamp) { this.exchangeTimestamp = exchangeTimestamp; }
    }
}
