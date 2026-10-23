package com.wealthwise.service;

import com.wealthwise.db.DatabaseManager;
import com.wealthwise.model.Holding;
import com.wealthwise.model.Transaction;
import com.wealthwise.price.PriceSimulator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PortfolioEngineTest {

    private PortfolioEngine engine;

    @BeforeEach
    void setUp() {
        engine = new PortfolioEngine();
    }

    @Test
    void buyCreatesHolding() throws Exception {
        engine.buy("aapl", "Apple", 10, 100);
        Holding h = engine.getHolding("AAPL");
        assertNotNull(h);
        assertEquals(10, h.getQuantity(), 1e-9);
        assertEquals(100, h.getAvgBuyPrice(), 1e-9);
        assertEquals(1, engine.getTransactions().size());
    }

    @Test
    void secondBuyUsesWeightedAverage() throws Exception {
        engine.buy("AAPL", "Apple", 10, 100);
        engine.buy("AAPL", "Apple", 10, 200);
        Holding h = engine.getHolding("AAPL");
        assertEquals(20, h.getQuantity(), 1e-9);
        assertEquals(150, h.getAvgBuyPrice(), 1e-9);
    }

    @Test
    void sellReducesQuantityAndKeepsAverage() throws Exception {
        engine.buy("AAPL", "Apple", 10, 100);
        engine.sell("AAPL", 4, 120);
        Holding h = engine.getHolding("AAPL");
        assertEquals(6, h.getQuantity(), 1e-9);
        assertEquals(100, h.getAvgBuyPrice(), 1e-9);
    }

    @Test
    void sellingEverythingRemovesHolding() throws Exception {
        engine.buy("AAPL", "Apple", 10, 100);
        engine.sell("AAPL", 10, 100);
        assertNull(engine.getHolding("AAPL"));
        assertTrue(engine.getHoldings().isEmpty());
    }

    @Test
    void cannotSellMoreThanOwned() throws Exception {
        engine.buy("AAPL", "Apple", 5, 100);
        assertThrows(PortfolioException.class, () -> engine.sell("AAPL", 6, 100));
        assertEquals(5, engine.getHolding("AAPL").getQuantity(), 1e-9);
    }

    @Test
    void cannotSellUnownedAsset() {
        assertThrows(PortfolioException.class, () -> engine.sell("TSLA", 1, 100));
    }

    @Test
    void rejectsInvalidInput() {
        assertThrows(PortfolioException.class, () -> engine.buy("", "x", 1, 1));
        assertThrows(PortfolioException.class, () -> engine.buy("A", "x", 0, 1));
        assertThrows(PortfolioException.class, () -> engine.buy("A", "x", -5, 1));
        assertThrows(PortfolioException.class, () -> engine.buy("A", "x", 1, 0));
        assertThrows(PortfolioException.class, () -> engine.buy("A", "x", Double.NaN, 1));
    }

    @Test
    void profitLossAndReturnAreCalculatedCorrectly() throws Exception {
        engine.buy("AAPL", "Apple", 10, 100);   // invested 1000
        engine.updatePrice("AAPL", 120);         // value 1200
        assertEquals(1000, engine.getTotalInvested(), 1e-9);
        assertEquals(1200, engine.getTotalValue(), 1e-9);
        assertEquals(200, engine.getTotalProfitLoss(), 1e-9);
        assertEquals(20.0, engine.getTotalReturnPercent(), 1e-9);
    }

    @Test
    void lossIsNegative() throws Exception {
        engine.buy("AAPL", "Apple", 10, 100);
        engine.updatePrice("AAPL", 80);
        assertEquals(-200, engine.getTotalProfitLoss(), 1e-9);
        assertEquals(-20.0, engine.getTotalReturnPercent(), 1e-9);
    }

    @Test
    void emptyPortfolioHasZeroReturn() {
        assertEquals(0, engine.getTotalReturnPercent(), 1e-9);
        assertNull(engine.getBestPerformer());
    }

    @Test
    void bestPerformerIsHighestReturn() throws Exception {
        engine.buy("AAA", "A", 1, 100);
        engine.buy("BBB", "B", 1, 100);
        engine.updatePrice("AAA", 110);
        engine.updatePrice("BBB", 150);
        assertEquals("BBB", engine.getBestPerformer().getSymbol());
    }

    @Test
    void simulatorChangesPricesWithinBounds() throws Exception {
        engine.buy("AAPL", "Apple", 10, 100);
        PriceSimulator sim = new PriceSimulator(engine, null, 0.02);
        for (int i = 0; i < 50; i++) {
            double before = engine.getHolding("AAPL").getCurrentPrice();
            sim.tick();
            double after = engine.getHolding("AAPL").getCurrentPrice();
            assertTrue(Math.abs(after - before) <= before * 0.02 + 0.01, "price moved too much");
            assertTrue(after > 0);
        }
    }

    @Test
    void databaseRoundTrip(@TempDir Path dir) throws Exception {
        DatabaseManager db = new DatabaseManager(dir.resolve("test.db").toString());
        db.init();

        engine.buy("AAPL", "Apple", 10, 100);
        engine.buy("MSFT", "Microsoft", 5, 300);
        engine.sell("AAPL", 2, 110);
        db.saveAll(engine.getHoldings(), engine.getTransactions());

        PortfolioEngine reloaded = new PortfolioEngine();
        reloaded.loadState(db.loadHoldings(), db.loadTransactions());

        assertEquals(2, reloaded.getHoldings().size());
        assertEquals(8, reloaded.getHolding("AAPL").getQuantity(), 1e-9);
        assertEquals(3, reloaded.getTransactions().size());
        assertEquals(Transaction.Type.SELL, reloaded.getTransactions().get(2).getType());
    }
}
