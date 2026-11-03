# Architecture notes

This page explains how WealthWise is organised and how its threads cooperate. The short version is in the [README](../README.md#how-it-works).

## Layers

| Layer | Package | Uses code from |
|---|---|---|
| Window | `com.wealthwise.ui` | All the other packages |
| Model | `com.wealthwise.model` | Nothing else in the project |
| Services | `com.wealthwise.service` | `model` |
| Price engine | `com.wealthwise.price` | `model`, `service` |
| Strategy Lab | `com.wealthwise.strategy` | Nothing else in the project |
| Export | `com.wealthwise.export` | `model`, `service` |
| Persistence | `com.wealthwise.db` | `model`, `service` (it stores the undo snapshots) |

The rules of the portfolio live in `PortfolioEngine` and the other services. The window code reads input, calls the services, and displays the answer. SQL appears only in `DatabaseManager`. The Strategy Lab engine has no JavaFX or database code, so it can be tested on its own.

## What happens when you click Buy

1. `DashboardController` reads the symbol, quantity and price from the text fields and checks they are valid numbers.
2. It calls `PortfolioEngine.buy(...)`. The engine checks quantity and price are positive, then creates the holding or updates it with a weighted-average buy price: `(oldQty x oldAvg + qty x price) / (oldQty + qty)`.
3. The portfolio state from before the trade is recorded in the `UndoManager`, so **Undo last** can restore it.
4. The controller refreshes the table, summary cards, charts and history on the JavaFX thread.
5. A snapshot of the portfolio is handed to the single-thread `db-writer` executor, which saves it to SQLite without blocking the screen.

Errors, such as selling more than you own, are reported as a `PortfolioException` and shown as a red message under the form.

## Threading model

| Thread | Started by | Responsibility |
|---|---|---|
| JavaFX Application Thread | JavaFX | All screen updates |
| `price-simulator` | `PriceSimulator` (`ScheduledExecutorService`, daemon) | Moves each price by a small random percentage every 2 seconds, checks price alerts, then requests a refresh |
| `db-writer` | `DashboardController` (single-thread executor, daemon) | Writes the portfolio to SQLite |
| `strategy-lab-coordinator` | `StrategyLabController` (JavaFX `Task`) | Starts a Strategy Lab run without blocking the screen |
| `strategy-lab-worker` pool | `StrategyBacktester` (fixed thread pool, daemon) | Runs simulation chunks in parallel |

Three rules keep this safe:

- **Only the JavaFX thread touches the screen.** Background threads call `Platform.runLater(...)`, or finish a `Task`, which delivers its result on the JavaFX thread.
- **Shared data is protected.** The portfolio uses thread-safe collections and `synchronized` methods, because the price thread and the screen thread both use it. Price alerts are `synchronized` so each one triggers exactly once.
- **Worker threads do not fight over results.** In Strategy Lab, every chunk writes only to its own slots in the result arrays, and the coordinator reads them after `invokeAll` has waited for every chunk.

All background threads are daemon threads, so they stop automatically when the window is closed.

## Persistence

`DatabaseManager` uses JDBC with `PreparedStatement`, so typed text can never alter the SQL. Saving runs inside one database transaction: it either completes or rolls back. The database file `wealthwise.db` is created on first launch and is excluded from version control.

| Table | Contents |
|---|---|
| `holdings` | Open positions: symbol, name, quantity, average buy price, current price |
| `transactions` | Every buy and sell with its time |
| `price_alerts` | Alert targets, conditions and trigger details |
| `undo_snapshots`, `undo_holdings`, `undo_transactions` | The undo history |

## Design decisions

- **Unrealised P/L only.** The dashboard reports profit and loss on positions you still hold. Trade preview uses exactly the same formula, so the number shown before a trade matches the number shown after it.
- **Weighted-average buy price.** A sell never changes the average buy price. A holding is removed when its quantity reaches zero.
- **Concentration is a measure, not a verdict.** `ConcentrationAnalyzer` reports each asset's share of total value and flags those above the threshold. It does not model volatility or judge whether an investment is safe.
- **Fixed random seed in Strategy Lab.** The same settings always give the same results, which makes the feature testable and the demo repeatable.
