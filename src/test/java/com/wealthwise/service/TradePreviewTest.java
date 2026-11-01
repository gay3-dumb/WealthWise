package com.wealthwise.service;

import com.wealthwise.model.Holding;
import com.wealthwise.model.Transaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradePreviewTest {

    private PortfolioEngine engine;

    @BeforeEach
    void setUp() {
        engine = new PortfolioEngine();
    }

    private TradePreview preview(Transaction.Type action, String symbol, double quantity, double price) {
        return TradePreview.project(engine.getHoldings(), action, symbol, quantity, price);
    }

    @Test
    void buyingNewSymbolStartsAtBreakEvenInsteadOfInstantLoss() {
        TradePreview result = preview(Transaction.Type.BUY, "TEST", 10, 100);

        assertEquals(TradePreview.Status.BREAK_EVEN, result.getStatus());
        assertEquals(0.0, result.getProjectedProfitLoss(), 1e-9);
        assertTrue(result.isAvailable());
        assertNull(result.getHint());
    }

    @Test
    void buyProjectionMatchesActualPortfolioProfitLossAfterTrade() throws Exception {
        engine.buy("AAPL", "Apple", 10, 100);
        engine.updatePrice("AAPL", 120); // current P/L = +200

        TradePreview result = preview(Transaction.Type.BUY, "AAPL", 5, 100);
        assertEquals(TradePreview.Status.PROFIT, result.getStatus());
        assertEquals(300, result.getProjectedProfitLoss(), 1e-9);

        engine.buy("AAPL", "Apple", 5, 100);
        assertEquals(result.getProjectedProfitLoss(), engine.getTotalProfitLoss(), 1e-9);
    }

    @Test
    void buyingAboveMarketValueCanProduceProjectedLoss() throws Exception {
        engine.buy("AAPL", "Apple", 10, 100);
        engine.updatePrice("AAPL", 120); // current P/L = +200

        TradePreview result = preview(Transaction.Type.BUY, "AAPL", 10, 150);
        assertEquals(TradePreview.Status.LOSS, result.getStatus());
        assertEquals(-100, result.getProjectedProfitLoss(), 1e-9);

        engine.buy("AAPL", "Apple", 10, 150);
        assertEquals(-100, engine.getTotalProfitLoss(), 1e-9);
    }

    @Test
    void sellingWholeProfitableHoldingProjectsBreakEven() throws Exception {
        engine.buy("AAPL", "Apple", 10, 100);
        engine.updatePrice("AAPL", 120); // current P/L = +200

        TradePreview result = preview(Transaction.Type.SELL, "AAPL", 10, 120);
        assertEquals(TradePreview.Status.BREAK_EVEN, result.getStatus());
        assertEquals(0.0, result.getProjectedProfitLoss(), 1e-9);

        engine.sell("AAPL", 10, 120);
        assertEquals(0.0, engine.getTotalProfitLoss(), 1e-9);
    }

    @Test
    void partialSaleRemovesOnlySoldUnitsProfitContribution() throws Exception {
        engine.buy("AAPL", "Apple", 10, 100);
        engine.updatePrice("AAPL", 120); // current P/L = +200

        TradePreview result = preview(Transaction.Type.SELL, "AAPL", 4, 999);
        assertEquals(TradePreview.Status.PROFIT, result.getStatus());
        assertEquals(120, result.getProjectedProfitLoss(), 1e-9);

        engine.sell("AAPL", 4, 999);
        assertEquals(120, engine.getTotalProfitLoss(), 1e-9);
    }

    @Test
    void salePriceDoesNotChangeDashboardProfitLossBecauseRealisedGainsAreNotTracked() throws Exception {
        engine.buy("AAPL", "Apple", 10, 100);
        engine.updatePrice("AAPL", 120);

        TradePreview lowPrice = preview(Transaction.Type.SELL, "AAPL", 4, 50);
        TradePreview highPrice = preview(Transaction.Type.SELL, "AAPL", 4, 500);

        assertEquals(lowPrice.getProjectedProfitLoss(), highPrice.getProjectedProfitLoss(), 1e-9);
        assertEquals(120, lowPrice.getProjectedProfitLoss(), 1e-9);
    }

    @Test
    void lossPortfolioStaysLossWhenSellingPartOfPosition() throws Exception {
        engine.buy("AAPL", "Apple", 10, 100);
        engine.updatePrice("AAPL", 80); // current P/L = -200

        TradePreview result = preview(Transaction.Type.SELL, "AAPL", 5, 80);
        assertEquals(TradePreview.Status.LOSS, result.getStatus());
        assertEquals(-100, result.getProjectedProfitLoss(), 1e-9);
    }

    @Test
    void purchaseIntoExistingPortfolioAccountsForExistingProfitLoss() throws Exception {
        engine.buy("AAPL", "Apple", 10, 100);
        engine.buy("MSFT", "Microsoft", 5, 300);
        engine.updatePrice("AAPL", 120); // AAPL +200
        engine.updatePrice("MSFT", 280); // MSFT -100, total P/L +100

        TradePreview result = preview(Transaction.Type.BUY, "NEW", 2, 50);
        assertEquals(TradePreview.Status.PROFIT, result.getStatus());
        assertEquals(100, result.getProjectedProfitLoss(), 1e-9);
    }

    @Test
    void invalidOrIncompleteInputsProduceNeutralHints() {
        assertUnavailable(preview(Transaction.Type.BUY, "", 1, 100));
        assertUnavailable(preview(Transaction.Type.BUY, "   ", 1, 100));
        assertUnavailable(preview(Transaction.Type.BUY, "AAPL", Double.NaN, 100));
        assertUnavailable(preview(Transaction.Type.BUY, "AAPL", 0, 100));
        assertUnavailable(preview(Transaction.Type.BUY, "AAPL", -1, 100));
        assertUnavailable(preview(Transaction.Type.BUY, "AAPL", 1, Double.NaN));
        assertUnavailable(preview(Transaction.Type.BUY, "AAPL", 1, 0));
        assertUnavailable(preview(Transaction.Type.SELL, "AAPL", 1, 100));
    }

    @Test
    void sellingMoreThanOwnedIsUnavailable() throws Exception {
        engine.buy("AAPL", "Apple", 5, 100);

        TradePreview result = preview(Transaction.Type.SELL, "AAPL", 6, 100);
        assertUnavailable(result);
        assertTrue(result.getHint().contains("5.00"));
    }

    @Test
    void previewNeverChangesPortfolioOrTransactions() throws Exception {
        engine.buy("AAPL", "Apple", 10, 100);
        engine.updatePrice("AAPL", 120);
        List<Holding> holdingsBefore = engine.getHoldings();
        List<Transaction> transactionsBefore = engine.getTransactions();

        preview(Transaction.Type.BUY, "AAPL", 2, 110);
        preview(Transaction.Type.SELL, "AAPL", 3, 130);
        preview(Transaction.Type.BUY, "NEW", 5, 50);

        assertEquals(holdingsBefore.size(), engine.getHoldings().size());
        assertEquals(transactionsBefore.size(), engine.getTransactions().size());
        assertEquals(10, engine.getHolding("AAPL").getQuantity(), 1e-9);
        assertEquals(100, engine.getHolding("AAPL").getAvgBuyPrice(), 1e-9);
        assertEquals(120, engine.getHolding("AAPL").getCurrentPrice(), 1e-9);
        assertNull(engine.getHolding("NEW"));
    }

    private static void assertUnavailable(TradePreview preview) {
        assertEquals(TradePreview.Status.UNAVAILABLE, preview.getStatus());
        assertFalse(preview.isAvailable());
        assertNotNull(preview.getHint());
        assertFalse(preview.getHint().isBlank());
    }
}
