package com.univocity.trader.account;

import java.math.*;
import java.util.*;
import java.util.concurrent.atomic.*;

import static com.univocity.trader.account.Balance.*;

public class DefaultOrder extends OrderRequest implements Order {

	private static final AtomicLong orderIds = new AtomicLong(1);

	private String orderId = String.valueOf(orderIds.incrementAndGet());
	private BigDecimal executedQuantity;
	private Order.Status status;
	private BigDecimal feesPaid = BigDecimal.ZERO;
	private BigDecimal averagePrice = BigDecimal.ZERO;
	private List<Order> attachments;
	private Order parent;

	public DefaultOrder(String assetSymbol, String fundSymbol, Order.Side side, Trade.Side tradeSide, long time) {
		super(assetSymbol, fundSymbol, side, tradeSide, time, null);
	}

	public DefaultOrder(OrderRequest request) {
		this(request.getAssetsSymbol(), request.getFundsSymbol(), request.getSide(), request.getTradeSide(), request.getTime());
	}

	public void setParent(DefaultOrder parent) {
		this.parent = parent;
		if (parent.attachments == null) {
			parent.attachments = new ArrayList<>();
		}
		parent.attachments.add(this);
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

	public final List<Order> getAttachments() {
		return attachments == null ? null : Collections.unmodifiableList(attachments);
	}

	public final Order getParent() {
		return parent;
	}

	public final String getParentOrderId() {
		return parent == null ? "" : parent.getOrderId();
	}

	public BigDecimal getQuantity() {
		BigDecimal out = super.getQuantity();
		if (parent != null && parent.isFinalized()) {
			BigDecimal p = parent.getExecutedQuantity();
			if (out.compareTo(p) > 0 || p.compareTo(BigDecimal.ZERO) == 0) {
				return p;
			}
		}
		return out;
	}
}
