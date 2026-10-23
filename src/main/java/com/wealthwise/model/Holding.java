package com.wealthwise.model;

/**
 * A position the user currently owns: an asset, how many units, and the average buy price.
 * Layer 2 (business model). Contains no UI or database code.
 */
public class Holding {
    private final String symbol;
    private final String name;
    private double quantity;
    private double avgBuyPrice;
    private double currentPrice;

    public Holding(String symbol, String name, double quantity, double avgBuyPrice, double currentPrice) {
        this.symbol = symbol;
        this.name = name;
        this.quantity = quantity;
        this.avgBuyPrice = avgBuyPrice;
        this.currentPrice = currentPrice;
    }

    public String getSymbol() { return symbol; }
    public String getName() { return name; }
    public double getQuantity() { return quantity; }
    public double getAvgBuyPrice() { return avgBuyPrice; }
    public double getCurrentPrice() { return currentPrice; }

    public void setQuantity(double quantity) { this.quantity = quantity; }
    public void setAvgBuyPrice(double avgBuyPrice) { this.avgBuyPrice = avgBuyPrice; }
    public void setCurrentPrice(double currentPrice) { this.currentPrice = currentPrice; }

    /** Money paid for this position: quantity x average buy price. */
    public double getInvested() { return quantity * avgBuyPrice; }

    /** What the position is worth now: quantity x current price. */
    public double getMarketValue() { return quantity * currentPrice; }

    /** Unrealised profit (positive) or loss (negative). */
    public double getProfitLoss() { return getMarketValue() - getInvested(); }

    /** Return as a percentage of the amount invested. 0 if nothing is invested. */
    public double getReturnPercent() {
        double invested = getInvested();
        return invested == 0 ? 0 : (getProfitLoss() / invested) * 100.0;
    }
}
