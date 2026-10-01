package com.handoffly.notification;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A reusable plain-text email. An optional first line {@code Subject: …} gives the subject; the rest
 * is the body. Both may contain {@code {placeholders}}, filled in a single pass — a value is never
 * scanned again, so text such as {@code {x}} or {@code $1} inside a handoff title stays as written.
 * A placeholder with no value is left exactly as typed, so a typo shows up in the email rather than
 * silently disappearing.
 */
public final class EmailTemplate {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)}");
    private static final String SUBJECT_PREFIX = "Subject:";

    /** A filled-in email: a single-line subject and the body. */
    public record Rendered(String subject, String body) {}

    private final String subject;
    private final String body;

    private EmailTemplate(String subject, String body) {
        this.subject = subject;
        this.body = body;
    }

    /** @param defaultSubject used when the text has no {@code Subject:} line (may itself hold placeholders) */
    public static EmailTemplate parse(String text, String defaultSubject) {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.regionMatches(true, 0, SUBJECT_PREFIX, 0, SUBJECT_PREFIX.length())) {
            int newline = normalized.indexOf('\n');
            String firstLine = newline < 0 ? normalized : normalized.substring(0, newline);
            String rest = newline < 0 ? "" : normalized.substring(newline + 1);
            return new EmailTemplate(firstLine.substring(SUBJECT_PREFIX.length()).trim(), rest.stripLeading());
        }
        return new EmailTemplate(defaultSubject, normalized);
    }

    public Rendered render(Map<String, String> values) {
        // A subject is one header line: line breaks from a substituted value must never start a new header.
        String renderedSubject = fill(subject, values).replaceAll("[\\r\\n]+", " ").trim();
        return new Rendered(renderedSubject, fill(body, values).stripTrailing() + "\n");
    }

    private static String fill(String template, Map<String, String> values) {
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = values.get(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(value != null ? value : m.group()));
        }
        m.appendTail(out);
        return out.toString();
    }
}
