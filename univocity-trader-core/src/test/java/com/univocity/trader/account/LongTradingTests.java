package com.univocity.trader.account;

import com.univocity.trader.*;
import com.univocity.trader.candles.*;
import org.junit.*;

import java.math.*;

import static com.univocity.trader.account.Order.Status.*;
import static com.univocity.trader.account.Order.Type.*;
import static com.univocity.trader.account.Trade.Side.*;
import static com.univocity.trader.indicators.Signal.*;
import static junit.framework.TestCase.*;

/**
 * @author uniVocity Software Pty Ltd - <a href="mailto:dev@univocity.com">dev@univocity.com</a>
 */
public class LongTradingTests extends OrderFillChecker {

	@Test
	public void testLongPositionTrading() {
		AccountManager account = getAccountManager();

		final double MAX = 40.0;
		final double initialBalance = 100;

		account.setAmount("USDT", initialBalance);
		account.configuration().maximumInvestmentAmountPerTrade(MAX);

		Trader trader = account.getTraderOf("ADAUSDT");

		double usdBalance = account.getAmount("USDT");
		tradeOnPrice(trader, 1, 1.0, BUY);
		final Trade trade = trader.trades().iterator().next();

		double quantity1 = checkTradeAfterLongBuy(usdBalance, trade, MAX, 0.0, 1.0, 1.0, 1.0);
		tradeOnPrice(trader, 5, 1.1, NEUTRAL);
		checkLongTradeStats(trade, 1.1, 1.1, 1.0);

		usdBalance = account.getAmount("USDT");
		tradeOnPrice(trader, 10, 0.8, BUY);
		double quantity2 = checkTradeAfterLongBuy(usdBalance, trade, MAX, quantity1, 0.8, 1.1, 0.8);

		double averagePrice = ((quantity1 * 1.0) + (quantity2 * 0.8)) / (quantity1 + quantity2);
		assertEquals(averagePrice, trade.averagePrice(), 0.001);

		usdBalance = account.getAmount("USDT");
		tradeOnPrice(trader, 20, 0.95, SELL);
		checkTradeAfterLongSell(usdBalance, trade, (quantity1 + quantity2), 0.95, 1.1, 0.8);
		assertEquals(averagePrice, trade.averagePrice(), 0.001); //average price is about 0.889

		assertFalse(trade.stopped());
		assertEquals("Sell signal", trade.exitReason());
		assertFalse(trade.tryingToExit());

		checkProfitLoss(trade, initialBalance, (quantity1 * 1.0) + (quantity2 * 0.8));
	}

	@Test
	public void testTradingWithStopLoss() {
		AccountManager account = getAccountManager();

		final double MAX = 40.0;
		final double initialBalance = 100;

		account.setAmount("USDT", initialBalance);
		account.configuration().maximumInvestmentAmountPerTrade(MAX);

		Trader trader = account.getTraderOf("ADAUSDT");

		double usdBalance = account.getAmount("USDT");
		tradeOnPrice(trader, 1, 1.0, BUY);
		final Trade trade = trader.trades().iterator().next();

		double quantity1 = checkTradeAfterLongBuy(usdBalance, trade, MAX, 0.0, 1.0, 1.0, 1.0);
		tradeOnPrice(trader, 5, 1.1, NEUTRAL);
		checkLongTradeStats(trade, 1.1, 1.1, 1.0);

		usdBalance = account.getAmount("USDT");

		OrderRequest or = new OrderRequest("ADA", "USDT", Order.Side.SELL, LONG, 2, null);
		or.setQuantity(BigDecimal.valueOf(quantity1));
		or.setTriggerCondition(Order.TriggerCondition.STOP_LOSS, new BigDecimal("0.9"));
		Order o = account.executeOrder(or);

		trader.tradingManager.updateOpenOrders("ADAUSDT", newTick(3, 1.5));
		assertEquals(Order.Status.NEW, o.getStatus());
		assertFalse(o.isActive());
		assertEquals(usdBalance, account.getAmount("USDT"), 0.001);

		trader.tradingManager.updateOpenOrders("ADAUSDT", newTick(4, 0.8999));
		assertEquals(Order.Status.NEW, o.getStatus());
		assertTrue(o.isActive());
		assertEquals(usdBalance, account.getAmount("USDT"), 0.001);

		trader.tradingManager.updateOpenOrders("ADAUSDT", newTick(4, 0.92));
		assertEquals(FILLED, o.getStatus());
		assertTrue(o.isActive());
		assertEquals(0.0, account.getAmount("ADA"), 0.001);
		assertEquals(usdBalance + ((o.getExecutedQuantity().doubleValue() /*quantity*/) * 0.92 /*price*/) * 0.999 /*fees*/, account.getAmount("USDT"), 0.001);
	}

	@Test
	public void testTradingWithStopGain() {
		AccountManager account = getAccountManager();

		final double MAX = 40.0;
		final double initialBalance = 100;

		account.setAmount("USDT", initialBalance);
		account.configuration().maximumInvestmentAmountPerTrade(MAX);

		Trader trader = account.getTraderOf("ADAUSDT");

		double usdBalance = account.getAmount("USDT");
		tradeOnPrice(trader, 1, 1.0, BUY);
		final Trade trade = trader.trades().iterator().next();

		double quantity1 = checkTradeAfterLongBuy(usdBalance, trade, MAX, 0.0, 1.0, 1.0, 1.0);
		tradeOnPrice(trader, 5, 1.1, NEUTRAL);
		checkLongTradeStats(trade, 1.1, 1.1, 1.0);

		usdBalance = account.getAmount("USDT");
		assertEquals(initialBalance - ((MAX * 0.9999 /*quantity offset*/) * 0.999 /*fees*/), usdBalance, 0.001);
		assertEquals(60.044, usdBalance, 0.001);
		assertEquals(MAX * 0.999 * 0.999 * 0.9999, account.getAmount("ADA"), 0.001);

		OrderRequest or = new OrderRequest("ADA", "USDT", Order.Side.BUY, LONG, 2, null);
		or.setQuantity(BigDecimal.valueOf(quantity1));
		or.setTriggerCondition(Order.TriggerCondition.STOP_GAIN, new BigDecimal("1.2"));
		Order o = account.executeOrder(or);

		trader.tradingManager.updateOpenOrders("ADAUSDT", newTick(3, 0.8999));
		assertEquals(Order.Status.NEW, o.getStatus());
		assertFalse(o.isActive());
		assertEquals(usdBalance - o.getTotalOrderAmount().doubleValue(), account.getAmount("USDT"), 0.001);

		trader.tradingManager.updateOpenOrders("ADAUSDT", newTick(4, 1.5));
		assertTrue(o.isActive());
		assertEquals(Order.Status.NEW, o.getStatus()); //can't fill because price is too high and we want to pay 1.2
		assertEquals(usdBalance - o.getTotalOrderAmount().doubleValue(), account.getAmount("USDT"), 0.001);


		double previousUsdBalance = usdBalance;
		trader.tradingManager.updateOpenOrders("ADAUSDT", newTick(5, 0.8));
		assertTrue(o.isActive());
		assertEquals(FILLED, o.getStatus());

		assertEquals(2 * MAX * 0.999 * 0.999 * 0.9999, account.getAmount("ADA"), 0.001);
		assertEquals(previousUsdBalance - (((MAX * 0.9999 /*quantity offset*/) * 0.8 /*price*/) * 0.999 /*fees*/), account.getAmount("USDT"), 0.001);
	}

	@Test
	public void testLongTradingWithMarketBracketOrder() {
		AccountManager account = getAccountManager(new DefaultOrderManager() {
			@Override
			public void prepareOrder(SymbolPriceDetails priceDetails, OrderBook book, OrderRequest order, Candle latestCandle) {
				if (order.isBuy() && order.isLong() || order.isSell() && order.isShort()) {
					OrderRequest marketSellOnLoss = order.attach(MARKET, -1.0);
					OrderRequest takeProfit = order.attach(MARKET, 1.0);
				}
			}
		});


		final double MAX = 40.0;
		double initialBalance = 100;

		account.setAmount("USDT", initialBalance);
		account.configuration().maximumInvestmentAmountPerTrade(MAX);

		initialBalance = testLongBracketOrder(account, initialBalance, 1.0, -0.1, 10);
		initialBalance = testLongBracketOrder(account, initialBalance, 1.0, -0.1, 20);
		initialBalance = testLongBracketOrder(account, initialBalance, 1.0, 0.1, 30);
		initialBalance = testLongBracketOrder(account, initialBalance, 1.0, 0.1, 40);

	}

	double testLongBracketOrder(AccountManager account, double initialBalance, double unitPrice, double priceIncrement, long time) {
		Trader trader = account.getTraderOf("ADAUSDT");

		double usdBalance = account.getAmount("USDT");
		tradeOnPrice(trader, ++time, unitPrice, BUY);
		final Trade trade = trader.trades().iterator().next();

		double quantity1 = checkTradeAfterLongBracketOrder(usdBalance, trade, 40.0, 0.0, unitPrice, unitPrice, unitPrice);
		usdBalance = account.getAmount("USDT");

		assertEquals(40.0 / unitPrice * 0.9999 * 0.999 * 0.999, quantity1); //40 minus offset + 2x fees
		assertEquals(initialBalance - (quantity1 * unitPrice + (quantity1 * unitPrice * 0.001)), usdBalance, 0.0001); //attached orders submitted, so 1x fees again

		Order parent = trade.position().iterator().next();
		assertEquals(2, parent.getAttachments().size());

		Order profitOrder = null;
		Order lossOrder = null;

		for (Order o : parent.getAttachments()) {
			assertEquals(NEW, o.getStatus());
			assertEquals(parent.getOrderId(), o.getParentOrderId());
			assertFalse(o.isActive());
			if (o.getTriggerPrice().doubleValue() > unitPrice) {
				profitOrder = o;
			} else {
				lossOrder = o;
			}
		}

		assertNotNull(profitOrder);
		assertNotNull(lossOrder);

		assertEquals(parent, profitOrder.getParent());
		assertEquals(parent, lossOrder.getParent());

		unitPrice = unitPrice + priceIncrement;

		trader.tradingManager.updateOpenOrders("ADAUSDT", newTick(++time, unitPrice)); //this finalizes all orders
		trader.tradingManager.updateOpenOrders("ADAUSDT", newTick(++time, unitPrice)); //so this should not do anything

		assertEquals(0.0, account.getBalance("ADA").getLocked().doubleValue(), 0.00001);
		assertEquals(0.0, account.getBalance("ADA").getFree().doubleValue(), 0.00001);


		double currentBalance = account.getAmount("USDT");
		assertEquals(usdBalance + (quantity1 * unitPrice) * 0.999, currentBalance, 0.00001);

		if (priceIncrement > 0) {
			assertEquals(CANCELLED, lossOrder.getStatus());
			assertEquals(FILLED, profitOrder.getStatus());
		} else {
			assertEquals(FILLED, lossOrder.getStatus());
			assertEquals(CANCELLED, profitOrder.getStatus());
		}


		return currentBalance;
	}

}
