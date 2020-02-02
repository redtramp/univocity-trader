package com.univocity.trader.account;

import org.apache.commons.lang3.*;

import java.math.*;
import java.util.*;

import static com.univocity.trader.account.Balance.*;
import static com.univocity.trader.account.Order.Side.*;
import static com.univocity.trader.account.Order.TriggerCondition.*;

public class OrderRequest {

	private boolean cancelled = false;
	private final String assetsSymbol;
	private final String fundsSymbol;
	private final Order.Side side;
	private final Trade.Side tradeSide;
	private long time;
	private final Order resubmittedFrom;

	private BigDecimal triggerPrice;
	private Order.TriggerCondition triggerCondition = NONE;

	private BigDecimal price = BigDecimal.ZERO;
	private BigDecimal quantity = BigDecimal.ZERO;
	private Order.Type type = Order.Type.LIMIT;
	private boolean active = true;

	protected Order parent;

	private List<OrderRequest> attachedRequests = new ArrayList<>();

	public OrderRequest(Order parent, Order.Side side, Trade.Side tradeSide, long time, Order resubmittedFrom) {
		this(parent.getAssetsSymbol(), parent.getFundsSymbol(), side, tradeSide, time, resubmittedFrom);
		this.parent = parent;
	}

	public OrderRequest(String assetsSymbol, String fundsSymbol, Order.Side side, Trade.Side tradeSide, long time, Order resubmittedFrom) {
		this.parent = null;
		this.resubmittedFrom = resubmittedFrom;
		this.time = time;
		if (StringUtils.isBlank(assetsSymbol)) {
			throw new IllegalArgumentException("Assets symbol cannot be null/blank");
		}
		if (StringUtils.isBlank(fundsSymbol)) {
			throw new IllegalArgumentException("Funds symbol cannot be null/blank");
		}
		if (side == null) {
			throw new IllegalArgumentException("Order side cannot be null");
		}
		this.assetsSymbol = assetsSymbol;
		this.fundsSymbol = fundsSymbol;
		this.side = side;
		this.tradeSide = tradeSide;
	}

	public String getAssetsSymbol() {
		return assetsSymbol;
	}

	public String getFundsSymbol() {
		return fundsSymbol;
	}

	public String getSymbol() {
		return assetsSymbol + fundsSymbol;
	}

	public BigDecimal getPrice() {
		return price;
	}

	public void setPrice(BigDecimal price) {
		this.price = round(price);
	}

	public BigDecimal getQuantity() {
		return quantity;
	}

	public void setQuantity(BigDecimal quantity) {
		this.quantity = round(quantity);
	}

	public Order.Side getSide() {
		return side;
	}

	public Trade.Side getTradeSide() {
		return tradeSide;
	}

	public Order.Type getType() {
		return type;
	}

	public void setType(Order.Type type) {
		this.type = type;
	}

	public BigDecimal getTotalOrderAmount() {
		return round(price.multiply(quantity));
	}

	public long getTime() {
		return time;
	}

	@Override
	public String toString() {
		return "OrderPreparation{" +
				"symbol='" + getSymbol() + '\'' +
				(triggerCondition == NONE ? "" : ", {" + triggerCondition + "@" + triggerPrice + "}") +
				", type=" + type +
				", tradeSide=" + tradeSide +
				", side=" + side +
				", price=" + price +
				", quantity=" + quantity +
				'}';
	}

	public boolean isCancelled() {
		return cancelled;
	}

	public void cancel() {
		cancelled = true;
	}

	public final boolean isResubmission() {
		return resubmittedFrom != null;
	}

	public final Order getOriginalOrder() {
		return resubmittedFrom;
	}

	public final boolean isShort() {
		return tradeSide == Trade.Side.SHORT;
	}

	public final boolean isLong() {
		return tradeSide == Trade.Side.LONG;
	}

	public final boolean isBuy() {
		return side == Order.Side.BUY;
	}

	public final boolean isSell() {
		return side == SELL;
	}

	public void updateTime(long time) {
		this.time = time;
	}

	public final List<OrderRequest> attachedOrderRequests() {
		return attachedRequests == null ? null : Collections.unmodifiableList(attachedRequests);
	}

	public final Order getParent() {
		return parent;
	}

	public final String getParentOrderId() {
		return parent == null ? "" : parent.getOrderId();
	}

	public final Order.TriggerCondition getTriggerCondition() {
		return this.triggerCondition;
	}

	public final BigDecimal getTriggerPrice() {
		return this.triggerPrice;
	}

	public final void setTriggerCondition(Order.TriggerCondition triggerCondition, BigDecimal triggerPrice) {
		this.triggerCondition = triggerCondition;
		this.triggerPrice = triggerPrice;
		this.active = !(triggerCondition != Order.TriggerCondition.NONE && triggerPrice != null);
		if (this.price.equals(BigDecimal.ZERO)) {
			this.price = triggerPrice;
		}
	}

	public final void activate() {
		active = true;
	}

	public final boolean isActive() {
		return active && !isCancelled();
	}

	public OrderRequest attach(Order.Type type, double change) {
		if (attachedRequests == null) {
			throw new IllegalArgumentException("Can only attach orders to the parent order");
		}

		OrderRequest attachment = new OrderRequest(assetsSymbol, fundsSymbol, side == BUY ? SELL : BUY, this.tradeSide, this.time, null);
		attachment.attachedRequests = null;

		this.attachedRequests.add(attachment);
		attachment.setQuantity(this.quantity);
		attachment.setPrice(this.price.multiply(BigDecimal.valueOf(1.0 + (change / 100.0))));
		attachment.setType(type);

		if (change < 0.0) {
			attachment.setTriggerCondition(STOP_LOSS, attachment.getPrice());
		}

		if (change >= 0.0) {
			attachment.setTriggerCondition(STOP_GAIN, attachment.getPrice());
		}

		return attachment;
	}

	protected void setAttachedOrderRequests(List<OrderRequest> attachedRequests) {
		this.attachedRequests = attachedRequests == null ? null : new ArrayList<>(attachedRequests);
	}
}
