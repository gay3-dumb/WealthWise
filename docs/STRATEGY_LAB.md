# Strategy Lab guide

Strategy Lab compares investing strategies by running them against thousands of simulated futures for one asset. It answers a question the dashboard cannot: **what range of outcomes should I expect from this approach, not just one?** The short version is in the [README](../README.md#strategy-lab).

## Opening it

Click **Strategy Lab** in the toolbar at the top of the dashboard. If you pick a holding in the first drop-down, its current price fills the start price box. The window also works with an empty portfolio.

## Settings

| Setting | Default | Meaning |
|---|---:|---|
| Start price | 100.00 | Price of the asset on day 0 |
| Starting cash | 100000 | Money available at the start |
| Days to simulate | 252 | Trading days, roughly one year |
| Number of simulations | 10000 | How many random futures to run (up to 200,000) |
| Daily volatility (%) | 1.5 | Typical size of a daily price move |
| Daily drift (%) | 0.03 | Average daily growth |
| Monthly buying: every (days) | 21 | Gap between instalments |
| Monthly buying: purchases | 12 | Number of equal instalments |
| Trailing stop (% fall) | 10 | Fall from the peak that triggers the sale |

Invalid input, such as text in a number box, shows a red message instead of running.

## Strategies

- **Buy and hold** invests all the cash on day 0 and does nothing else. It is the baseline the others are compared against.
- **Monthly buying** splits the cash into equal instalments and buys one every set number of days. Cash that has not been invested yet stays idle.
- **Trailing stop-loss** invests everything on day 0 and tracks the highest price since. If the price falls the chosen percentage below that peak, it sells everything and stays in cash for the rest of the run.

## Reading the results

| Column | Meaning |
|---|---|
| Worst case (5%) | Only 5% of simulations ended with less than this |
| Median | Half of the simulations ended higher and half lower |
| Best case (95%) | Only 5% of simulations ended with more than this |
| Chance of loss | Share of simulations that ended below the starting cash |
| Avg biggest fall | The largest peak-to-trough drop in each simulation, averaged |
| Beats buy and hold | Share of simulations where the strategy finished above buy and hold |

The line under the table names the strategy with the highest median, the smallest average fall and the best worst case. They are often different strategies, which is the point: reducing risk usually costs some upside.

## How it works

1. **Invent a future.** Each simulation is a path of daily prices. Each day the price is multiplied by `exp((drift - volatility^2 / 2) + volatility x Z)`, where `Z` is a random number from a bell curve. This keeps prices above zero.
2. **Run every strategy on the same path** and track the portfolio value each day, including the biggest fall from its peak.
3. **Summarise** the final values of all paths into the percentiles and averages shown in the table.

### Where the threads are

`StrategyBacktester` splits the simulations into chunks and runs them on a fixed thread pool with `invokeAll`. Each simulation uses its own random generator seeded from its index, so results are identical whether one thread or many are used, and each chunk writes only to its own slots in the result arrays, so no locks are needed. In the window, a JavaFX `Task` starts the run on a separate thread and delivers the result back on the JavaFX thread, so the screen stays responsive.

### Repeatable results

The random seed is fixed, so the same settings always give the same table. That makes the feature testable and the demo repeatable.

## Limits

- The futures are random price moves with the volatility and drift you enter. They are not predictions, and real markets can behave differently.
- There are no trading fees, taxes or slippage.
- One asset is simulated at a time, and prices come from a random model, not real history.
- The trailing stop sells once and does not buy back in.
