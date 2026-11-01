package com.wealthwise.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * In-memory undo stack for portfolio-changing actions. Each entry is the portfolio state from
 * immediately before one Buy, Sell, or Sell All; undoing restores that state rather than
 * editing transaction rows. Oldest entries are dropped once the stack is full.
 */
public class UndoManager {

    public static final int MAX_ENTRIES = 50;

    private final Deque<PortfolioSnapshot> stack = new ArrayDeque<>();

    /** Record the state that the next action will replace. */
    public synchronized void record(PortfolioSnapshot snapshot) {
        stack.push(snapshot);
        while (stack.size() > MAX_ENTRIES) {
            stack.removeLast();
        }
    }

    public synchronized boolean canUndo() {
        return !stack.isEmpty();
    }

    /** Description of the action that the next undo would reverse. */
    public synchronized String nextActionLabel() {
        PortfolioSnapshot next = stack.peek();
        return next == null ? null : next.getLabel();
    }

    /** Remove and return the most recent snapshot, or null when nothing can be undone. */
    public synchronized PortfolioSnapshot undo() {
        return stack.poll();
    }

    /** Entries in chronological order, oldest first, as stored in the database. */
    public synchronized List<PortfolioSnapshot> getSnapshots() {
        List<PortfolioSnapshot> ordered = new ArrayList<>(stack);
        Collections.reverse(ordered);
        return ordered;
    }

    /** Replace the stack with previously saved entries (oldest first). */
    public synchronized void load(List<PortfolioSnapshot> snapshots) {
        stack.clear();
        for (PortfolioSnapshot snapshot : snapshots) {
            stack.push(snapshot);
        }
        while (stack.size() > MAX_ENTRIES) {
            stack.removeLast();
        }
    }
}
