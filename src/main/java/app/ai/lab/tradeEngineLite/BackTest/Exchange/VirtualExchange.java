package app.ai.lab.tradeEngineLite.BackTest.Exchange;

import app.ai.lab.tradeEngineLite.BackTest.Engine.HistoricalData.Block;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;

/**
 * Simple in-memory virtual exchange used for backtesting.
 * Orders are kept in a book and executed when incoming price
 * feeds satisfy their conditions. Optional callbacks can be
 * registered to receive order execution notifications.
 */
@Service
public class VirtualExchange {

    /** Types of orders supported by the exchange. */
    public enum OrderType {
        BUY_M,    // Buy Market Order
        BUY_L,    // Buy Limit Order
        SELL_M,   // Sell Market Order
        SELL_L    // Sell Limit Order
    }

    /** Possible responses for an order. */
    public enum ResponseType {
        COMPLETED,
        REJECT
    }

    /** Representation of a single order in the exchange. */
    public static class Order {
        private String orderId;
        private final int instrumentId;
        private OrderType orderType;
        private double price;
        private final double triggerPrice;

        public Order(int instrumentId, OrderType orderType, double price, double triggerPrice) {
            this.instrumentId = instrumentId;
            this.orderType = orderType;
            this.price = price;
            this.triggerPrice = triggerPrice;
        }

        public String getOrderId() {
            return orderId;
        }

        public void setOrderId(String orderId) {
            this.orderId = orderId;
        }

        public int getInstrumentId() {
            return instrumentId;
        }

        public OrderType getOrderType() {
            return orderType;
        }

        public void setOrderType(OrderType orderType) {
            this.orderType = orderType;
        }

        public double getPrice() {
            return price;
        }

        public void setPrice(double price) {
            this.price = price;
        }

        public double getTriggerPrice() {
            return triggerPrice;
        }
    }

    /** Response returned for executed or rejected orders. */
    public static class OrderResponse {
        private final ResponseType responseType;
        private final String orderId;
        private final int instrumentId;
        private final double avgPrice;
        private final String rejectReason;

        public OrderResponse(ResponseType responseType,
                             String orderId,
                             int instrumentId,
                             double avgPrice,
                             String rejectReason) {
            this.responseType = responseType;
            this.orderId = orderId;
            this.instrumentId = instrumentId;
            this.avgPrice = avgPrice;
            this.rejectReason = rejectReason;
        }

        public ResponseType getResponseType() {
            return responseType;
        }

        public String getOrderId() {
            return orderId;
        }

        public int getInstrumentId() {
            return instrumentId;
        }

        public double getAvgPrice() {
            return avgPrice;
        }

        public String getRejectReason() {
            return rejectReason;
        }
    }

    private final List<Order> orders = new CopyOnWriteArrayList<>();
    private final Set<Integer> activeInstruments = new CopyOnWriteArraySet<>();
    private Consumer<OrderResponse> feedOrderStatusCallback;
    private final long virtualOrderDelay;
    private final Random random = new Random();

    public VirtualExchange() {
        this(0L);
    }

    public VirtualExchange(long virtualOrderDelay) {
        this.virtualOrderDelay = virtualOrderDelay;
    }

    /** Register a callback to receive order execution notifications. */
    public void setOrderStatusCallback(Consumer<OrderResponse> callback) {
        this.feedOrderStatusCallback = callback;
    }

    /**
     * Process incoming price feed and execute matching orders.
     */
    public void instrumentPriceFeed(int instrumentId,
                                    double priceLtp,
                                    double priceAsk,
                                    double priceBid) {
        List<Order> executedOrders = new ArrayList<>();

        for (Order order : orders) {
            if (order.getInstrumentId() != instrumentId) {
                continue;
            }
            switch (order.getOrderType()) {
                case BUY_M:
                    order.setPrice(priceLtp);
                    executedOrders.add(order);
                    break;
                case SELL_M:
                    order.setPrice(priceLtp);
                    executedOrders.add(order);
                    break;
                case BUY_L:
                    if (priceLtp <= order.getPrice()) {
                        executedOrders.add(order);
                    }
                    break;
                case SELL_L:
                    if (priceLtp >= order.getPrice()) {
                        executedOrders.add(order);
                    }
                    break;
            }
        }

        orders.removeAll(executedOrders);

        if (orders.stream().noneMatch(o -> o.getInstrumentId() == instrumentId)) {
            activeInstruments.remove(instrumentId);
        }

        if (feedOrderStatusCallback != null) {
            for (Order order : executedOrders) {
                if (virtualOrderDelay > 0) {
                    try {
                        Thread.sleep(virtualOrderDelay);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                OrderResponse response = new OrderResponse(
                        ResponseType.COMPLETED,
                        order.getOrderId(),
                        order.getInstrumentId(),
                        order.getPrice(),
                        null
                );
                feedOrderStatusCallback.accept(response);
            }
        }
    }

    /**
     * Convenience method that processes a {@link Block} and forwards contained
     * price information to {@link #instrumentPriceFeed(int, double, double, double)}.
     * Both {@link Block.IndexPacket} and {@link Block.StockPacket} are supported.
     */
    public void onBlock(Block block) {
        if (block == null || block.getInfo() == null) {
            return;
        }

        if (activeInstruments.isEmpty()) {
            return;
        }

        for (Block.PacketData pd : block.getInfo()) {
            if (pd instanceof Block.IndexPacket ip) {
                int token = (int) ip.getToken();
                if (!activeInstruments.contains(token)) continue;
                double price = ip.getLastTradedPrice() / 100.0;
                instrumentPriceFeed(token, price, price, price);
            } else if (pd instanceof Block.StockPacket sp) {
                int token = (int) sp.getInstrumentToken();
                if (!activeInstruments.contains(token)) continue;
                double price = sp.getLastTradedPrice() / 100.0;
                instrumentPriceFeed(token, price, price, price);
            }
        }
    }

    /** Place a new order into the exchange and return its ID. */
    public String placeOrder(Order order) {
        String orderId = generateOrderId();
        order.setOrderId(orderId);
        orders.add(order);
        trackInstrument(order.getInstrumentId());
        return orderId;
    }

    /** Modify an existing order identified by {@code orderId}. */
    public boolean modifyOrder(String orderId, Order newOrder) {
        for (int i = 0; i < orders.size(); i++) {
            Order existing = orders.get(i);
            if (Objects.equals(existing.getOrderId(), orderId)) {
                newOrder.setOrderId(orderId);
                orders.set(i, newOrder);
                trackInstrument(newOrder.getInstrumentId());
                return true;
            }
        }
        return false;
    }

    /** Cancel an order from the exchange. */
    public boolean cancelOrder(String orderId) {
        Integer instrumentId = null;
        for (Order o : orders) {
            if (Objects.equals(o.getOrderId(), orderId)) {
                instrumentId = o.getInstrumentId();
                break;
            }
        }
        boolean removed = orders.removeIf(o -> Objects.equals(o.getOrderId(), orderId));
        if (removed && instrumentId != null) {
            final int id = instrumentId;
            if (orders.stream().noneMatch(o -> o.getInstrumentId() == id)) {
                activeInstruments.remove(id);
            }
        }
        return removed;
    }

    private String generateOrderId() {
        long id = Math.abs(random.nextLong()) % 1_000_000_000_000L;
        return String.format("%012d", id);
    }

    /** Adds the instrument to active set if not already present */
    private void trackInstrument(int instrumentId) {
        if (!activeInstruments.contains(instrumentId)) {
            activeInstruments.add(instrumentId);
        }
    }
}

