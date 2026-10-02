package com.handoffly.support;

/**
 * How the customer reached support. A direct call is a plain phone number on the customer's screen and
 * leaves no record here. A MESSAGE is a plans-without-tickets customer's simple support request: support
 * handles it as a ticket, but the customer does not get the ticket workflow. Stored per ticket so another
 * channel can be added later without touching existing rows.
 */
public enum ContactMethod {
    TICKET,
    MESSAGE
}
