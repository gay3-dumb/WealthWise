package com.wealthwise.service;

import com.wealthwise.db.DatabaseManager;
import com.wealthwise.model.PriceAlert;
import com.wealthwise.price.PriceSimulator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PriceAlertServiceTest {

    private PriceAlertService alertService;

    @BeforeEach
    void setUp() {
        alertService = new PriceAlertService();
    }

    @Test
    void upwardAlertTriggersWhenPriceEqualsTarget() {
        PriceAlert alert = alertService.createAlert(
                "AAPL", "Apple", 100.0, PriceAlert.Condition.RISES_TO_OR_ABOVE);

        List<PriceAlert> triggered = alertService.evaluatePrice("AAPL", 100.0);

        assertEquals(List.of(alert), triggered);
        assertFalse(alert.isActive());
        assertEquals(100.0, alert.getTriggeredPrice().doubleValue(), 1e-9);
        assertNotNull(alert.getTriggeredAt());
    }

    @Test
    void upwardAlertTriggersWhenPriceExceedsTarget() {
        PriceAlert alert = alertService.createAlert(
                "AAPL", "Apple", 100.0, PriceAlert.Condition.RISES_TO_OR_ABOVE);

        assertEquals(List.of(alert), alertService.evaluatePrice("AAPL", 125.0));
        assertEquals(125.0, alert.getTriggeredPrice().doubleValue(), 1e-9);
    }

    @Test
    void downwardAlertTriggersWhenPriceEqualsTarget() {
        PriceAlert alert = alertService.createAlert(
                "AAPL", "Apple", 100.0, PriceAlert.Condition.FALLS_TO_OR_BELOW);

        assertEquals(List.of(alert), alertService.evaluatePrice("AAPL", 100.0));
    }

    @Test
    void downwardAlertTriggersWhenPriceFallsBelowTarget() {
        PriceAlert alert = alertService.createAlert(
                "AAPL", "Apple", 100.0, PriceAlert.Condition.FALLS_TO_OR_BELOW);

        assertEquals(List.of(alert), alertService.evaluatePrice("AAPL", 80.0));
        assertEquals(80.0, alert.getTriggeredPrice().doubleValue(), 1e-9);
    }

    @Test
    void alertDoesNotTriggerWhenConditionIsNotSatisfied() {
        PriceAlert upward = alertService.createAlert(
                "AAPL", "Apple", 150.0, PriceAlert.Condition.RISES_TO_OR_ABOVE);
        PriceAlert downward = alertService.createAlert(
                "MSFT", "Microsoft", 50.0, PriceAlert.Condition.FALLS_TO_OR_BELOW);

        assertTrue(alertService.evaluatePrice("AAPL", 149.99).isEmpty());
        assertTrue(alertService.evaluatePrice("MSFT", 50.01).isEmpty());
        assertTrue(upward.isActive());
        assertTrue(downward.isActive());
    }

    @Test
    void triggeredAlertDoesNotTriggerRepeatedly() {
        AtomicInteger notifications = new AtomicInteger();
        alertService.setTriggerListener(alert -> notifications.incrementAndGet());
        PriceAlert alert = alertService.createAlert(
                "AAPL", "Apple", 100.0, PriceAlert.Condition.RISES_TO_OR_ABOVE);

        assertEquals(1, alertService.evaluatePrice("AAPL", 110.0).size());
        assertTrue(alertService.evaluatePrice("AAPL", 120.0).isEmpty());
        assertTrue(alertService.evaluatePrice("AAPL", 130.0).isEmpty());
        assertEquals(1, notifications.get());
        assertTrue(alert.isTriggered());
    }

    @Test
    void twoIndependentAlertsCanCoexistForSameAsset() {
        PriceAlert first = alertService.createAlert(
                "AAPL", "Apple", 100.0, PriceAlert.Condition.RISES_TO_OR_ABOVE);
        PriceAlert second = alertService.createAlert(
                "AAPL", "Apple", 150.0, PriceAlert.Condition.RISES_TO_OR_ABOVE);

        List<PriceAlert> triggered = alertService.evaluatePrice("AAPL", 160.0);

        assertEquals(2, triggered.size());
        assertTrue(triggered.contains(first));
        assertTrue(triggered.contains(second));
        assertTrue(first.isTriggered());
        assertTrue(second.isTriggered());
    }

    @Test
    void invalidTargetsAndSymbolsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> alertService.createAlert(
                "AAPL", "Apple", 0.0, PriceAlert.Condition.RISES_TO_OR_ABOVE));
        assertThrows(IllegalArgumentException.class, () -> alertService.createAlert(
                "AAPL", "Apple", -1.0, PriceAlert.Condition.RISES_TO_OR_ABOVE));
        assertThrows(IllegalArgumentException.class, () -> alertService.createAlert(
                "AAPL", "Apple", Double.NaN, PriceAlert.Condition.RISES_TO_OR_ABOVE));
        assertThrows(IllegalArgumentException.class, () -> alertService.createAlert(
                "AAPL", "Apple", Double.POSITIVE_INFINITY,
                PriceAlert.Condition.RISES_TO_OR_ABOVE));
        assertThrows(IllegalArgumentException.class, () -> alertService.createAlert(
                " ", "Apple", 100.0, PriceAlert.Condition.RISES_TO_OR_ABOVE));
        assertThrows(IllegalArgumentException.class, () -> alertService.createAlert(
                "AAPL", "Apple", 100.0, null));
    }

    @Test
    void concurrentPriceUpdatesCannotTriggerTheSameAlertTwice() throws Exception {
        PriceAlert alert = alertService.createAlert(
                "AAPL", "Apple", 100.0, PriceAlert.Condition.RISES_TO_OR_ABOVE);
        AtomicInteger notifications = new AtomicInteger();
        alertService.setTriggerListener(triggered -> notifications.incrementAndGet());

        int threadCount = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < threadCount; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    alertService.evaluatePrice("AAPL", 150.0);
                    return null;
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            for (Future<?> future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, notifications.get());
        assertTrue(alert.isTriggered());
    }

    @Test
    void existingSimulatorEvaluatesAlertsOnPriceUpdates() throws Exception {
        PortfolioEngine engine = new PortfolioEngine();
        engine.buy("AAPL", "Apple", 1, 100.0);
        PriceAlert alert = alertService.createAlert(
                "AAPL", "Apple", 1_000_000.0, PriceAlert.Condition.FALLS_TO_OR_BELOW);

        PriceSimulator simulator = new PriceSimulator(engine, alertService, null, 0.02);
        simulator.tick();

        assertTrue(alert.isTriggered());
        assertNotNull(alert.getTriggeredPrice());
    }

    @Test
    void alertPersistenceAndRemovalRoundTrip(@TempDir Path dir) throws Exception {
        DatabaseManager db = new DatabaseManager(dir.resolve("alerts.db").toString());
        db.init();

        PriceAlert active = alertService.createAlert(
                "AAPL", "Apple", 150.0, PriceAlert.Condition.RISES_TO_OR_ABOVE);
        PriceAlert triggered = alertService.createAlert(
                "MSFT", "Microsoft", 100.0, PriceAlert.Condition.FALLS_TO_OR_BELOW);
        assertTrue(triggered.evaluateAndTrigger(95.0, LocalDateTime.now().withNano(0)));

        db.saveAlerts(alertService.getAlerts());
        List<PriceAlert> loaded = db.loadAlerts();
        assertEquals(2, loaded.size());

        PriceAlert loadedActive = byId(loaded, active.getId());
        PriceAlert loadedTriggered = byId(loaded, triggered.getId());
        assertTrue(loadedActive.isActive());
        assertEquals(150.0, loadedActive.getTargetPrice(), 1e-9);
        assertFalse(loadedTriggered.isActive());
        assertEquals(95.0, loadedTriggered.getTriggeredPrice().doubleValue(), 1e-9);
        assertNotNull(loadedTriggered.getTriggeredAt());

        PriceAlertService reloaded = new PriceAlertService();
        reloaded.loadAlerts(loaded);
        assertEquals(2, reloaded.getAlerts().size());
        assertEquals(1, reloaded.getActiveCount());
        assertEquals(1, reloaded.getTriggeredCount());

        PriceAlert next = reloaded.createAlert(
                "TSLA", "Tesla", 200.0, PriceAlert.Condition.RISES_TO_OR_ABOVE);
        assertTrue(next.getId() > Math.max(active.getId(), triggered.getId()));

        assertTrue(reloaded.removeAlert(active.getId()));
        db.saveAlerts(reloaded.getAlerts());
        List<PriceAlert> afterRemoval = db.loadAlerts();
        assertEquals(2, afterRemoval.size());
        assertTrue(afterRemoval.stream().noneMatch(alert -> alert.getId() == active.getId()));
    }

    private static PriceAlert byId(List<PriceAlert> alerts, int id) {
        return alerts.stream()
                .filter(alert -> alert.getId() == id)
                .findFirst()
                .orElseThrow();
    }
}
