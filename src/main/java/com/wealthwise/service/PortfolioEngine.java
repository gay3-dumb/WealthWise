package com.wealthwise.service;

import com.wealthwise.model.Holding;
import com.wealthwise.model.Transaction;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Core business logic (Layer 2). No JavaFX and no SQL in here, so it is easy to unit test.
 *
 * Rules (keep these consistent everywhere, and write them in your report):
 *  - Quantity and price must be greater than 0.
 *  - BUY: new average price = (oldQty*oldAvg + qty*price) / (oldQty + qty)  (weighted average cost).
 *  - SELL: cannot sell more than owned. Average buy price does not change on a sell.
 *  - When quantity reaches 0 the holding is removed.
 *  - Profit/Loss = market value - invested; Return % = P/L / invested * 100.
 *
 * The maps/lists are thread-safe because the price simulator thread reads and writes them
 * while the UI thread also reads them.
 */
public class PortfolioEngine {

    private static final double EPS = 1e-9;

    private final Map<String, Holding> holdings = new ConcurrentHashMap<>();
    private final List<Transaction> transactions = new CopyOnWriteArrayList<>();

    // ---------- trading ----------

    public synchronized Transaction buy(String symbol, String name, double quantity, double price) throws PortfolioException {
        String sym = normalise(symbol);
        validate(quantity, price);

        Holding h = holdings.get(sym);
        if (h == null) {
            holdings.put(sym, new Holding(sym, name == null || name.isBlank() ? sym : name.trim(), quantity, price, price));
        } else {
            double newQty = h.getQuantity() + quantity;
            double newAvg = (h.getQuantity() * h.getAvgBuyPrice() + quantity * price) / newQty;
            h.setQuantity(newQty);
            h.setAvgBuyPrice(newAvg);
        }
        Transaction t = new Transaction(sym, Transaction.Type.BUY, quantity, price);
        transactions.add(t);
        return t;
    }

    public synchronized Transaction sell(String symbol, double quantity, double price) throws PortfolioException {
        String sym = normalise(symbol);
        validate(quantity, price);

        Holding h = holdings.get(sym);
        if (h == null) {
            throw new PortfolioException("You do not own any " + sym + ".");
        }
        if (quantity > h.getQuantity() + EPS) {
            throw new PortfolioException(String.format("Cannot sell %.2f %s: you only own %.2f.", quantity, sym, h.getQuantity()));
        }
        double remaining = h.getQuantity() - quantity;
        if (remaining < EPS) {
            holdings.remove(sym);
        } else {
            h.setQuantity(remaining);
        }
        Transaction t = new Transaction(sym, Transaction.Type.SELL, quantity, price);
        transactions.add(t);
        return t;
    }

    // ---------- price updates (called by the background simulator) ----------

    public void updatePrice(String symbol, double newPrice) {
        Holding h = holdings.get(symbol);
        if (h != null && newPrice > 0) {
            h.setCurrentPrice(newPrice);
        }
    }

    // ---------- queries ----------

    public List<Holding> getHoldings() {
        return new ArrayList<>(holdings.values());
    }

    public Holding getHolding(String symbol) {
        return symbol == null ? null : holdings.get(symbol.trim().toUpperCase());
    }

    public List<Transaction> getTransactions() {
        return new ArrayList<>(transactions);
    }

    public double getTotalInvested() {
        return holdings.values().stream().mapToDouble(Holding::getInvested).sum();
    }

    public double getTotalValue() {
        return holdings.values().stream().mapToDouble(Holding::getMarketValue).sum();
    }

    public double getTotalProfitLoss() {
        return getTotalValue() - getTotalInvested();
    }

    public double getTotalReturnPercent() {
        double invested = getTotalInvested();
        return invested == 0 ? 0 : (getTotalProfitLoss() / invested) * 100.0;
    }

    /** Best performing holding by return %, or null when the portfolio is empty. */
    public Holding getBestPerformer() {
        return holdings.values().stream()
                .max(java.util.Comparator.comparingDouble(Holding::getReturnPercent))
                .orElse(null);
    }

    // ---------- loading saved data (used by the database layer on startup) ----------

    public synchronized void loadState(Collection<Holding> savedHoldings, Collection<Transaction> savedTransactions) {
        holdings.clear();
        transactions.clear();
        for (Holding h : savedHoldings) {
            holdings.put(h.getSymbol(), h);
        }
        transactions.addAll(savedTransactions);
    }

    // ---------- helpers ----------

    private static String normalise(String symbol) throws PortfolioException {
        if (symbol == null || symbol.isBlank()) {
            throw new PortfolioException("Asset symbol cannot be empty.");
        }
        return symbol.trim().toUpperCase();
    }

    private static void validate(double quantity, double price) throws PortfolioException {
        if (Double.isNaN(quantity) || quantity <= 0) {
            throw new PortfolioException("Quantity must be greater than 0.");
        }
        if (Double.isNaN(price) || price <= 0) {
            throw new PortfolioException("Price must be greater than 0.");
        }
    }
}
