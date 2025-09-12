package app.ai.lab.tradeEngineLite.BackTest.Exchange;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
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

    /** Place a new order into the exchange and return its ID. */
    public String placeOrder(Order order) {
        String orderId = generateOrderId();
        order.setOrderId(orderId);
        orders.add(order);
        return orderId;
    }

    /** Modify an existing order identified by {@code orderId}. */
    public boolean modifyOrder(String orderId, Order newOrder) {
        for (int i = 0; i < orders.size(); i++) {
            Order existing = orders.get(i);
            if (Objects.equals(existing.getOrderId(), orderId)) {
                newOrder.setOrderId(orderId);
                orders.set(i, newOrder);
                return true;
            }
        }
        return false;
    }

    /** Cancel an order from the exchange. */
    public boolean cancelOrder(String orderId) {
        return orders.removeIf(o -> Objects.equals(o.getOrderId(), orderId));
    }

    private String generateOrderId() {
        long id = Math.abs(random.nextLong()) % 1_000_000_000_000L;
        return String.format("%012d", id);
    }
}

