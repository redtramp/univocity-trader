package com.univocity.trader.account;

import com.univocity.trader.*;
import com.univocity.trader.candles.*;
import org.junit.*;

import static com.univocity.trader.account.Order.Status.*;
import static com.univocity.trader.account.Order.Type.*;
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
		double averagePrice = getInvestmentAmount(trader, ((quantity1 * 0.9) + (quantity2 * 1.2))) / (quantity1 + quantity2);
		assertEquals(averagePrice, trade.averagePrice(), 0.001);

		usdBalance = account.getAmount("USDT");
		reservedBalance = account.getMarginReserve("USDT", "ADA").doubleValue();

		//CANCEL
		tradeOnPrice(trader, 11, 1.1, SELL, true);
		averagePrice = getInvestmentAmount(trader, ((quantity1 * 0.9) + (quantity2 * 1.2))) / (quantity1 + quantity2);
		assertEquals(averagePrice, trade.averagePrice(), 0.001);
		assertEquals(usdBalance, account.getAmount("USDT"), 0.001);
		assertEquals(reservedBalance, account.getMarginReserve("USDT", "ADA").doubleValue(), 0.001);


		tradeOnPrice(trader, 20, 0.1, BUY);

		checkTradeAfterShortBuy(usdBalance, reservedBalance, trade, quantity1 + quantity2, 0.1, 1.2, 0.1);

		assertFalse(trade.stopped());
		assertEquals("Buy signal", trade.exitReason());
		assertFalse(trade.tryingToExit());
		assertEquals(72.062, trade.actualProfitLoss(), 0.001);
		assertEquals(90.258, trade.actualProfitLossPct(), 0.001);
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
		assertEquals(150.0 - shortFees, trader.holdings(), 0.001);


		tradeOnPrice(trader, 5, 1.0, NEUTRAL);
		checkShortTradeStats(trade, 1.0, 1.0, 0.9);

		//NO BALANCE AVAILABLE TO SHORT
		usdBalance = account.getAmount("USDT");
		reservedBalance = account.getMarginReserve("USDT", "ADA").doubleValue();
		tradeOnPrice(trader, 10, 1.2, SELL);
		checkShortTradeStats(trade, 1.2, 1.2, 0.9);
		double updatedAmountShorted = quantity1 * 1.2;
		double shortLoss = updatedAmountShorted - amountShorted;
		assertEquals(150.0 - shortLoss - shortFees, trader.holdings(), 0.001);

		double averagePrice = getInvestmentAmount(trader, ((quantity1 * 0.9))) / (quantity1);
		assertEquals(averagePrice, trade.averagePrice(), 0.001);
		assertEquals(usdBalance, account.getAmount("USDT"), 0.001);
		assertEquals(reservedBalance, account.getMarginReserve("USDT", "ADA").doubleValue(), 0.001);

		//COVER
		tradeOnPrice(trader, 20, 1.0, BUY);
		checkTradeAfterShortBuy(usdBalance, reservedBalance, trade, quantity1, 1.0, 1.2, 0.9);

		assertFalse(trade.stopped());
		assertEquals("Buy signal", trade.exitReason());
		assertFalse(trade.tryingToExit());
		assertEquals(-11.31, trade.actualProfitLoss(), 0.001);
		assertEquals(-11.333, trade.actualProfitLossPct(), 0.001);

		//profit/loss includes fees.
		assertEquals(150.0 - 11.31, trader.holdings(), 0.001);

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

	private double testShortBracketOrder(AccountManager account, double initialBalance, double unitPrice, double priceIncrement, long time) {
		Trader trader = account.getTraderOf("ADAUSDT");

		double usdBalance = account.getAmount("USDT");
		double marginReserve = account.getMarginReserve("USDT", "ADA").doubleValue();
		tradeOnPrice(trader, ++time, unitPrice, SELL);
		final Trade trade = trader.trades().iterator().next();

		double quantity1 = checkTradeAfterBracketShortSell(usdBalance, marginReserve, trade, 40.0, 0.0, unitPrice, unitPrice, unitPrice);
		usdBalance = account.getAmount("USDT");

		assertEquals(40.0 / unitPrice * 0.999, quantity1); //40 minus 1x fees
		//half of quantity * unit price (50% of short sell goes to margin account) + fees over full quantity buy back
		double totalSale = (quantity1 * unitPrice);
		double reserved = quantity1 * 0.5 * unitPrice;
		//remove margin reserve and fees paid to sell everything.
		assertEquals(initialBalance - (totalSale * 0.001 + reserved), usdBalance, 0.0001);

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
		assertEquals(0.0, account.getBalance("ADA").getShorted().doubleValue(), 0.00001);


		double currentBalance = account.getAmount("USDT");
		double totalBuyback = quantity1 * unitPrice;
		//put margin reserve back, add difference of total sold + rebought, subtract fees to cover.
		assertEquals(usdBalance + reserved + (totalSale - totalBuyback) - totalBuyback * 0.001, currentBalance, 0.00001);

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
