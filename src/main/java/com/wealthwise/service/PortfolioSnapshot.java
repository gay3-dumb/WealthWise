package com.wealthwise.service;

import com.wealthwise.model.Holding;
import com.wealthwise.model.Transaction;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * A frozen copy of the portfolio taken immediately before a user action, kept so the action
 * can be undone later. Holdings are copied because they are mutable (prices change constantly);
 * transactions are immutable and can be shared.
 */
public final class PortfolioSnapshot {

    private final String label;
    private final LocalDateTime createdAt;
    private final List<Holding> holdings;
    private final List<Transaction> transactions;

    public PortfolioSnapshot(String label, LocalDateTime createdAt,
                             Collection<Holding> holdings, Collection<Transaction> transactions) {
        this.label = label;
        this.createdAt = createdAt;
        this.holdings = copyHoldings(holdings);
        this.transactions = List.copyOf(transactions);
    }

    /** Capture the current state and describe the action that is about to modify it. */
    public static PortfolioSnapshot capture(String label, Collection<Holding> holdings,
                                            Collection<Transaction> transactions) {
        return new PortfolioSnapshot(label, LocalDateTime.now().withNano(0), holdings, transactions);
    }

    private static List<Holding> copyHoldings(Collection<Holding> source) {
        List<Holding> copy = new ArrayList<>(source.size());
        for (Holding h : source) {
            copy.add(new Holding(h.getSymbol(), h.getName(), h.getQuantity(),
                    h.getAvgBuyPrice(), h.getCurrentPrice()));
        }
        return Collections.unmodifiableList(copy);
    }

    public String getLabel() { return label; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public List<Holding> getHoldings() { return holdings; }
    public List<Transaction> getTransactions() { return transactions; }
}
