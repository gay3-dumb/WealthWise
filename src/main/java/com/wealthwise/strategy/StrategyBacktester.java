package com.wealthwise.strategy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The engine behind the Strategy Lab (business layer: no JavaFX, no SQL).
 *
 * What it does, in plain words:
 *  1. Invent many possible futures for one asset (each is a "price path" of random daily moves).
 *  2. Run every strategy on the SAME path, so the comparison is fair.
 *  3. Collect the final value and the worst fall from each path, then summarise them.
 *
 * Why threads: the simulations do not depend on each other, so the work is cut into chunks and
 * a fixed pool of worker threads runs the chunks at the same time. Each simulation has its own
 * random generator, seeded from its index, so the answer is identical no matter how many
 * threads are used. Each chunk writes only to its own slots of the result arrays, so no locks
 * are needed.
 */
public class StrategyBacktester {

    private static final int STRATEGY_COUNT = StrategyType.values().length;
    private static final int HOLD = StrategyType.BUY_AND_HOLD.ordinal();
    private static final int MONTHLY = StrategyType.MONTHLY_BUYING.ordinal();
    private static final int STOP = StrategyType.TRAILING_STOP.ordinal();

    /** Runs with one worker thread per processor core. */
    public BacktestReport run(BacktestConfig config) throws InterruptedException {
        return run(config, Runtime.getRuntime().availableProcessors());
    }

    public BacktestReport run(BacktestConfig config, int threads) throws InterruptedException {
        if (threads < 1) {
            throw new IllegalArgumentException("At least one thread is required.");
        }
        long started = System.nanoTime();
        int n = config.simulations();

        double[][] finalValues = new double[STRATEGY_COUNT][n];
        double[][] maxDrawdowns = new double[STRATEGY_COUNT][n];

        ExecutorService pool = Executors.newFixedThreadPool(threads, runnable -> {
            Thread t = new Thread(runnable, "strategy-lab-worker");
            t.setDaemon(true);   // never keeps the app alive after the window is closed
            return t;
        });
        try {
            int chunks = Math.min(n, threads * 4);
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int c = 0; c < chunks; c++) {
                int from = (int) ((long) n * c / chunks);
                int to = (int) ((long) n * (c + 1) / chunks);
                tasks.add(() -> {
                    for (int i = from; i < to; i++) {
                        simulateOnePath(config, i, finalValues, maxDrawdowns);
                    }
                    return null;
                });
            }
            // invokeAll waits until every chunk has finished
            for (Future<Void> f : pool.invokeAll(tasks)) {
                try {
                    f.get();
                } catch (ExecutionException e) {
                    throw new IllegalStateException("A simulation failed: " + e.getCause(), e.getCause());
                }
            }
        } finally {
            pool.shutdownNow();
        }

        List<StrategyResult> results = new ArrayList<>();
        for (StrategyType type : StrategyType.values()) {
            results.add(summarise(type, config, finalValues, maxDrawdowns));
        }
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
        return new BacktestReport(results, n, threads, elapsedMillis);
    }

    // ---------------------------------------------------------------- one simulated future

    /**
     * Simulates one price path and runs all three strategies along it, day by day.
     * Prices follow a standard random walk: each day the price is multiplied by
     * exp((drift - volatility^2 / 2) + volatility * Z), where Z is a random number from a
     * bell curve. This keeps prices above zero. There are no trading fees or taxes.
     */
    private void simulateOnePath(BacktestConfig cfg, int pathIndex,
                                 double[][] finalValues, double[][] maxDrawdowns) {
        Random random = new Random(cfg.seed() + pathIndex * 7919L);
        double sigma = cfg.dailyVolatility();
        double dailyShift = cfg.dailyDrift() - 0.5 * sigma * sigma;

        double[] cash = new double[STRATEGY_COUNT];
        double[] shares = new double[STRATEGY_COUNT];
        double[] peakValue = new double[STRATEGY_COUNT];
        double[] worstFall = new double[STRATEGY_COUNT];
        Arrays.fill(cash, cfg.startingCash());

        double installment = cfg.startingCash() / cfg.buyCount();
        int installmentsBought = 0;
        double peakPrice = cfg.startPrice();   // for the trailing stop
        double price = cfg.startPrice();

        for (int day = 0; day <= cfg.days(); day++) {
            if (day > 0) {
                price = price * Math.exp(dailyShift + sigma * random.nextGaussian());
            }

            if (day == 0) {
                // buy and hold, and trailing stop: everything goes in on day 0
                shares[HOLD] = cash[HOLD] / price;
                cash[HOLD] = 0;
                shares[STOP] = cash[STOP] / price;
                cash[STOP] = 0;
            }

            // trailing stop: remember the highest price, sell everything on a big enough fall
            if (shares[STOP] > 0) {
                peakPrice = Math.max(peakPrice, price);
                if (price <= peakPrice * (1 - cfg.trailingStop())) {
                    cash[STOP] = shares[STOP] * price;
                    shares[STOP] = 0;
                }
            }

            // monthly buying: one equal instalment every buyIntervalDays
            if (installmentsBought < cfg.buyCount() && day % cfg.buyIntervalDays() == 0) {
                double spend = Math.min(installment, cash[MONTHLY]);
                shares[MONTHLY] += spend / price;
                cash[MONTHLY] -= spend;
                installmentsBought++;
            }

            for (int s = 0; s < STRATEGY_COUNT; s++) {
                double value = cash[s] + shares[s] * price;
                peakValue[s] = Math.max(peakValue[s], value);
                double fall = peakValue[s] == 0 ? 0 : (peakValue[s] - value) / peakValue[s];
                worstFall[s] = Math.max(worstFall[s], fall);
            }
        }

        for (int s = 0; s < STRATEGY_COUNT; s++) {
            finalValues[s][pathIndex] = cash[s] + shares[s] * price;
            maxDrawdowns[s][pathIndex] = worstFall[s];
        }
    }

    // ---------------------------------------------------------------- summarising

    private StrategyResult summarise(StrategyType type, BacktestConfig cfg,
                                     double[][] finalValues, double[][] maxDrawdowns) {
        int s = type.ordinal();
        double[] values = finalValues[s];
        int n = values.length;

        double[] sorted = values.clone();
        Arrays.sort(sorted);

        double sum = 0;
        int losses = 0;
        double drawdownSum = 0;
        int beatsHold = 0;
        for (int i = 0; i < n; i++) {
            sum += values[i];
            if (values[i] < cfg.startingCash()) {
                losses++;
            }
            drawdownSum += maxDrawdowns[s][i];
            if (values[i] > finalValues[HOLD][i]) {
                beatsHold++;
            }
        }

        return new StrategyResult(
                type,
                percentile(sorted, 0.05),
                percentile(sorted, 0.50),
                percentile(sorted, 0.95),
                sum / n,
                (double) losses / n,
                drawdownSum / n,
                type == StrategyType.BUY_AND_HOLD ? Double.NaN : (double) beatsHold / n);
    }

    /** Value at the given percentile (0 to 1) of an already sorted array. */
    static double percentile(double[] sorted, double p) {
        int index = (int) Math.round(p * (sorted.length - 1));
        return sorted[index];
    }
}
