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
		double unitPrice = orderDetails.getPrice();
		double orderAmount = orderDetails.getTotalOrderAmount();

		double availableFunds = account.getPreciseAmount(fundsSymbol);
		double availableAssets = account.getPreciseAmount(assetsSymbol);
		if (orderDetails.isShort()) {
			if (orderDetails.isBuy()) {
				availableFunds = availableFunds+account.getMarginReserve(fundsSymbol, assetsSymbol);
			} else if (orderDetails.isSell()) {
				availableAssets = account.getPreciseShortedAmount(assetsSymbol);
			}
		}

		double quantity = orderDetails.getQuantity();
		double fees = getTradingFees().feesOnOrder(orderDetails);
		boolean hasFundsAvailable = availableFunds - fees >= orderAmount - EFFECTIVELY_ZERO;
		if (!hasFundsAvailable && !orderDetails.isLongSell()) {
			double maxAmount = orderAmount - fees;
			double price = orderDetails.getPrice();
			if (price <= EFFECTIVELY_ZERO) {
				price = getAccount().getTraderOf(orderDetails.getSymbol()).lastClosingPrice();
			}
			quantity = (maxAmount / price) * 0.9999;
			orderDetails.setQuantity(quantity);

			double currentOrderAmount = orderDetails.getTotalOrderAmount();
			if (fees < currentOrderAmount && currentOrderAmount / maxAmount > 0.95) { //ensure we didn't create a very small order
				fees = getTradingFees().feesOnOrder(orderDetails);
				hasFundsAvailable = availableFunds - fees >= currentOrderAmount - EFFECTIVELY_ZERO;
				orderAmount = orderDetails.getTotalOrderAmount();
			}
		}


		DefaultOrder order = null;

		if (orderDetails.isBuy() && hasFundsAvailable) {
			if (orderDetails.isLong()) {
				account.lockAmount(fundsSymbol, orderAmount + fees);
			}
			order = createOrder(orderDetails, quantity, unitPrice);

		} else if (orderDetails.isSell()) {
			if (orderDetails.isLong()) {
				if (availableAssets < quantity) {
					double difference = 1.0 - (availableAssets / quantity);
					if (difference < 0.00001) { //0.001% quantity mismatch.
						quantity = availableAssets * 0.9999;
					}
				}
				if (availableAssets >= quantity) {
					account.lockAmount(assetsSymbol, orderDetails.getQuantity());
					order = createOrder(orderDetails, quantity, unitPrice);
				}
			} else if (orderDetails.isShort()) {
				if (hasFundsAvailable) {
					double locked = account.applyMarginReserve(orderAmount) - orderAmount;
					account.lockAmount(fundsSymbol, locked + fees);
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

	private DefaultOrder createOrder(OrderRequest request, double quantity, double price) {
		DefaultOrder out = new DefaultOrder(request);
		initializeOrder(out, price, quantity, request);
		return out;
	}

	private void initializeOrder(DefaultOrder out, double price, double quantity, OrderRequest request) {
		out.setTriggerCondition(request.getTriggerCondition(), request.getTriggerPrice());
		out.setPrice(price);
		out.setQuantity(quantity);
		out.setType(request.getType());
		out.setStatus(Order.Status.NEW);
		out.setExecutedQuantity(0.0);
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
//		System.out.println("-------");
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

			order.setFeesPaid(order.getFeesPaid()+ getTradingFees().feesOnPartialFill(order));

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

			if (triggeredOrder != null && triggeredOrder.getQuantity() > 0) {
				processAttachedOrder(order, (DefaultOrder) triggeredOrder, candle);
			}
		}
		return true;
	}

	private void processAttachedOrder(DefaultOrder parent, DefaultOrder attached, Candle candle) {
		double locked = parent.getExecutedQuantity();
		if (locked > 0) {
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

		Double triggerPrice;
		Order.TriggerCondition trigger;
		if (attachment == null) {
			if (parent.getTriggerCondition() == Order.TriggerCondition.NONE) {
				return false;
			}
			triggerPrice = parent.getTriggerPrice();
			trigger = parent.getTriggerCondition();
		} else {
			triggerPrice = (attachment.getTriggerPrice() != 0.0) ? attachment.getTriggerPrice() : attachment.getPrice();
			trigger = attachment.getTriggerCondition();
		}
		if (triggerPrice == null) {
			return false;
		}

		double conditionalPrice = triggerPrice;

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
		double reserved = funds.getMarginReserve(assetSymbol);

		double shortedQuantity = account.getBalance(assetSymbol).getShorted();
		if (shortedQuantity <= EFFECTIVELY_ZERO) {
			funds.setFree(funds.getFree() + reserved);
			funds.setMarginReserve(assetSymbol, 0.0);
		} else {
			double close;
			if (candle == null) {
				Trader trader = getAccount().getTraderOf(assetSymbol + fundSymbol);
				close = trader.lastClosingPrice();
			} else {
				close = candle.close;
			}

			double newReserve = account.applyMarginReserve(shortedQuantity * close);
			funds.setFree(funds.getFree() + reserved - newReserve);
			funds.setMarginReserve(assetSymbol, newReserve);
		}
	}

	private double getLockedFees(Order order) {
		return getTradingFees().feesOnTotalOrderAmount(order);
	}

	private void updateBalances(DefaultOrder order, Candle candle) {
		final String asset = order.getAssetsSymbol();
		final String funds = order.getFundsSymbol();

		final double lastFillTotalPrice = order.getPartialFillTotalPrice();

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
							final double lockedFunds = order.getTotalOrderAmount();
							double unspentAmount = lockedFunds - order.getTotalTraded();

							if (unspentAmount != 0) {
								account.addToFreeBalance(funds, unspentAmount);
							}

							account.subtractFromLockedBalance(funds, lockedFunds);

							double maxFees =getTradingFees().feesOnTotalOrderAmount(order);
							account.subtractFromLockedOrFreeBalance(order.getFundsSymbol(), maxFees);
							account.addToFreeBalance(order.getFundsSymbol(), maxFees-order.getFeesPaid());
						}
					} else if (order.isShort()) {
						if (order.hasPartialFillDetails()) {
							double covered = order.getPartialFillQuantity();
							double shorted = account.getPreciseShortedAmount(asset);

							if (covered >= shorted) { //bought to fully cover short and hold long position
								account.subtractFromShortedBalance(asset, shorted);
								account.subtractFromMarginReserveBalance(funds, asset, shorted*order.getAveragePrice());

								double remainderBought = covered - shorted;
								account.addToFreeBalance(asset, remainderBought);
								updateMarginReserve(asset, funds, candle);
								account.subtractFromFreeBalance(funds, remainderBought * order.getAveragePrice());
							} else {
								account.subtractFromShortedBalance(asset, covered);
								account.subtractFromMarginReserveBalance(funds, asset, lastFillTotalPrice);
								updateMarginReserve(asset, funds, candle);
							}

							double fee = tradingFees.feesOnAmount(order.getPartialFillTotalPrice(), order.getType(), order.getSide());
							account.subtractFromFreeBalance(order.getFundsSymbol(),fee);
						}
					}
				} else if (order.isSell()) {
					if (order.isLong()) {
						if (order.hasPartialFillDetails()) {
							account.addToFreeBalance(funds, lastFillTotalPrice);
							account.subtractFromLockedBalance(asset, order.getPartialFillQuantity());
							double fee = tradingFees.feesOnAmount(order.getPartialFillTotalPrice(), order.getType(), order.getSide());
							account.subtractFromFreeBalance(order.getFundsSymbol(),fee);
						}

						if (order.isFinalized()) {
							if (order.getParent() == null || (order.getParent().getAttachments().size() > 1 && order.getExecutedQuantity()> 0)) {
								account.addToFreeBalance(asset, order.getRemainingQuantity());
								account.subtractFromLockedBalance(asset, order.getRemainingQuantity());
							}
						}
					} else if (order.isShort()) {
						if (order.hasPartialFillDetails()) {
							double total = order.getPartialFillTotalPrice();
							double totalReserve = account.applyMarginReserve(total);
							double accountReserve = totalReserve - total;
							account.addToMarginReserveBalance(funds, asset, totalReserve);

							account.subtractFromLockedOrFreeBalance(funds, asset, accountReserve);
							account.addToShortedBalance(asset, order.getPartialFillQuantity());

							double fee = tradingFees.feesOnAmount(order.getPartialFillTotalPrice(), order.getType(), order.getSide());
							account.subtractFromLockedOrFreeBalance(order.getFundsSymbol(), fee);
						}

						if (order.isFinalized()) {
							double totalTraded = order.getTotalTraded();
							double totalReserve = account.applyMarginReserve(order.getTotalOrderAmount());
							double unusedReserve = totalReserve - account.applyMarginReserve(totalTraded);
							if (unusedReserve > 0) {
								double unusedFunds = unusedReserve - (order.getTotalOrderAmountAtAveragePrice() - totalTraded);
								account.subtractFromLockedBalance(funds, unusedFunds);
								account.addToFreeBalance(funds, unusedFunds);
							}

							double maxFee = tradingFees.feesOnAmount(order.getQuantity() * order.getPrice(), order.getType(), order.getSide());
							double lockedReserveForFees = maxFee - order.getFeesPaid();
							if(lockedReserveForFees > 0) {
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


