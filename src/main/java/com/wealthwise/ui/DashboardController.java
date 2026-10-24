package com.wealthwise.ui;

import com.wealthwise.db.DatabaseManager;
import com.wealthwise.model.Holding;
import com.wealthwise.model.Transaction;
import com.wealthwise.price.PriceSimulator;
import com.wealthwise.service.PortfolioEngine;
import com.wealthwise.service.PortfolioException;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.PieChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.beans.property.ReadOnlyObjectWrapper;

import java.sql.SQLException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Presentation layer (Layer 1). Handles clicks and displays data. It contains no business
 * rules: all of that is delegated to PortfolioEngine.
 */
public class DashboardController {

    private static final int MAX_CHART_POINTS = 40;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    // summary cards
    @FXML private Label totalValueLabel, investedLabel, profitLossLabel, returnLabel, statusLabel, messageLabel;
    // form
    @FXML private TextField symbolField, nameField, quantityField, priceField;
    @FXML private ToggleButton simToggle;
    // table
    @FXML private TableView<Holding> holdingsTable;
    @FXML private TableColumn<Holding, String> colSymbol, colName;
    @FXML private TableColumn<Holding, Number> colQty, colAvg, colPrice, colValue, colPL, colRet;
    // history + charts
    @FXML private ListView<String> historyList;
    @FXML private LineChart<String, Number> valueChart;
    @FXML private PieChart allocationChart;

    private PortfolioEngine engine;
    private DatabaseManager db;
    private PriceSimulator simulator;
    private final XYChart.Series<String, Number> valueSeries = new XYChart.Series<>();
    // a single background thread for database writes so the UI never waits on disk
    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "db-writer");
        t.setDaemon(true);
        return t;
    });

    /** Called by Main after the FXML has loaded. */
    public void setup(PortfolioEngine engine, DatabaseManager db) {
        this.engine = engine;
        this.db = db;

        setupTable();
        valueChart.getData().add(valueSeries);

        refreshAll();

        // Step 4: background price simulation. It calls refreshAll via Platform.runLater.
        simulator = new PriceSimulator(engine, () -> Platform.runLater(this::refreshAll), 0.02);
        simulator.start(2);
        statusLabel.setText("Prices updating every 2s");
    }

    // ---------------- table setup ----------------

    private void setupTable() {
        colSymbol.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().getSymbol()));
        colName.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().getName()));
        colQty.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().getQuantity()));
        colAvg.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().getAvgBuyPrice()));
        colPrice.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().getCurrentPrice()));
        colValue.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().getMarketValue()));
        colPL.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().getProfitLoss()));
        colRet.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().getReturnPercent()));

        colQty.setCellFactory(col -> numberCell("%.2f", false));
        colAvg.setCellFactory(col -> numberCell("%.2f", false));
        colPrice.setCellFactory(col -> numberCell("%.2f", false));
        colValue.setCellFactory(col -> numberCell("%.2f", false));
        colPL.setCellFactory(col -> numberCell("%+.2f", true));
        colRet.setCellFactory(col -> numberCell("%+.2f%%", true));
    }

    /** A table cell that formats numbers and (optionally) colours them green/red. */
    private static javafx.scene.control.TableCell<Holding, Number> numberCell(String format, boolean colour) {
        return new javafx.scene.control.TableCell<>() {
            @Override
            protected void updateItem(Number item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("positive", "negative");
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(String.format(format, item.doubleValue()));
                    if (colour) {
                        getStyleClass().add(item.doubleValue() >= 0 ? "positive" : "negative");
                    }
                }
            }
        };
    }

    // ---------------- button handlers ----------------

    @FXML
    private void onBuy() {
        executeTrade(true);
    }

    @FXML
    private void onSell() {
        executeTrade(false);
    }

    private void executeTrade(boolean isBuy) {
        try {
            String symbol = symbolField.getText();
            double qty = parseNumber(quantityField.getText(), "Quantity");
            double price = parseNumber(priceField.getText(), "Price");

            if (isBuy) {
                engine.buy(symbol, nameField.getText(), qty, price);
            } else {
                engine.sell(symbol, qty, price);
            }
            showMessage((isBuy ? "Bought " : "Sold ") + qty + " " + symbol.trim().toUpperCase(), false);
            clearForm();
            refreshAll();
            saveAsync();
        } catch (PortfolioException e) {
            showMessage(e.getMessage(), true);
        }
    }

    /** Input validation for text fields: must be a valid number. */
    private double parseNumber(String text, String fieldName) throws PortfolioException {
        if (text == null || text.isBlank()) {
            throw new PortfolioException(fieldName + " is required.");
        }
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            throw new PortfolioException(fieldName + " must be a number.");
        }
    }

    @FXML
    private void onToggleSimulation() {
        if (simToggle.isSelected()) {
            simulator.stop();
            simToggle.setText("Resume prices");
            statusLabel.setText("Prices paused");
        } else {
            simulator.start(2);
            simToggle.setText("Pause prices");
            statusLabel.setText("Prices updating every 2s");
        }
    }

    // ---------------- refreshing the screen (always on the FX thread) ----------------

    private void refreshAll() {
        List<Holding> holdings = engine.getHoldings().stream()
                .sorted(Comparator.comparing(Holding::getSymbol))
                .collect(Collectors.toList());

        holdingsTable.setItems(FXCollections.observableArrayList(holdings));
        holdingsTable.refresh();


        double total = engine.getTotalValue();
        double pl = engine.getTotalProfitLoss();
        totalValueLabel.setText(String.format("%,.2f", total));
        investedLabel.setText(String.format("%,.2f", engine.getTotalInvested()));
        profitLossLabel.setText(String.format("%+,.2f", pl));
        returnLabel.setText(String.format("%+.2f%%", engine.getTotalReturnPercent()));
        colourLabel(profitLossLabel, pl);
        colourLabel(returnLabel, pl);

        // history (newest first) built with a Stream
        List<String> history = engine.getTransactions().stream()
                .sorted(Comparator.comparing(Transaction::getTimestamp).reversed())
                .map(Transaction::toString)
                .collect(Collectors.toList());
        historyList.setItems(FXCollections.observableArrayList(history));

        // allocation pie chart (value per asset)
        allocationChart.setData(FXCollections.observableArrayList(
                holdings.stream()
                        .map(h -> new PieChart.Data(h.getSymbol(), h.getMarketValue()))
                        .collect(Collectors.toList())));

        // live line chart of total value
        valueSeries.getData().add(new XYChart.Data<>(LocalTime.now().format(TIME_FMT), total));
        if (valueSeries.getData().size() > MAX_CHART_POINTS) {
            valueSeries.getData().remove(0);
        }
    }

    private void colourLabel(Label label, double value) {
        label.getStyleClass().removeAll("positive", "negative");
        label.getStyleClass().add(value >= 0 ? "positive" : "negative");
    }

    private void showMessage(String text, boolean error) {
        messageLabel.setText(text);
        messageLabel.getStyleClass().removeAll("error", "success");
        messageLabel.getStyleClass().add(error ? "error" : "success");
    }

    private void clearForm() {
        symbolField.clear();
        nameField.clear();
        quantityField.clear();
        priceField.clear();
    }

    // ---------------- persistence ----------------

    private void saveAsync() {
        // snapshot on the calling thread, write on the db thread
        List<Holding> holdingsSnapshot = engine.getHoldings();
        List<Transaction> txSnapshot = engine.getTransactions();
        dbExecutor.submit(() -> {
            try {
                db.saveAll(holdingsSnapshot, txSnapshot);
            } catch (SQLException e) {
                Platform.runLater(() -> showMessage("Could not save: " + e.getMessage(), true));
            }
        });
    }

    /** Called from Main.stop(): stop threads and save one last time. */
    public void shutdown() {
        if (simulator != null) {
            simulator.stop();
        }
        try {
            db.saveAll(engine.getHoldings(), engine.getTransactions());
        } catch (SQLException e) {
            e.printStackTrace();
        }
        dbExecutor.shutdown();
    }
}
