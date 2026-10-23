package com.wealthwise.model;

import java.time.LocalDateTime;

/** One recorded BUY or SELL. Immutable once created. */
public class Transaction {

    public enum Type { BUY, SELL }

    private final int id;              // 0 until saved in the database
    private final String symbol;
    private final Type type;
    private final double quantity;
    private final double price;
    private final LocalDateTime timestamp;

    public Transaction(int id, String symbol, Type type, double quantity, double price, LocalDateTime timestamp) {
        this.id = id;
        this.symbol = symbol;
        this.type = type;
        this.quantity = quantity;
        this.price = price;
        this.timestamp = timestamp;
    }

    public Transaction(String symbol, Type type, double quantity, double price) {
        this(0, symbol, type, quantity, price, LocalDateTime.now().withNano(0));
    }

    public int getId() { return id; }
    public String getSymbol() { return symbol; }
    public Type getType() { return type; }
    public double getQuantity() { return quantity; }
    public double getPrice() { return price; }
    public LocalDateTime getTimestamp() { return timestamp; }
    public double getTotal() { return quantity * price; }

    @Override
    public String toString() {
        return String.format("%s %s %s x%.2f @ %.2f", timestamp.toString().replace('T', ' '), type, symbol, quantity, price);
    }
}
