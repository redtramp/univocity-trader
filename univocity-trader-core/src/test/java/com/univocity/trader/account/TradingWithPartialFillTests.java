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
	public void testLongTradingWithPartialFillThenCancel() {
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
	public void testLongTradingWithPartialFillAverage() {
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

		tradeOnPrice(trader, 2, 0.5, NEUTRAL);

		assertEquals(order.getQuantity().doubleValue(), order.getExecutedQuantity().doubleValue(), DELTA); //each tick has volume = 33 units
		assertEquals(order.getQuantity().doubleValue(), account.getBalance("ADA").getFree().doubleValue(), DELTA);
		assertEquals(0.0, account.getBalance("USDT").getLocked().doubleValue(), DELTA);
		assertEquals(100.0, account.getBalance("USDT").getFree().doubleValue() + (33 * 1.0) + (6.99596 * 0.5) + feesOn(order.getTotalTraded()));
		assertEquals(60.00404 + (6.99596 * 0.5), account.getBalance("USDT").getFree().doubleValue(), DELTA); //add amount not spent as price dropped in half
	}
}
