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
		BigDecimal orderAmount = orderDetails.getTotalOrderAmount();

		BigDecimal availableFunds = account.getPreciseAmount(fundsSymbol);
		BigDecimal availableAssets = account.getPreciseAmount(assetsSymbol);
		if (orderDetails.isShort()) {
			if (orderDetails.isBuy()) {
				availableFunds = availableFunds.add(account.getMarginReserve(fundsSymbol, assetsSymbol));
			} else if (orderDetails.isSell()) {
				availableAssets = account.getPreciseShortedAmount(assetsSymbol);
			}
		}

		BigDecimal quantity = orderDetails.getQuantity();
		double fees = getTradingFees().feesOnOrder(orderDetails);
		boolean hasFundsAvailable = availableFunds.doubleValue() - fees >= orderAmount.doubleValue() - EFFECTIVELY_ZERO;
		if (!hasFundsAvailable && !orderDetails.isLongSell()) {
			double maxAmount = orderAmount.doubleValue() - fees;
			double price = orderDetails.getPrice().doubleValue();
			if (price <= EFFECTIVELY_ZERO) {
				price = getAccount().getTraderOf(orderDetails.getSymbol()).lastClosingPrice();
			}
			quantity = BigDecimal.valueOf((maxAmount / price) * 0.9999);
			orderDetails.setQuantity(quantity);

			double currentOrderAmount = orderDetails.getTotalOrderAmount().doubleValue();
			if (fees < currentOrderAmount && currentOrderAmount / maxAmount > 0.95) { //ensure we didn't create a very small order
				fees = getTradingFees().feesOnOrder(orderDetails);
				hasFundsAvailable = availableFunds.doubleValue() - fees >= currentOrderAmount - EFFECTIVELY_ZERO;
				orderAmount = orderDetails.getTotalOrderAmount();
			}
		}


		DefaultOrder order = null;

		if (orderDetails.isBuy() && hasFundsAvailable) {
			if (orderDetails.isLong()) {
				account.lockAmount(fundsSymbol, orderAmount.add(BigDecimal.valueOf(fees)));
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
					account.lockAmount(assetsSymbol, orderDetails.getQuantity());
					order = createOrder(orderDetails, quantity, unitPrice);
				}
			} else if (orderDetails.isShort()) {
				if (hasFundsAvailable) {
					BigDecimal locked = account.applyMarginReserve(orderAmount).subtract(orderAmount);
					account.lockAmount(fundsSymbol, locked.add(BigDecimal.valueOf(fees)));
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
		if (candle != null && order != null && !order.isCancelled()) {
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
		//System.out.println("-------");
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

			order.setFeesPaid(order.getFeesPaid().add(BigDecimal.valueOf(getTradingFees().feesOnPartialFill(order))));

			if (order.isFinalized()) {
				it.remove();
				if (order.getParent() != null) { //order is child of a bracket order
					updateBalances(order, candle);
					for (Order attached : order.getParent().getAttachments()) { //cancel all open orders
						attached.cancel();
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
			} else if (order.hasPartialFillDetails()) {
				updateBalances(order, candle);
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

	private void updateBalances(DefaultOrder order, Candle candle) {
		final String asset = order.getAssetsSymbol();
		final String funds = order.getFundsSymbol();

		final BigDecimal lastFillTotalPrice = order.getPartialFillTotalPrice();

		try {
			synchronized (account) {
				if (order.isBuy()) {
					if (order.isLong()) {
						if (order.getAttachments() != null) { //to be used by attached orders
							account.addToLockedBalance(asset, order.getPartialFillQuantity());
						} else {
							account.addToFreeBalance(asset, order.getPartialFillQuantity());
						}
						if (order.isFinalized()) {
							final BigDecimal lockedFunds = order.getTotalOrderAmount();
							BigDecimal unspentAmount = lockedFunds.subtract(order.getTotalTraded());

							if (unspentAmount.compareTo(BigDecimal.ZERO) != 0) {
								account.addToFreeBalance(funds, unspentAmount);
							}

							account.subtractFromLockedBalance(funds, lockedFunds);

							BigDecimal maxFees = BigDecimal.valueOf(getTradingFees().feesOnTotalOrderAmount(order));
							account.subtractFromLockedOrFreeBalance(order.getFundsSymbol(), maxFees);
							account.addToFreeBalance(order.getFundsSymbol(), maxFees.subtract(order.getFeesPaid()));
						}
					} else if (order.isShort()) {
						if (order.hasPartialFillDetails()) {
							BigDecimal covered = order.getPartialFillQuantity();
							BigDecimal shorted = account.getPreciseShortedAmount(asset);

							if (covered.compareTo(shorted) > 0) { //bought to fully cover short and hold long position
								account.subtractFromShortedBalance(asset, shorted);
								account.subtractFromMarginReserveBalance(funds, asset, shorted.multiply(order.getAveragePrice()));

								BigDecimal remainderBought = covered.subtract(shorted);
								account.addToFreeBalance(asset, remainderBought);
								updateMarginReserve(asset, funds, candle);
								account.subtractFromFreeBalance(funds, remainderBought.multiply(order.getAveragePrice()));
							} else {
								account.subtractFromShortedBalance(asset, covered);
								account.subtractFromMarginReserveBalance(funds, asset, lastFillTotalPrice);
								updateMarginReserve(asset, funds, candle);
							}

							double fee = tradingFees.feesOnAmount(order.getPartialFillTotalPrice().doubleValue(), order.getType(), order.getSide());
							account.subtractFromFreeBalance(order.getFundsSymbol(), BigDecimal.valueOf(fee));
						}
					}
				} else if (order.isSell()) {
					if (order.isLong()) {
						if (order.hasPartialFillDetails()) {
							account.addToFreeBalance(funds, lastFillTotalPrice);
							account.subtractFromLockedBalance(asset, order.getPartialFillQuantity());
							double fee = tradingFees.feesOnAmount(order.getPartialFillTotalPrice().doubleValue(), order.getType(), order.getSide());
							account.subtractFromFreeBalance(order.getFundsSymbol(), BigDecimal.valueOf(fee));
						}

						if (order.isFinalized()) {
							if (order.getParent() == null || (order.getParent().getAttachments().size() > 1 && order.getExecutedQuantity().compareTo(BigDecimal.ZERO) > 0)) {
								account.addToFreeBalance(asset, order.getRemainingQuantity());
								account.subtractFromLockedBalance(asset, order.getRemainingQuantity());
							}
						}
					} else if (order.isShort()) {
						if (order.hasPartialFillDetails()) {
							BigDecimal total = order.getPartialFillTotalPrice();
							BigDecimal totalReserve = account.applyMarginReserve(total);
							BigDecimal accountReserve = totalReserve.subtract(total);
							account.addToMarginReserveBalance(funds, asset, totalReserve);

							account.subtractFromLockedOrFreeBalance(funds, asset, accountReserve);
							account.addToShortedBalance(asset, order.getPartialFillQuantity());

							double fee = tradingFees.feesOnAmount(order.getPartialFillTotalPrice().doubleValue(), order.getType(), order.getSide());
							BigDecimal partialFillFee = BigDecimal.valueOf(fee);
							account.subtractFromLockedOrFreeBalance(order.getFundsSymbol(), partialFillFee);
						}

						if (order.isFinalized()) {
							BigDecimal totalTraded = order.getTotalTraded();
							BigDecimal totalReserve = account.applyMarginReserve(order.getTotalOrderAmount());
							BigDecimal unusedReserve = totalReserve.subtract(account.applyMarginReserve(totalTraded));
							if (unusedReserve.compareTo(BigDecimal.ZERO) > 0) {
								BigDecimal unusedFunds = unusedReserve.subtract(order.getTotalOrderAmountAtAveragePrice().subtract(totalTraded));
								account.subtractFromLockedBalance(funds, unusedFunds);
								account.addToFreeBalance(funds, unusedFunds);
							}

							double maxFee = tradingFees.feesOnAmount(order.getQuantity().doubleValue() * order.getPrice().doubleValue(), order.getType(), order.getSide());
							BigDecimal lockedReserveForFees = BigDecimal.valueOf(maxFee - order.getFeesPaid().doubleValue());
							if(lockedReserveForFees.compareTo(BigDecimal.ZERO) > 0) {
								account.subtractFromLockedBalance(funds, lockedReserveForFees);
								account.addToFreeBalance(funds, lockedReserveForFees);
							}
						}
					}
				}
			}
		} catch (Exception e) {
			throw new IllegalStateException("Error updating balances from order " + order, e);
		} finally {
			order.clearPartialFillDetails();
		}
	}

	@Override
	public int marginReservePercentage() {
		return marginReservePercentage;
	}
}


