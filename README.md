# WealthWise - Portfolio & Wealth Dashboard

WealthWise is a JavaFX desktop application for tracking a simulated investment portfolio. It lets you buy and sell assets, watch prices change in real time, review allocation and concentration warnings, and keep your data in a local SQLite database.

This project was built as a Semester 3 end-semester project for the course Advanced Java Programming.

## Features

- Buy and sell assets with input validation.
- Weighted-average buy price and protection against selling more than you own.
- Holdings table with quantity, average buy price, current price, value, allocation, profit/loss, and return.
- Summary cards for total value, invested amount, profit/loss, and return.
- Background price simulator that updates held assets every 2 seconds, with pause and resume controls.
- Portfolio concentration panel that warns when an asset exceeds a configurable threshold (default 30%).
- Live portfolio value line chart and allocation pie chart.
- Transaction history and a Sell All Holdings action with a confirmation dialog.
- Automatic saving to SQLite and loading on the next launch.

Prices are simulated locally. They are not real market prices.

## Tech Stack

- Java 21
- JavaFX 21.0.5 (Controls and FXML)
- FXML and CSS for the interface
- SQLite via sqlite-jdbc 3.46.1.3
- Maven with javafx-maven-plugin 0.0.8
- JUnit 5 (Jupiter 5.10.2) for tests

The code is split into layers: `ui` (controller and FXML), `service` and `model` (portfolio rules and concentration analysis), `price` (background simulator), and `db` (SQLite access).

## Getting Started

### Prerequisites

- JDK 21
- Maven 3.9 or newer
- No separate database server is needed

JavaFX and SQLite dependencies are downloaded by Maven.

### Installation

Clone the repository and build the project:

```bash
git clone https://github.com/gay3-dumb/WealthWise.git
cd WealthWise
mvn clean install
```

### Configuration

No configuration is required. On first launch the application creates `wealthwise.db` in the directory you run it from. The database stores holdings and transactions, and is loaded automatically the next time you start the app.

## Usage

Start the application from the project directory:

```bash
mvn javafx:run
```

Then:

1. Enter a symbol, an optional asset name, quantity, and price. The name defaults to the symbol for a new asset.
2. Click Buy to add the position or Sell to reduce it. Quantity and price must be greater than zero, and you cannot sell more than you own.
3. Use Pause prices and Resume prices to control the simulated price updates.
4. Watch the holdings table, summary cards, charts, and transaction history update.
5. Set the concentration threshold in the Portfolio Concentration panel. Any asset above the threshold is listed; the setting applies for the current session.
6. Use Sell All Holdings to close every position at its current price. A confirmation dialog appears before anything is sold.

The app saves the portfolio after trades and when the window closes.

## Screenshots

### Opening screen

![Opening screen](screenshots/opening-screen.png)

### Portfolio with assets

![Portfolio with assets](screenshots/portfolio-with-assets.png)

### Sell All confirmation

![Sell All confirmation](screenshots/sell-all-confirmation.png)

## Contributing

1. Create a branch for your change.
2. Make the change and keep it focused.
3. Run the tests:

   ```bash
   mvn test
   ```

4. Commit, push, and open a pull request into `main`.
