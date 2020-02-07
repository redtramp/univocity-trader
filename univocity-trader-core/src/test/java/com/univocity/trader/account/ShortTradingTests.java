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
public class ShortTradingTests extends OrderFillChecker {


	@Test
	public void testShortPositionTrading() {
		AccountManager account = getAccountManager();

		final double MAX = 40.0;
		final double initialBalance = 100;

		account.setAmount("USDT", initialBalance);
		account.configuration()
				.maximumInvestmentAmountPerTrade(MAX)
				.minimumInvestmentAmountPerTrade(10.0);

		Trader trader = account.getTraderOf("ADAUSDT");

		double usdBalance = account.getAmount("USDT");
		double reservedBalance = account.getMarginReserve("USDT", "ADA").doubleValue();
		tradeOnPrice(trader, 1, 0.9, SELL);
		Trade trade = trader.trades().iterator().next();
		double quantity1 = checkTradeAfterShortSell(usdBalance, reservedBalance, trade, MAX, 0.0, 0.9, 0.9, 0.9);

		tradeOnPrice(trader, 5, 1.0, NEUTRAL);
		checkShortTradeStats(trade, 1.0, 1.0, 0.9);

		usdBalance = account.getAmount("USDT");
		reservedBalance = account.getMarginReserve("USDT", "ADA").doubleValue();
		tradeOnPrice(trader, 10, 1.2, SELL);
		double quantity2 = checkTradeAfterShortSell(usdBalance, reservedBalance, trade, MAX, quantity1, 1.2, 1.2, 0.9);

		//average price calculated to include fees to exit
		double averagePrice = (subtractFees(quantity1 * 0.9) + subtractFees(quantity2 * 1.2)) / (quantity1 + quantity2);
		assertEquals(averagePrice, trade.averagePrice(), DELTA);

		usdBalance = account.getAmount("USDT");
		reservedBalance = account.getMarginReserve("USDT", "ADA").doubleValue();

		//CANCEL
		tradeOnPrice(trader, 11, 1.1, SELL, true);
		averagePrice = (subtractFees(quantity1 * 0.9) + subtractFees(quantity2 * 1.2)) / (quantity1 + quantity2);
		assertEquals(averagePrice, trade.averagePrice(), DELTA);
		assertEquals(reservedBalance, account.getMarginReserve("USDT", "ADA").doubleValue(), DELTA);
		assertEquals(usdBalance, account.getAmount("USDT"), DELTA);


		tradeOnPrice(trader, 20, 0.1, BUY);

		checkTradeAfterShortBuy(usdBalance, reservedBalance, trade, quantity1 + quantity2, 0.1, 1.2, 0.1);

		assertFalse(trade.stopped());
		assertEquals("Buy signal", trade.exitReason());
		assertFalse(trade.tryingToExit());
		assertEquals(72.13444444, trade.actualProfitLoss(), DELTA);
		assertEquals(90.25831386, trade.actualProfitLossPct(), DELTA);
	}


	@Test
	public void testShortPositionTradingNoMax() {
		AccountManager account = getAccountManager();

		final double initialBalance = 100;

		account.setAmount("USDT", initialBalance);
		account.configuration()
				.minimumInvestmentAmountPerTrade(10.0);

		Trader trader = account.getTraderOf("ADAUSDT");

		assertEquals(150.0, trader.holdings());

		//FIRST SHORT, COMMITS ALL ACCOUNT BALANCE
		double usdBalance = account.getAmount("USDT");
		double reservedBalance = account.getMarginReserve("USDT", "ADA").doubleValue();
		tradeOnPrice(trader, 1, 0.9, SELL);
		Trade trade = trader.trades().iterator().next();
		double quantity1 = checkTradeAfterShortSell(usdBalance, reservedBalance, trade, initialBalance, 0.0, 0.9, 0.9, 0.9);

		double amountShorted = quantity1 * 0.9;
		double shortFees = trader.tradingFees().feesOnAmount(amountShorted, Order.Type.LIMIT, Order.Side.SELL);
		assertEquals(150.0 - shortFees, trader.holdings(), DELTA);


		tradeOnPrice(trader, 5, 1.0, NEUTRAL);
		checkShortTradeStats(trade, 1.0, 1.0, 0.9);

		//NO BALANCE AVAILABLE TO SHORT
		usdBalance = account.getAmount("USDT");
		reservedBalance = account.getMarginReserve("USDT", "ADA").doubleValue();
		tradeOnPrice(trader, 10, 1.2, SELL);
		checkShortTradeStats(trade, 1.2, 1.2, 0.9);
		double updatedAmountShorted = quantity1 * 1.2;
		double shortLoss = updatedAmountShorted - amountShorted;
		assertEquals(150.0 - shortLoss - shortFees, trader.holdings(), DELTA);

		double averagePrice = subtractFees((quantity1 * 0.9)) / (quantity1);
		assertEquals(averagePrice, trade.averagePrice(), DELTA);
		assertEquals(usdBalance, account.getAmount("USDT"), DELTA);
		assertEquals(reservedBalance, account.getMarginReserve("USDT", "ADA").doubleValue(), DELTA);

		//COVER
		tradeOnPrice(trader, 20, 1.0, BUY);
		checkTradeAfterShortBuy(usdBalance, reservedBalance, trade, quantity1, 1.0, 1.2, 0.9);

		assertFalse(trade.stopped());
		assertEquals("Buy signal", trade.exitReason());
		assertFalse(trade.tryingToExit());
		assertEquals(-11.309768909, trade.actualProfitLoss(), DELTA);
		assertEquals(-11.333555777, trade.actualProfitLossPct(), DELTA);

		//profit/loss includes fees.
		assertEquals(150.0 - 11.309768909, trader.holdings(), DELTA);

	}

	public void checkBalancesAfterShort(double initialBalance, double currentBalance, double quantity, double unitPrice) {
		//half of quantity * unit price (50% of short sell goes to margin account) + fees over full quantity buy back
		double totalSale = (quantity * unitPrice);
		double reserved = quantity * 0.5 * unitPrice;
		//remove margin reserve and fees paid to sell everything.
		assertEquals(initialBalance - (feesOn(totalSale) + reserved), currentBalance, DELTA);
	}

	public void checkBalancesAfterBuyBack(double initialBalance, double currentBalance, double quantity, double saleUnitPrice, double unitPrice) {
		double totalSale = quantity * saleUnitPrice; //don't take fees from sale as initial balance comes with fees accounted for
		double totalBuyback = addFees(quantity * unitPrice);
		double reserved = totalSale * 0.5;

		assertEquals(initialBalance + reserved + totalSale - totalBuyback, currentBalance, DELTA);
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
		tradeOnPrice(trader, 1, 1.0, SELL);
		final Trade trade = trader.trades().iterator().next();

		double quantity1 = checkTradeAfterShortSell(usdBalance, 0, trade, MAX, 0.0, 1.0, 1.0, 1.0);
		tradeOnPrice(trader, 5, 1.1, NEUTRAL);
		checkShortTradeStats(trade, 1.1, 1.1, 1.0);

		usdBalance = account.getAmount("USDT");
		checkBalancesAfterShort(initialBalance, usdBalance, 40, 1.0);
		double assetsAt1_0 = account.getShortedAmount("ADA");
		double marginAt_10 = account.getMarginReserve("USDT", "ADA").doubleValue();
		assertEquals(assetsAt1_0 * 1.5, marginAt_10, DELTA);


		OrderRequest or = new OrderRequest("ADA", "USDT", Order.Side.SELL, SHORT, 2, null);
		or.setQuantity(BigDecimal.valueOf(quantity1));
		or.setTriggerCondition(Order.TriggerCondition.STOP_LOSS, new BigDecimal("0.9"));
		Order o = account.executeOrder(or);

		trader.tradingManager.updateOpenOrders("ADAUSDT", newTick(3, 1.5));
		assertEquals(Order.Status.NEW, o.getStatus());
		assertFalse(o.isActive());

		trader.tradingManager.updateOpenOrders("ADAUSDT", newTick(4, 0.8999));
		assertEquals(Order.Status.NEW, o.getStatus());
		assertTrue(o.isActive());


		//triggers another short sell at 0.92
		trader.tradingManager.updateOpenOrders("ADAUSDT", newTick(4, 0.92));
		assertEquals(Order.Status.FILLED, o.getStatus());
		assertTrue(o.isActive());

		double assetsAt0_90 = account.getShortedAmount("ADA") - assetsAt1_0;
		assertEquals(40, assetsAt0_90, DELTA);
		//margin requirement calculated based on initial order price ($0.9 here). Funds are locked according to margin reserve % based on initial price as well.
		double marginAt0_90 = account.getMarginReserve("USDT", "ADA").doubleValue() - marginAt_10;
		assertEquals(assetsAt0_90 * 0.9 * 1.5, marginAt0_90, DELTA);

		assertEquals(assetsAt1_0, assetsAt0_90, DELTA);

		double amountInMargin = (assetsAt1_0 * 1.0 * 1.5) + (assetsAt0_90 * 0.9 * 1.5);
		assertEquals(amountInMargin, account.getMarginReserve("USDT", "ADA").doubleValue());

		assertEquals(0.0, account.getBalance("USDT").getLocked().doubleValue());

		double feesPaid = feesOn(assetsAt1_0 * 1.0) + feesOn(assetsAt0_90 * 0.92);
		double fundsInMargin = (assetsAt1_0 * 1.0 * 0.5) + (assetsAt0_90 * 0.9 * 0.5);
		assertEquals(initialBalance - fundsInMargin - feesPaid, account.getBalance("USDT").getFreeAmount());
	}

	@Test
	public void testTradingWithStopGain() {
		AccountManager account = getAccountManager();

		final double MAX = 40.0;
		final double initialBalance = 100;

		account.setAmount("USDT", initialBalance);
		account.configuration().maximumInvestmentAmountPerTrade(MAX);

		Trader trader = account.getTraderOf("ADAUSDT");

		double shortUnitPrice = 1.0;
		double usdBalance = account.getAmount("USDT");
		tradeOnPrice(trader, 1, shortUnitPrice, SELL);
		final Trade trade = trader.trades().iterator().next();

		double quantity1 = checkTradeAfterShortSell(usdBalance, 0, trade, MAX, 0.0, shortUnitPrice, shortUnitPrice, shortUnitPrice);
		tradeOnPrice(trader, 5, 1.1, NEUTRAL);
		checkShortTradeStats(trade, 1.1, 1.1, 1.0);

		usdBalance = account.getAmount("USDT");
		checkBalancesAfterShort(initialBalance, usdBalance, 40.0, shortUnitPrice);

		OrderRequest or = new OrderRequest("ADA", "USDT", Order.Side.BUY, SHORT, 2, null);
		or.setQuantity(BigDecimal.valueOf(quantity1));
		or.setTriggerCondition(Order.TriggerCondition.STOP_GAIN, new BigDecimal("1.2"));
		Order o = account.executeOrder(or);

		trader.tradingManager.updateOpenOrders("ADAUSDT", newTick(3, 0.8999));
		assertEquals(Order.Status.NEW, o.getStatus());
		assertFalse(o.isActive());

		usdBalance = account.getAmount("USDT");
		checkBalancesAfterShort(initialBalance, usdBalance, 40.0, shortUnitPrice);

		trader.tradingManager.updateOpenOrders("ADAUSDT", newTick(4, 1.5));
		assertTrue(o.isActive());
		assertEquals(Order.Status.NEW, o.getStatus()); //can't fill because price is too high and we want to pay 1.2

		double previousUsdBalance = usdBalance;
		trader.tradingManager.updateOpenOrders("ADAUSDT", newTick(5, 0.8));
		assertTrue(o.isActive());
		assertEquals(FILLED, o.getStatus());

		usdBalance = account.getAmount("USDT");
		checkBalancesAfterBuyBack(previousUsdBalance, usdBalance, 40.0, shortUnitPrice, 0.8);

		assertEquals(0.0, account.getShortedAmount("ADA"));
		assertEquals(0.0, account.getMarginReserve("USDT", "ADA").doubleValue());
	}

	@Test
	public void testShortTradingWithMarketBracketOrder() {
		AccountManager account = getAccountManager(new DefaultOrderManager() {
			@Override
			public void prepareOrder(SymbolPriceDetails priceDetails, OrderBook book, OrderRequest order, Candle latestCandle) {
				if (order.isBuy() && order.isLong() || order.isSell() && order.isShort()) {
					OrderRequest takeProfit = order.attach(MARKET, -1.0);
					OrderRequest marketBuyOnLoss = order.attach(MARKET, 1.0);
				}
			}
		});


		final double MAX = 40.0;
		double initialBalance = 100;

		account.setAmount("USDT", initialBalance);
		account.configuration().maximumInvestmentAmountPerTrade(MAX);

		initialBalance = testShortBracketOrder(account, initialBalance, 1.0, -0.1, 10);
		initialBalance = testShortBracketOrder(account, initialBalance, 1.0, -0.1, 20);
		initialBalance = testShortBracketOrder(account, initialBalance, 1.0, 0.1, 30);
		initialBalance = testShortBracketOrder(account, initialBalance, 1.0, 0.1, 40);

	}

	private double testShortBracketOrder(AccountManager account, double initialBalance, final double unitPrice, double priceIncrement, long time) {
		Trader trader = account.getTraderOf("ADAUSDT");

		double usdBalance = account.getAmount("USDT");
		double marginReserve = account.getMarginReserve("USDT", "ADA").doubleValue();
		tradeOnPrice(trader, ++time, unitPrice, SELL);
		final Trade trade = trader.trades().iterator().next();

		checkTradeAfterBracketShortSell(usdBalance, marginReserve, trade, 40.0, 0.0, unitPrice, unitPrice, unitPrice);

		usdBalance = account.getAmount("USDT");
		checkBalancesAfterShort(initialBalance, usdBalance, 40.0, unitPrice);

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

		double newUnitPrice = unitPrice + priceIncrement;

		trader.tradingManager.updateOpenOrders("ADAUSDT", newTick(++time, newUnitPrice)); //this finalizes all orders
		trader.tradingManager.updateOpenOrders("ADAUSDT", newTick(++time, newUnitPrice)); //so this should not do anything

		assertEquals(0.0, account.getBalance("ADA").getLocked().doubleValue(), DELTA);
		assertEquals(0.0, account.getBalance("ADA").getFree().doubleValue(), DELTA);
		assertEquals(0.0, account.getBalance("ADA").getShorted().doubleValue(), DELTA);

		double currentBalance = account.getAmount("USDT");
		checkBalancesAfterBuyBack(usdBalance, currentBalance, 40.0, unitPrice, newUnitPrice);

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
