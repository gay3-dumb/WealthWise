package com.wealthwise.service;

/** Thrown when a buy/sell request breaks a rule (bad input, selling more than owned, ...). */
public class PortfolioException extends Exception {
    public PortfolioException(String message) {
        super(message);
    }
}
