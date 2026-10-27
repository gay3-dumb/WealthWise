package com.wealthwise.model;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * A user-defined price threshold for a held asset. An alert becomes inactive exactly once when
 * its condition is satisfied, and records the price and time that caused the trigger.
 */
public class PriceAlert {

    public enum Condition {
        RISES_TO_OR_ABOVE("Rises to or above target", "rose to or above"),
        FALLS_TO_OR_BELOW("Falls to or below target", "fell to or below");

        private final String displayName;
        private final String notificationPhrase;

        Condition(String displayName, String notificationPhrase) {
            this.displayName = displayName;
            this.notificationPhrase = notificationPhrase;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getNotificationPhrase() {
            return notificationPhrase;
        }

        public boolean isSatisfiedBy(double currentPrice, double targetPrice) {
            return switch (this) {
                case RISES_TO_OR_ABOVE -> currentPrice >= targetPrice;
                case FALLS_TO_OR_BELOW -> currentPrice <= targetPrice;
            };
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    private final int id;
    private final String symbol;
    private final String name;
    private final double targetPrice;
    private final Condition condition;
    private final LocalDateTime createdAt;

    private boolean active;
    private LocalDateTime triggeredAt;
    private Double triggeredPrice;

    public PriceAlert(int id, String symbol, String name, double targetPrice, Condition condition,
                      boolean active, LocalDateTime createdAt, LocalDateTime triggeredAt,
                      Double triggeredPrice) {
        if (id <= 0) {
            throw new IllegalArgumentException("Alert ID must be greater than 0.");
        }
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("Asset symbol cannot be empty.");
        }
        if (!Double.isFinite(targetPrice) || targetPrice <= 0) {
            throw new IllegalArgumentException("Target price must be a finite number greater than 0.");
        }
        this.id = id;
        this.symbol = symbol.trim().toUpperCase(java.util.Locale.ROOT);
        this.name = name == null || name.isBlank() ? this.symbol : name.trim();
        this.targetPrice = targetPrice;
        this.condition = Objects.requireNonNull(condition, "Alert condition is required.");
        this.createdAt = Objects.requireNonNull(createdAt, "Creation time is required.");
        this.active = active;
        this.triggeredAt = triggeredAt;
        this.triggeredPrice = triggeredPrice;
    }

    public PriceAlert(int id, String symbol, String name, double targetPrice, Condition condition,
                      LocalDateTime createdAt) {
        this(id, symbol, name, targetPrice, condition, true, createdAt, null, null);
    }

    public int getId() {
        return id;
    }

    public String getSymbol() {
        return symbol;
    }

    public String getName() {
        return name;
    }

    public double getTargetPrice() {
        return targetPrice;
    }

    public Condition getCondition() {
        return condition;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public synchronized boolean isActive() {
        return active;
    }

    public synchronized boolean isTriggered() {
        return !active;
    }

    public synchronized LocalDateTime getTriggeredAt() {
        return triggeredAt;
    }

    public synchronized Double getTriggeredPrice() {
        return triggeredPrice;
    }

    /**
     * Marks this alert triggered if it is still active and the observed price satisfies its
     * condition. Synchronization guarantees that concurrent observations cannot trigger it twice.
     */
    public synchronized boolean evaluateAndTrigger(double currentPrice, LocalDateTime now) {
        if (!active || !Double.isFinite(currentPrice) || currentPrice <= 0) {
            return false;
        }
        if (!condition.isSatisfiedBy(currentPrice, targetPrice)) {
            return false;
        }
        LocalDateTime triggerTime = Objects.requireNonNull(now, "Trigger time is required.");
        active = false;
        triggeredAt = triggerTime;
        triggeredPrice = currentPrice;
        return true;
    }
}
