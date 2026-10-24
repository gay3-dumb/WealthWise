package com.wealthwise.service;

import com.wealthwise.model.Holding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConcentrationAnalyzerTest {

    private static final double EPS = 1e-9;

    private ConcentrationAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new ConcentrationAnalyzer();
    }

    @Test
    void defaultThresholdIsThirtyPercent() {
        assertEquals(30.0, analyzer.getThresholdPercent(), EPS);
    }

    @Test
    void emptyPortfolioReturnsNoAllocationsAndNoBreaches() {
        ConcentrationAnalyzer.ConcentrationResult result = analyzer.analyze(List.of());

        assertFalse(result.hasValidTotal());
        assertEquals(0.0, result.totalMarketValue(), EPS);
        assertTrue(result.allocations().isEmpty());
        assertFalse(result.hasBreaches());
    }

    @Test
    void singleHoldingReceivesOneHundredPercent() {
        ConcentrationAnalyzer.ConcentrationResult result =
                analyzer.analyze(List.of(holding("AAPL", 10, 100)));

        assertTrue(result.hasValidTotal());
        assertEquals(1000.0, result.totalMarketValue(), EPS);
        assertEquals(1, result.allocations().size());

        ConcentrationAnalyzer.Allocation allocation = result.allocations().get(0);
        assertEquals("AAPL", allocation.symbol());
        assertEquals(1000.0, allocation.marketValue(), EPS);
        assertEquals(100.0, allocation.allocationPercent(), EPS);
        assertTrue(allocation.exceedsThreshold());
        assertTrue(result.hasBreaches());
    }

    @Test
    void twoHoldingsReceiveCorrectAllocations() {
        ConcentrationAnalyzer.ConcentrationResult result = analyzer.analyze(List.of(
                holding("AAPL", 10, 100),    // 1,000
                holding("MSFT", 10, 300)));  // 3,000

        assertEquals(4000.0, result.totalMarketValue(), EPS);
        assertEquals(2, result.allocations().size());

        ConcentrationAnalyzer.Allocation msft = allocationFor(result, "MSFT");
        assertEquals(3000.0, msft.marketValue(), EPS);
        assertEquals(75.0, msft.allocationPercent(), EPS);

        ConcentrationAnalyzer.Allocation aapl = allocationFor(result, "AAPL");
        assertEquals(1000.0, aapl.marketValue(), EPS);
        assertEquals(25.0, aapl.allocationPercent(), EPS);
    }

    @Test
    void allocationStrictlyBelowThresholdDoesNotBreach() {
        ConcentrationAnalyzer.ConcentrationResult result = analyzer.analyze(List.of(
                holding("AAPL", 1, 20),   // 20%
                holding("MSFT", 1, 80))); // 80%

        ConcentrationAnalyzer.Allocation aapl = allocationFor(result, "AAPL");
        assertEquals(20.0, aapl.allocationPercent(), EPS);
        assertFalse(aapl.exceedsThreshold());

        assertTrue(allocationFor(result, "MSFT").exceedsThreshold());
    }

    @Test
    void allocationExactlyAtThresholdDoesNotBreach() {
        ConcentrationAnalyzer atTwentyFive = new ConcentrationAnalyzer(25.0);

        ConcentrationAnalyzer.ConcentrationResult result = atTwentyFive.analyze(List.of(
                holding("AAA", 1, 25),   // 25%
                holding("BBB", 3, 25))); // 75%

        ConcentrationAnalyzer.Allocation aaa = allocationFor(result, "AAA");
        assertEquals(25.0, aaa.allocationPercent(), EPS);
        assertFalse(aaa.exceedsThreshold());
    }

    @Test
    void allocationStrictlyAboveThresholdBreaches() {
        ConcentrationAnalyzer.ConcentrationResult result = analyzer.analyze(List.of(
                holding("AAA", 1, 40),   // 40%
                holding("BBB", 1, 60))); // 60%

        assertTrue(allocationFor(result, "AAA").exceedsThreshold());
        assertTrue(allocationFor(result, "BBB").exceedsThreshold());
        assertTrue(result.hasBreaches());
    }

    @Test
    void invalidThresholdsAreRejected() {
        double[] invalidThresholds = {
                Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                0.0, -0.1, -5.0, 100.0001
        };

        for (double threshold : invalidThresholds) {
            assertThrows(IllegalArgumentException.class, () -> new ConcentrationAnalyzer(threshold),
                    "threshold should be rejected: " + threshold);
        }

        assertDoesNotThrow(() -> new ConcentrationAnalyzer(100.0));
    }

    @Test
    void nullHoldingsSnapshotIsRejected() {
        assertThrows(NullPointerException.class, () -> analyzer.analyze(null));
    }

    @Test
    void negativeMarketValueMakesTotalInvalid() {
        // Holding allows this state when constructed directly; PortfolioEngine prevents it.
        ConcentrationAnalyzer.ConcentrationResult result =
                analyzer.analyze(List.of(holding("BAD", 10, -5)));

        assertFalse(result.hasValidTotal());
        assertEquals(0.0, result.totalMarketValue(), EPS);
        assertTrue(result.allocations().isEmpty());
        assertFalse(result.hasBreaches());
    }

    @Test
    void nonFiniteMarketValueMakesTotalInvalid() {
        for (double price : new double[]{Double.NaN, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY}) {
            ConcentrationAnalyzer.ConcentrationResult result =
                    analyzer.analyze(List.of(holding("BAD", 10, price),
                            holding("GOOD", 1, 100)));

            assertFalse(result.hasValidTotal(), "should be invalid for price " + price);
            assertEquals(0.0, result.totalMarketValue(), EPS);
            assertTrue(result.allocations().isEmpty());
            assertFalse(result.hasBreaches());
        }
    }

    @Test
    void zeroTotalMarketValueProducesNoValidTotal() {
        ConcentrationAnalyzer.ConcentrationResult result =
                analyzer.analyze(List.of(holding("ZERO", 0, 100)));

        assertFalse(result.hasValidTotal());
        assertEquals(0.0, result.totalMarketValue(), EPS);
        assertTrue(result.allocations().isEmpty());
    }

    @Test
    void allocationsAreSortedByDescendingPercentThenSymbol() {
        ConcentrationAnalyzer.ConcentrationResult result = analyzer.analyze(List.of(
                holding("CCC", 1, 25),   // 25%, tied with BBB
                holding("AAA", 1, 50),   // 50%
                holding("BBB", 1, 25))); // 25%, tied with CCC

        assertEquals(List.of("AAA", "BBB", "CCC"), result.allocations().stream()
                .map(ConcentrationAnalyzer.Allocation::symbol)
                .toList());
    }

    @Test
    void allocationPercentagesSumToApproximatelyOneHundred() {
        ConcentrationAnalyzer.ConcentrationResult result = analyzer.analyze(List.of(
                holding("AAPL", 7, 13.37),
                holding("MSFT", 11, 29.99),
                holding("TSLA", 5, 101.5)));

        double sum = result.allocations().stream()
                .mapToDouble(ConcentrationAnalyzer.Allocation::allocationPercent)
                .sum();
        assertEquals(100.0, sum, 1e-9);
    }

    @Test
    void resultAllocationsAreImmutable() {
        ConcentrationAnalyzer.ConcentrationResult result =
                analyzer.analyze(List.of(holding("AAPL", 1, 100)));

        assertThrows(UnsupportedOperationException.class, () -> result.allocations().clear());
    }

    @Test
    void customThresholdIsReflectedInResultAndWarnings() {
        ConcentrationAnalyzer atTen = new ConcentrationAnalyzer(10.0);

        ConcentrationAnalyzer.ConcentrationResult result = atTen.analyze(List.of(
                holding("AAPL", 1, 5),    // 5%
                holding("MSFT", 1, 95))); // 95%

        assertEquals(10.0, result.thresholdPercent(), EPS);
        assertFalse(allocationFor(result, "AAPL").exceedsThreshold());

        List<ConcentrationAnalyzer.ConcentrationWarning> warnings = result.warnings();
        assertEquals(1, warnings.size());
        assertEquals("MSFT", warnings.get(0).symbol());
        assertEquals(10.0, warnings.get(0).thresholdPercent(), EPS);
    }

    @Test
    void holdingAboveThresholdGeneratesWarning() {
        ConcentrationAnalyzer.ConcentrationResult result = analyzer.analyze(List.of(
                holding("AAPL", 1, 20),   // 20%
                holding("MSFT", 1, 80))); // 80%

        List<ConcentrationAnalyzer.ConcentrationWarning> warnings = result.warnings();
        assertEquals(1, warnings.size());

        ConcentrationAnalyzer.ConcentrationWarning warning = warnings.get(0);
        assertEquals("MSFT", warning.symbol());
        assertEquals(80.0, warning.marketValue(), EPS);
        assertEquals(80.0, warning.allocationPercent(), EPS);
        assertEquals(30.0, warning.thresholdPercent(), EPS);
        assertTrue(warning.message().contains("MSFT"));
    }

    @Test
    void holdingExactlyAtThresholdDoesNotGenerateWarning() {
        ConcentrationAnalyzer atSeventyFive = new ConcentrationAnalyzer(75.0);

        ConcentrationAnalyzer.ConcentrationResult result = atSeventyFive.analyze(List.of(
                holding("AAA", 1, 25),   // 25%
                holding("BBB", 3, 25))); // 75%, equal to the threshold

        assertTrue(result.warnings().isEmpty());
    }

    @Test
    void holdingBelowThresholdDoesNotGenerateWarning() {
        ConcentrationAnalyzer atNinety = new ConcentrationAnalyzer(90.0);

        ConcentrationAnalyzer.ConcentrationResult result = atNinety.analyze(List.of(
                holding("AAA", 1, 20),   // 20%
                holding("BBB", 1, 80))); // 80%

        assertTrue(result.warnings().isEmpty());
    }

    @Test
    void multipleConcentratedHoldingsGenerateSeparateWarnings() {
        ConcentrationAnalyzer.ConcentrationResult result = analyzer.analyze(List.of(
                holding("AAA", 1, 40),   // 40%
                holding("BBB", 1, 35),   // 35%
                holding("CCC", 1, 25))); // 25%

        List<ConcentrationAnalyzer.ConcentrationWarning> warnings = result.warnings();
        assertEquals(2, warnings.size());
        assertNotSame(warnings.get(0), warnings.get(1));
        assertEquals(List.of("AAA", "BBB"), warningSymbols(result));
    }

    @Test
    void warningMessageContainsSymbolAllocationAndThreshold() {
        ConcentrationAnalyzer atTwenty = new ConcentrationAnalyzer(20.0);

        ConcentrationAnalyzer.ConcentrationResult result = atTwenty.analyze(List.of(
                holding("AAA", 1, 40),   // 40%
                holding("BBB", 1, 60))); // 60%

        String message = warningFor(result, "AAA").message();
        assertAll(
                () -> assertTrue(message.contains("AAA")),
                () -> assertTrue(message.contains("40.0%")),
                () -> assertTrue(message.contains("20.0%")));
    }

    @Test
    void emptyPortfolioProducesNoWarnings() {
        assertTrue(analyzer.analyze(List.of()).warnings().isEmpty());
    }

    @Test
    void invalidPortfolioProducesNoWarnings() {
        ConcentrationAnalyzer.ConcentrationResult result =
                analyzer.analyze(List.of(holding("BAD", 10, -5)));

        assertFalse(result.hasValidTotal());
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    void warningListIsImmutable() {
        ConcentrationAnalyzer.ConcentrationResult result =
                analyzer.analyze(List.of(holding("AAPL", 1, 100)));

        assertFalse(result.warnings().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> result.warnings().clear());
    }

    @Test
    void warningOrderMatchesAllocationOrder() {
        // 37.5%, 37.5%, 25%: two tied breaches, ordered by symbol.
        ConcentrationAnalyzer.ConcentrationResult result = analyzer.analyze(List.of(
                holding("CCC", 3, 1),
                holding("BBB", 3, 1),
                holding("AAA", 2, 1)));

        assertEquals(List.of("BBB", "CCC", "AAA"), result.allocations().stream()
                .map(ConcentrationAnalyzer.Allocation::symbol)
                .toList());
        assertEquals(List.of("BBB", "CCC"), warningSymbols(result));
    }

    @Test
    void marketValueIsCapturedOncePerAnalysis() {
        // Simulates a price update racing with analysis: both the total and the allocation
        // must use the same captured value.
        class ChangingHolding extends Holding {
            private int reads;

            ChangingHolding() {
                super("AAA", "Alpha", 1, 1, 1);
            }

            @Override
            public double getMarketValue() {
                reads++;
                return reads == 1 ? 25.0 : 75.0;
            }
        }

        ChangingHolding changing = new ChangingHolding();
        ConcentrationAnalyzer.ConcentrationResult result = analyzer.analyze(List.of(changing));

        assertEquals(1, changing.reads);
        assertEquals(25.0, result.totalMarketValue(), EPS);
        assertEquals(1, result.allocations().size());
        assertEquals(25.0, result.allocations().get(0).marketValue(), EPS);
        assertEquals(100.0, result.allocations().get(0).allocationPercent(), EPS);
    }

    @Test
    void consecutiveAnalysesReflectUpdatedMarketValues() {
        Holding aaa = holding("AAA", 1, 40);
        Holding bbb = holding("BBB", 1, 60);

        ConcentrationAnalyzer.ConcentrationResult before = analyzer.analyze(List.of(aaa, bbb));
        assertEquals(40.0, allocationFor(before, "AAA").allocationPercent(), EPS);
        assertEquals(List.of("BBB", "AAA"), warningSymbols(before));

        bbb.setCurrentPrice(10); // values become 40 and 10: 80% / 20%

        ConcentrationAnalyzer.ConcentrationResult after = analyzer.analyze(List.of(aaa, bbb));
        assertEquals(50.0, after.totalMarketValue(), EPS);
        assertEquals(80.0, allocationFor(after, "AAA").allocationPercent(), EPS);
        assertEquals(20.0, allocationFor(after, "BBB").allocationPercent(), EPS);
        assertEquals(List.of("AAA"), warningSymbols(after));
    }

    @Test
    void warningsCorrespondExactlyToBreachingAllocations() {
        ConcentrationAnalyzer.ConcentrationResult result = analyzer.analyze(List.of(
                holding("AAA", 1, 30),   // 30%, equal to the threshold
                holding("BBB", 1, 35),   // 35%
                holding("CCC", 1, 35))); // 35%

        List<ConcentrationAnalyzer.Allocation> breaching = result.allocations().stream()
                .filter(ConcentrationAnalyzer.Allocation::exceedsThreshold)
                .toList();
        List<ConcentrationAnalyzer.ConcentrationWarning> warnings = result.warnings();

        assertEquals(2, breaching.size());
        assertEquals(breaching.size(), warnings.size());
        for (int i = 0; i < breaching.size(); i++) {
            ConcentrationAnalyzer.Allocation allocation = breaching.get(i);
            ConcentrationAnalyzer.ConcentrationWarning warning = warnings.get(i);
            assertAll(
                    () -> assertEquals(allocation.symbol(), warning.symbol()),
                    () -> assertEquals(allocation.marketValue(), warning.marketValue(), EPS),
                    () -> assertEquals(allocation.allocationPercent(),
                            warning.allocationPercent(), EPS),
                    () -> assertEquals(result.thresholdPercent(), warning.thresholdPercent(), EPS));
        }
    }

    @Test
    void thresholdChangesProduceExpectedWarningsForSamePortfolio() {
        List<Holding> holdings = List.of(
                holding("AAA", 1, 10),   // 10%
                holding("BBB", 1, 90));  // 90%

        assertEquals(List.of("BBB"),
                warningSymbols(new ConcentrationAnalyzer(50.0).analyze(holdings)));
        assertEquals(List.of("BBB", "AAA"),
                warningSymbols(new ConcentrationAnalyzer(5.0).analyze(holdings)));
        assertTrue(new ConcentrationAnalyzer(90.0).analyze(holdings).warnings().isEmpty());
    }

    private static Holding holding(String symbol, double quantity, double currentPrice) {
        return new Holding(symbol, symbol, quantity, currentPrice, currentPrice);
    }

    private static List<String> warningSymbols(
            ConcentrationAnalyzer.ConcentrationResult result) {
        return result.warnings().stream()
                .map(ConcentrationAnalyzer.ConcentrationWarning::symbol)
                .toList();
    }

    private static ConcentrationAnalyzer.ConcentrationWarning warningFor(
            ConcentrationAnalyzer.ConcentrationResult result, String symbol) {
        return result.warnings().stream()
                .filter(warning -> warning.symbol().equals(symbol))
                .findFirst()
                .orElseThrow();
    }

    private static ConcentrationAnalyzer.Allocation allocationFor(
            ConcentrationAnalyzer.ConcentrationResult result, String symbol) {
        return result.allocations().stream()
                .filter(allocation -> allocation.symbol().equals(symbol))
                .findFirst()
                .orElseThrow();
    }
}
