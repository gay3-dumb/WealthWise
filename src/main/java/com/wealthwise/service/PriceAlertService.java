package com.wealthwise.service;

import com.wealthwise.model.PriceAlert;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Thread-safe owner of alert definitions and one-time trigger evaluation.
 *
 * <p>Evaluation may run on the price-simulator thread. The trigger listener is invoked after the
 * alert's short synchronized state change, so listeners may safely hand work back to the JavaFX
 * thread without locks being held here.
 */
public class PriceAlertService {

    private final ConcurrentMap<Integer, PriceAlert> alerts = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private volatile Consumer<PriceAlert> triggerListener = alert -> { };

    public synchronized void loadAlerts(Collection<PriceAlert> savedAlerts) {
        alerts.clear();
        int highestId = 0;
        if (savedAlerts != null) {
            for (PriceAlert alert : savedAlerts) {
                if (alert == null) {
                    continue;
                }
                alerts.put(alert.getId(), alert);
                highestId = Math.max(highestId, alert.getId());
            }
        }
        nextId.set(highestId + 1);
    }

    public PriceAlert createAlert(String symbol, String name, double targetPrice,
                                  PriceAlert.Condition condition) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("Asset symbol cannot be empty.");
        }
        if (!Double.isFinite(targetPrice) || targetPrice <= 0) {
            throw new IllegalArgumentException("Target price must be a finite number greater than 0.");
        }
        if (condition == null) {
            throw new IllegalArgumentException("Choose an alert condition.");
        }

        PriceAlert alert = new PriceAlert(
                nextId.getAndIncrement(),
                symbol.trim().toUpperCase(Locale.ROOT),
                name,
                targetPrice,
                condition,
                LocalDateTime.now().withNano(0));
        alerts.put(alert.getId(), alert);
        return alert;
    }

    public boolean removeAlert(int alertId) {
        return alerts.remove(alertId) != null;
    }

    /** Returns an immutable snapshot with active alerts first, then newest alerts first. */
    public List<PriceAlert> getAlerts() {
        return alerts.values().stream()
                .sorted(Comparator.comparingInt((PriceAlert alert) -> alert.isActive() ? 0 : 1)
                        .thenComparing(PriceAlert::getCreatedAt, Comparator.reverseOrder())
                        .thenComparingInt(PriceAlert::getId))
                .toList();
    }

    public int getActiveCount() {
        return (int) alerts.values().stream().filter(PriceAlert::isActive).count();
    }

    public int getTriggeredCount() {
        return (int) alerts.values().stream().filter(PriceAlert::isTriggered).count();
    }

    /**
     * Evaluates every alert for the given symbol. Each alert triggers at most once even when
     * several threads evaluate the same price concurrently.
     *
     * @return the alerts that were triggered by this call
     */
    public List<PriceAlert> evaluatePrice(String symbol, double currentPrice) {
        if (symbol == null || symbol.isBlank() || !Double.isFinite(currentPrice) || currentPrice <= 0) {
            return List.of();
        }

        String normalisedSymbol = symbol.trim().toUpperCase(Locale.ROOT);
        LocalDateTime now = LocalDateTime.now().withNano(0);
        List<PriceAlert> triggered = new ArrayList<>();

        for (PriceAlert alert : alerts.values()) {
            if (alert.getSymbol().equals(normalisedSymbol)
                    && alert.evaluateAndTrigger(currentPrice, now)) {
                triggered.add(alert);
            }
        }

        for (PriceAlert alert : triggered) {
            try {
                triggerListener.accept(alert);
            } catch (RuntimeException e) {
                // A failing notification listener must not prevent other alerts or the simulator.
                e.printStackTrace();
            }
        }
        return List.copyOf(triggered);
    }

    public void setTriggerListener(Consumer<PriceAlert> listener) {
        this.triggerListener = Objects.requireNonNullElse(listener, alert -> { });
    }
}
