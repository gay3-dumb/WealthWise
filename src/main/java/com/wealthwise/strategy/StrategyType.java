package com.wealthwise.strategy;

/** The investing strategies the Strategy Lab can test against simulated price paths. */
public enum StrategyType {

    /** Put all the cash in on day 0 and never touch it. The baseline everything is compared to. */
    BUY_AND_HOLD("Buy and hold"),

    /** Split the cash into equal instalments and buy one every N days (dollar-cost averaging). */
    MONTHLY_BUYING("Monthly buying"),

    /** Buy everything on day 0, sell all if the price falls X% from its peak, then stay in cash. */
    TRAILING_STOP("Trailing stop-loss");

    private final String displayName;

    StrategyType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
