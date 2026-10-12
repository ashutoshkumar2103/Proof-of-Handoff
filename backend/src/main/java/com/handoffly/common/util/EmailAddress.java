package com.handoffly.common.util;

/**
 * What counts as an email address a handoff can be sent to. Bean Validation's {@code @Email} is deliberately loose (it accepts
 * {@code name@ee}, with no dot in the domain), which lets a typo through as a recipient that can never be reached. This is the practical
 * form: a local part of the usual characters (no leading, trailing or doubled dot, at most 64 long), then {@code @}, then a domain of
 * letter-digit-hyphen labels separated by dots, ending in a top-level domain (letters, at least two, or a punycode {@code xn--} one).
 * Addresses with non-ASCII characters are not accepted (they would have to be written in punycode). The clients mirror this to show the
 * problem early; this is what decides.
 */
public final class EmailAddress {

    private static final String ATOM = "[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+";
    private static final String LOCAL = "(?=[^@]{1,64}@)" + ATOM + "(?:\\." + ATOM + ")*";
    // A label has no dot, is at most 63 long, and a hyphen only ever sits between letters or digits: written so there is one way to match it
    // (no nested repeats of the same characters), so a hostile address cannot make the matching slow.
    private static final String LABEL = "(?=[^.]{1,63}\\.)[A-Za-z0-9]+(?:-+[A-Za-z0-9]+)*";
    private static final String TLD = "(?:[A-Za-z]{2,63}|xn--[A-Za-z0-9-]{1,59})";

    /** For {@code @Pattern(regexp = EmailAddress.REGEX, ...)}. */
    public static final String REGEX = "(?=.{1,254}$)" + LOCAL + "@(?:" + LABEL + "\\.)+" + TLD;   // the length cap also bounds how deep the matching can go

    /** The message a rejected address is answered with. */
    public static final String MESSAGE = "Enter a valid email address, for example name@example.com.";

    private EmailAddress() {}
}
