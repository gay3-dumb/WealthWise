package com.wealthwise.export;

import com.wealthwise.model.Holding;
import com.wealthwise.model.Transaction;
import com.wealthwise.service.PortfolioEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortfolioCsvExporterTest {

    private final PortfolioCsvExporter exporter = new PortfolioCsvExporter();

    @Test
    void includesHeadersHoldingsAllocationAndSummary() throws Exception {
        PortfolioEngine engine = new PortfolioEngine();
        engine.buy("AAPL", "Apple", 10, 100.0);
        engine.buy("MSFT", "Microsoft", 10, 300.0);

        String csv = exporter.buildCsv(engine);

        assertTrue(csv.startsWith("WealthWise Portfolio Export\r\n"));
        assertTrue(csv.contains("PERFORMANCE SUMMARY"));
        assertTrue(csv.contains("Total Invested,4000.00"));
        assertTrue(csv.contains("Total Portfolio Value,4000.00"));
        assertTrue(csv.contains("Total Profit/Loss,0.00"));
        assertTrue(csv.contains("Total Return %,0.00"));
        assertTrue(csv.contains("Current Holdings,2"));
        assertTrue(csv.contains("Recorded Transactions,2"));

        assertTrue(csv.contains("HOLDINGS\r\nSymbol,Name,Quantity,Average Buy Price,"
                + "Current Price,Invested Amount,Market Value,Profit/Loss,Return %,Allocation %"));
        assertTrue(csv.contains("AAPL,Apple,10,100.00,100.00,1000.00,1000.00,0.00,0.00,25.00"));
        assertTrue(csv.contains("MSFT,Microsoft,10,300.00,300.00,3000.00,3000.00,0.00,0.00,75.00"));

        assertTrue(csv.contains("ALLOCATION\r\nSymbol,Name,Market Value,Allocation %"));
        assertTrue(csv.contains("MSFT,Microsoft,3000.00,75.00"));
        assertTrue(csv.contains("AAPL,Apple,1000.00,25.00"));

        assertTrue(csv.contains("TRANSACTION HISTORY"));
        assertTrue(csv.contains(",AAPL,BUY,10,100.00,"));
        assertTrue(csv.contains(",MSFT,BUY,10,300.00,"));
    }

    @Test
    void allocatesUsingCurrentMarketValuesAndRoundedPrecision() throws Exception {
        PortfolioEngine engine = new PortfolioEngine();
        engine.buy("AAPL", "Apple", 10, 100.0);
        engine.buy("MSFT", "Microsoft", 10, 300.0);
        engine.updatePrice("AAPL", 120.0);

        String csv = exporter.buildCsv(engine);

        // Total value is 4,200; AAPL is 1,200 (28.57%) and MSFT is 3,000 (71.43%).
        assertTrue(csv.contains("AAPL,Apple,10,100.00,120.00,1000.00,1200.00,200.00,20.00,28.57"));
        assertTrue(csv.contains("MSFT,Microsoft,10,300.00,300.00,3000.00,3000.00,0.00,0.00,71.43"));
        assertTrue(csv.contains("Total Profit/Loss,200.00"));
        assertTrue(csv.contains("Total Return %,5.00"));
    }

    @Test
    void exportsStoredTransactionIdsAndTimestamps() {
        PortfolioEngine engine = new PortfolioEngine();
        LocalDateTime timestamp = LocalDateTime.of(2026, 10, 27, 10, 30, 0);
        engine.loadState(
                List.of(new Holding("AAPL", "Apple", 10, 100.0, 110.0)),
                List.of(new Transaction(42, "AAPL", Transaction.Type.BUY, 10, 100.0, timestamp)));

        String csv = exporter.buildCsv(engine);

        assertTrue(csv.contains("42,AAPL,BUY,10,100.00,2026-10-27 10:30:00,1000.00"));
    }

    @Test
    void escapesCommasQuotesAndNewlines() throws Exception {
        PortfolioEngine engine = new PortfolioEngine();
        engine.buy("XYZ", "Alpha, \"Global\"\nHoldings", 1, 100.0);

        String csv = exporter.buildCsv(engine);

        assertTrue(csv.contains("\"Alpha, \"\"Global\"\"\nHoldings\""));
    }

    @Test
    void emptyPortfolioProducesValidZeroSummary() {
        String csv = exporter.buildCsv(new PortfolioEngine());

        assertTrue(csv.contains("Total Invested,0.00"));
        assertTrue(csv.contains("Total Portfolio Value,0.00"));
        assertTrue(csv.contains("Current Holdings,0"));
        assertTrue(csv.contains("Recorded Transactions,0"));
        assertFalse(csv.contains("NaN"));
        assertFalse(csv.contains("Infinity"));
    }

    @Test
    void zeroValuePortfolioDoesNotDivideByZero() {
        PortfolioEngine engine = new PortfolioEngine();
        engine.loadState(
                List.of(new Holding("ZERO", "Zero", 0, 100.0, 100.0)),
                List.of());

        String csv = exporter.buildCsv(engine);

        assertFalse(csv.contains("NaN"));
        assertFalse(csv.contains("Infinity"));
        assertTrue(csv.contains("Current Holdings,1"));
        assertTrue(csv.contains("ZERO,Zero,0,100.00,100.00,0.00,0.00,0.00,0.00,0.00"));
    }

    @Test
    void writesUtf8WithBom(@TempDir Path dir) throws Exception {
        PortfolioEngine engine = new PortfolioEngine();
        engine.buy("AAPL", "Apple", 1, 100.0);
        Path file = dir.resolve("portfolio.csv");

        exporter.writeCsv(engine, file);

        byte[] bytes = Files.readAllBytes(file);
        assertArrayEquals(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF},
                Arrays.copyOf(bytes, 3));
        String text = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(text.contains("Currency,INR (₹)"));
    }

    @Test
    void fileWritingFailurePropagatesIoException(@TempDir Path dir) {
        assertThrows(IOException.class,
                () -> exporter.writeCsv(new PortfolioEngine(), dir));
    }
}
