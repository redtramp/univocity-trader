package com.univocity.trader.account;

import java.math.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class Balance implements Cloneable {

	public static final Map<String, AtomicLong> balanceUpdateCounts = new ConcurrentHashMap<>();
	public static final Balance ZERO = new Balance("");

	private final String symbol;
	private BigDecimal free = BigDecimal.ZERO;
	private BigDecimal locked = BigDecimal.ZERO;
	private BigDecimal shorted = BigDecimal.ZERO;
	private Map<String, BigDecimal> marginReserves = new ConcurrentHashMap<>();
	private double freeAmount = -1.0;
	private double shortedAmount = -1.0;

	public static final MathContext ROUND_MC = new MathContext(8, RoundingMode.HALF_EVEN);

	public Balance(String symbol) {
		this.symbol = symbol;
	}

	public Balance(String symbol, double free) {
		this.symbol = symbol;
		this.free = ensurePositive(BigDecimal.valueOf(free), "free balance");
	}

	public String getSymbol() {
		return symbol;
	}

	public BigDecimal getFree() {
		return free;
	}

	public double getFreeAmount() {
		if (freeAmount < 0.0) {
			freeAmount = free.doubleValue();
		}
		return freeAmount;
	}

	public void setFree(BigDecimal free) {
		this.free = ensurePositive(free, "free balance");
		this.freeAmount = -1.0;
	}

	public BigDecimal getLocked() {
		return locked;
	}

	public void setLocked(BigDecimal locked) {
		this.locked = ensurePositive(locked, "locked balance");
	}

	public double getShortedAmount() {
		if (shortedAmount < 0.0) {
			shortedAmount = shorted.doubleValue();
		}
		return shortedAmount;
	}

	public BigDecimal getShorted() {
		return shorted;
	}

	public void setShorted(BigDecimal shorted) {
		this.shorted = ensurePositive(shorted, "shorted balance");
		this.shortedAmount = -1.0;
	}

	public BigDecimal getMarginReserve(String assetSymbol) {
		return round(marginReserves.getOrDefault(assetSymbol, BigDecimal.ZERO));
	}

	public Set<String> getShortedAssetSymbols() {
		return marginReserves.keySet();
	}

	public void setMarginReserve(String assetSymbol, BigDecimal marginReserve) {
		marginReserve = ensurePositive(marginReserve, "margin reserve");
		if (marginReserve.compareTo(BigDecimal.ZERO) <= 0) {
			this.marginReserves.remove(assetSymbol);
		} else {
			this.marginReserves.put(assetSymbol, marginReserve);
		}
	}

	private BigDecimal ensurePositive(BigDecimal bd, String field) {
		bd = round(bd == null ? BigDecimal.ZERO : bd);
		if (bd.compareTo(BigDecimal.ZERO) >= 0) {
			balanceUpdateCounts.computeIfAbsent(symbol, (s) -> new AtomicLong(1)).incrementAndGet();
			return bd;
		}
		throw new IllegalStateException(symbol + ": can't set " + field + " to  " + bd);
	}

	public BigDecimal getTotal() {
		return round(free.add(locked));
	}

	@Override
	public String toString() {
		return "{" +
				"'" + symbol + '\'' +
				", free=" + getFree() +
				", locked=" + getLocked() +
				", shorted=" + getShorted() +
				", margin reserves=" + marginReserves +
				'}';
	}

	public static final BigDecimal round(BigDecimal bd) {
		if (bd.scale() != ROUND_MC.getPrecision()) {
			return bd.setScale(ROUND_MC.getPrecision(), ROUND_MC.getRoundingMode());
		}
		return bd;
	}

	public static final String roundStr(BigDecimal bd) {
		return round(bd).toPlainString();
	}

	public Balance clone() {
		try {
			return (Balance) super.clone();
		} catch (CloneNotSupportedException e) {
			throw new IllegalStateException(e);
		}
	}

}
