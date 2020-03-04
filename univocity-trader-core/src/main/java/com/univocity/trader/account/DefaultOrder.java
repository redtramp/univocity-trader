package com.univocity.trader.account;

import java.math.*;
import java.util.*;
import java.util.concurrent.atomic.*;

import static com.univocity.trader.account.Balance.*;

public class DefaultOrder extends OrderRequest implements Order, Comparable<DefaultOrder> {

	private static final AtomicLong orderIds = new AtomicLong(0);

	private final long id = orderIds.incrementAndGet();
	private String orderId = String.valueOf(id);
	private double executedQuantity = 0.0;
	private Order.Status status;
	private double feesPaid = 0.0;
	private double averagePrice = 0.0;
	private List<Order> attachments;
	private Order parent;
	private double partialFillPrice = 0.0;
	private double partialFillQuantity = 0.0;
	private double originalMarginReserve = 0.0;

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
	public double getExecutedQuantity() {
		return executedQuantity;
	}

	public double getTotalOrderAmountAtAveragePrice() {
		if (averagePrice == 0) {
			return getPrice() * getQuantity();
		}
		return averagePrice * getQuantity();
	}

	public void setExecutedQuantity(double executedQuantity) {
		this.executedQuantity = executedQuantity;
	}

	@Override
	public void setPrice(double price) {
		super.setPrice(price);
	}

	@Override
	public void setQuantity(double quantity) {
		super.setQuantity(quantity);
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
	public double getFeesPaid() {
		return feesPaid;
	}

	public void setFeesPaid(double feesPaid) {
		this.feesPaid = feesPaid;
	}

	@Override
	public String toString() {
		return print(0);
	}

	public void setAveragePrice(double averagePrice) {
		this.averagePrice = averagePrice;
	}

	@Override
	public final double getAveragePrice() {
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

	public double getQuantity() { //TODO: check this implementation in live trading.
		double out = super.getQuantity();
		if (parent != null && parent.isFinalized()) {
			double p = parent.getExecutedQuantity();
			if (out > p || p == 0) {
				return p;
			}
		}
		return out;
	}

	public boolean hasPartialFillDetails() {
		return partialFillQuantity != 0.0 && partialFillQuantity > 0;
	}

	public void clearPartialFillDetails() {
		partialFillQuantity = 0.0;
		partialFillPrice = 0.0;
	}

	public double getPartialFillTotalPrice() {
		return partialFillQuantity * partialFillPrice;
	}

	public double getPartialFillQuantity() {
		return partialFillQuantity;
	}

	public void setPartialFillDetails(double filledQuantity, double fillPrice) {
		this.partialFillPrice = fillPrice;
		this.partialFillQuantity = filledQuantity;
	}

	public double getOriginalMarginReserve() {
		return originalMarginReserve;
	}

	public void setOriginalMarginReserve(double originalMarginReserve) {
		this.originalMarginReserve = originalMarginReserve;
	}

	@Override
	public int compareTo(DefaultOrder o) {
		return Long.compare(this.id, o.id);
	}
}
