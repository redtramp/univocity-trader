package com.univocity.trader.simulation;

import com.univocity.trader.*;
import com.univocity.trader.account.*;
import com.univocity.trader.candles.*;
import com.univocity.trader.config.*;
import com.univocity.trader.simulation.orderfill.*;

import java.math.*;
import java.util.*;
import java.util.concurrent.*;

import static com.univocity.trader.config.Allocation.*;

public class SimulatedClientAccount implements ClientAccount {

	private Map<String, Set<Order>> orders = new HashMap<>();
	private TradingFees tradingFees;
	private final AccountManager account;
	private OrderFillEmulator orderFillEmulator;
	private final int marginReservePercentage;

	private Map<String, BigDecimal> sharedFunds = new ConcurrentHashMap<>();

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

		BigDecimal shared = null;
		if (orderDetails instanceof DefaultOrder) {
			shared = sharedFunds.get(((DefaultOrder) orderDetails).getParentOrderId());
			if (shared != null) {
				if (orderDetails.isBuy()) {
					availableFunds = shared;
				} else if (orderDetails.isSell()) {
					availableAssets = shared;
				}
			}
		}

		BigDecimal quantity = orderDetails.getQuantity();
		double fees = getTradingFees().feesOnOrder(orderDetails);

		BigDecimal locked = shared == null ? BigDecimal.ZERO : shared;

		DefaultOrder order = null;
		final boolean hasFundsAvailable = availableFunds.doubleValue() - fees >= orderAmount.doubleValue() - EFFECTIVELY_ZERO;
		if (orderDetails.isBuy() && hasFundsAvailable) {
			if (shared == null && orderDetails.isLong()) {
				locked = orderDetails.getTotalOrderAmount();
				account.lockAmount(fundsSymbol, locked.add(BigDecimal.valueOf(fees)));
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
				if (hasFundsAvailable) {
					if (shared == null) {
						locked = account.applyMarginReserve(orderDetails.getTotalOrderAmount()).subtract(orderDetails.getTotalOrderAmount());
						account.lockAmount(fundsSymbol, locked.add(BigDecimal.valueOf(fees)));
					}
					order = createOrder(orderDetails, quantity, unitPrice);
				}
			}
		}

		if (order != null) {
			activateOrder(order);
			List<OrderRequest> attachments = orderDetails.attachedOrderRequests();
			if (attachments != null) {
				for (OrderRequest attachment : attachments) {
					DefaultOrder child = createOrder(attachment, attachment.getQuantity(), attachment.getPrice());
					child.setParent(order);
				}
			}
		}

		return order;
	}

	private void activateOrder(Order order) {
		orders.computeIfAbsent(order.getSymbol(), (s) -> new ConcurrentSkipListSet<>()).add(order);
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
		Set<Order> s = orders.get(symbol);
		if (s == null || s.isEmpty()) {
			return false;
		}
		Iterator<Order> it = s.iterator();
		while (it.hasNext()) {
			Order pendingOrder = it.next();
			DefaultOrder order = (DefaultOrder) pendingOrder;

			activateAndTryFill(candle, order);

			Order triggeredOrder = null;
			if (!order.isFinalized() && order.getFillPct() > 0.0) {
				//if attached order is triggered, cancel parent and submit triggered order.
				List<Order> attachments = order.getAttachments();
				if (attachments != null && !attachments.isEmpty()) {
					for (Order attachment : attachments) {
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

				if (order.getParent() != null) { //order that is finalized is an end of a bracket order
					updateBalances(order, candle);
					if (sharedFunds.remove(order.getParentOrderId()) != null) {
						for (Order attached : order.getParent().getAttachments()) { //cancel all open orders
							attached.cancel();
						}
					}
				} else {
					updateBalances(order, candle);
				}

				List<Order> attachments = order.getAttachments();
				if (triggeredOrder == null && attachments != null && !attachments.isEmpty()) {
					for (Order attachment : attachments) {
						processAttachedOrder(order, (DefaultOrder) attachment, candle);
					}
				}
			}

			if (triggeredOrder != null && triggeredOrder.getQuantity().compareTo(BigDecimal.ZERO) > 0) {
				processAttachedOrder(order, (DefaultOrder) triggeredOrder, candle);
			}
		}
		return true;
	}

	private void processAttachedOrder(DefaultOrder parent, DefaultOrder attached, Candle candle) {
		BigDecimal locked = parent.getExecutedQuantity();
		if (locked.compareTo(BigDecimal.ZERO) > 0) {
			attached.updateTime(candle != null ? candle.openTime : parent.getTime());
			if (sharedFunds.put(parent.getOrderId(), locked) == null) {
				if (attached.isLong()) {
					if (attached.isSell()) {
						account.lockAmount(attached.getAssetsSymbol(), locked);
					} else {
						account.lockAmount(attached.getFundsSymbol(), locked);
					}
				}
			}
			activateOrder(attached);
			account.waitForFill(attached);
			activateAndTryFill(candle, attached);
		}
	}


	private boolean triggeredBy(Order parent, Order attachment, Candle candle) {
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

	private BigDecimal getLockedFees(Order order) {
		return BigDecimal.valueOf(getTradingFees().feesOnTotalOrderAmount(order));
	}

	private void updateFees(Order order) {
		if (order.isFinalized()) {
			if (order.isLongBuy() || order.isShortSell()) {
				BigDecimal maxFees = BigDecimal.valueOf(getTradingFees().feesOnTotalOrderAmount(order));
				account.subtractFromLockedBalance(order.getFundsSymbol(), maxFees);
				account.addToFreeBalance(order.getFundsSymbol(), maxFees.subtract(order.getFeesPaid()));
			} else if (order.isLongSell() || order.isShortCover()) {
				account.subtractFromFreeBalance(order.getFundsSymbol(), order.getFeesPaid());
			}
		}
	}

	private void updateBalances(DefaultOrder order, Candle candle) {
		if (order.getParent() != null && order.isCancelled() && !sharedFunds.containsKey(order.getParentOrderId())) {
			return;
		}

		final String asset = order.getAssetsSymbol();
		final String funds = order.getFundsSymbol();

		final BigDecimal rate = order.consume();
		final BigDecimal totalAmount = order.getTotalOrderAmountAtAveragePrice();

		try {
			synchronized (account) {
				if (order.isBuy()) {
					if (order.isLong()) {
						final BigDecimal lockedFunds = order.getTotalOrderAmount();
						account.addToFreeBalance(asset, rate.multiply(order.getQuantity()));
						account.subtractFromLockedBalance(funds, rate.multiply(lockedFunds));

						if (order.isFinalized()) {
							BigDecimal unspentAmount = lockedFunds.subtract(order.getTotalTraded());
							if (unspentAmount.compareTo(BigDecimal.ZERO) != 0) {
								account.addToFreeBalance(funds, unspentAmount);
							}

							updateFees(order);
						}
					} else if (order.isShort()) {
						if (rate.compareTo(BigDecimal.ZERO) != 0) {
							account.subtractFromShortedBalance(asset, rate.multiply(order.getQuantity()));
							account.subtractFromMarginReserveBalance(funds, asset, rate.multiply(totalAmount));
							updateMarginReserve(asset, funds, candle);
						}
						if (order.isFinalized()) {
							updateFees(order);
						}
					}
				} else if (order.isSell()) {
					if (order.isLong()) {
						account.addToFreeBalance(asset, order.getRemainingQuantity());
						account.addToFreeBalance(funds, order.getTotalTraded());
						account.subtractFromLockedBalance(asset, order.getQuantity());
						updateFees(order);
					} else if (order.isShort()) {
						BigDecimal totalReserve = account.applyMarginReserve(totalAmount);
						if (rate.compareTo(BigDecimal.ZERO) != 0) {
							BigDecimal accountReserve = totalReserve.subtract(totalAmount);
							account.addToMarginReserveBalance(funds, asset, rate.multiply(totalReserve));
							account.subtractFromLockedBalance(funds, rate.multiply(accountReserve));
							account.addToShortedBalance(asset, rate.multiply(order.getExecutedQuantity()));
						}

						if (order.isFinalized()) {
							BigDecimal totalTraded = order.getTotalTraded();
							BigDecimal unusedReserve = totalReserve.subtract(account.applyMarginReserve(totalTraded));
							BigDecimal unusedFunds = unusedReserve.subtract(totalAmount.subtract(totalTraded));

							account.subtractFromLockedBalance(funds, unusedFunds);
							account.addToFreeBalance(funds, unusedFunds);
							updateFees(order);
						}
					}
				}
			}
		} catch (Exception e) {
			throw new IllegalStateException("Error updating balances from order " + order, e);
		}
	}

	@Override
	public int marginReservePercentage() {
		return marginReservePercentage;
	}
}


