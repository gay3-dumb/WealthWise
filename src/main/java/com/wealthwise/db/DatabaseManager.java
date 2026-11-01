package com.wealthwise.db;

import com.wealthwise.model.Holding;
import com.wealthwise.model.PriceAlert;
import com.wealthwise.model.Transaction;
import com.wealthwise.service.PortfolioSnapshot;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
            s.execute("""
                CREATE TABLE IF NOT EXISTS undo_snapshots (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    label TEXT NOT NULL,
                    created_at TEXT NOT NULL
                )""");
            s.execute("""
                CREATE TABLE IF NOT EXISTS undo_holdings (
                    snapshot_id INTEGER NOT NULL,
                    symbol TEXT NOT NULL,
                    name TEXT NOT NULL,
                    quantity REAL NOT NULL,
                    avg_buy_price REAL NOT NULL,
                    current_price REAL NOT NULL
                )""");
            s.execute("""
                CREATE TABLE IF NOT EXISTS undo_transactions (
                    snapshot_id INTEGER NOT NULL,
                    seq INTEGER NOT NULL,
                    symbol TEXT NOT NULL,
                    type TEXT NOT NULL,
                    quantity REAL NOT NULL,
                    price REAL NOT NULL,
                    timestamp TEXT NOT NULL
                )""");
        }
    }

    /** Replace everything stored with the current portfolio state, in one transaction. */
    public void saveAll(List<Holding> holdings, List<Transaction> transactions) throws SQLException {
        saveAll(holdings, transactions, null);
    }

    /**
     * Replace the portfolio snapshot in one transaction. When {@code undoSnapshots} is not null
     * the undo history is rewritten in the same transaction, so the saved portfolio and the
     * actions that can be undone can never disagree.
     */
    public void saveAll(List<Holding> holdings, List<Transaction> transactions,
                        List<PortfolioSnapshot> undoSnapshots) throws SQLException {
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try {
                replacePortfolio(c, holdings, transactions);
                if (undoSnapshots != null) {
                    replaceUndoHistory(c, undoSnapshots);
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        }
    }

    private void replacePortfolio(Connection c, List<Holding> holdings,
                                  List<Transaction> transactions) throws SQLException {
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
    }

    private void replaceUndoHistory(Connection c, List<PortfolioSnapshot> snapshots) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.executeUpdate("DELETE FROM undo_holdings");
            s.executeUpdate("DELETE FROM undo_transactions");
            s.executeUpdate("DELETE FROM undo_snapshots");
        }
        try (PreparedStatement snapshotInsert = c.prepareStatement(
                "INSERT INTO undo_snapshots(label, created_at) VALUES (?,?)", Statement.RETURN_GENERATED_KEYS);
             PreparedStatement holdingInsert = c.prepareStatement(
                     "INSERT INTO undo_holdings(snapshot_id, symbol, name, quantity, avg_buy_price, current_price)"
                             + " VALUES (?,?,?,?,?,?)");
             PreparedStatement txInsert = c.prepareStatement(
                     "INSERT INTO undo_transactions(snapshot_id, seq, symbol, type, quantity, price, timestamp)"
                             + " VALUES (?,?,?,?,?,?,?)")) {

            for (PortfolioSnapshot snapshot : snapshots) {
                snapshotInsert.setString(1, snapshot.getLabel());
                snapshotInsert.setString(2, snapshot.getCreatedAt().toString());
                snapshotInsert.executeUpdate();
                int snapshotId;
                try (ResultSet keys = snapshotInsert.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new SQLException("Could not store undo history.");
                    }
                    snapshotId = keys.getInt(1);
                }

                for (Holding h : snapshot.getHoldings()) {
                    holdingInsert.setInt(1, snapshotId);
                    holdingInsert.setString(2, h.getSymbol());
                    holdingInsert.setString(3, h.getName());
                    holdingInsert.setDouble(4, h.getQuantity());
                    holdingInsert.setDouble(5, h.getAvgBuyPrice());
                    holdingInsert.setDouble(6, h.getCurrentPrice());
                    holdingInsert.addBatch();
                }
                holdingInsert.executeBatch();

                int seq = 0;
                for (Transaction t : snapshot.getTransactions()) {
                    txInsert.setInt(1, snapshotId);
                    txInsert.setInt(2, seq++);
                    txInsert.setString(3, t.getSymbol());
                    txInsert.setString(4, t.getType().name());
                    txInsert.setDouble(5, t.getQuantity());
                    txInsert.setDouble(6, t.getPrice());
                    txInsert.setString(7, t.getTimestamp().toString());
                    txInsert.addBatch();
                }
                txInsert.executeBatch();
            }
        }
    }

    /** Undo entries in chronological order (oldest first). */
    public List<PortfolioSnapshot> loadUndoSnapshots() throws SQLException {
        Map<Integer, String> labels = new LinkedHashMap<>();
        Map<Integer, LocalDateTime> timestamps = new LinkedHashMap<>();
        Map<Integer, List<Holding>> holdings = new LinkedHashMap<>();
        Map<Integer, List<Transaction>> transactions = new LinkedHashMap<>();

        try (Connection c = connect()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, label, created_at FROM undo_snapshots ORDER BY id");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int id = rs.getInt(1);
                    labels.put(id, rs.getString(2));
                    timestamps.put(id, LocalDateTime.parse(rs.getString(3)));
                    holdings.put(id, new ArrayList<>());
                    transactions.put(id, new ArrayList<>());
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT snapshot_id, symbol, name, quantity, avg_buy_price, current_price"
                            + " FROM undo_holdings ORDER BY snapshot_id, rowid");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    List<Holding> target = holdings.get(rs.getInt(1));
                    if (target != null) {
                        target.add(new Holding(rs.getString(2), rs.getString(3), rs.getDouble(4),
                                rs.getDouble(5), rs.getDouble(6)));
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT snapshot_id, symbol, type, quantity, price, timestamp"
                            + " FROM undo_transactions ORDER BY snapshot_id, seq");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    List<Transaction> target = transactions.get(rs.getInt(1));
                    if (target != null) {
                        target.add(new Transaction(0, rs.getString(2),
                                Transaction.Type.valueOf(rs.getString(3)), rs.getDouble(4),
                                rs.getDouble(5), LocalDateTime.parse(rs.getString(6))));
                    }
                }
            }
        }

        List<PortfolioSnapshot> snapshots = new ArrayList<>(labels.size());
        for (Map.Entry<Integer, String> entry : labels.entrySet()) {
            int id = entry.getKey();
            snapshots.add(new PortfolioSnapshot(entry.getValue(), timestamps.get(id),
                    holdings.get(id), transactions.get(id)));
        }
        return snapshots;
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
