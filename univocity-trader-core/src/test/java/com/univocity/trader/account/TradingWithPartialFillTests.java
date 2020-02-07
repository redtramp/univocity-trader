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
	public void testLongTradingWithPartialFills() {
		AccountManager account = getAccountManager();

		final double initialBalance = 100;

		account.setAmount("USDT", initialBalance);

		Trader trader = account.getTraderOf("ADAUSDT");

		double usdBalance = account.getAmount("USDT");
		tradeOnPrice(trader, 1, 1.0, BUY);
		final Trade trade = trader.trades().iterator().next();
		Order order = trade.position().iterator().next();

		assertEquals(33.0, order.getExecutedQuantity().doubleValue(), DELTA); //each tick has volume = 33 units

		assertEquals(33.0, account.getBalance("ADA").getFree().doubleValue(), DELTA);



		double quantity1 = checkTradeAfterLongBuy(usdBalance, trade, 100, 0.0, 1.0, 1.0, 1.0);
		tradeOnPrice(trader, 5, 1.1, NEUTRAL);
		checkLongTradeStats(trade, 1.1, 1.1, 1.0);

		usdBalance = account.getAmount("USDT");
		tradeOnPrice(trader, 10, 0.8, BUY);
		double quantity2 = checkTradeAfterLongBuy(usdBalance, trade, 100, quantity1, 0.8, 1.1, 0.8);

		double averagePrice = (addFees(quantity1 * 1.0) + addFees(quantity2 * 0.8)) / (quantity1 + quantity2);
		assertEquals(averagePrice, trade.averagePrice(), DELTA);

		usdBalance = account.getAmount("USDT");
		tradeOnPrice(trader, 20, 0.95, SELL);
		checkTradeAfterLongSell(usdBalance, trade, (quantity1 + quantity2), 0.95, 1.1, 0.8);
		assertEquals(averagePrice, trade.averagePrice(), DELTA); //average price is about 0.889

		assertFalse(trade.stopped());
		assertEquals("Sell signal", trade.exitReason());
		assertFalse(trade.tryingToExit());

		checkProfitLoss(trade, initialBalance, (quantity1 * 1.0) + (quantity2 * 0.8));

	}
}
