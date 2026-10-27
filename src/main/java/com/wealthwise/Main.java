package com.wealthwise;

import com.wealthwise.db.DatabaseManager;
import com.wealthwise.service.PortfolioEngine;
import com.wealthwise.service.PriceAlertService;
import com.wealthwise.ui.DashboardController;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.sql.SQLException;

/** Application entry point: wires the layers together (UI, engine, price simulator, database). */
public class Main extends Application {

    private DashboardController controller;

    @Override
    public void start(Stage stage) throws Exception {
        PortfolioEngine engine = new PortfolioEngine();
        DatabaseManager db = new DatabaseManager("wealthwise.db");
        db.init();
        // Step 1 of the workflow: load the saved portfolio and alerts on launch
        engine.loadState(db.loadHoldings(), db.loadTransactions());

        PriceAlertService alertService = new PriceAlertService();
        try {
            alertService.loadAlerts(db.loadAlerts());
        } catch (SQLException | RuntimeException e) {
            // A corrupt or unavailable alert table must not prevent the portfolio from opening.
            e.printStackTrace();
        }

        FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/wealthwise/ui/dashboard.fxml"));
        Scene scene = new Scene(loader.load(), 1100, 720);
        scene.getStylesheets().add(getClass().getResource("/com/wealthwise/ui/style.css").toExternalForm());

        controller = loader.getController();
        controller.setup(engine, db, alertService);

        stage.setTitle("WealthWise - Portfolio & Wealth Dashboard");
        stage.setScene(scene);
        stage.show();
    }

    @Override
    public void stop() {
        // Step 6 of the workflow: persist data and stop background work when the window closes
        if (controller != null) {
            controller.shutdown();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
