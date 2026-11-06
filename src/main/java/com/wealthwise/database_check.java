package com.wealthwise;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class database_check{
    public static void main(String[] args) {
        String url = "jdbc:sqlite:wealthwise.db";

        try (Connection conn = DriverManager.getConnection(url)) {
            // 1. Get a list of all user-created tables
            List<String> tables = new ArrayList<>();
            DatabaseMetaData dbMeta = conn.getMetaData();
            try (ResultSet rs = dbMeta.getTables(null, null, "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    tables.add(rs.getString("TABLE_NAME"));
                }
            }

            if (tables.isEmpty()) {
                System.out.println("Connected successfully, but no tables exist in this database!");
                return;
            }

            // 2. Loop through each table and print its contents
            try (Statement stmt = conn.createStatement()) {
                for (String tableName : tables) {
                    System.out.println("\n========================================");
                    System.out.println("TABLE: " + tableName);
                    System.out.println("========================================");

                    try (ResultSet rs = stmt.executeQuery("SELECT * FROM " + tableName)) {
                        ResultSetMetaData rsMeta = rs.getMetaData();
                        int columnCount = rsMeta.getColumnCount();

                        // Print Column Headers
                        for (int i = 1; i <= columnCount; i++) {
                            System.out.print(rsMeta.getColumnName(i) + "\t");
                        }
                        System.out.println("\n----------------------------------------");

                        // Print Row Data
                        boolean hasRows = false;
                        while (rs.next()) {
                            hasRows = true;
                            for (int i = 1; i <= columnCount; i++) {
                                System.out.print(rs.getString(i) + "\t");
                            }
                            System.out.println();
                        }

                        if (!hasRows) {
                            System.out.println("(Table is empty)");
                        }
                    }
                }
            }

        } catch (SQLException e) {
            System.out.println("Database error: " + e.getMessage());
        }
    }
}