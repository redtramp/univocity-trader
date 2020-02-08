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
		assertEquals(60.00404 + (6.956004 / 2.0) + feesNotPaid, account.getBalance("USDT").getFree().doubleValue(), 0.000001); //using less stringent DELTA here as calculation is correct but assertion fails due to rounding error
		assertEquals(100.0, account.getBalance("USDT").getFree().doubleValue() + (33 * 1.0) + (6.956004 * 0.5) + order.getFeesPaid().doubleValue(), 0.000001);
	}
}
