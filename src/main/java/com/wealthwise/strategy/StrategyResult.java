package com.wealthwise.strategy;

/**
 * Summary of one strategy across all simulated price paths. All money values are final
 * portfolio values (cash plus shares) at the end of the simulated period.
 *
 * @param strategy            which strategy this row describes
 * @param worstCase           5th percentile: only 5% of paths ended lower than this
 * @param median              the middle outcome: half the paths ended higher, half lower
 * @param bestCase            95th percentile: only 5% of paths ended higher than this
 * @param mean                simple average of all final values
 * @param probabilityOfLoss   share of paths that ended below the starting cash (0 to 1)
 * @param averageMaxDrawdown  average of the biggest peak-to-trough fall in each path (0 to 1)
 * @param beatBuyAndHoldRate  share of paths where this strategy beat buy-and-hold (0 to 1);
 *                            NaN for buy-and-hold itself, because it cannot beat itself
 */
public record StrategyResult(
        StrategyType strategy,
        double worstCase,
        double median,
        double bestCase,
        double mean,
        double probabilityOfLoss,
        double averageMaxDrawdown,
        double beatBuyAndHoldRate) {
}
