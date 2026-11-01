package com.wealthwise.strategy;

/**
 * All the settings for one Strategy Lab run. Immutable, and it checks itself: an invalid
 * combination throws IllegalArgumentException with a message that is safe to show the user.
 *
 * @param startPrice         price of the asset on day 0
 * @param startingCash       money available at the start
 * @param days               how many trading days to simulate (for example 252 is about one year)
 * @param dailyVolatility    typical daily move as a fraction (0.015 means about 1.5% a day)
 * @param dailyDrift         average daily growth as a fraction (0.0003 means about 0.03% a day)
 * @param buyIntervalDays    days between instalments for the monthly-buying strategy
 * @param buyCount           number of equal instalments for the monthly-buying strategy
 * @param trailingStop       fall from the peak that triggers the stop, as a fraction (0.10 means 10%)
 * @param simulations        how many random price paths to run
 * @param seed               random seed: the same seed always gives the same results
 */
public record BacktestConfig(
        double startPrice,
        double startingCash,
        int days,
        double dailyVolatility,
        double dailyDrift,
        int buyIntervalDays,
        int buyCount,
        double trailingStop,
        int simulations,
        long seed) {

    public static final int MAX_SIMULATIONS = 200_000;
    public static final int MAX_DAYS = 3_650;

    public BacktestConfig {
        if (!(startPrice > 0)) {
            throw new IllegalArgumentException("Start price must be greater than 0.");
        }
        if (!(startingCash > 0)) {
            throw new IllegalArgumentException("Starting cash must be greater than 0.");
        }
        if (days < 1 || days > MAX_DAYS) {
            throw new IllegalArgumentException("Days must be between 1 and " + MAX_DAYS + ".");
        }
        if (!(dailyVolatility >= 0) || dailyVolatility > 0.5) {
            throw new IllegalArgumentException("Daily volatility must be between 0% and 50%.");
        }
        if (!(dailyDrift > -0.5) || dailyDrift > 0.5) {
            throw new IllegalArgumentException("Daily drift must be between -50% and 50%.");
        }
        if (buyIntervalDays < 1) {
            throw new IllegalArgumentException("Days between purchases must be at least 1.");
        }
        if (buyCount < 1) {
            throw new IllegalArgumentException("Number of purchases must be at least 1.");
        }
        if (!(trailingStop > 0) || trailingStop >= 1) {
            throw new IllegalArgumentException("Trailing stop must be between 0% and 100%.");
        }
        if (simulations < 1 || simulations > MAX_SIMULATIONS) {
            throw new IllegalArgumentException(
                    "Simulations must be between 1 and " + MAX_SIMULATIONS + ".");
        }
    }
}
