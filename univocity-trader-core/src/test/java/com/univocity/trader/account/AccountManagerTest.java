package com.univocity.trader.account;

import com.univocity.trader.config.*;
import org.junit.*;

import static com.univocity.trader.account.Trade.Side.*;
import static junit.framework.TestCase.*;

public class AccountManagerTest extends OrderFillChecker {

	@Test
	public void testFundAllocationBasics() {
		Balance.balanceUpdateCounts.clear();
		AccountManager account = getAccountManager();
		AccountConfiguration<?> cfg = account.configuration();

		account.setAmount("USDT", 350);
		cfg.maximumInvestmentAmountPerAsset(20.0);

		double funds = account.allocateFunds("ADA", LONG);
		assertEquals(19.98, funds, 0.001);

		cfg.maximumInvestmentPercentagePerAsset(2.0);
		funds = account.allocateFunds("ADA", LONG);
		assertEquals(7.992, funds, 0.001);

		cfg.maximumInvestmentAmountPerTrade(6);
		funds = account.allocateFunds("ADA", LONG);
		assertEquals(5.994, funds, 0.001);

		cfg.maximumInvestmentPercentagePerTrade(1.0);
		funds = account.allocateFunds("ADA", LONG);
		assertEquals(3.996, funds, 0.001);

		cfg.maximumInvestmentAmountPerTrade(3);
		funds = account.allocateFunds("ADA", LONG);
		assertEquals(2.997, funds, 0.001);


		cfg.minimumInvestmentAmountPerTrade(10);
		funds = account.allocateFunds("ADA", LONG);
		assertEquals(0.0, funds, 0.001);

	}

	@Test
	public void testFundAllocationPercentageWithInvestedAmounts() {
		AccountManager account = getAccountManager();

		account.setAmount("USDT", 100);
		account.configuration().maximumInvestmentPercentagePerAsset(90.0);

		double funds = account.allocateFunds("ADA", LONG);
		assertEquals(99.9, funds, 0.001);

		account.setAmount("USDT", 50);
		account.setAmount("ADA", 50 / CLOSE);

		funds = account.allocateFunds("ADA", LONG);
		assertEquals(49.95, funds, 0.001);

		account.setAmount("USDT", 10);
		account.setAmount("ADA", 90 / CLOSE);

		funds = account.allocateFunds("ADA", LONG);
		assertEquals(9.99, funds, 0.001);

		account.setAmount("USDT", 0);
		account.setAmount("ADA", 100 / CLOSE);

		funds = account.allocateFunds("ADA", LONG);
		assertEquals(0.0, funds, 0.001);
	}

	@Test
	public void testFundAllocationAmountWithInvestedAmounts() {
		AccountManager account = getAccountManager();

		account.setAmount("USDT", 100);
		account.configuration().maximumInvestmentAmountPerAsset(60.0);

		double funds = account.allocateFunds("ADA", LONG);
		assertEquals(59.94, funds, 0.001);

		account.setAmount("USDT", 50);
		account.setAmount("ADA", 50 / CLOSE);

		funds = account.allocateFunds("ADA", LONG);
		assertEquals(9.99, funds, 0.001);

		account.setAmount("USDT", 10);
		account.setAmount("ADA", 90 / CLOSE);

		funds = account.allocateFunds("ADA", LONG);
		assertEquals(0.0, funds, 0.001);
	}

	@Test
	public void testFundAllocationPercentagePerTradeWithInvestedAmounts() {
		AccountManager account = getAccountManager();

		account.setAmount("USDT", 100);
		account.configuration().maximumInvestmentPercentagePerTrade(40.0);

		double funds = account.allocateFunds("ADA", LONG);
		assertEquals(59.94, funds, 0.001); //total funds = 150: 100 USDT + 1 BNB (worth 50 USDT).

		account.setAmount("USDT", 60);
		account.setAmount("ADA", 40 / CLOSE);

		funds = account.allocateFunds("ADA", LONG);
		assertEquals(59.94, funds, 0.001);

		account.setAmount("USDT", 20);
		account.setAmount("ADA", 80 / CLOSE);

		funds = account.allocateFunds("ADA", LONG);
		;
		assertEquals(19.98, funds, 0.001);
		account.setAmount("USDT", 0);
		account.setAmount("ADA", 100 / CLOSE);

		funds = account.allocateFunds("ADA", LONG);
		assertEquals(0.0, funds, 0.001);
	}

	@Test
	public void testFundAllocationAmountPerTradeWithInvestedAmounts() {
		AccountManager account = getAccountManager();

		account.setAmount("USDT", 100);
		account.configuration().maximumInvestmentAmountPerTrade(40.0);

		double funds = account.allocateFunds("ADA", LONG);
		assertEquals(39.96, funds, 0.001);

		account.setAmount("USDT", 60);
		account.setAmount("ADA", 40 / CLOSE);

		funds = account.allocateFunds("ADA", LONG);
		assertEquals(39.96, funds, 0.001);

		account.setAmount("USDT", 20);
		account.setAmount("ADA", 80 / CLOSE);

		funds = account.allocateFunds("ADA", LONG);
		assertEquals(19.98, funds, 0.001);
		account.setAmount("USDT", 0);
		account.setAmount("ADA", 100 / CLOSE);

		funds = account.allocateFunds("ADA", LONG);
		assertEquals(0.0, funds, 0.001);
	}
}