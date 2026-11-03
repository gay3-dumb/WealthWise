# Usage guide

A step-by-step tour of WealthWise. To install and start the app, see [Getting started](../README.md#getting-started).

## Record a trade

1. In the **Record a Trade** panel, enter a symbol (for example `AAPL`), an optional asset name, a quantity and a price per unit. For a new asset the name defaults to the symbol.
2. The **trade preview** shows the projected portfolio profit or loss for a Buy or a Sell before you commit.
3. Click **Buy** or **Sell**. Quantity and price must be positive numbers, and you cannot sell more than you own. A green message confirms a trade and a red one explains what went wrong.

Buying more of an asset you already hold updates its weighted-average buy price. Selling does not change it, and a holding disappears when its quantity reaches zero.

## Undo and Sell All

- **Undo last** reverses the most recent Buy, Sell or Sell All, up to the last 50 actions.
- **Sell All Holdings** closes every position at its current price. A confirmation dialog shows how many holdings are affected and their total value before anything happens.

## Watch the prices

Prices change every 2 seconds. Use **Pause prices** and **Resume prices** to control the simulation. The holdings table, summary cards, charts and history refresh with every update, and profit and loss turn green or red.

## Price alerts

1. Click **Price Alerts** in the toolbar.
2. Choose a holding, enter a target price and pick a condition (rises to or above, or falls to or below).
3. Click **Create Alert**. The alert is checked on every price update. When it triggers you get a notification banner on the dashboard, and the alert stays in the list, marked as triggered, until you remove it.

Alerts are saved and survive restarting the app.

## Concentration warnings

The **Portfolio Concentration** panel lists each asset's share of your portfolio value and highlights any above the threshold (30% by default). Change the threshold with the spinner. The setting applies for the current session.

## Strategy Lab

Click **Strategy Lab** in the toolbar to compare strategies. The [Strategy Lab guide](STRATEGY_LAB.md) explains every setting and result.

## Export and saving

Click **Export CSV**, choose a location, and the app saves the summary, holdings, allocation and transactions as a UTF-8 CSV file (with a byte-order mark, so spreadsheet software reads it correctly).

The app saves automatically after each trade and when the window closes. Your data is in `wealthwise.db`, in the folder you started the app from.
