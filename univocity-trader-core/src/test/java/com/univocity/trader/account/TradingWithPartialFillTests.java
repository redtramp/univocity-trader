package com.univocity.trader.account;

import com.univocity.trader.config.*;
import org.junit.*;

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

		assertEquals(33.0, order.getExecutedQuantity().doubleValue(), DELTA); //each tick has volume = 33 units
		assertEquals(33.0, account.getBalance("ADA").getFree().doubleValue(), DELTA);
		assertEquals(60.00404, account.getBalance("USDT").getFree().doubleValue(), DELTA);
		assertEquals(39.99596, account.getBalance("USDT").getLocked().doubleValue(), DELTA);

		cancelOrder(account, order, 2);

		assertEquals(33.0, order.getExecutedQuantity().doubleValue(), DELTA); //each tick has volume = 33 units
		assertEquals(33.0, account.getBalance("ADA").getFree().doubleValue(), DELTA);
		assertEquals(0.0, account.getBalance("USDT").getLocked().doubleValue(), DELTA);
		assertEquals(100.0, account.getBalance("USDT").getFree().doubleValue() + order.getExecutedQuantity().doubleValue() + feesOn(33));
		assertEquals(60.00404 + order.getRemainingQuantity().doubleValue() + (feesOn(order.getQuantity()) - feesOn(33.0)), account.getBalance("USDT").getFree().doubleValue(), DELTA);
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

		assertEquals(33.0, order.getExecutedQuantity().doubleValue(), DELTA); //each tick has volume = 33 units
		assertEquals(33.0, account.getBalance("ADA").getFree().doubleValue(), DELTA);
		assertEquals(60.00404, account.getBalance("USDT").getFree().doubleValue(), DELTA);
		assertEquals(39.99596, account.getBalance("USDT").getLocked().doubleValue(), DELTA);
		assertEquals(6.956004, order.getRemainingQuantity().doubleValue(), DELTA);

		trader.tradingManager.updateOpenOrders(trader.symbol(), newTick(2, 0.5)); //next tick should fill 6.956004 units at $0.5

		assertTrue(order.isFinalized());
		assertEquals(100.0, order.getFillPct());

		assertEquals(order.getQuantity().doubleValue(), order.getExecutedQuantity().doubleValue(), DELTA);
		assertEquals(order.getQuantity().doubleValue(), account.getBalance("ADA").getFree().doubleValue(), DELTA);
		assertEquals(0.0, account.getBalance("USDT").getLocked().doubleValue(), DELTA);
		assertEquals(0.0, account.getBalance("ADA").getLocked().doubleValue(), DELTA);

		double feesNotPaid = feesOn(order.getTotalOrderAmount().doubleValue()) - order.getFeesPaid().doubleValue();

		//add amount not spent as price dropped in half
		//using less stringent DELTA here as calculation is correct but assertion fails due to rounding error
		assertEquals(60.00404 + (6.956004 / 2.0) + feesNotPaid, account.getBalance("USDT").getFree().doubleValue(), 0.000001);
		assertEquals(100.0, account.getBalance("USDT").getFree().doubleValue() + (33 * 1.0) + (6.956004 * 0.5) + order.getFeesPaid().doubleValue(), 0.000001);
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
		assertEquals(0.0, ada.getLocked().doubleValue(), DELTA);
		assertEquals(quantity, ada.getFree().doubleValue(), DELTA);
		assertEquals(0.0, usdt.getLocked().doubleValue(), DELTA);
		//again using less stringent DELTA here as calculation is correct but assertion fails due to rounding error
		assertEquals(100.0 - addFees(first) - addFees(second), usdt.getFree().doubleValue(), 0.000001);
		final double balance = usdt.getFreeAmount();

		tradeOnPrice(trader, 1, 1.0, SELL);
		Order order = trade.exitOrders().iterator().next();

		assertEquals(33.0, order.getExecutedQuantity().doubleValue(), DELTA); //each tick has volume = 33 units
		assertEquals(quantity - 33, order.getRemainingQuantity().doubleValue(), DELTA);
		assertEquals(quantity - 33, ada.getLocked().doubleValue(), DELTA);
		assertEquals(0.0, ada.getFree().doubleValue(), DELTA);
		assertEquals(balance + subtractFees(33), usdt.getFree().doubleValue(), DELTA);
		assertEquals(0.0, usdt.getLocked().doubleValue(), DELTA);

		trader.tradingManager.updateOpenOrders(trader.symbol(), newTick(++time, 2.0)); //fills another 33 units
		assertEquals(quantity - 66, order.getRemainingQuantity().doubleValue(), DELTA);
		assertEquals(quantity - 66, ada.getLocked().doubleValue(), DELTA);
		assertEquals(0.0, ada.getFree().doubleValue(), DELTA);
		assertEquals(balance + subtractFees(33) + subtractFees(33 * 2.0), usdt.getFree().doubleValue(), DELTA);
		assertEquals(0.0, usdt.getLocked().doubleValue(), DELTA);

		trader.tradingManager.updateOpenOrders(trader.symbol(), newTick(++time, 1.5)); //fills rest of the order
		assertEquals(0, order.getRemainingQuantity().doubleValue(), DELTA);
		assertEquals(0, ada.getLocked().doubleValue(), DELTA);
		assertEquals(0.0, ada.getFree().doubleValue(), DELTA);
		assertEquals(balance + subtractFees(33.0) + subtractFees(33 * 2.0) + subtractFees((quantity - 66) * 1.5), usdt.getFree().doubleValue(), DELTA);
		assertEquals(0.0, usdt.getLocked().doubleValue(), DELTA);
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
		assertEquals(0.0, ada.getLocked().doubleValue(), DELTA);
		assertEquals(quantity, ada.getFree().doubleValue(), DELTA);
		assertEquals(0.0, usdt.getLocked().doubleValue(), DELTA);
		//again using less stringent DELTA here as calculation is correct but assertion fails due to rounding error
		assertEquals(100.0 - addFees(first) - addFees(second), usdt.getFree().doubleValue(), 0.000001);
		final double balance = usdt.getFreeAmount();

		tradeOnPrice(trader, 1, 1.0, SELL);
		Order order = trade.exitOrders().iterator().next();

		assertEquals(33.0, order.getExecutedQuantity().doubleValue(), DELTA); //each tick has volume = 33 units
		assertEquals(quantity - 33, order.getRemainingQuantity().doubleValue(), DELTA);
		assertEquals(quantity - 33, ada.getLocked().doubleValue(), DELTA);
		assertEquals(0.0, ada.getFree().doubleValue(), DELTA);
		assertEquals(balance + subtractFees(33), usdt.getFree().doubleValue(), DELTA);
		assertEquals(0.0, usdt.getLocked().doubleValue(), DELTA);

		trader.tradingManager.updateOpenOrders(trader.symbol(), newTick(++time, 2.0)); //fills another 33 units
		assertEquals(quantity - 66, order.getRemainingQuantity().doubleValue(), DELTA);
		assertEquals(quantity - 66, ada.getLocked().doubleValue(), DELTA);
		assertEquals(0.0, ada.getFree().doubleValue(), DELTA);
		assertEquals(balance + subtractFees(33) + subtractFees(33 * 2.0), usdt.getFree().doubleValue(), DELTA);
		assertEquals(0.0, usdt.getLocked().doubleValue(), DELTA);

		order.cancel();
		trader.tradingManager.updateOpenOrders(trader.symbol(), newTick(++time, 1.5));
		assertEquals(quantity - 66, order.getRemainingQuantity().doubleValue(), DELTA);
		assertEquals(0.0, ada.getLocked().doubleValue(), DELTA);
		assertEquals(quantity - 66, ada.getFree().doubleValue(), DELTA);
		assertEquals(balance + subtractFees(33) + subtractFees(33 * 2.0), usdt.getFree().doubleValue(), DELTA);
		assertEquals(0.0, usdt.getLocked().doubleValue(), DELTA);
	}
}
