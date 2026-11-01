package com.wealthwise.service;

import com.wealthwise.db.DatabaseManager;
import com.wealthwise.model.Holding;
import com.wealthwise.model.Transaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UndoManagerTest {

    private PortfolioEngine engine;
    private UndoManager undoManager;

    @BeforeEach
    void setUp() {
        engine = new PortfolioEngine();
        undoManager = new UndoManager();
    }

    /** Mirrors the controller: snapshot first, then trade, then record only on success. */
    private void buyAndRecord(String symbol, String name, double qty, double price) throws Exception {
        PortfolioSnapshot before = PortfolioSnapshot.capture(
                "Buy " + qty + " " + symbol, engine.getHoldings(), engine.getTransactions());
        engine.buy(symbol, name, qty, price);
        undoManager.record(before);
    }

    private void sellAndRecord(String symbol, double qty, double price) throws Exception {
        PortfolioSnapshot before = PortfolioSnapshot.capture(
                "Sell " + qty + " " + symbol, engine.getHoldings(), engine.getTransactions());
        engine.sell(symbol, qty, price);
        undoManager.record(before);
    }

    private void undo() {
        PortfolioSnapshot snapshot = undoManager.undo();
        assertNotNull(snapshot);
        engine.restoreState(snapshot.getHoldings(), snapshot.getTransactions());
    }

    private double qty(String symbol) {
        Holding h = engine.getHolding(symbol);
        return h == null ? 0 : h.getQuantity();
    }

    @Test
    void undoBuyRemovesHoldingThatThePurchaseCreated() throws Exception {
        buyAndRecord("TEST", "Test Inc.", 3, 100);

        assertEquals(3, qty("TEST"), 1e-9);
        assertEquals(1, engine.getTransactions().size());
        assertTrue(undoManager.canUndo());

        undo();

        assertNull(engine.getHolding("TEST"));
        assertTrue(engine.getTransactions().isEmpty());
        assertEquals(0, engine.getTotalValue(), 1e-9);
        assertFalse(undoManager.canUndo());
    }

    @Test
    void undoBuyRestoresPreviousQuantityAndAverageCost() throws Exception {
        buyAndRecord("AAPL", "Apple", 10, 100);
        buyAndRecord("AAPL", "Apple", 10, 200);

        assertEquals(20, qty("AAPL"), 1e-9);
        assertEquals(150, engine.getHolding("AAPL").getAvgBuyPrice(), 1e-9);

        undo();

        assertEquals(10, qty("AAPL"), 1e-9);
        assertEquals(100, engine.getHolding("AAPL").getAvgBuyPrice(), 1e-9);
        assertEquals(1, engine.getTransactions().size());
        assertEquals(1000, engine.getTotalInvested(), 1e-9);
    }

    @Test
    void undoSaleRestoresHoldingThatWasCompletelySold() throws Exception {
        buyAndRecord("AAPL", "Apple", 10, 100);
        sellAndRecord("AAPL", 10, 120);

        assertNull(engine.getHolding("AAPL"));
        assertEquals(2, engine.getTransactions().size());

        undo();

        assertEquals(10, qty("AAPL"), 1e-9);
        assertEquals(100, engine.getHolding("AAPL").getAvgBuyPrice(), 1e-9);
        assertEquals(1, engine.getTransactions().size());
        assertEquals(Transaction.Type.BUY, engine.getTransactions().get(0).getType());
    }

    @Test
    void undoSaleRestoresPartialQuantity() throws Exception {
        buyAndRecord("AAPL", "Apple", 10, 100);
        sellAndRecord("AAPL", 4, 120);

        assertEquals(6, qty("AAPL"), 1e-9);

        undo();

        assertEquals(10, qty("AAPL"), 1e-9);
        assertEquals(100, engine.getHolding("AAPL").getAvgBuyPrice(), 1e-9);
        assertEquals(1, engine.getTransactions().size());
    }

    @Test
    void sellAllIsUndoneInOneStep() throws Exception {
        engine.buy("AAPL", "Apple", 10, 100);
        engine.buy("MSFT", "Microsoft", 5, 300);

        PortfolioSnapshot before = PortfolioSnapshot.capture(
                "Sell all holdings", engine.getHoldings(), engine.getTransactions());
        engine.sell("AAPL", 10, 110);
        engine.sell("MSFT", 5, 320);
        undoManager.record(before);

        assertTrue(engine.getHoldings().isEmpty());
        assertEquals(4, engine.getTransactions().size());

        undo();

        assertEquals(10, qty("AAPL"), 1e-9);
        assertEquals(5, qty("MSFT"), 1e-9);
        assertEquals(2, engine.getTransactions().size());
        assertFalse(undoManager.canUndo());
    }

    @Test
    void consecutiveUndosGoBackInReverseChronologicalOrder() throws Exception {
        buyAndRecord("AAA", "A", 1, 10);
        buyAndRecord("BBB", "B", 1, 20);
        buyAndRecord("CCC", "C", 1, 30);

        assertEquals(3, engine.getHoldings().size());

        undo();
        assertNull(engine.getHolding("CCC"));
        assertEquals(2, engine.getHoldings().size());

        undo();
        assertNull(engine.getHolding("BBB"));
        assertEquals(1, engine.getHoldings().size());

        undo();
        assertNull(engine.getHolding("AAA"));
        assertTrue(engine.getHoldings().isEmpty());

        assertFalse(undoManager.canUndo());
        assertNull(undoManager.undo());
    }

    @Test
    void undoDoesNotCreateAnotherUndoEntry() throws Exception {
        buyAndRecord("AAPL", "Apple", 10, 100);
        buyAndRecord("MSFT", "Microsoft", 5, 300);
        assertEquals(2, undoManager.getSnapshots().size());

        undo();

        assertEquals(1, undoManager.getSnapshots().size());
        assertEquals(1, engine.getHoldings().size());
        assertEquals("Buy 10.0 AAPL", undoManager.nextActionLabel());
    }

    @Test
    void snapshotsAreNotAffectedByLaterPriceUpdates() throws Exception {
        buyAndRecord("AAPL", "Apple", 10, 100);
        buyAndRecord("MSFT", "Microsoft", 5, 300);

        // The background simulator changes the live holding after the snapshot was taken.
        engine.updatePrice("AAPL", 200);
        assertEquals(200, engine.getHolding("AAPL").getCurrentPrice(), 1e-9);

        undo(); // reverses the MSFT purchase, restoring the snapshot taken before it

        assertEquals(100, engine.getHolding("AAPL").getCurrentPrice(), 1e-9);
    }

    @Test
    void undoHistorySurvivesDatabaseRoundTrip(@TempDir Path dir) throws Exception {
        DatabaseManager db = new DatabaseManager(dir.resolve("test.db").toString());
        db.init();

        buyAndRecord("AAPL", "Apple", 10, 100);
        buyAndRecord("MSFT", "Microsoft", 5, 300);
        buyAndRecord("AAPL", "Apple", 2, 150);
        db.saveAll(engine.getHoldings(), engine.getTransactions(), undoManager.getSnapshots());

        PortfolioEngine reloaded = new PortfolioEngine();
        DatabaseManager reopened = new DatabaseManager(dir.resolve("test.db").toString());
        reopened.init();
        reloaded.loadState(reopened.loadHoldings(), reopened.loadTransactions());

        UndoManager reloadedUndo = new UndoManager();
        reloadedUndo.load(reopened.loadUndoSnapshots());

        assertEquals(3, reloadedUndo.getSnapshots().size());
        assertEquals("Buy 2.0 AAPL", reloadedUndo.nextActionLabel());

        PortfolioSnapshot snapshot = reloadedUndo.undo();
        reloaded.restoreState(snapshot.getHoldings(), snapshot.getTransactions());

        assertEquals(10, reloaded.getHolding("AAPL").getQuantity(), 1e-9);
        assertEquals(5, reloaded.getHolding("MSFT").getQuantity(), 1e-9);
        assertEquals(100, reloaded.getHolding("AAPL").getAvgBuyPrice(), 1e-9);
        assertEquals(2, reloaded.getTransactions().size());
        assertTrue(reloadedUndo.canUndo());
    }
}
