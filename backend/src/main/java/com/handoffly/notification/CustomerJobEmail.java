package com.handoffly.notification;

import java.util.List;

/**
 * What one customer-job email says, as plain facts: a heading that names the job, a sentence of introduction, one
 * line per handoff or figure, and a closing note. Defined here so the notification module does not depend on the
 * job module; the one email per run is built from it.
 */
public record CustomerJobEmail(String heading, String intro, List<String> lines, String footnote) {}
