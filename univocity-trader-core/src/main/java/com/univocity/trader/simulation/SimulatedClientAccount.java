package com.univocity.trader.simulation;

import com.univocity.trader.*;
import com.univocity.trader.account.*;
import com.univocity.trader.candles.*;
import com.univocity.trader.config.*;
import com.univocity.trader.simulation.orderfill.*;

import java.math.*;
import java.util.*;
import java.util.concurrent.*;

import static com.univocity.trader.account.Balance.*;
import static com.univocity.trader.config.Allocation.*;

public class SimulatedClientAccount implements ClientAccount {

	private Map<String, Set<PendingOrder>> orders = new HashMap<>();
	private TradingFees tradingFees;
	private final AccountManager account;
	private OrderFillEmulator orderFillEmulator;
	private final int marginReservePercentage;

	private Map<String, BigDecimal> sharedFunds = new ConcurrentHashMap<>();

	private static class PendingOrder implements Comparable<PendingOrder> {
		final Order order;
		final BigDecimal lockedAmount;

		public PendingOrder(Order order, BigDecimal lockedAmount) {
			this.order = order;
			this.lockedAmount = round(lockedAmount);
		}

		@Override
		public int compareTo(PendingOrder o) {
			int comparison = Long.compare(this.order.getTime(), o.order.getTime());
			if (comparison == 0) {
				comparison = this.order.getOrderId().compareTo(o.order.getOrderId());
			}
			return comparison;
		}
	}

	public SimulatedClientAccount(AccountConfiguration<?> accountConfiguration, Simulation simulation) {
		this.marginReservePercentage = accountConfiguration.marginReservePercentage();
		this.account = new AccountManager(this, accountConfiguration, simulation);
		this.orderFillEmulator = simulation.orderFillEmulator();
	}

	public final TradingFees getTradingFees() {
		if (this.tradingFees == null) {
			this.tradingFees = account.getTradingFees();
			if (this.tradingFees == null) {
				throw new IllegalArgumentException("Trading fees cannot be null");
			}
		}
		return this.tradingFees;
	}

	@Override
	public synchronized Order executeOrder(OrderRequest orderDetails) {
		String fundsSymbol = orderDetails.getFundsSymbol();
		String assetsSymbol = orderDetails.getAssetsSymbol();
		Order.Type orderType = orderDetails.getType();
		BigDecimal unitPrice = orderDetails.getPrice();
		final BigDecimal orderAmount = orderDetails.getTotalOrderAmount();

		BigDecimal availableFunds = account.getPreciseAmount(fundsSymbol);
		BigDecimal availableAssets = account.getPreciseAmount(assetsSymbol);
		if (orderDetails.isShort()) {
			if (orderDetails.isBuy()) {
				availableFunds = availableFunds.add(account.getMarginReserve(fundsSymbol, assetsSymbol));
			} else if (orderDetails.isSell()) {
				availableAssets = account.getPreciseShortedAmount(assetsSymbol);
			}
		}

		BigDecimal shared = sharedFunds.get(orderDetails.getParentOrderId());
		if (shared != null) {
			if (orderDetails.isBuy()) {
				availableFunds = shared;
			} else if (orderDetails.isSell()) {
				availableAssets = shared;
			}
		}

		BigDecimal quantity = orderDetails.getQuantity();
		double fees = orderAmount.doubleValue() - getTradingFees().takeFee(orderAmount.doubleValue(), orderType, orderDetails.getSide());

		BigDecimal locked = shared == null ? BigDecimal.ZERO : shared;

		DefaultOrder order = null;
		if (orderDetails.isBuy() && availableFunds.doubleValue() - fees >= orderAmount.doubleValue() - EFFECTIVELY_ZERO) {
			if (shared == null && orderDetails.isLong()) {
				locked = orderDetails.getTotalOrderAmount();
				account.lockAmount(fundsSymbol, locked);
			}
			order = createOrder(orderDetails, quantity, unitPrice);

		} else if (orderDetails.isSell()) {
			if (orderDetails.isLong()) {
				if (availableAssets.compareTo(quantity) < 0) {
					double difference = 1.0 - (availableAssets.doubleValue() / quantity.doubleValue());
					if (difference < 0.00001) { //0.001% quantity mismatch.
						quantity = availableAssets.multiply(new BigDecimal("0.9999"));
					}
				}
				if (availableAssets.compareTo(quantity) >= 0) {
					if (shared == null) {
						locked = orderDetails.getQuantity();
						account.lockAmount(assetsSymbol, locked);
					}
					order = createOrder(orderDetails, quantity, unitPrice);
				}
			} else if (orderDetails.isShort()) {
				if (availableFunds.compareTo(orderAmount) >= 0) {
					if (shared == null) {
						locked = account.applyMarginReserve(orderDetails.getTotalOrderAmount()).subtract(orderDetails.getTotalOrderAmount());
						account.lockAmount(fundsSymbol, locked);
					}
					order = createOrder(orderDetails, quantity, unitPrice);
				}
			}

		}

		if (order != null) {
			if (orderDetails.getParent() != null) {
				order.setParent((DefaultOrder) orderDetails.getParent());
			}
			orders.computeIfAbsent(order.getSymbol(), (s) -> new ConcurrentSkipListSet<>()).add(new PendingOrder(order, locked));
		}

		return order;
	}

	private DefaultOrder createOrder(OrderRequest request, BigDecimal quantity, BigDecimal price) {
		DefaultOrder out = new DefaultOrder(request);
		initializeOrder(out, price, quantity, request);
		return out;
	}

	private void initializeOrder(DefaultOrder out, BigDecimal price, BigDecimal quantity, OrderRequest request) {
		out.setTriggerCondition(request.getTriggerCondition(), request.getTriggerPrice());
		out.setPrice(price);
		out.setQuantity(quantity);
		out.setType(request.getType());
		out.setStatus(Order.Status.NEW);
		out.setExecutedQuantity(BigDecimal.ZERO);
		out.setOrderId(UUID.randomUUID().toString());
	}

	@Override
	public Map<String, Balance> updateBalances() {
		return account.getBalances();
	}

	public AccountManager getAccount() {
		return account;
	}

	@Override
	public OrderBook getOrderBook(String symbol, int depth) {
		return null;
	}

	@Override
	public Order updateOrderStatus(Order order) {
		return order;
	}

	@Override
	public void cancel(Order order) {
		order.cancel();
		updateOpenOrders(order.getSymbol(), null);
	}

	@Override
	public boolean isSimulated() {
		return true;
	}

	private void activateAndTryFill(Candle candle, DefaultOrder order) {
		if (candle != null && order != null) {
			if (!order.isActive()) {
				if (triggeredBy(order, null, candle)) {
					order.activate();
				}
			}
			if (order.isActive()) {
				orderFillEmulator.fillOrder(order, candle);
			}
		}
	}

	@Override
	public final synchronized boolean updateOpenOrders(String symbol, Candle candle) {
		Set<PendingOrder> s = orders.get(symbol);
		if (s == null || s.isEmpty()) {
			return false;
		}
		Iterator<PendingOrder> it = s.iterator();
		while (it.hasNext()) {
			PendingOrder pendingOrder = it.next();
			DefaultOrder order = (DefaultOrder) pendingOrder.order;

			activateAndTryFill(candle, order);

			OrderRequest triggeredOrder = null;
			if (!order.isFinalized() && order.getFillPct() > 0.0) {
				//if attached order is triggered, cancel parent and submit triggered order.
				List<OrderRequest> attachments = order.attachedOrderRequests();
				if (attachments != null && !attachments.isEmpty()) {
					for (OrderRequest attachment : attachments) {
						if (triggeredBy(order, attachment, candle)) {
							triggeredOrder = attachment;
							break;
						}
					}

					if (triggeredOrder != null) {
						order.cancel();
					}
				}
			}

			if (order.isFinalized()) {
				it.remove();
				order.setFeesPaid(BigDecimal.valueOf(getTradingFees().feesOnTradedAmount(order)));

				BigDecimal amountToUnlock = pendingOrder.lockedAmount;
				if (order.getParent() != null) { //order that is finalized is an end of a bracket order
					if (sharedFunds.remove(order.getParentOrderId()) == null) {
						amountToUnlock = BigDecimal.ZERO; //already unlocked by an order from the same parent.
					} else {
						for (Order attached : order.getParent().getAttachments()) { //cancel all open orders
							attached.cancel();
						}
					}
				}

				updateBalances(order, amountToUnlock, candle);

				List<OrderRequest> attachments = order.attachedOrderRequests();
				if (triggeredOrder == null && attachments != null && !attachments.isEmpty()) {
					for (OrderRequest attachment : attachments) {
						processAttachedOrder(order, attachment, candle);
					}
				}
			}

			if (triggeredOrder != null && triggeredOrder.getQuantity().compareTo(BigDecimal.ZERO) > 0) {
				processAttachedOrder(order, triggeredOrder, candle);
			}
		}
		return true;
	}

	private void processAttachedOrder(DefaultOrder parent, OrderRequest request, Candle candle) {
		if (parent.getQuantity().compareTo(BigDecimal.ZERO) > 0) {
			request.setParent(parent);
			request.updateTime(candle != null ? candle.openTime : parent.getTime());
			DefaultOrder linkedOrder = (DefaultOrder) account.executeOrder(request);  //TODO -> check if order is managed by everything
			if (linkedOrder != null) {
				String parentId = linkedOrder.getParentOrderId();
				if (!parentId.isEmpty() && !sharedFunds.containsKey(parentId)) {
					sharedFunds.put(parentId, linkedOrder.getQuantity());
				}
			}
			activateAndTryFill(candle, linkedOrder);
		}
	}


	private boolean triggeredBy(Order parent, OrderRequest attachment, Candle candle) {
		if (candle == null) {
			return false;
		}

		BigDecimal triggerPrice;
		Order.TriggerCondition trigger;
		if (attachment == null) {
			if (parent.getTriggerCondition() == Order.TriggerCondition.NONE) {
				return false;
			}
			triggerPrice = parent.getTriggerPrice();
			trigger = parent.getTriggerCondition();
		} else {
			triggerPrice = (attachment.getTriggerPrice() != null) ? attachment.getTriggerPrice() : attachment.getPrice();
			trigger = attachment.getTriggerCondition();
		}
		if (triggerPrice == null) {
			return false;
		}

		double conditionalPrice = triggerPrice.doubleValue();

		switch (trigger) {
			case STOP_GAIN:
				return candle.low >= conditionalPrice;
			case STOP_LOSS:
				return candle.high <= conditionalPrice;
		}
		return false;
	}

	private void updateMarginReserve(String assetSymbol, String fundSymbol, Candle candle) {
		Balance funds = account.getBalance(fundSymbol);
		BigDecimal reserved = funds.getMarginReserve(assetSymbol);

		BigDecimal shortedQuantity = account.getBalance(assetSymbol).getShorted();
		if (shortedQuantity.doubleValue() <= EFFECTIVELY_ZERO) {
			funds.setFree(funds.getFree().add(reserved));
			funds.setMarginReserve(assetSymbol, BigDecimal.ZERO);
		} else {
			double close;
			if (candle == null) {
				Trader trader = getAccount().getTraderOf(assetSymbol + fundSymbol);
				close = trader.lastClosingPrice();
			} else {
				close = candle.close;
			}

			BigDecimal newReserve = account.applyMarginReserve(shortedQuantity.multiply(BigDecimal.valueOf(close)));
			funds.setFree(funds.getFree().add(reserved).subtract(newReserve));
			funds.setMarginReserve(assetSymbol, newReserve);
		}
	}

	private void updateBalances(Order order, BigDecimal locked, Candle candle) {
		final String asset = order.getAssetsSymbol();
		final String funds = order.getFundsSymbol();

		double amountTraded = order.getTotalTraded().doubleValue();
		double fees = amountTraded - getTradingFees().takeFee(amountTraded, order.getType(), order.getSide());

		synchronized (account) {
			if (order.isBuy()) {
				if (order.isLong()) {
					account.addToFreeBalance(asset, order.getExecutedQuantity());
					account.subtractFromLockedBalance(funds, locked);

					BigDecimal unspentAmount = locked.subtract(order.getTotalTraded());
					account.addToFreeBalance(funds, unspentAmount);
				} else if (order.isShort()) {
					BigDecimal quantity = order.getExecutedQuantity();
					account.subtractFromShortedBalance(asset, quantity);
					account.subtractFromMarginReserveBalance(funds, asset, order.getTotalTraded());
					updateMarginReserve(asset, funds, candle);
				}
			} else if (order.isSell()) {
				if (order.isLong()) {
					if (locked.compareTo(BigDecimal.ZERO) > 0) {
						account.subtractFromLockedBalance(asset, locked);
						account.addToFreeBalance(asset, order.getRemainingQuantity());
						account.addToFreeBalance(funds, order.getTotalTraded());
					}
				} else if (order.isShort()) {
					account.subtractFromLockedBalance(funds, locked);
					account.addToFreeBalance(funds, locked);

					BigDecimal reserve = account.applyMarginReserve(order.getTotalTraded());
					account.subtractFromFreeBalance(funds, reserve.subtract(order.getTotalTraded()));
					account.addToMarginReserveBalance(funds, asset, reserve);
					account.addToShortedBalance(asset, order.getExecutedQuantity());
				}

			}
			account.subtractFromFreeBalance(funds, BigDecimal.valueOf(fees));
		}
	}

	@Override
	public int marginReservePercentage() {
		return marginReservePercentage;
	}
}


