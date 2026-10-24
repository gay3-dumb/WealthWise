package com.wealthwise.ui;

import com.wealthwise.db.DatabaseManager;
import com.wealthwise.model.Holding;
import com.wealthwise.model.Transaction;
import com.wealthwise.price.PriceSimulator;
import com.wealthwise.service.ConcentrationAnalyzer;
import com.wealthwise.service.PortfolioEngine;
import com.wealthwise.service.PortfolioException;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.PieChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.beans.property.ReadOnlyObjectWrapper;

import java.sql.SQLException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import java.util.Optional;

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
    @FXML private TableColumn<Holding, Number> colQty, colAvg, colPrice, colValue, colAlloc, colPL, colRet;
    // concentration
    @FXML private Spinner<Integer> thresholdSpinner;
    @FXML private Label concentrationStatusLabel;
    @FXML private ListView<ConcentrationAnalyzer.ConcentrationWarning> warningList;
    // history + charts
    @FXML private ListView<String> historyList;
    @FXML private LineChart<String, Number> valueChart;
    @FXML private PieChart allocationChart;

    private PortfolioEngine engine;
    private DatabaseManager db;
    private PriceSimulator simulator;
    private ConcentrationAnalyzer analyzer = new ConcentrationAnalyzer();
    private Map<String, ConcentrationAnalyzer.Allocation> allocationsBySymbol = Map.of();
    private int thresholdValue = (int) ConcentrationAnalyzer.DEFAULT_THRESHOLD_PERCENT;
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
        setupConcentrationPanel();
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
        colAlloc.setCellValueFactory(c -> {
            ConcentrationAnalyzer.Allocation allocation = allocationsBySymbol.get(c.getValue().getSymbol());
            return new ReadOnlyObjectWrapper<>(allocation == null ? null : allocation.allocationPercent());
        });
        colPL.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().getProfitLoss()));
        colRet.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().getReturnPercent()));

        colQty.setCellFactory(col -> numberCell("%.2f", false));
        colAvg.setCellFactory(col -> numberCell("%.2f", false));
        colPrice.setCellFactory(col -> numberCell("%.2f", false));
        colValue.setCellFactory(col -> numberCell("%.2f", false));
        colAlloc.setCellFactory(col -> allocationCell());
        colPL.setCellFactory(col -> numberCell("%+.2f", true));
        colRet.setCellFactory(col -> numberCell("%+.2f%%", true));
    }

    /** Allocation is looked up from the latest analyzer result; "—" means it is unavailable. */
    private static javafx.scene.control.TableCell<Holding, Number> allocationCell() {
        return new javafx.scene.control.TableCell<>() {
            @Override
            protected void updateItem(Number item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) {
                    setText(null);
                } else if (item == null) {
                    setText("—");
                } else {
                    setText(String.format("%.1f%%", item.doubleValue()));
                }
            }
        };
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

    // ---------------- concentration panel setup ----------------

    private void setupConcentrationPanel() {
        thresholdSpinner.setValueFactory(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 100, thresholdValue));
        thresholdSpinner.setEditable(true);
        thresholdSpinner.setTooltip(new Tooltip(
                "Assets whose share of portfolio value exceeds this percentage trigger a warning."));

        // Only digits, so the editor can never contain a non-numeric value.
        thresholdSpinner.getEditor().setTextFormatter(new TextFormatter<String>(change ->
                !change.isContentChange() || change.getControlNewText().matches("\\d{1,3}")
                        ? change : null));

        // Commit typed text on Enter and when the editor loses focus.
        thresholdSpinner.getEditor().addEventHandler(ActionEvent.ACTION,
                event -> thresholdSpinner.increment(0));
        thresholdSpinner.getEditor().focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (!isFocused) {
                thresholdSpinner.increment(0);
            }
        });

        thresholdSpinner.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (newValue == null) {
                return;
            }
            // The value factory clamps too, but clamp here so the analyzer never sees an
            // intermediate out-of-range value.
            int selected = Math.max(1, Math.min(100, newValue));
            if (selected != newValue) {
                thresholdSpinner.getValueFactory().setValue(selected);
            }
            if (selected == thresholdValue) {
                return;
            }
            thresholdValue = selected;
            analyzer = new ConcentrationAnalyzer(selected);
            // Concentration settings do not change portfolio value history.
            refreshAll(false);
        });

        warningList.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(ConcentrationAnalyzer.ConcentrationWarning warning,
                                      boolean empty) {
                super.updateItem(warning, empty);
                if (empty || warning == null) {
                    setText(null);
                    setTooltip(null);
                } else {
                    setText(String.format("%s  %.1f%% (limit %.1f%%)", warning.symbol(),
                            warning.allocationPercent(), warning.thresholdPercent()));
                    setTooltip(new Tooltip(warning.message()));
                }
            }
        });
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

    @FXML
    private void onSellAll() {
        List<Holding> current = engine.getHoldings();
        if (current.isEmpty()) {
            showMessage("You have no holdings to sell.", true);
            return;
        }

        Alert alert = new Alert(Alert.AlertType.WARNING,
                String.format("This will sell ALL %d holdings at their current prices (total value %,.2f). This cannot be undone.",
                        current.size(), engine.getTotalValue()),
                ButtonType.OK, ButtonType.CANCEL);
        alert.setTitle("Sell all holdings");
        alert.setHeaderText("Are you sure?");

        Optional<ButtonType> result = alert.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            try {
                for (Holding h : current) {
                    engine.sell(h.getSymbol(), h.getQuantity(), h.getCurrentPrice());
                }
                showMessage("Sold all holdings.", false);
            } catch (PortfolioException e) {
                showMessage(e.getMessage(), true);
            }
            refreshAll();
            saveAsync();
        }
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
        refreshAll(true);
    }

    private void refreshAll(boolean recordChartPoint) {
        List<Holding> holdings = engine.getHoldings().stream()
                .sorted(Comparator.comparing(Holding::getSymbol))
                .collect(Collectors.toList());

        ConcentrationAnalyzer.ConcentrationResult concentration = analyzer.analyze(holdings);
        allocationsBySymbol = concentration.allocations().stream()
                .collect(Collectors.toUnmodifiableMap(
                        ConcentrationAnalyzer.Allocation::symbol,
                        allocation -> allocation));

        holdingsTable.setItems(FXCollections.observableArrayList(holdings));
        holdingsTable.refresh();

        updateConcentrationPanel(concentration, holdings.isEmpty());

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
        if (recordChartPoint) {
            valueSeries.getData().add(new XYChart.Data<>(LocalTime.now().format(TIME_FMT), total));
            if (valueSeries.getData().size() > MAX_CHART_POINTS) {
                valueSeries.getData().remove(0);
            }
        }
    }

    private void updateConcentrationPanel(ConcentrationAnalyzer.ConcentrationResult result,
                                          boolean portfolioEmpty) {
        concentrationStatusLabel.getStyleClass().removeAll(
                "concentration-ok", "concentration-warning", "concentration-neutral");

        if (!result.hasValidTotal()) {
            concentrationStatusLabel.setText(portfolioEmpty
                    ? "No holdings to analyze yet."
                    : "Allocation unavailable for the current holdings.");
            concentrationStatusLabel.getStyleClass().add("concentration-neutral");
            warningList.getItems().clear();
            setWarningsVisible(false);
            return;
        }

        List<ConcentrationAnalyzer.ConcentrationWarning> warnings = result.warnings();
        if (warnings.isEmpty()) {
            concentrationStatusLabel.setText(String.format(
                    "No assets exceed the %.1f%% threshold.", result.thresholdPercent()));
            concentrationStatusLabel.getStyleClass().add("concentration-ok");
            warningList.getItems().clear();
            setWarningsVisible(false);
            return;
        }

        String verb = warnings.size() == 1 ? "asset exceeds" : "assets exceed";
        concentrationStatusLabel.setText(String.format("%d %s the %.1f%% threshold.",
                warnings.size(), verb, result.thresholdPercent()));
        concentrationStatusLabel.getStyleClass().add("concentration-warning");
        warningList.setItems(FXCollections.observableArrayList(warnings));
        setWarningsVisible(true);
    }

    private void setWarningsVisible(boolean visible) {
        warningList.setVisible(visible);
        warningList.setManaged(visible);
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
