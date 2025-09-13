package app.ai.lab.tradeEngineLite.BackTest.Exchange;

import app.ai.lab.tradeEngineLite.BackTest.Engine.HistoricalData.Block;
import org.springframework.stereotype.Service;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Simple order management service (OMS) for back-testing that supports only
 * market orders. Internally it delegates order execution to {@link VirtualExchange}.
 * Clients can register callbacks to be notified when an order completes or fails.
 */
@Service
public class OrderManagementService {

    /** Callback invoked when an order completes successfully. */
    private Consumer<VirtualExchange.OrderResponse> onOrderComplete;
    /** Callback invoked when order placement or cancellation fails. */
    private BiConsumer<String, String> onOrderFailed;

    private final VirtualExchange exchange;

    public OrderManagementService() {
        this.exchange = new VirtualExchange();
        this.exchange.setOrderStatusCallback(resp -> {
            if (onOrderComplete != null) {
                onOrderComplete.accept(resp);
            }
        });
    }

    /** Register callback for successful order completion. */
    public void setOnOrderComplete(Consumer<VirtualExchange.OrderResponse> callback) {
        this.onOrderComplete = callback;
    }

    /** Register callback for order failures. */
    public void setOnOrderFailed(BiConsumer<String, String> callback) {
        this.onOrderFailed = callback;
    }

    /**
     * Create a new market order. Only {@code BUY_M} and {@code SELL_M} order types
     * are accepted. Returns the generated order ID or {@code null} when rejected.
     */
    public String createOrder(int instrumentId, VirtualExchange.OrderType orderType) {
        if (orderType != VirtualExchange.OrderType.BUY_M &&
                orderType != VirtualExchange.OrderType.SELL_M) {
            if (onOrderFailed != null) {
                onOrderFailed.accept(null, "Only market orders supported");
            }
            return null;
        }
        VirtualExchange.Order order = new VirtualExchange.Order(instrumentId, orderType, 0.0, 0.0);
        return exchange.placeOrder(order);
    }

    /** Cancel an existing order. Returns {@code true} if the order was removed. */
    public boolean cancelOrder(String orderId) {
        boolean removed = exchange.cancelOrder(orderId);
        if (!removed && onOrderFailed != null) {
            onOrderFailed.accept(orderId, "Order not found");
        }
        return removed;
    }

    /** Forward price feed data to the underlying exchange. */
    public void instrumentPriceFeed(int instrumentId, double priceLtp, double priceAsk, double priceBid) {
        exchange.instrumentPriceFeed(instrumentId, priceLtp, priceAsk, priceBid);
    }

    /** Forward a historical data block to the exchange. */
    public void onBlock(Block block) {
        exchange.onBlock(block);
    }
}