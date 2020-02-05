package com.univocity.trader.account;

import com.univocity.trader.*;
import com.univocity.trader.candles.*;
import com.univocity.trader.config.*;
import com.univocity.trader.indicators.*;
import com.univocity.trader.simulation.*;

import java.math.*;
import java.util.*;
import java.util.function.*;

import static junit.framework.TestCase.*;

/**
 * @author uniVocity Software Pty Ltd - <a href="mailto:dev@univocity.com">dev@univocity.com</a>
 */
public class OrderFillChecker {
	static final double CLOSE = 0.4379;
	static final double DELTA = 0.00000001;

	AccountManager getAccountManager() {
		return getAccountManager(null);
	}

	AccountManager getAccountManager(OrderManager orderManager) {
		SimulationConfiguration configuration = new SimulationConfiguration();

		SimulationAccount accountCfg = new SimulationConfiguration().account();
		accountCfg
				.referenceCurrency("USDT")
				.tradeWithPair("ADA", "BNB")
				.tradeWith("ADA", "BNB")
				.enableShorting();

		if (orderManager != null) {
			accountCfg.orderManager(orderManager);
		}


		SimulatedClientAccount clientAccount = new SimulatedClientAccount(accountCfg, configuration.simulation());
		AccountManager account = clientAccount.getAccount();

		TradingManager m = new TradingManager(new SimulatedExchange(account), null, account, "ADA", "USDT", Parameters.NULL);
		Trader trader = new Trader(m, null, new HashSet<>());
		trader.trade(new Candle(1, 2, 0.04371, 0.4380, 0.4369, CLOSE, 100.0), Signal.NEUTRAL, null);

		m = new TradingManager(new SimulatedExchange(account), null, account, "BNB", "USDT", Parameters.NULL);
		trader = new Trader(m, null, new HashSet<>());
		trader.trade(new Candle(1, 2, 50, 50, 50, 50, 100.0), Signal.NEUTRAL, null);

		account.setAmount("BNB", 1);

		return account;
	}

	void tradeOnPrice(Trader trader, long time, double price, Signal signal, boolean cancel) {
		Candle next = newTick(time, price);
		trader.trade(next, signal, null);
		if (signal != Signal.NEUTRAL) {
			if (cancel) {
				trader.trades().iterator().next().position().forEach(Order::cancel);
			}
			trader.tradingManager.updateOpenOrders(trader.symbol(), next = newTick(time + 1, price));
			trader.trade(next, Signal.NEUTRAL, null);
		}
	}

	void checkProfitLoss(Trade trade, double initialBalance, double totalInvested) {
		Trader trader = trade.trader();
		AccountManager account = trader.tradingManager.getAccount();

		double finalBalance = account.getAmount("USDT");
		double profitLoss = finalBalance - initialBalance;
		assertEquals(profitLoss, trade.actualProfitLoss(), DELTA);

		double invested = totalInvested + trader.tradingFees().feesOnAmount(totalInvested, Order.Type.LIMIT, Order.Side.SELL);
		double profitLossPercentage = ((profitLoss / invested)) * 100.0;
		assertEquals(profitLossPercentage, trade.actualProfitLossPct(), DELTA);
	}

	double calculateBuyingQuantity(double usdBalanceBeforeTrade, double spendingLimit, double unitPrice) {
		if (spendingLimit > usdBalanceBeforeTrade) {
			spendingLimit = usdBalanceBeforeTrade;
		}
		double fees = feesOn(spendingLimit);
		spendingLimit = spendingLimit - fees;

		double quantity = (spendingLimit / unitPrice) * 0.9999; //quantity adjustment to ensure exchange doesn't reject order for mismatching decimals
		return quantity;
	}

	double addFees(double total) {
		return total + feesOn(total);
	}

	double addFees(BigDecimal total) {
		return total.doubleValue() + feesOn(total);
	}

	double feesOn(double total) {
		return total * 0.001;
	}

	double feesOn(BigDecimal total) {
		return total.doubleValue() * 0.001;
	}

	double checkTradeAfterLongBuy(double usdBalanceBeforeTrade, Trade trade, double spendingLimit, double previousQuantity, double unitPrice, double maxUnitPrice, double minUnitPrice, Function<AccountManager, Double> assetBalance) {
		Trader trader = trade.trader();

		double quantity = calculateBuyingQuantity(usdBalanceBeforeTrade, spendingLimit, unitPrice);
		double totalCost = quantity * unitPrice;
		double fees = feesOn(totalCost);

		double totalQuantity = quantity + previousQuantity;

		checkLongTradeStats(trade, unitPrice, maxUnitPrice, minUnitPrice);

		assertEquals(totalQuantity, trade.quantity(), DELTA);

		AccountManager account = trader.tradingManager.getAccount();
		assertEquals(totalQuantity, assetBalance.apply(account), DELTA);

		double balance = account.getAmount("USDT");
		assertEquals(usdBalanceBeforeTrade - (totalCost + fees), balance, DELTA);

		return quantity;
	}

	void checkTradeAfterLongSell(double usdBalanceBeforeTrade, Trade trade, double quantity, double unitPrice, double maxUnitPrice, double minUnitPrice) {
		Trader trader = trade.trader();
		final TradingFees fees = trader.tradingFees();

		double totalToReceive = quantity * unitPrice;

		final double receivedAfterFees = fees.takeFee(totalToReceive, Order.Type.LIMIT, Order.Side.SELL);

		checkLongTradeStats(trade, unitPrice, maxUnitPrice, minUnitPrice);

		assertEquals(quantity, trade.quantity(), DELTA);
		AccountManager account = trader.tradingManager.getAccount();
		assertEquals(0.0, account.getAmount("ADA"), DELTA);
		assertEquals(usdBalanceBeforeTrade + receivedAfterFees, account.getAmount("USDT"), DELTA);
	}

	double checkTradeAfterLongBuy(double usdBalanceBeforeTrade, Trade trade, double totalSpent, double previousQuantity, double unitPrice, double maxUnitPrice, double minUnitPrice) {
		return checkTradeAfterLongBuy(usdBalanceBeforeTrade, trade, totalSpent, previousQuantity, unitPrice, maxUnitPrice, minUnitPrice,
				(account) -> account.getAmount("ADA"));
	}

	double checkTradeAfterLongBracketOrder(double usdBalanceBeforeTrade, Trade trade, double totalSpent, double previousQuantity, double unitPrice, double maxUnitPrice, double minUnitPrice) {
		return checkTradeAfterLongBuy(usdBalanceBeforeTrade, trade, totalSpent, previousQuantity, unitPrice, maxUnitPrice, minUnitPrice,
				//bracket order locks amount bought to sell it back in two opposing orders.
				//locked balance must be relative to amount bought in parent order, and both orders share the same locked balance.
				(account) -> account.getBalance("ADA").getLocked().doubleValue());

	}


	void checkLongTradeStats(Trade trade, double unitPrice, double maxUnitPrice, double minUnitPrice) {
		final double change = ((unitPrice - trade.averagePrice()) / trade.averagePrice()) * 100.0;
		final double minChange = ((minUnitPrice - trade.averagePrice()) / trade.averagePrice()) * 100.0;
		final double maxChange = ((maxUnitPrice - trade.averagePrice()) / trade.averagePrice()) * 100.0;

		assertEquals(maxChange, trade.maxChange(), DELTA);
		assertEquals(minChange, trade.minChange(), DELTA);
		assertEquals(change, trade.priceChangePct(), DELTA);
		assertEquals(maxUnitPrice, trade.maxPrice(), DELTA);
		assertEquals(minUnitPrice, trade.minPrice(), DELTA);
		assertEquals(unitPrice, trade.lastClosingPrice(), DELTA);

	}

	void checkShortTradeStats(Trade trade, double unitPrice, double maxUnitPrice, double minUnitPrice) {
		final double change = ((trade.averagePrice() - unitPrice) / trade.averagePrice()) * 100.0;
		final double minChange = ((trade.averagePrice() - maxUnitPrice) / trade.averagePrice()) * 100.0;
		final double maxChange = ((trade.averagePrice() - minUnitPrice) / trade.averagePrice()) * 100.0;

		assertEquals(maxChange, trade.maxChange(), DELTA);
		assertEquals(minChange, trade.minChange(), DELTA);
		assertEquals(change, trade.priceChangePct(), DELTA);
		assertEquals(maxUnitPrice, trade.maxPrice(), DELTA);
		assertEquals(minUnitPrice, trade.minPrice(), DELTA);
		assertEquals(unitPrice, trade.lastClosingPrice(), DELTA);
	}

	void checkTradeAfterShortBuy(double usdBalanceBeforeTrade, double usdReservedBeforeTrade, Trade trade, double quantity, double unitPrice, double maxUnitPrice, double minUnitPrice) {
		Trader trader = trade.trader();
		final TradingFees fees = trader.tradingFees();

		checkShortTradeStats(trade, unitPrice, maxUnitPrice, minUnitPrice);

		assertEquals(quantity, trade.quantity(), DELTA);
		AccountManager account = trader.tradingManager.getAccount();
		assertEquals(0.0, account.getAmount("ADA"), DELTA);

		assertEquals(0.0, account.getBalance("ADA").getFreeAmount(), DELTA);
		assertEquals(0.0, account.getBalance("ADA").getLocked().doubleValue(), DELTA);
		assertEquals(0.0, account.getBalance("ADA").getShortedAmount(), DELTA);
		assertEquals(0.0, account.getMarginReserve("USDT", "ADA").doubleValue(), DELTA);

		double pricePaid = quantity * unitPrice;
		double rebuyCostAfterFees = pricePaid + fees.feesOnAmount(pricePaid, Order.Type.LIMIT, Order.Side.BUY);

		double tradeProfit = usdReservedBeforeTrade - rebuyCostAfterFees;
		double netAccountBalance = usdBalanceBeforeTrade + tradeProfit;

		assertEquals(netAccountBalance, account.getAmount("USDT"), DELTA);
	}


	double checkTradeAfterBracketShortSell(double usdBalanceBeforeTrade, double usdReservedBeforeTrade, Trade trade, double totalSpent, double previousQuantity, double unitPrice, double maxUnitPrice, double minUnitPrice) {
		Trader trader = trade.trader();

		double feesPaid = totalSpent - totalSpent;
		double quantityAfterFees = (totalSpent / unitPrice);

		double totalQuantity = quantityAfterFees + previousQuantity;

		checkShortTradeStats(trade, unitPrice, maxUnitPrice, minUnitPrice);

		assertEquals(totalQuantity, trade.quantity(), DELTA);

		AccountManager account = trader.tradingManager.getAccount();
		assertEquals(0.0, account.getAmount("ADA"), DELTA);
		assertEquals(totalQuantity, account.getShortedAmount("ADA"), DELTA); //orders submitted to buy it all back
		assertEquals(0.0, account.getBalance("ADA").getLocked().doubleValue(), DELTA);

		double inReserve = account.marginReserveFactorPct() * totalSpent;
		assertEquals(inReserve + usdReservedBeforeTrade, account.getMarginReserve("USDT", "ADA").doubleValue(), DELTA);

		double movedToReserve = inReserve - totalSpent;
		double freeBalance = usdBalanceBeforeTrade - (movedToReserve + feesPaid);
		assertEquals(freeBalance, account.getAmount("USDT"), DELTA);

		return quantityAfterFees;
	}


	double checkTradeAfterShortSell(double usdBalanceBeforeTrade, double usdReservedBeforeTrade, Trade trade, double totalSpent, double previousQuantity, double unitPrice, double maxUnitPrice, double minUnitPrice) {
		Trader trader = trade.trader();

		double feesPaid = totalSpent - totalSpent;
		double quantityAfterFees = (totalSpent / unitPrice);

		double totalQuantity = quantityAfterFees + previousQuantity;

		checkShortTradeStats(trade, unitPrice, maxUnitPrice, minUnitPrice);

		assertEquals(totalQuantity, trade.quantity(), DELTA);

		AccountManager account = trader.tradingManager.getAccount();
		assertEquals(0.0, account.getAmount("ADA"), DELTA);
		assertEquals(totalQuantity, account.getShortedAmount("ADA"), DELTA);

		double inReserve = account.marginReserveFactorPct() * totalSpent;
		assertEquals(inReserve + usdReservedBeforeTrade, account.getMarginReserve("USDT", "ADA").doubleValue(), DELTA);

		double movedToReserve = inReserve - totalSpent;
		double freeBalance = usdBalanceBeforeTrade - (movedToReserve + feesPaid);
		assertEquals(freeBalance, account.getAmount("USDT"), DELTA);

		return quantityAfterFees;
	}


	void tradeOnPrice(Trader trader, long time, double price, Signal signal) {
		tradeOnPrice(trader, time, price, signal, false);
	}

	Candle newTick(long time, double price) {
		return new Candle(time, time, price, price, price, price, 100.0);
	}

}
