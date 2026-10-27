package com.wealthwise.db;

import com.wealthwise.model.Holding;
import com.wealthwise.model.PriceAlert;
import com.wealthwise.model.Transaction;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Data access layer (Layer 4): everything that touches SQLite lives here.
 * Uses JDBC with PreparedStatements so user input can never break the SQL.
 */
public class DatabaseManager {

    private final String url;

    /** Use "wealthwise.db" for the real app, or ":memory:" in tests. */
    public DatabaseManager(String dbFile) {
        this.url = "jdbc:sqlite:" + dbFile;
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(url);
    }

    public void init() throws SQLException {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("""
                CREATE TABLE IF NOT EXISTS holdings (
                    symbol TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    quantity REAL NOT NULL,
                    avg_buy_price REAL NOT NULL,
                    current_price REAL NOT NULL
                )""");
            s.execute("""
                CREATE TABLE IF NOT EXISTS transactions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    symbol TEXT NOT NULL,
                    type TEXT NOT NULL,
                    quantity REAL NOT NULL,
                    price REAL NOT NULL,
                    timestamp TEXT NOT NULL
                )""");
            s.execute("""
                CREATE TABLE IF NOT EXISTS price_alerts (
                    id INTEGER PRIMARY KEY,
                    symbol TEXT NOT NULL,
                    name TEXT NOT NULL,
                    target_price REAL NOT NULL,
                    condition_type TEXT NOT NULL,
                    active INTEGER NOT NULL,
                    created_at TEXT NOT NULL,
                    triggered_at TEXT,
                    triggered_price REAL
                )""");
        }
    }

    /** Replace everything stored with the current portfolio state, in one transaction. */
    public void saveAll(List<Holding> holdings, List<Transaction> transactions) throws SQLException {
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try {
                try (Statement s = c.createStatement()) {
                    s.executeUpdate("DELETE FROM holdings");
                    s.executeUpdate("DELETE FROM transactions");
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO holdings(symbol, name, quantity, avg_buy_price, current_price) VALUES (?,?,?,?,?)")) {
                    for (Holding h : holdings) {
                        ps.setString(1, h.getSymbol());
                        ps.setString(2, h.getName());
                        ps.setDouble(3, h.getQuantity());
                        ps.setDouble(4, h.getAvgBuyPrice());
                        ps.setDouble(5, h.getCurrentPrice());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO transactions(symbol, type, quantity, price, timestamp) VALUES (?,?,?,?,?)")) {
                    for (Transaction t : transactions) {
                        ps.setString(1, t.getSymbol());
                        ps.setString(2, t.getType().name());
                        ps.setDouble(3, t.getQuantity());
                        ps.setDouble(4, t.getPrice());
                        ps.setString(5, t.getTimestamp().toString());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        }
    }

    public List<Holding> loadHoldings() throws SQLException {
        List<Holding> list = new ArrayList<>();
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement("SELECT symbol, name, quantity, avg_buy_price, current_price FROM holdings");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(new Holding(rs.getString(1), rs.getString(2), rs.getDouble(3), rs.getDouble(4), rs.getDouble(5)));
            }
        }
        return list;
    }

    public List<Transaction> loadTransactions() throws SQLException {
        List<Transaction> list = new ArrayList<>();
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement("SELECT id, symbol, type, quantity, price, timestamp FROM transactions ORDER BY id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(new Transaction(rs.getInt(1), rs.getString(2), Transaction.Type.valueOf(rs.getString(3)),
                        rs.getDouble(4), rs.getDouble(5), LocalDateTime.parse(rs.getString(6))));
            }
        }
        return list;
    }

    /** Replace the stored alert snapshot, including triggered state, in one transaction. */
    public void saveAlerts(List<PriceAlert> alerts) throws SQLException {
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try {
                try (Statement s = c.createStatement()) {
                    s.executeUpdate("DELETE FROM price_alerts");
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO price_alerts(id, symbol, name, target_price, condition_type, active, "
                                + "created_at, triggered_at, triggered_price) VALUES (?,?,?,?,?,?,?,?,?)")) {
                    for (PriceAlert alert : alerts) {
                        ps.setInt(1, alert.getId());
                        ps.setString(2, alert.getSymbol());
                        ps.setString(3, alert.getName());
                        ps.setDouble(4, alert.getTargetPrice());
                        ps.setString(5, alert.getCondition().name());
                        ps.setInt(6, alert.isActive() ? 1 : 0);
                        ps.setString(7, alert.getCreatedAt().toString());
                        if (alert.getTriggeredAt() == null) {
                            ps.setNull(8, Types.VARCHAR);
                        } else {
                            ps.setString(8, alert.getTriggeredAt().toString());
                        }
                        if (alert.getTriggeredPrice() == null) {
                            ps.setNull(9, Types.REAL);
                        } else {
                            ps.setDouble(9, alert.getTriggeredPrice());
                        }
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        }
    }

    public List<PriceAlert> loadAlerts() throws SQLException {
        List<PriceAlert> list = new ArrayList<>();
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, symbol, name, target_price, condition_type, active, created_at, "
                             + "triggered_at, triggered_price FROM price_alerts ORDER BY id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String triggeredAtText = rs.getString(8);
                LocalDateTime triggeredAt = triggeredAtText == null ? null : LocalDateTime.parse(triggeredAtText);
                double triggeredPriceValue = rs.getDouble(9);
                Double triggeredPrice = rs.wasNull() ? null : triggeredPriceValue;

                list.add(new PriceAlert(
                        rs.getInt(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getDouble(4),
                        PriceAlert.Condition.valueOf(rs.getString(5)),
                        rs.getInt(6) != 0,
                        LocalDateTime.parse(rs.getString(7)),
                        triggeredAt,
                        triggeredPrice));
            }
        }
        return list;
    }
}
