package com.wealthwise.ui;

import com.wealthwise.model.Holding;
import com.wealthwise.service.PortfolioEngine;
import com.wealthwise.strategy.BacktestConfig;
import com.wealthwise.strategy.BacktestReport;
import com.wealthwise.strategy.StrategyBacktester;
import com.wealthwise.strategy.StrategyResult;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Presentation layer for the Strategy Lab dialog. It reads the text boxes, hands the work to
 * StrategyBacktester on a background thread, and shows the answer when it is ready.
 * No simulation maths lives here.
 *
 * The thread story: a click on Run starts a JavaFX Task on its own thread. That thread starts
 * the backtester, which spreads the simulations over a pool of worker threads. When everything
 * is done, Task.setOnSucceeded runs back on the JavaFX thread, which is the only thread allowed
 * to touch the screen. The window never freezes while the simulation runs.
 */
public class StrategyLabController {

    private static final long SEED = 42L;   // fixed, so the same settings always give the same answer

    @FXML private ComboBox<Holding> assetCombo;
    @FXML private TextField startPriceField, cashField, daysField, simulationsField;
    @FXML private TextField volatilityField, driftField, buyIntervalField, buyCountField, stopField;
    @FXML private Button runButton;
    @FXML private ProgressIndicator progress;
    @FXML private Label messageLabel, verdictLabel, summaryLabel;
    @FXML private TableView<StrategyResult> resultTable;
    @FXML private TableColumn<StrategyResult, String> colStrategy, colWorst, colMedian, colBest,
            colLoss, colDrawdown, colBeat;

    /** Called by DashboardController after the FXML has loaded. */
    public void setup(PortfolioEngine engine) {
        // sensible starting values, so the dialog works even with an empty portfolio
        startPriceField.setText("100.00");
        cashField.setText("100000");
        daysField.setText("252");
        simulationsField.setText("10000");
        volatilityField.setText("1.5");
        driftField.setText("0.03");
        buyIntervalField.setText("21");
        buyCountField.setText("12");
        stopField.setText("10");

        assetCombo.setCellFactory(list -> holdingCell());
        assetCombo.setButtonCell(holdingCell());
        List<Holding> holdings = engine.getHoldings().stream()
                .sorted(Comparator.comparing(Holding::getSymbol))
                .toList();
        assetCombo.getItems().setAll(holdings);
        assetCombo.valueProperty().addListener((obs, oldValue, holding) -> {
            if (holding != null) {
                startPriceField.setText(String.format(Locale.ROOT, "%.2f", holding.getCurrentPrice()));
            }
        });
        if (!holdings.isEmpty()) {
            assetCombo.getSelectionModel().selectFirst();
        }

        colStrategy.setCellValueFactory(c ->
                new ReadOnlyObjectWrapper<>(c.getValue().strategy().getDisplayName()));
        colWorst.setCellValueFactory(c -> money(c.getValue().worstCase()));
        colMedian.setCellValueFactory(c -> money(c.getValue().median()));
        colBest.setCellValueFactory(c -> money(c.getValue().bestCase()));
        colLoss.setCellValueFactory(c -> percent(c.getValue().probabilityOfLoss()));
        colDrawdown.setCellValueFactory(c -> percent(c.getValue().averageMaxDrawdown()));
        colBeat.setCellValueFactory(c -> Double.isNaN(c.getValue().beatBuyAndHoldRate())
                ? new ReadOnlyObjectWrapper<>("—")
                : percent(c.getValue().beatBuyAndHoldRate()));
    }

    @FXML
    private void onRun() {
        BacktestConfig parsed;
        try {
            parsed = readConfig();
        } catch (IllegalArgumentException e) {
            showMessage(e.getMessage(), true);
            return;
        }
        final BacktestConfig config = parsed;

        Task<BacktestReport> task = new Task<>() {
            @Override
            protected BacktestReport call() throws Exception {
                return new StrategyBacktester().run(config);   // runs OFF the screen thread
            }
        };
        // these two run back on the JavaFX thread, so they may safely change the screen
        task.setOnSucceeded(event -> {
            showReport(task.getValue());
            setRunning(false);
        });
        task.setOnFailed(event -> {
            Throwable error = task.getException();
            showMessage("Simulation failed: " + (error == null ? "unknown error" : error.getMessage()), true);
            setRunning(false);
        });

        setRunning(true);
        showMessage("Running " + String.format("%,d", config.simulations()) + " simulations...", false);
        Thread worker = new Thread(task, "strategy-lab-coordinator");
        worker.setDaemon(true);
        worker.start();
    }

    // ---------------------------------------------------------------- reading the form

    private BacktestConfig readConfig() {
        return new BacktestConfig(
                number(startPriceField, "Start price"),
                number(cashField, "Starting cash"),
                whole(daysField, "Days"),
                number(volatilityField, "Daily volatility") / 100.0,
                number(driftField, "Daily drift") / 100.0,
                whole(buyIntervalField, "Days between purchases"),
                whole(buyCountField, "Number of purchases"),
                number(stopField, "Trailing stop") / 100.0,
                whole(simulationsField, "Number of simulations"),
                SEED);
    }

    private static double number(TextField field, String name) {
        String text = field.getText();
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(name + " is required.");
        }
        try {
            double value = Double.parseDouble(text.trim());
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a number.");
        }
    }

    private static int whole(TextField field, String name) {
        String text = field.getText();
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(name + " is required.");
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a whole number.");
        }
    }

    // ---------------------------------------------------------------- showing the answer

    private void showReport(BacktestReport report) {
        resultTable.setItems(FXCollections.observableArrayList(report.results()));

        StrategyResult highestMedian = report.results().stream()
                .max(Comparator.comparingDouble(StrategyResult::median)).orElseThrow();
        StrategyResult smallestFall = report.results().stream()
                .min(Comparator.comparingDouble(StrategyResult::averageMaxDrawdown)).orElseThrow();
        StrategyResult bestWorstCase = report.results().stream()
                .max(Comparator.comparingDouble(StrategyResult::worstCase)).orElseThrow();

        verdictLabel.setText(String.format(
                "Highest median: %s. Smallest average fall: %s. Best worst case: %s.",
                highestMedian.strategy().getDisplayName(),
                smallestFall.strategy().getDisplayName(),
                bestWorstCase.strategy().getDisplayName()));
        summaryLabel.setText(String.format(
                "%,d simulations shared across %d worker threads, finished in %,d ms.",
                report.simulations(), report.threads(), report.elapsedMillis()));
        showMessage("Done.", false);
    }

    private void setRunning(boolean running) {
        runButton.setDisable(running);
        progress.setVisible(running);
    }

    private void showMessage(String text, boolean error) {
        messageLabel.setText(text);
        messageLabel.getStyleClass().removeAll("error", "success");
        messageLabel.getStyleClass().add(error ? "error" : "success");
    }

    private static ReadOnlyObjectWrapper<String> money(double value) {
        return new ReadOnlyObjectWrapper<>(String.format("%,.0f", value));
    }

    private static ReadOnlyObjectWrapper<String> percent(double fraction) {
        return new ReadOnlyObjectWrapper<>(String.format("%.1f%%", fraction * 100.0));
    }

    private ListCell<Holding> holdingCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(Holding holding, boolean empty) {
                super.updateItem(holding, empty);
                setText(empty || holding == null
                        ? null
                        : holding.getSymbol() + " — " + holding.getName());
            }
        };
    }
}
