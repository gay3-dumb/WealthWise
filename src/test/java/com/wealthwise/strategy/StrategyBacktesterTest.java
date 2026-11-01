package com.wealthwise.strategy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StrategyBacktesterTest {

    private static BacktestConfig config(double vol, double drift, int sims) {
        return new BacktestConfig(100, 10_000, 252, vol, drift, 21, 12, 0.10, sims, 42L);
    }

    @Test
    void flatMarketKeepsTheStartingCashForEveryStrategy() throws Exception {
        BacktestReport report = new StrategyBacktester().run(config(0.0, 0.0, 50), 2);
        for (StrategyResult r : report.results()) {
            assertEquals(10_000, r.median(), 1e-6, r.strategy().toString());
            assertEquals(0.0, r.averageMaxDrawdown(), 1e-9);
        }
    }

    @Test
    void steadyGrowthMakesHoldBeatMonthlyBuying() throws Exception {
        // no randomness, prices only go up, so buying everything on day 0 must win
        BacktestReport report = new StrategyBacktester().run(config(0.0, 0.001, 20), 2);
        double hold = report.resultFor(StrategyType.BUY_AND_HOLD).median();
        double monthly = report.resultFor(StrategyType.MONTHLY_BUYING).median();
        double stop = report.resultFor(StrategyType.TRAILING_STOP).median();
        assertTrue(hold > monthly);
        assertEquals(hold, stop, 1e-6);   // prices never fall, so the stop never triggers
        assertEquals(0.0, report.resultFor(StrategyType.MONTHLY_BUYING).beatBuyAndHoldRate(), 1e-9);
    }

    @Test
    void sameSeedGivesSameResultWhateverTheThreadCount() throws Exception {
        BacktestConfig cfg = config(0.02, 0.0003, 2_000);
        BacktestReport one = new StrategyBacktester().run(cfg, 1);
        BacktestReport four = new StrategyBacktester().run(cfg, 4);
        for (StrategyType type : StrategyType.values()) {
            assertEquals(one.resultFor(type).median(), four.resultFor(type).median(), 1e-9);
            assertEquals(one.resultFor(type).mean(), four.resultFor(type).mean(), 1e-9);
        }
    }

    @Test
    void trailingStopNeverHasAWorseDrawdownThanHolding() throws Exception {
        BacktestReport report = new StrategyBacktester().run(config(0.025, 0.0, 3_000), 4);
        assertTrue(report.resultFor(StrategyType.TRAILING_STOP).averageMaxDrawdown()
                <= report.resultFor(StrategyType.BUY_AND_HOLD).averageMaxDrawdown() + 1e-12);
    }

    @Test
    void resultsAreOrderedWorstMedianBest() throws Exception {
        BacktestReport report = new StrategyBacktester().run(config(0.02, 0.0003, 1_000), 2);
        for (StrategyResult r : report.results()) {
            assertTrue(r.worstCase() <= r.median());
            assertTrue(r.median() <= r.bestCase());
            assertTrue(r.probabilityOfLoss() >= 0 && r.probabilityOfLoss() <= 1);
        }
        assertTrue(Double.isNaN(report.resultFor(StrategyType.BUY_AND_HOLD).beatBuyAndHoldRate()));
        assertEquals(1_000, report.simulations());
    }

    @Test
    void invalidSettingsAreRejectedWithAClearMessage() {
        assertThrows(IllegalArgumentException.class,
                () -> new BacktestConfig(0, 10_000, 252, 0.01, 0, 21, 12, 0.1, 100, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new BacktestConfig(100, -5, 252, 0.01, 0, 21, 12, 0.1, 100, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new BacktestConfig(100, 10_000, 0, 0.01, 0, 21, 12, 0.1, 100, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new BacktestConfig(100, 10_000, 252, 0.01, 0, 21, 12, 1.5, 100, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new BacktestConfig(100, 10_000, 252, 0.01, 0, 21, 12, 0.1, 0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new StrategyBacktester().run(config(0.01, 0, 10), 0));
    }
}
