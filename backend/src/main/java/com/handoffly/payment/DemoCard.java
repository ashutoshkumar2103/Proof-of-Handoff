package com.handoffly.payment;

import java.util.Arrays;
import java.util.Optional;

/**
 * The only cards the demo provider knows — well-known public test numbers that work nowhere in the real world.
 * Any other number (in particular a real card) is refused and never kept: the demo provider takes no real card
 * data. Each test card has a fixed outcome, so both the success and the failure path can be tried.
 */
public enum DemoCard {
    APPROVED("4242424242424242"),
    DECLINED("4000000000000002");

    private final String number;

    DemoCard(String number) {
        this.number = number;
    }

    /** The test card with this number (spaces and dashes ignored), if it is one. */
    public static Optional<DemoCard> find(String cardNumber) {
        String digits = cardNumber == null ? "" : cardNumber.replaceAll("[\\s-]", "");
        return Arrays.stream(values()).filter(card -> card.number.equals(digits)).findFirst();
    }
}
