package com.wealthwise.strategy;

import java.util.List;

/**
 * The outcome of a whole Strategy Lab run.
 *
 * @param results        one row per strategy, in the order of {@link StrategyType#values()}
 * @param simulations    how many price paths were simulated
 * @param threads        how many worker threads shared the work
 * @param elapsedMillis  wall-clock time the run took
 */
public record BacktestReport(
        List<StrategyResult> results,
        int simulations,
        int threads,
        long elapsedMillis) {

    public BacktestReport {
        results = List.copyOf(results);
    }

    public StrategyResult resultFor(StrategyType type) {
        return results.stream()
                .filter(r -> r.strategy() == type)
                .findFirst()
                .orElseThrow();
    }
}
