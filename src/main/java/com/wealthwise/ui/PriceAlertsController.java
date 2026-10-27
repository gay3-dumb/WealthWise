package com.wealthwise.ui;

import com.wealthwise.model.Holding;
import com.wealthwise.model.PriceAlert;
import com.wealthwise.service.PortfolioEngine;
import com.wealthwise.service.PriceAlertService;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;

import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;

/** Controller for the dedicated price-alert management dialog. */
public class PriceAlertsController {

    private static final DateTimeFormatter TRIGGER_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @FXML private ComboBox<Holding> assetCombo;
    @FXML private Label currentPriceLabel;
    @FXML private TextField targetField;
    @FXML private ComboBox<PriceAlert.Condition> conditionCombo;
    @FXML private Label messageLabel;
    @FXML private ListView<PriceAlert> alertList;
    @FXML private Button removeButton;

    private PortfolioEngine engine;
    private PriceAlertService alertService;
    private Runnable onAlertsChanged = () -> { };

    public void setup(PortfolioEngine engine, PriceAlertService alertService,
                      Runnable onAlertsChanged) {
        this.engine = engine;
        this.alertService = alertService;
        this.onAlertsChanged = onAlertsChanged;

        conditionCombo.getItems().setAll(PriceAlert.Condition.values());
        conditionCombo.getSelectionModel().select(PriceAlert.Condition.RISES_TO_OR_ABOVE);

        assetCombo.setCellFactory(list -> holdingCell());
        assetCombo.setButtonCell(holdingCell());
        assetCombo.valueProperty().addListener((obs, oldValue, newValue) -> updateCurrentPrice());

        alertList.setCellFactory(list -> alertCell());
        alertList.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldValue, newValue) -> removeButton.setDisable(newValue == null));

        removeButton.setDisable(true);
        refreshAssets();
        refreshAlertList();

        if (assetCombo.getItems().isEmpty()) {
            showMessage("Add a holding before creating a price alert.", true);
        }
    }

    /** Refreshes the current simulated price while the dialog remains open. */
    public void refreshLiveData() {
        updateCurrentPrice();
    }

    /** Rebuilds the alert list, preserving the selected alert where possible. */
    public void refreshAlertList() {
        PriceAlert selected = alertList.getSelectionModel().getSelectedItem();
        int selectedId = selected == null ? -1 : selected.getId();

        List<PriceAlert> alerts = alertService.getAlerts();
        alertList.setItems(FXCollections.observableArrayList(alerts));
        if (selectedId > 0) {
            alerts.stream()
                    .filter(alert -> alert.getId() == selectedId)
                    .findFirst()
                    .ifPresent(alert -> alertList.getSelectionModel().select(alert));
        }
        removeButton.setDisable(alertList.getSelectionModel().getSelectedItem() == null);
    }

    @FXML
    private void onCreateAlert() {
        Holding holding = assetCombo.getValue();
        if (holding == null) {
            showMessage("Select an asset from your portfolio.", true);
            return;
        }
        if (engine.getHolding(holding.getSymbol()) == null) {
            showMessage("That asset is no longer in your portfolio.", true);
            refreshAssets();
            return;
        }

        double targetPrice;
        try {
            targetPrice = parseTargetPrice();
        } catch (IllegalArgumentException e) {
            showMessage(e.getMessage(), true);
            return;
        }

        PriceAlert.Condition condition = conditionCombo.getValue();
        if (condition == null) {
            showMessage("Choose an alert condition.", true);
            return;
        }

        try {
            PriceAlert alert = alertService.createAlert(
                    holding.getSymbol(), holding.getName(), targetPrice, condition);
            targetField.clear();
            refreshAlertList();
            alertList.getSelectionModel().select(alert);
            showMessage(String.format("Alert created for %s at %,.2f.",
                    alert.getSymbol(), alert.getTargetPrice()), false);
            onAlertsChanged.run();
        } catch (IllegalArgumentException e) {
            showMessage(e.getMessage(), true);
        }
    }

    @FXML
    private void onRemoveAlert() {
        PriceAlert selected = alertList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showMessage("Select an alert to remove.", true);
            return;
        }
        if (alertService.removeAlert(selected.getId())) {
            showMessage("Alert removed.", false);
            refreshAlertList();
            onAlertsChanged.run();
        } else {
            showMessage("That alert no longer exists.", true);
            refreshAlertList();
        }
    }

    private void refreshAssets() {
        Holding selected = assetCombo.getValue();
        String selectedSymbol = selected == null ? null : selected.getSymbol();

        List<Holding> holdings = engine.getHoldings().stream()
                .sorted(Comparator.comparing(Holding::getSymbol))
                .toList();
        assetCombo.getItems().setAll(FXCollections.observableArrayList(holdings));

        if (selectedSymbol != null) {
            holdings.stream()
                    .filter(holding -> holding.getSymbol().equals(selectedSymbol))
                    .findFirst()
                    .ifPresentOrElse(
                            holding -> assetCombo.getSelectionModel().select(holding),
                            () -> assetCombo.getSelectionModel().clearSelection());
        }
        if (assetCombo.getValue() == null && !holdings.isEmpty()) {
            assetCombo.getSelectionModel().selectFirst();
        }
        updateCurrentPrice();
    }

    private void updateCurrentPrice() {
        Holding holding = assetCombo.getValue();
        if (holding == null) {
            currentPriceLabel.setText("Current price: —");
            return;
        }
        Holding current = engine.getHolding(holding.getSymbol());
        double price = current == null ? holding.getCurrentPrice() : current.getCurrentPrice();
        currentPriceLabel.setText(String.format("Current price: %,.2f", price));
    }

    private double parseTargetPrice() {
        String text = targetField.getText();
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Target price is required.");
        }
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Target price must be a number.");
        }
    }

    private ListCell<Holding> holdingCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(Holding holding, boolean empty) {
                super.updateItem(holding, empty);
                if (empty || holding == null) {
                    setText(null);
                } else {
                    setText(holding.getSymbol() + " — " + holding.getName());
                }
            }
        };
    }

    private ListCell<PriceAlert> alertCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(PriceAlert alert, boolean empty) {
                super.updateItem(alert, empty);
                getStyleClass().removeAll("alert-active", "alert-triggered");
                if (empty || alert == null) {
                    setText(null);
                    setWrapText(false);
                    return;
                }

                String asset = alert.getName().equals(alert.getSymbol())
                        ? alert.getSymbol()
                        : alert.getSymbol() + " (" + alert.getName() + ")";
                String status;
                if (alert.isActive()) {
                    boolean held = engine.getHolding(alert.getSymbol()) != null;
                    status = held ? "ACTIVE" : "ACTIVE — asset no longer held";
                } else {
                    String actualPrice = alert.getTriggeredPrice() == null
                            ? ""
                            : String.format(" at %,.2f", alert.getTriggeredPrice());
                    String triggerTime = alert.getTriggeredAt() == null
                            ? ""
                            : " on " + alert.getTriggeredAt().format(TRIGGER_TIME_FORMAT);
                    status = "TRIGGERED" + actualPrice + triggerTime;
                }

                setWrapText(true);
                setText(String.format("%s%nTarget %,.2f · %s%n%s",
                        asset, alert.getTargetPrice(), alert.getCondition().getDisplayName(), status));
                getStyleClass().add(alert.isActive() ? "alert-active" : "alert-triggered");
            }
        };
    }

    private void showMessage(String text, boolean error) {
        messageLabel.setText(text);
        messageLabel.getStyleClass().removeAll("error", "success");
        messageLabel.getStyleClass().add(error ? "error" : "success");
    }
}
