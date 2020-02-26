package com.univocity.trader.account;

import com.univocity.trader.*;
import com.univocity.trader.candles.*;
import com.univocity.trader.config.*;
import org.junit.*;

import static com.univocity.trader.account.Order.Type.*;
import static com.univocity.trader.account.Trade.Side.*;
import static com.univocity.trader.indicators.Signal.*;
import static junit.framework.TestCase.*;

/**
 * @author uniVocity Software Pty Ltd - <a href="mailto:dev@univocity.com">dev@univocity.com</a>
 */
public class TradingWithPartialFillTests extends OrderFillChecker {

	protected void configure(SimulationConfiguration configuration) {
		configuration.simulation().emulateSlippage();
	}

	@Test
	public void testLongBuyTradingWithPartialFillThenCancel() {
		AccountManager account = getAccountManager();

		final double MAX = 40.0;
		final double initialBalance = 100;

		account.setAmount("USDT", initialBalance);
		account.configuration().maximumInvestmentAmountPerTrade(MAX);
		Trader trader = account.getTraderOf("ADAUSDT");

		tradeOnPrice(trader, 1, 1.0, BUY);
		final Trade trade = trader.trades().iterator().next();
		Order order = trade.position().iterator().next();

		assertEquals(33.0, order.getExecutedQuantity(), DELTA); //each tick has volume = 33 units
		assertEquals(33.0, account.getBalance("ADA").getFree(), DELTA);
		assertEquals(60.00404, account.getBalance("USDT").getFree(), DELTA);
		assertEquals(39.99596, account.getBalance("USDT").getLocked(), DELTA);

		cancelOrder(account, order, 2);

		assertEquals(33.0, order.getExecutedQuantity(), DELTA); //each tick has volume = 33 units
		assertEquals(33.0, account.getBalance("ADA").getFree(), DELTA);
		assertEquals(0.0, account.getBalance("USDT").getLocked(), DELTA);
		assertEquals(100.0, account.getBalance("USDT").getFree() + order.getExecutedQuantity() + feesOn(33));
		assertEquals(60.00404 + order.getRemainingQuantity() + (feesOn(order.getQuantity()) - feesOn(33.0)), account.getBalance("USDT").getFree(), DELTA);
	}

	@Test
	public void testLongBuyTradingWithPartialFillAverage() {
		AccountManager account = getAccountManager();

		final double MAX = 40.0;
		final double initialBalance = 100;

		account.setAmount("USDT", initialBalance);
		account.configuration().maximumInvestmentAmountPerTrade(MAX);
		Trader trader = account.getTraderOf("ADAUSDT");

		tradeOnPrice(trader, 1, 1.0, BUY);
		final Trade trade = trader.trades().iterator().next();
		Order order = trade.position().iterator().next();

		assertEquals(33.0, order.getExecutedQuantity(), DELTA); //each tick has volume = 33 units
		assertEquals(33.0, account.getBalance("ADA").getFree(), DELTA);
		assertEquals(60.00404, account.getBalance("USDT").getFree(), DELTA);
		assertEquals(39.99596, account.getBalance("USDT").getLocked(), DELTA);
		assertEquals(6.956004, order.getRemainingQuantity(), DELTA);

		trader.tradingManager.updateOpenOrders(trader.symbol(), newTick(2, 0.5)); //next tick should fill 6.956004 units at $0.5

		assertTrue(order.isFinalized());
		assertEquals(100.0, order.getFillPct());

		assertEquals(order.getQuantity(), order.getExecutedQuantity(), DELTA);
		assertEquals(order.getQuantity(), account.getBalance("ADA").getFree(), DELTA);
		assertEquals(0.0, account.getBalance("USDT").getLocked(), DELTA);
		assertEquals(0.0, account.getBalance("ADA").getLocked(), DELTA);

		double feesNotPaid = feesOn(order.getTotalOrderAmount()) - order.getFeesPaid();

		//add amount not spent as price dropped in half
		//using less stringent DELTA here as calculation is correct but assertion fails due to rounding error
		assertEquals(60.00404 + (6.956004 / 2.0) + feesNotPaid, account.getBalance("USDT").getFree(), 0.000001);
		assertEquals(100.0, account.getBalance("USDT").getFree() + (33 * 1.0) + (6.956004 * 0.5) + order.getFeesPaid(), 0.000001);
	}

	@Test
	public void testLongSellTradingWithPartialFill() {
		AccountManager account = getAccountManager();

		long time = 0;
		final double MAX = 40.0;
		final double initialBalance = 100;

		account.setAmount("USDT", initialBalance);
		account.configuration().maximumInvestmentAmountPerTrade(MAX);
		Trader trader = account.getTraderOf("ADAUSDT");

		tradeOnPrice(trader, ++time, 1.0, BUY);

		trader.tradingManager.updateOpenOrders(trader.symbol(), newTick(++time, 0.5)); //next tick should fill 6.956004 units at $0.5
		tradeOnPrice(trader, ++time, 0.8, BUY);
		trader.tradingManager.updateOpenOrders(trader.symbol(), newTick(++time, 0.6)); //next tick should fill 6.956004 units at $0.4

		Balance ada = account.getBalance("ADA");
		Balance usdt = account.getBalance("USDT");

		final Trade trade = trader.trades().iterator().next();

		final double quantity = (33 + 6.956004) + (33 + 16.94500500);
		final double first = 33 * 1.0 + 6.956004 * 0.5;
		final double second = 33 * 0.8 + 16.94500500 * 0.6;

		assertEquals((addFees(first) + addFees(second)) / (quantity), trade.averagePrice(), DELTA);
		assertEquals(0.0, ada.getLocked(), DELTA);
		assertEquals(quantity, ada.getFree(), DELTA);
		assertEquals(0.0, usdt.getLocked(), DELTA);
		//again using less stringent DELTA here as calculation is correct but assertion fails due to rounding error
		assertEquals(100.0 - addFees(first) - addFees(second), usdt.getFree(), 0.000001);
		final double balance = usdt.getFree();

		tradeOnPrice(trader, 1, 1.0, SELL);
		Order order = trade.exitOrders().iterator().next();

		assertEquals(33.0, order.getExecutedQuantity(), DELTA); //each tick has volume = 33 units
		assertEquals(quantity - 33, order.getRemainingQuantity(), DELTA);
		assertEquals(quantity - 33, ada.getLocked(), DELTA);
		assertEquals(0.0, ada.getFree(), DELTA);
		assertEquals(balance + subtractFees(33), usdt.getFree(), DELTA);
		assertEquals(0.0, usdt.getLocked(), DELTA);

		trader.tradingManager.updateOpenOrders(trader.symbol(), newTick(++time, 2.0)); //fills another 33 units
		assertEquals(quantity - 66, order.getRemainingQuantity(), DELTA);
		assertEquals(quantity - 66, ada.getLocked(), DELTA);
		assertEquals(0.0, ada.getFree(), DELTA);
		assertEquals(balance + subtractFees(33) + subtractFees(33 * 2.0), usdt.getFree(), DELTA);
		assertEquals(0.0, usdt.getLocked(), DELTA);

		trader.tradingManager.updateOpenOrders(trader.symbol(), newTick(++time, 1.5)); //fills rest of the order
		assertEquals(0, order.getRemainingQuantity(), DELTA);
		assertEquals(0, ada.getLocked(), DELTA);
		assertEquals(0.0, ada.getFree(), DELTA);
		assertEquals(balance + subtractFees(33.0) + subtractFees(33 * 2.0) + subtractFees((quantity - 66) * 1.5), usdt.getFree(), DELTA);
		assertEquals(0.0, usdt.getLocked(), DELTA);
	}

	@Test
	public void testLongSellTradingWithPartialFillThenCancel() {
		AccountManager account = getAccountManager();

		long time = 0;
		final double MAX = 40.0;
		final double initialBalance = 100;

		account.setAmount("USDT", initialBalance);
		account.configuration().maximumInvestmentAmountPerTrade(MAX);
		Trader trader = account.getTraderOf("ADAUSDT");

		tradeOnPrice(trader, ++time, 1.0, BUY);

		trader.tradingManager.updateOpenOrders(trader.symbol(), newTick(++time, 0.5)); //next tick should fill 6.956004 units at $0.5
		tradeOnPrice(trader, ++time, 0.8, BUY);
		trader.tradingManager.updateOpenOrders(trader.symbol(), newTick(++time, 0.6)); //next tick should fill 6.956004 units at $0.4

		Balance ada = account.getBalance("ADA");
		Balance usdt = account.getBalance("USDT");

		final Trade trade = trader.trades().iterator().next();

		final double quantity = (33 + 6.956004) + (33 + 16.94500500);
		final double first = 33 * 1.0 + 6.956004 * 0.5;
		final double second = 33 * 0.8 + 16.94500500 * 0.6;

		assertEquals((addFees(first) + addFees(second)) / (quantity), trade.averagePrice(), DELTA);
		assertEquals(0.0, ada.getLocked(), DELTA);
		assertEquals(quantity, ada.getFree(), DELTA);
		assertEquals(0.0, usdt.getLocked(), DELTA);
		//again using less stringent DELTA here as calculation is correct but assertion fails due to rounding error
		assertEquals(100.0 - addFees(first) - addFees(second), usdt.getFree(), 0.000001);
		final double balance = usdt.getFree();

		tradeOnPrice(trader, 1, 1.0, SELL);
		Order order = trade.exitOrders().iterator().next();

		assertEquals(33.0, order.getExecutedQuantity(), DELTA); //each tick has volume = 33 units
		assertEquals(quantity - 33, order.getRemainingQuantity(), DELTA);
		assertEquals(quantity - 33, ada.getLocked(), DELTA);
		assertEquals(0.0, ada.getFree(), DELTA);
		assertEquals(balance + subtractFees(33), usdt.getFree(), DELTA);
		assertEquals(0.0, usdt.getLocked(), DELTA);

		trader.tradingManager.updateOpenOrders(trader.symbol(), newTick(++time, 2.0)); //fills another 33 units
		assertEquals(quantity - 66, order.getRemainingQuantity(), DELTA);
		assertEquals(quantity - 66, ada.getLocked(), DELTA);
		assertEquals(0.0, ada.getFree(), DELTA);
		assertEquals(balance + subtractFees(33) + subtractFees(33 * 2.0), usdt.getFree(), DELTA);
		assertEquals(0.0, usdt.getLocked(), DELTA);

		order.cancel();
		trader.tradingManager.updateOpenOrders(trader.symbol(), newTick(++time, 1.5));
		assertEquals(quantity - 66, order.getRemainingQuantity(), DELTA);
		assertEquals(0.0, ada.getLocked(), DELTA);
		assertEquals(quantity - 66, ada.getFree(), DELTA);
		assertEquals(balance + subtractFees(33) + subtractFees(33 * 2.0), usdt.getFree(), DELTA);
		assertEquals(0.0, usdt.getLocked(), DELTA);
	}


	@Test
	public void testShortSellTradingWithPartialFillThenCancel() {
		AccountManager account = getAccountManager();

		final double MAX = 40.0;
		final double initialBalance = 100;

		account.setAmount("USDT", initialBalance);
		account.configuration().maximumInvestmentAmountPerTrade(MAX);
		Trader trader = account.getTraderOf("ADAUSDT");

		Balance ada = account.getBalance("ADA");
		Balance usdt = account.getBalance("USDT");

		tradeOnPrice(trader, 1, 1.0, SELL);
		final Trade trade = trader.trades().iterator().next();
		Order order = trade.position().iterator().next();

		assertEquals(33.0, order.getExecutedQuantity(), DELTA); //each tick has volume = 33 units
		assertEquals(0.0, ada.getFree(), DELTA);
		assertEquals(0.0, ada.getLocked(), DELTA);
		assertEquals(33.0, ada.getShorted(), DELTA);
		assertEquals(79.96, usdt.getFree(), DELTA);

		// 7 units remain to be filled. When shorting we use 50% of funds so (7 / 2) + fees reserved to buy remainder 7 units.
		assertEquals(((40 - 33) / 2.0) + feesOn(7.0), usdt.getLocked(), DELTA);

		// margin over filled portion: 33 + 50%
		assertEquals(33 * 1.5, usdt.getMarginReserve("ADA"), DELTA);

		cancelOrder(account, order, 2);

		assertEquals(33.0, order.getExecutedQuantity(), DELTA); //each tick has volume = 33 units
		assertEquals(0.0, ada.getFree(), DELTA);
		assertEquals(0.0, usdt.getLocked(), DELTA);

		double takenFromFunds = (33 / 2.0);
		assertEquals(initialBalance - takenFromFunds - feesOn(33), usdt.getFree(), DELTA);
		assertEquals(33, ada.getShorted(), DELTA);
		assertEquals(33 * 1.5, usdt.getMarginReserve("ADA"), DELTA);
	}


	@Test
	public void testCancellationOnPartiallyFilledLongBracketOrderProfit() {
		OrderManager om = new DefaultOrderManager() {
			@Override
			public void prepareOrder(SymbolPriceDetails priceDetails, OrderBook book, OrderRequest order, Candle latestCandle) {
				if (order.isBuy() && order.isLong() || order.isSell() && order.isShort()) {
					OrderRequest limitSellOnLoss = order.attach(LIMIT, -1.0);
					OrderRequest takeProfit = order.attach(LIMIT, 1.0);
				}
			}
		};

		AccountManager account = getAccountManager(om);

		account.setAmount("USDT", 100.0);
		long time = 1;

		Order order = submitOrder(account, Order.Side.BUY, LONG, time++, 200, 0.5, om);
		assertNotNull(order);
		assertEquals(99.98990001, account.getBalance("USDT").getLocked(), DELTA);
		executeOrder(account, order, 0.5, time++);

		assertEquals(99.98990001, account.getBalance("USDT").getLocked(), DELTA);
		assertEquals(0.01009999, account.getBalance("USDT").getFree(), DELTA);
		assertEquals(0.0, account.getBalance("ADA").getFree(), DELTA);
		assertEquals(33.0, account.getBalance("ADA").getLocked(), DELTA);

		//fills another 33 ada
		tick(account.getTraderOf("ADAUSDT"), time++, 0.5);

		assertEquals(99.98990001, account.getBalance("USDT").getLocked(), DELTA);
		assertEquals(0.01009999, account.getBalance("USDT").getFree(), DELTA);
		assertEquals(0.0, account.getBalance("ADA").getFree(), DELTA);
		assertEquals(66.0, account.getBalance("ADA").getLocked(), DELTA);

		//tries to sell independently of bracket order. Should not work.
		Order sellOrder = submitOrder(account, Order.Side.SELL, LONG, time++, 30, 0.5, om);
		assertNull(sellOrder);

//		cancel partially filled parent, bracket orders activated as some ADA was bought.
		cancelOrder(account, order, 0.5, time++);

		assertEquals(0.0, account.getBalance("USDT").getLocked(), DELTA);
		assertEquals(100 - order.getTotalTraded() - order.getFeesPaid(), account.getBalance("USDT").getFree(), DELTA);
		//66 units locked by bracket orders.
		assertEquals(0.0, account.getBalance("ADA").getFree(), DELTA);
		assertEquals(66.0, account.getBalance("ADA").getLocked(), DELTA);

		//sell some of the ADA, 33 units available to sell only.
		tick(account.getTraderOf("ADAUSDT"), time++, 0.6);

		assertEquals(0.0, account.getBalance("USDT").getLocked(), DELTA);
		assertEquals(100 - order.getTotalTraded() - order.getFeesPaid() + subtractFees(33 * 0.6), account.getBalance("USDT").getFree(), DELTA);
		assertEquals(0.0, account.getBalance("ADA").getFree(), DELTA);
		assertEquals(33.0, account.getBalance("ADA").getLocked(), DELTA);

		long finalTime = time;
		order.getAttachments().forEach(o -> cancelOrder(account, o, 0.5, finalTime));

		Order profitOrder = order.getAttachments().get(1);

		assertEquals(0.0, account.getBalance("USDT").getLocked(), DELTA);
		assertEquals(100 - order.getTotalTraded() - order.getFeesPaid() + profitOrder.getTotalTraded() - profitOrder.getFeesPaid(), account.getBalance("USDT").getFree(), DELTA);
		assertEquals(33.0, account.getBalance("ADA").getFree(), DELTA);
		assertEquals(0.0, account.getBalance("ADA").getLocked(), DELTA);
	}


	@Test
	public void testCancellationOnPartiallyFilledLongBracketOrderLoss() {
		OrderManager om = new DefaultOrderManager() {
			@Override
			public void prepareOrder(SymbolPriceDetails priceDetails, OrderBook book, OrderRequest order, Candle latestCandle) {
				if (order.isBuy() && order.isLong() || order.isSell() && order.isShort()) {
					OrderRequest limitSellOnLoss = order.attach(LIMIT, -1.0);
					OrderRequest takeProfit = order.attach(LIMIT, 1.0);
				}
			}
		};

		AccountManager account = getAccountManager(om);

		account.setAmount("USDT", 100.0);
		long time = 1;

		Order order = submitOrder(account, Order.Side.BUY, LONG, time++, 200, 0.5, om);
		assertNotNull(order);
		assertEquals(99.98990001, account.getBalance("USDT").getLocked(), DELTA);
		executeOrder(account, order, 0.5, time++);

		assertEquals(99.98990001, account.getBalance("USDT").getLocked(), DELTA);
		assertEquals(0.01009999, account.getBalance("USDT").getFree(), DELTA);
		assertEquals(0.0, account.getBalance("ADA").getFree(), DELTA);
		assertEquals(33.0, account.getBalance("ADA").getLocked(), DELTA);

		//fills another 33 ada
		tick(account.getTraderOf("ADAUSDT"), time++, 0.5);

		assertEquals(99.98990001, account.getBalance("USDT").getLocked(), DELTA);
		assertEquals(0.01009999, account.getBalance("USDT").getFree(), DELTA);
		assertEquals(0.0, account.getBalance("ADA").getFree(), DELTA);
		assertEquals(66.0, account.getBalance("ADA").getLocked(), DELTA);

		//tries to sell independently of bracket order. Should not work.
		Order sellOrder = submitOrder(account, Order.Side.SELL, LONG, time++, 30, 0.5, om);
		assertNull(sellOrder);

//		cancel partially filled parent, bracket orders activated as some ADA was bought.
		cancelOrder(account, order, 0.5, time++);

		assertEquals(0.0, account.getBalance("USDT").getLocked(), DELTA);
		assertEquals(100 - order.getTotalTraded() - order.getFeesPaid(), account.getBalance("USDT").getFree(), DELTA);
		//66 units locked by bracket orders.
		assertEquals(0.0, account.getBalance("ADA").getFree(), DELTA);
		assertEquals(66.0, account.getBalance("ADA").getLocked(), DELTA);

		double price = 0.5 * 0.99; //loss of 1%

		//sell some of the ADA, 33 units available to sell only.
		tick(account.getTraderOf("ADAUSDT"), time++, price);

		assertEquals(0.0, account.getBalance("USDT").getLocked(), DELTA);
		assertEquals(100 - order.getTotalTraded() - order.getFeesPaid() + subtractFees(33 * price), account.getBalance("USDT").getFree(), DELTA);
		assertEquals(0.0, account.getBalance("ADA").getFree(), DELTA);
		assertEquals(33.0, account.getBalance("ADA").getLocked(), DELTA);

		long finalTime = time;
		order.getAttachments().forEach(o -> cancelOrder(account, o, 0.5, finalTime));

		Order lossOrder = order.getAttachments().get(0);

		assertEquals(0.0, account.getBalance("USDT").getLocked(), DELTA);
		assertEquals(100 - order.getTotalTraded() - order.getFeesPaid() + lossOrder.getTotalTraded() - lossOrder.getFeesPaid(), account.getBalance("USDT").getFree(), DELTA);
		assertEquals(33.0, account.getBalance("ADA").getFree(), DELTA);
		assertEquals(0.0, account.getBalance("ADA").getLocked(), DELTA);
	}

	@Test
	public void testCancellationOnPartiallyFilledShortBracketOrderProfit() {
		OrderManager om = new DefaultOrderManager() {
			@Override
			public void prepareOrder(SymbolPriceDetails priceDetails, OrderBook book, OrderRequest order, Candle latestCandle) {
				if (order.isBuy() && order.isLong() || order.isSell() && order.isShort()) {
					OrderRequest limitSellOnLoss = order.attach(LIMIT, -1.0);
					OrderRequest takeProfit = order.attach(LIMIT, 1.0);
				}
			}
		};

		AccountManager account = getAccountManager(om);

		account.setAmount("USDT", 100.0);
		long time = 1;

		Order order = submitOrder(account, Order.Side.SELL, SHORT, time++, 200, 0.5, om);
		assertNotNull(order);
		assertEquals(50.04489501, account.getBalance("USDT").getLocked(), DELTA);
		executeOrder(account, order, 0.5, time++);

		assertEquals((33 * 0.5) + (33 * 0.5 / 2.0), account.getBalance("USDT").getMarginReserve("ADA"), DELTA); //proceeds + 50% reserve
		assertEquals(50.04489501 - (33 * 0.5 / 2.0) - feesOn(33 * 0.5), account.getBalance("USDT").getLocked(), DELTA); //50% of traded amount moved from locked to margin reserve
		assertEquals(49.95510499, account.getBalance("USDT").getFree(), DELTA);
		assertEquals(0.0, account.getBalance("ADA").getFree(), DELTA);
		assertEquals(0.0, account.getBalance("ADA").getLocked(), DELTA);
		assertEquals(33.0, account.getBalance("ADA").getShorted(), DELTA);

		//fills another 33 ada
		tick(account.getTraderOf("ADAUSDT"), time++, 0.5);

		assertEquals((66 * 0.5) + (66 * 0.5 / 2.0), account.getBalance("USDT").getMarginReserve("ADA"), DELTA); //proceeds + 50% reserve
		assertEquals(50.04489501 - (66 * 0.5 / 2.0) - feesOn(66 * 0.5), account.getBalance("USDT").getLocked(), DELTA);  //50% of traded amount moved from locked to margin reserve
		assertEquals(49.95510499, account.getBalance("USDT").getFree(), DELTA);
		assertEquals(0.0, account.getBalance("ADA").getFree(), DELTA);
		assertEquals(0.0, account.getBalance("ADA").getLocked(), DELTA);
		assertEquals(66.0, account.getBalance("ADA").getShorted(), DELTA);

//		cancel partially filled parent, bracket orders activated as 66 ADA were sold short.
		cancelOrder(account, order, 0.5, time++);

		final double originalReserve = (66 * 0.5) + (66 * 0.5 / 2.0);//proceeds + 50% reserve
		assertEquals(originalReserve, account.getBalance("USDT").getMarginReserve("ADA"), DELTA);
		assertEquals(0.0, account.getBalance("USDT").getLocked(), DELTA);
		final double free = 49.95510499 + 50.04489501 - ((66 * 0.5 / 2.0) + feesOn(66 * 0.5));//amount previously locked returned for margin reserve not used and moved back to free funds + fees to buy shorted assets back
		assertEquals(free, account.getBalance("USDT").getFree(), DELTA);
		assertEquals(0.0, account.getBalance("ADA").getFree(), DELTA);
		assertEquals(0.0, account.getBalance("ADA").getLocked(), DELTA);
		assertEquals(66.0, account.getBalance("ADA").getShorted(), DELTA);

		//buy some of the ADA back, 33 units available to buy only.
		tick(account.getTraderOf("ADAUSDT"), time++, 0.4);

		assertEquals(0.0, account.getBalance("ADA").getFree(), DELTA);
		assertEquals(0.0, account.getBalance("ADA").getLocked(), DELTA);
		assertEquals(33.0, account.getBalance("ADA").getShorted(), DELTA);

		assertEquals(0.0, account.getBalance("USDT").getLocked(), DELTA);

		double newReserve = (33 * 0.4) + (33 * 0.4 / 2.0);
		assertEquals(newReserve, account.getBalance("USDT").getMarginReserve("ADA"), DELTA);

		double originalReserveRemaining = originalReserve - 33 * 0.4; //subtract amount bought back from original reserve
		double freed = originalReserveRemaining - newReserve;
		assertEquals(free + freed - feesOn(33 * 0.4), account.getBalance("USDT").getFree(), DELTA); //amount returned from margin reserve

		long finalTime = time;
		order.getAttachments().forEach(o -> cancelOrder(account, o, 0.5, finalTime));

		assertEquals(33.0, account.getBalance("ADA").getShorted(), DELTA);
		assertEquals(0.0, account.getBalance("ADA").getFree(), DELTA);
		assertEquals(0.0, account.getBalance("ADA").getLocked(), DELTA);

		assertEquals(0.0, account.getBalance("USDT").getLocked(), DELTA);
		assertEquals(newReserve, account.getBalance("USDT").getMarginReserve("ADA"), DELTA);
		assertEquals(free + freed - feesOn(33 * 0.4), account.getBalance("USDT").getFree(), DELTA);

	}
}
