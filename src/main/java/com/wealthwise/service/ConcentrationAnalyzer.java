package com.wealthwise.service;

import com.wealthwise.model.Holding;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Measures how much of a portfolio's current market value each holding represents and flags
 * holdings whose share exceeds a configurable concentration threshold.
 *
 * <p>This is a concentration measure only. It does not model volatility, predict losses, or
 * judge whether an investment is safe.
 *
 * <p>Invalid-data contract:
 * <ul>
 *   <li>A {@code null} snapshot is rejected with a {@link NullPointerException}.</li>
 *   <li>A threshold that is NaN, infinite, {@code <= 0}, or {@code > 100} is rejected with an
 *       {@link IllegalArgumentException}.</li>
 *   <li>If any holding's market value is negative, NaN, or infinite, or if the summed market
 *       value is not finite and strictly positive, the result reports
 *       {@code hasValidTotal() == false}, a total of {@code 0.0}, no allocations, and no
 *       threshold breaches. Percentages are never NaN or infinite.</li>
 * </ul>
 */
public class ConcentrationAnalyzer {

    public static final double DEFAULT_THRESHOLD_PERCENT = 30.0;

    private final double thresholdPercent;

    /** Creates an analyzer with the default threshold of 30%. */
    public ConcentrationAnalyzer() {
        this(DEFAULT_THRESHOLD_PERCENT);
    }

    /**
     * @param thresholdPercent concentration limit in percentage points, in the range (0, 100]
     */
    public ConcentrationAnalyzer(double thresholdPercent) {
        if (Double.isNaN(thresholdPercent) || Double.isInfinite(thresholdPercent)
                || thresholdPercent <= 0.0 || thresholdPercent > 100.0) {
            throw new IllegalArgumentException(
                    "Concentration threshold must be a finite percentage in the range (0, 100].");
        }
        this.thresholdPercent = thresholdPercent;
    }

    public double getThresholdPercent() {
        return thresholdPercent;
    }

    /**
     * Analyzes a snapshot of holdings. Each holding's market value is read once, and both the
     * total and every allocation are computed from those captured values, so concurrent price
     * updates cannot make the result internally inconsistent. The supplied holdings are not
     * modified.
     */
    public ConcentrationResult analyze(List<Holding> holdings) {
        Objects.requireNonNull(holdings, "Holdings snapshot must not be null.");

        List<HoldingValue> holdingValues = new ArrayList<>(holdings.size());
        double totalMarketValue = 0.0;
        for (Holding holding : holdings) {
            Objects.requireNonNull(holding, "Holdings snapshot must not contain null entries.");
            double marketValue = holding.getMarketValue();
            if (!Double.isFinite(marketValue) || marketValue < 0.0) {
                return invalidResult();
            }
            holdingValues.add(new HoldingValue(holding.getSymbol(), marketValue));
            totalMarketValue += marketValue;
        }

        if (!Double.isFinite(totalMarketValue) || totalMarketValue <= 0.0) {
            return invalidResult();
        }

        List<Allocation> allocations = new ArrayList<>(holdingValues.size());
        for (HoldingValue holdingValue : holdingValues) {
            double allocationPercent = holdingValue.marketValue() / totalMarketValue * 100.0;
            allocations.add(new Allocation(holdingValue.symbol(), holdingValue.marketValue(),
                    allocationPercent, allocationPercent > thresholdPercent));
        }
        allocations.sort(Comparator.comparingDouble(Allocation::allocationPercent).reversed()
                .thenComparing(Allocation::symbol));

        return new ConcentrationResult(totalMarketValue, true, thresholdPercent, allocations);
    }

    private ConcentrationResult invalidResult() {
        return new ConcentrationResult(0.0, false, thresholdPercent, List.of());
    }

    private record HoldingValue(String symbol, double marketValue) {
    }

    /** One holding's share of the analyzed portfolio. */
    public record Allocation(String symbol, double marketValue, double allocationPercent,
                             boolean exceedsThreshold) {
    }

    /**
     * Warning for one holding whose allocation strictly exceeds the configured threshold.
     * Percentages are the unrounded calculated values; the message formats them for display.
     */
    public record ConcentrationWarning(String symbol, double marketValue,
                                       double allocationPercent, double thresholdPercent,
                                       String message) {
    }

    /**
     * Result of one analysis. {@code allocations} is an unmodifiable snapshot sorted by
     * descending allocation percentage, with symbol as the tie-breaker.
     */
    public record ConcentrationResult(double totalMarketValue, boolean hasValidTotal,
                                      double thresholdPercent, List<Allocation> allocations) {

        public ConcentrationResult {
            allocations = List.copyOf(allocations);
        }

        public boolean hasBreaches() {
            return allocations.stream().anyMatch(Allocation::exceedsThreshold);
        }

        /**
         * Warnings for every allocation that strictly exceeds the threshold, in the same
         * deterministic order as {@link #allocations()}. Empty for invalid or empty portfolios.
         * Warnings are derived from the existing allocations; no values are recalculated.
         */
        public List<ConcentrationWarning> warnings() {
            if (!hasValidTotal) {
                return List.of();
            }
            return allocations.stream()
                    .filter(Allocation::exceedsThreshold)
                    .map(allocation -> new ConcentrationWarning(
                            allocation.symbol(),
                            allocation.marketValue(),
                            allocation.allocationPercent(),
                            thresholdPercent,
                            String.format(
                                    "%s represents %.1f%% of your portfolio, exceeding your "
                                            + "%.1f%% concentration threshold.",
                                    allocation.symbol(), allocation.allocationPercent(),
                                    thresholdPercent)))
                    .toList();
        }
    }
}
