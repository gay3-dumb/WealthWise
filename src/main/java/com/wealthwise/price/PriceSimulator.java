package com.wealthwise.price;

import com.wealthwise.model.Holding;
import com.wealthwise.service.PortfolioEngine;
import com.wealthwise.service.PriceAlertService;

import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Background price engine (Layer 3). This is the "multi-threaded" part of the project.
 *
 * A ScheduledExecutorService runs on its own daemon thread. Every few seconds it nudges each
 * held asset's price up or down by a small random percentage, then calls onTick so the UI can
 * refresh. The UI is responsible for wrapping its refresh in Platform.runLater(), because only
 * the JavaFX Application Thread may touch UI controls. That is what keeps the window from freezing.
 */
public class PriceSimulator {

    private final PortfolioEngine engine;
    private final PriceAlertService alertService;
    private final Runnable onTick;
    private final Random random = new Random();
    private ScheduledExecutorService executor;

    /** Maximum move per tick, e.g. 0.02 = +/-2%. */
    private final double maxChange;

    public PriceSimulator(PortfolioEngine engine, Runnable onTick, double maxChange) {
        this(engine, null, onTick, maxChange);
    }

    public PriceSimulator(PortfolioEngine engine, PriceAlertService alertService,
                          Runnable onTick, double maxChange) {
        this.engine = engine;
        this.alertService = alertService;
        this.onTick = onTick;
        this.maxChange = maxChange;
    }

    public synchronized void start(long periodSeconds) {
        if (executor != null && !executor.isShutdown()) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "price-simulator");
            t.setDaemon(true);   // thread dies automatically when the app closes
            return t;
        });
        executor.scheduleAtFixedRate(this::tick, periodSeconds, periodSeconds, TimeUnit.SECONDS);
    }

    public synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    public synchronized boolean isRunning() {
        return executor != null && !executor.isShutdown();
    }

    /** One simulation step. Public so tests can call it directly without waiting. */
    public void tick() {
        try {
            for (Holding h : engine.getHoldings()) {
                double change = (random.nextDouble() * 2 - 1) * maxChange;   // -max .. +max
                double newPrice = Math.max(0.01, h.getCurrentPrice() * (1 + change));
                double roundedPrice = Math.round(newPrice * 100.0) / 100.0;
                engine.updatePrice(h.getSymbol(), roundedPrice);
                if (alertService != null) {
                    alertService.evaluatePrice(h.getSymbol(), roundedPrice);
                }
            }
            if (onTick != null) {
                onTick.run();
            }
        } catch (Exception e) {
            // never let an exception kill the scheduled task
            e.printStackTrace();
        }
    }
}
