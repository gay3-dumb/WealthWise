# WealthWise - Multi-threaded Portfolio & Wealth Management Dashboard

Java endsem project. A JavaFX desktop app to manage a simulated investment portfolio.

## Features
- Buy / sell assets with input validation
- Holdings table: quantity, average buy price, current price, value, profit/loss, return %
- Summary cards: total value, invested, profit/loss, return %
- Background price simulator (own thread, updates the UI safely with `Platform.runLater`)
- Live line chart of portfolio value and a pie chart of allocation
- Transaction history
- Data saved in SQLite via JDBC and restored on the next launch

## Architecture (layers)
| Layer | Package | Responsibility |
|---|---|---|
| UI | `com.wealthwise.ui` + `resources/.../ui` | FXML, controller, CSS |
| Business logic | `com.wealthwise.model`, `com.wealthwise.service` | Holding, Transaction, PortfolioEngine |
| Background engine | `com.wealthwise.price` | PriceSimulator (ScheduledExecutorService) |
| Persistence | `com.wealthwise.db` | DatabaseManager (JDBC + SQLite) |

## Calculation rules
- Quantity and price must be > 0
- Buy: new average = (oldQty x oldAvg + qty x price) / (oldQty + qty)
- Sell: cannot exceed quantity owned; average buy price unchanged; holding removed at 0
- Profit/Loss = market value - invested
- Return % = Profit/Loss / invested x 100

## Run
Requirements: JDK 21, Maven.

```
mvn test          # run unit tests
mvn javafx:run    # start the app
```

## Team workflow
1. `git pull` before you start
2. Work on your own branch: `git checkout -b feature/<name>`
3. Commit small and often, then push and open a Pull Request into `main`
