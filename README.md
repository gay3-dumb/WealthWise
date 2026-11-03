<h1 align="center">WealthWise</h1>

<p align="center">
  <strong>A multi-threaded JavaFX dashboard for tracking, stress-testing and understanding a simulated investment portfolio.</strong><br>
  Live prices. Risk warnings. Strategy backtesting. Everything saved locally.
</p>

<p align="center">
  <code>Java 21</code> &nbsp; <code>JavaFX 21</code> &nbsp; <code>SQLite</code> &nbsp; <code>JDBC</code> &nbsp; <code>Maven</code> &nbsp; <code>JUnit 5</code>
</p>

<p align="center">
  <img src="screenshots/portfolio-with-assets.png" alt="WealthWise dashboard with summary cards, holdings table, value chart and allocation pie chart" width="100%"><br>
  <sub>The live dashboard: summary cards, holdings with allocation and P/L, transaction history, a value chart and an allocation pie chart.</sub>
</p>

WealthWise is a desktop application for learning how an investment portfolio behaves. Record buys and sells, watch simulated prices move in the background, get warned when too much of your money sits in one asset, set price alerts, and test investing strategies against thousands of possible futures before risking anything. Prices are simulated, never real market data.

Built as a Semester 3 end-semester project for **Advanced Java Programming**. The central Java concept is **multithreading**: a background price engine, a worker thread pool for simulations, and a safe hand-off from background threads to the JavaFX screen thread.
