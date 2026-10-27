package com.wealthwise.export;

import com.wealthwise.model.Holding;
import com.wealthwise.model.Transaction;
import com.wealthwise.service.PortfolioEngine;

import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Builds one spreadsheet-friendly CSV report from a coherent portfolio snapshot.
 * The report contains a summary, holdings with allocation, an allocation table, and
 * transaction history.
 */
public class PortfolioCsvExporter {

    private static final String EOL = "\r\n";
    private static final DateTimeFormatter DATE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Builds the CSV content without a byte-order mark. */
    public String buildCsv(PortfolioEngine engine) {
        Objects.requireNonNull(engine, "Portfolio engine is required.");

        List<Holding> holdings = engine.getHoldings().stream()
                .sorted(Comparator.comparing(Holding::getSymbol))
                .toList();
        List<Transaction> transactions = engine.getTransactions();

        List<HoldingSnapshot> snapshots = new ArrayList<>(holdings.size());
        double totalInvested = 0.0;
        double totalValue = 0.0;

        for (Holding holding : holdings) {
            double quantity = holding.getQuantity();
            double averageBuyPrice = holding.getAvgBuyPrice();
            double currentPrice = holding.getCurrentPrice();
            double investedAmount = holding.getInvested();
            double marketValue = holding.getMarketValue();

            if (!Double.isFinite(quantity) || !Double.isFinite(averageBuyPrice)
                    || !Double.isFinite(currentPrice) || !Double.isFinite(investedAmount)
                    || !Double.isFinite(marketValue)) {
                throw new IllegalStateException(
                        "Holding " + holding.getSymbol() + " contains invalid numeric data.");
            }

            double profitLoss = marketValue - investedAmount;
            double returnPercent = investedAmount == 0.0 ? 0.0 : profitLoss / investedAmount * 100.0;

            snapshots.add(new HoldingSnapshot(holding.getSymbol(), holding.getName(), quantity,
                    averageBuyPrice, currentPrice, investedAmount, marketValue, profitLoss,
                    returnPercent));
            totalInvested += investedAmount;
            totalValue += marketValue;
        }

        if (!Double.isFinite(totalInvested) || !Double.isFinite(totalValue)) {
            throw new IllegalStateException("Portfolio totals are not finite.");
        }

        double totalProfitLoss = totalValue - totalInvested;
        double totalReturnPercent =
                totalInvested == 0.0 ? 0.0 : totalProfitLoss / totalInvested * 100.0;

        List<AllocationRow> allocationRows = new ArrayList<>(snapshots.size());
        for (HoldingSnapshot snapshot : snapshots) {
            double allocationPercent = totalValue > 0.0
                    ? snapshot.marketValue() / totalValue * 100.0
                    : 0.0;
            allocationRows.add(new AllocationRow(snapshot, allocationPercent));
        }
        allocationRows.sort(Comparator.comparingDouble(AllocationRow::allocationPercent).reversed()
                .thenComparing(row -> row.holding().symbol()));

        StringBuilder csv = new StringBuilder();
        row(csv, "WealthWise Portfolio Export");
        row(csv, "Generated At", LocalDateTime.now().format(DATE_TIME_FORMAT));
        row(csv, "Currency", "INR (₹)");
        blank(csv);

        row(csv, "PERFORMANCE SUMMARY");
        row(csv, "Total Invested", money(totalInvested));
        row(csv, "Total Portfolio Value", money(totalValue));
        row(csv, "Total Profit/Loss", money(totalProfitLoss));
        row(csv, "Total Return %", percent(totalReturnPercent));
        row(csv, "Current Holdings", Integer.toString(snapshots.size()));
        row(csv, "Recorded Transactions", Integer.toString(transactions.size()));
        blank(csv);

        row(csv, "HOLDINGS");
        row(csv, "Symbol", "Name", "Quantity", "Average Buy Price", "Current Price",
                "Invested Amount", "Market Value", "Profit/Loss", "Return %", "Allocation %");
        for (AllocationRow allocationRow : allocationRows.stream()
                .sorted(Comparator.comparing(
                        (AllocationRow row) -> row.holding().symbol()))
                .toList()) {
            HoldingSnapshot snapshot = allocationRow.holding();
            row(csv,
                    snapshot.symbol(),
                    snapshot.name(),
                    quantity(snapshot.quantity()),
                    money(snapshot.averageBuyPrice()),
                    money(snapshot.currentPrice()),
                    money(snapshot.investedAmount()),
                    money(snapshot.marketValue()),
                    money(snapshot.profitLoss()),
                    percent(snapshot.returnPercent()),
                    percent(allocationRow.allocationPercent()));
        }
        blank(csv);

        row(csv, "ALLOCATION");
        row(csv, "Symbol", "Name", "Market Value", "Allocation %");
        for (AllocationRow allocationRow : allocationRows) {
            HoldingSnapshot snapshot = allocationRow.holding();
            row(csv, snapshot.symbol(), snapshot.name(), money(snapshot.marketValue()),
                    percent(allocationRow.allocationPercent()));
        }
        blank(csv);

        row(csv, "TRANSACTION HISTORY");
        row(csv, "Transaction ID", "Symbol", "Type", "Quantity", "Price", "Timestamp", "Total");
        for (Transaction transaction : transactions) {
            row(csv,
                    transaction.getId() > 0 ? Integer.toString(transaction.getId()) : "",
                    transaction.getSymbol(),
                    transaction.getType().name(),
                    quantity(transaction.getQuantity()),
                    money(transaction.getPrice()),
                    transaction.getTimestamp().format(DATE_TIME_FORMAT),
                    money(transaction.getTotal()));
        }

        return csv.toString();
    }

    /** Writes the report as UTF-8 with a BOM so Excel recognises non-ASCII currency text. */
    public void writeCsv(PortfolioEngine engine, Path path) throws IOException {
        Objects.requireNonNull(path, "Export path is required.");
        String csv = buildCsv(engine);
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write('\uFEFF');
            writer.write(csv);
        }
    }

    private static String money(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String percent(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String quantity(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static void blank(StringBuilder csv) {
        csv.append(EOL);
    }

    private static void row(StringBuilder csv, String... fields) {
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) {
                csv.append(',');
            }
            csv.append(escape(fields[i]));
        }
        csv.append(EOL);
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        boolean needsQuotes = value.indexOf(',') >= 0
                || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0;
        if (!needsQuotes) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private record HoldingSnapshot(String symbol, String name, double quantity,
                                   double averageBuyPrice, double currentPrice,
                                   double investedAmount, double marketValue,
                                   double profitLoss, double returnPercent) {
    }

    private record AllocationRow(HoldingSnapshot holding, double allocationPercent) {
    }
}
