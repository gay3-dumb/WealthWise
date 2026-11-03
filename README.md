<h1 align="center">WealthWise</h1>

<p align="center">
  <strong>A multi-threaded JavaFX dashboard for tracking, stress-testing and understanding a simulated investment portfolio.</strong><br>
  Live prices. Risk warnings. Strategy backtesting. Everything saved locally.
</p>

<p align="center">
  <code>Java 21</code> &nbsp; <code>JavaFX 21</code> &nbsp; <code>SQLite</code> &nbsp; <code>JDBC</code> &nbsp; <code>Maven</code> &nbsp; <code>JUnit 5</code>
</p>

<p align="center">
  <a href="#features">Features</a> &nbsp; / &nbsp;
  <a href="#how-it-works">How it works</a>
</p>

<p align="center">
  <img src="screenshots/portfolio-with-assets.png" alt="WealthWise dashboard with summary cards, holdings table, value chart and allocation pie chart" width="100%"><br>
  <sub>The live dashboard: summary cards, holdings with allocation and P/L, transaction history, a value chart and an allocation pie chart.</sub>
</p>

WealthWise is a desktop application for learning how an investment portfolio behaves. Record buys and sells, watch simulated prices move in the background, get warned when too much of your money sits in one asset, set price alerts, and test investing strategies against thousands of possible futures before risking anything. Prices are simulated, never real market data.

Built as a Semester 3 end-semester project for **Advanced Java Programming**. The central Java concept is **multithreading**: a background price engine, a worker thread pool for simulations, and a safe hand-off from background threads to the JavaFX screen thread.

<table>
  <tr>
    <th width="50%">Price alerts</th>
    <th width="50%">Sell All confirmation</th>
  </tr>
  <tr>
    <td valign="top"><img src="screenshots/price-alert-dialog.png" alt="Price alert dialog with asset, target price and condition fields" width="100%"></td>
    <td valign="top"><img src="screenshots/sell-all-confirmation.png" alt="Warning dialog asking the user to confirm selling every holding" width="100%"></td>
  </tr>
  <tr>
    <td valign="top">Set an upward or downward target on any holding. Each alert triggers once and stays listed until removed.</td>
    <td valign="top">Closing every position shows how many holdings are affected and their total value, and needs a confirmation.</td>
  </tr>
</table>

<details>
<summary><strong>Empty start screen</strong></summary>

![WealthWise opening screen with an empty portfolio](screenshots/opening-screen.png)

</details>

## Features

- **Trade with confidence.** Buy and sell with input validation, a weighted-average buy price, and protection against selling more than you own. A **trade preview** shows the projected portfolio profit or loss before you commit, **Undo last** reverses up to 50 actions, and **Sell All** asks for confirmation first.
- **Watch it move.** A background price simulator updates every 2 seconds (pause and resume at any time). The holdings table shows quantity, prices, value, allocation, P/L and return, next to a live value chart and an allocation pie chart.
- **Stay informed.** Price alerts notify you when a holding crosses a target. The concentration panel warns when any asset exceeds a threshold of your portfolio (30% by default, adjustable).
- **Test before you trust.** Strategy Lab runs thousands of simulated futures in parallel to compare investing strategies.
- **Keep your data.** Everything is saved automatically to a local SQLite database, and **Export CSV** writes the summary, holdings, allocation and transactions for spreadsheet software.

## How it works

```mermaid
flowchart LR
    U["User"] --> UI["JavaFX dashboard<br/>FXML and controllers"]
    UI --> E["PortfolioEngine<br/>trades, P/L, rules"]
    UI --> X["Alerts, concentration,<br/>undo, trade preview"]
    UI --> S["Strategy Lab<br/>StrategyBacktester"]
    P["PriceSimulator<br/>background thread"] -->|"new prices"| E
    P -->|"Platform.runLater"| UI
    S --> W["Worker thread pool"]
    UI --> Q["Database writer thread"]
    Q --> D[("SQLite via JDBC")]
```

The code is split into layers. Each package has one job, and the lower layers know nothing about the screen.

| Layer | Package | Responsibility |
|---|---|---|
| Window | `ui`, FXML, CSS | Buttons, tables, charts and dialogs. No business rules. |
| Model | `model` | `Holding`, `Transaction`, `PriceAlert` |
| Services | `service` | `PortfolioEngine`, `ConcentrationAnalyzer`, `PriceAlertService`, `TradePreview`, `UndoManager` |
| Price engine | `price` | `PriceSimulator`, a background price feed |
| Strategy Lab | `strategy` | `StrategyBacktester` and its configuration and result types |
| Export | `export` | `PortfolioCsvExporter` |
| Persistence | `db` | `DatabaseManager`, all JDBC and SQL |

### Threads

| Thread | What it does | Why it is separate |
|---|---|---|
| JavaFX Application Thread | Draws and updates the screen | The only thread allowed to touch UI controls |
| `price-simulator` | Moves prices every 2 seconds, then asks the screen to refresh | Prices keep changing without freezing the window |
| `db-writer` | Saves the portfolio to SQLite | Disk access never blocks the screen |
| `strategy-lab-worker` pool | Runs the simulated futures in parallel | Thousands of simulations finish quickly and the window stays responsive |

Background threads never touch the screen directly. They hand results over with `Platform.runLater(...)`. More detail is in the [architecture notes](docs/ARCHITECTURE.md).

### Project structure

```text
WealthWise/
├── pom.xml
├── screenshots/                     images used in this README
├── docs/                            architecture, usage and Strategy Lab guides
└── src/
    ├── main/java/com/wealthwise/
    │   ├── Main.java                application entry point
    │   ├── ui/                      controllers for the dashboard, alerts and Strategy Lab
    │   ├── model/                   Holding, Transaction, PriceAlert
    │   ├── service/                 portfolio rules, alerts, concentration, undo, trade preview
    │   ├── price/                   background price simulator
    │   ├── strategy/                Strategy Lab engine
    │   ├── export/                  CSV export
    │   └── db/                      SQLite access through JDBC
    ├── main/resources/com/wealthwise/ui/   FXML layouts and style.css
    └── test/java/com/wealthwise/    JUnit 5 tests that mirror the packages above
```
