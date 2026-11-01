package com.wealthwise.service;

import com.wealthwise.model.Holding;
import com.wealthwise.model.Transaction;

import java.util.Collection;
import java.util.Locale;

/**
 * Projects the portfolio-wide profit/loss after a proposed trade, without changing any state.
 *
 * <p>WealthWise reports <em>unrealised</em> P/L only: for each open holding,
 * {@code quantity * currentPrice - quantity * avgBuyPrice}. The projection below mirrors the
 * engine exactly, so the number shown before a trade matches what the dashboard will show after
 * it is recorded.</p>
 *
 * <p>Consequences of that existing model, kept deliberately for consistency:</p>
 * <ul>
 *   <li>A BUY into a new symbol is valued at the trade price, so it adds no immediate P/L.</li>
 *   <li>A BUY into an existing symbol adds {@code quantity * (currentPrice - tradePrice)}.</li>
 *   <li>A SELL removes the unrealised P/L of the units sold:
 *       {@code soldQuantity * (currentPrice - avgBuyPrice)}. The sale price itself is not part
 *       of the dashboard's P/L because realised gains and cash balances are not tracked.</li>
 * </ul>
 */
public final class TradePreview {

    public enum Status { PROFIT, LOSS, BREAK_EVEN, UNAVAILABLE }

    private static final double QUANTITY_EPS = 1e-9;

    private final Status status;
    private final double projectedProfitLoss;
    private final String hint;

    private TradePreview(Status status, double projectedProfitLoss, String hint) {
        this.status = status;
        this.projectedProfitLoss = projectedProfitLoss;
        this.hint = hint;
    }

    /**
     * @param holdings current portfolio holdings (read only)
     * @param action   BUY or SELL
     * @param symbol   symbol typed by the user
     * @param quantity proposed quantity; use NaN when the input is missing or not a number
     * @param price    proposed unit price; use NaN when the input is missing or not a number
     */
    public static TradePreview project(Collection<Holding> holdings, Transaction.Type action,
                                       String symbol, double quantity, double price) {
        if (holdings == null) {
            return unavailable("Portfolio data is not available yet.");
        }
        if (action == null) {
            return unavailable("Choose Buy or Sell to preview the portfolio P&L.");
        }
        if (symbol == null || symbol.isBlank()) {
            return unavailable("Enter a symbol, quantity and price to preview the portfolio P&L.");
        }
        if (!Double.isFinite(quantity) || quantity <= 0) {
            return unavailable("Enter a quantity greater than 0 to preview the portfolio P&L.");
        }
        if (!Double.isFinite(price) || price <= 0) {
            return unavailable("Enter a price greater than 0 to preview the portfolio P&L.");
        }

        String sym = symbol.trim().toUpperCase(Locale.ROOT);
        Holding holding = findHolding(holdings, sym);
        double currentProfitLoss = holdings.stream().mapToDouble(Holding::getProfitLoss).sum();

        double projected;
        if (action == Transaction.Type.BUY) {
            // A new holding is created with currentPrice = trade price, so its P/L starts at 0.
            double addedProfitLoss = holding == null
                    ? 0.0
                    : quantity * (holding.getCurrentPrice() - price);
            projected = currentProfitLoss + addedProfitLoss;
        } else {
            if (holding == null) {
                return unavailable("You do not own " + sym + "; there is nothing to sell.");
            }
            if (quantity > holding.getQuantity() + QUANTITY_EPS) {
                return unavailable(String.format(Locale.ROOT,
                        "You only own %.2f %s; enter a smaller quantity.",
                        holding.getQuantity(), sym));
            }
            // Selling removes the sold units' unrealised P/L from the open portfolio. Mirror
            // the engine's rule that a remaining quantity below EPS closes the position.
            double remaining = holding.getQuantity() - quantity;
            double soldQuantity = remaining < QUANTITY_EPS ? holding.getQuantity() : quantity;
            projected = currentProfitLoss
                    - soldQuantity * (holding.getCurrentPrice() - holding.getAvgBuyPrice());
        }

        return classify(projected);
    }

    private static Holding findHolding(Collection<Holding> holdings, String symbol) {
        for (Holding holding : holdings) {
            if (holding.getSymbol().equalsIgnoreCase(symbol)) {
                return holding;
            }
        }
        return null;
    }

    private static TradePreview classify(double projected) {
        if (!Double.isFinite(projected)) {
            return unavailable("This trade is too large to preview.");
        }
        // Classify and display to the nearest paisa so tiny floating-point noise never
        // turns a break-even portfolio into a profit or loss statement.
        double rounded = Math.round(projected * 100.0) / 100.0;
        if (rounded == 0.0) {
            rounded = 0.0;
        }
        Status status = rounded > 0 ? Status.PROFIT
                : rounded < 0 ? Status.LOSS
                : Status.BREAK_EVEN;
        return new TradePreview(status, rounded, null);
    }

    private static TradePreview unavailable(String hint) {
        return new TradePreview(Status.UNAVAILABLE, 0.0, hint);
    }

    public Status getStatus() {
        return status;
    }

    /** Projected total P/L rounded to two decimals; 0 when unavailable. */
    public double getProjectedProfitLoss() {
        return projectedProfitLoss;
    }

    /** Neutral explanation when the inputs cannot produce a reliable projection. */
    public String getHint() {
        return hint;
    }

    public boolean isAvailable() {
        return status != Status.UNAVAILABLE;
    }
}
