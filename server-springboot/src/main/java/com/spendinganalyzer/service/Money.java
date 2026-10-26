package com.spendinganalyzer.service;

/** Arithmetic on amounts that every service needs the same way. */
public final class Money {

    private Money() {}

    /**
     * To the cent, half up -- how every total and projection here is reported. Thirteen services
     * used to carry their own identical copy of this.
     */
    public static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
