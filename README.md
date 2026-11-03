<h1 align="center">WealthWise</h1>

<p align="center">
  <strong>A multi-threaded JavaFX dashboard for tracking, stress-testing and understanding a simulated investment portfolio.</strong><br>
  Live prices. Risk warnings. Strategy backtesting. Everything saved locally.
</p>

<p align="center">
  <code>Java 21</code> &nbsp; <code>JavaFX 21</code> &nbsp; <code>SQLite</code> &nbsp; <code>JDBC</code> &nbsp; <code>Maven</code> &nbsp; <code>JUnit 5</code>
</p>

<p align="center">
  <a href="#features">Features</a>
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
