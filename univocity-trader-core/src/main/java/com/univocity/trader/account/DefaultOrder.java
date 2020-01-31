package com.univocity.trader.account;

import java.math.*;
import java.util.*;

import static com.univocity.trader.account.Balance.*;

public class DefaultOrder extends OrderRequest implements Order {

	private String orderId;
	private BigDecimal executedQuantity;
	private Order.Status status;
	private BigDecimal feesPaid = BigDecimal.ZERO;
	private BigDecimal averagePrice = BigDecimal.ZERO;

	public DefaultOrder(String assetSymbol, String fundSymbol, Order.Side side, Trade.Side tradeSide, long time) {
		super(assetSymbol, fundSymbol, side, tradeSide, time, null);
	}

	public DefaultOrder(DefaultOrder parent, Order.Side side, Trade.Side tradeSide, long time) {
		super(parent, side, tradeSide, time, null);
		if (parent.attachments == null) {
			parent.attachments = new ArrayList<>();
		}
		parent.attachments.add(this);
	}

	public DefaultOrder(Order order) {
		super(order.getAssetsSymbol(), order.getFundsSymbol(), order.getSide(), order.getTradeSide(), order.getTime(), null);
		this.setOrderId(order.getOrderId());
		this.setType(order.getType());
		this.setQuantity(order.getQuantity());
		this.setPrice(order.getPrice());
		this.attachments = order.getAttachments();
	}

	@Override
	public String getOrderId() {
		return orderId;
	}

	public void setOrderId(String orderId) {
		this.orderId = orderId;
	}

	@Override
	public BigDecimal getExecutedQuantity() {
		return executedQuantity;
	}

	public void setExecutedQuantity(BigDecimal executedQuantity) {
		this.executedQuantity = round(executedQuantity);
	}

	@Override
	public void setPrice(BigDecimal price) {
		super.setPrice(round(price));
	}

	@Override
	public void setQuantity(BigDecimal quantity) {
		super.setQuantity(round(quantity));
	}

	@Override
	public Status getStatus() {
		return status;
	}

	public void setStatus(Status status) {
		this.status = status;
	}

	@Override
	public void cancel() {
		if (this.status != Status.FILLED) {
			this.status = Status.CANCELLED;
		}
	}

	public boolean isCancelled() {
		return this.status == Status.CANCELLED;
	}

	@Override
	public BigDecimal getFeesPaid() {
		return feesPaid;
	}

	public void setFeesPaid(BigDecimal feesPaid) {
		this.feesPaid = round(feesPaid);
	}

	@Override
	public String toString() {
		return print(0);
	}

	public void setAveragePrice(BigDecimal averagePrice) {
		this.averagePrice = round(averagePrice);
	}

	@Override
	public final BigDecimal getAveragePrice() {
		return averagePrice;
	}
}
