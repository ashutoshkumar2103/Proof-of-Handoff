package com.handoffly.handoff;

import com.handoffly.ai.AiUnavailableException;
import com.handoffly.ai.AiUnavailableException.Reason;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What the customer's message asks for, as the AI Handoff Creator understood it: only what the message SAYS, in fields, with nothing looked up. It is
 * the one thing the AI produces, and it is untrusted — {@link #fromModelJson} accepts only an answer of exactly this shape, and {@link #cleaned}
 * bounds every text and number again, whoever it came from (the page sends it back when the customer answers a question, so it is checked like
 * the model's own answer). Which recipient, which items and which handoff it means is worked out afterwards from the customer's own records.
 *
 * @param supported            false when the message is not a request to prepare a handoff
 * @param recipientName        who it is for, as written; empty when not named
 * @param recipientEmail       only an address written in the message; empty otherwise
 * @param recipientPhone       only a number written in the message; empty otherwise
 * @param sourceReference      the handoff or quotation the message points at, as written ("12", "AK-12"); empty when none
 * @param copyItemsFromSource  the message asks for that handoff's items ("the same items", "duplicate")
 * @param recipientFromSource  the message asks for that handoff's recipient ("with the same person", "who received quotation 15")
 * @param items                items the message lists itself
 * @param changes              quantity changes to the items copied from the source ("change the marker quantity to 10")
 * @param dueDate              YYYY-MM-DD, or empty when no date is named
 * @param saveDraft            the message asks to save the draft too ("...and save the draft", "keep it for later"): the page then saves it as soon as
 *                             nothing is unclear. It is still only a draft; saving it submits nothing and sends nothing.
 */
public record HandoffIntent(
        boolean supported,
        String recipientName,
        String recipientEmail,
        String recipientPhone,
        String sourceReference,
        boolean copyItemsFromSource,
        boolean recipientFromSource,
        String title,
        String purpose,
        String category,
        String dueDate,
        List<Item> items,
        List<Change> changes,
        boolean saveDraft
) {
    /** A request that does not say anything about saving: the draft waits for the customer to press Save Draft. */
    public HandoffIntent(boolean supported, String recipientName, String recipientEmail, String recipientPhone, String sourceReference,
                         boolean copyItemsFromSource, boolean recipientFromSource, String title, String purpose, String category, String dueDate,
                         List<Item> items, List<Change> changes) {
        this(supported, recipientName, recipientEmail, recipientPhone, sourceReference, copyItemsFromSource, recipientFromSource, title, purpose,
                category, dueDate, items, changes, false);
    }

    /** One item the message lists; {@code quantity} is a number as text, or empty when the message gives none (it is never guessed). */
    public record Item(String name, String quantity, String unit) {}

    /** A new quantity for one of the items copied from the source. */
    public record Change(String itemName, String quantity) {}

    static final int MAX_ITEMS = 50;
    static final int MAX_NAME = 300;
    static final int MAX_QUANTITY_TEXT = 20;
    private static final BigDecimal MAX_QUANTITY = new BigDecimal("999999999999.999");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** A message that is not about preparing a handoff. */
    public static HandoffIntent notSupported() {
        return new HandoffIntent(false, "", "", "", "", false, false, "", "", "", "", List.of(), List.of());
    }

    /** The JSON schema the model must follow (every field required, so none is left for it to improvise). */
    static Map<String, Object> schema() {
        Map<String, Object> text = Map.of("type", "string");
        Map<String, Object> flag = Map.of("type", "boolean");
        Map<String, Object> item = Map.of("type", "object",
                "properties", Map.of("name", text, "quantity", text, "unit", text),
                "required", List.of("name", "quantity", "unit"));
        Map<String, Object> change = Map.of("type", "object",
                "properties", Map.of("itemName", text, "quantity", text),
                "required", List.of("itemName", "quantity"));
        Map<String, Object> properties = new java.util.LinkedHashMap<>();
        properties.put("supported", flag);
        properties.put("recipientName", text);
        properties.put("recipientEmail", text);
        properties.put("recipientPhone", text);
        properties.put("sourceReference", text);
        properties.put("copyItemsFromSource", flag);
        properties.put("recipientFromSource", flag);
        properties.put("title", text);
        properties.put("purpose", text);
        properties.put("category", text);
        properties.put("dueDate", text);
        properties.put("items", Map.of("type", "array", "items", item));
        properties.put("changes", Map.of("type", "array", "items", change));
        properties.put("saveDraft", flag);
        return Map.of("type", "object", "properties", properties, "required", List.copyOf(properties.keySet()));
    }

    /** Reads the model's answer: exactly the shape of {@link #schema}, or the answer is refused as unusable. */
    static HandoffIntent fromModelJson(String answer) {
        JsonNode root;
        try {
            root = JSON.readTree(answer);
        } catch (RuntimeException e) {
            throw new AiUnavailableException(Reason.INVALID_RESPONSE);
        }
        if (root == null || !root.isObject() || !root.path("supported").isBoolean()) {
            throw new AiUnavailableException(Reason.INVALID_RESPONSE);
        }
        if (!root.path("supported").asBoolean()) {
            return notSupported();
        }
        List<Item> items = new ArrayList<>();
        for (JsonNode n : array(root, "items")) {
            items.add(new Item(string(n, "name"), string(n, "quantity"), string(n, "unit")));
        }
        List<Change> changes = new ArrayList<>();
        for (JsonNode n : array(root, "changes")) {
            changes.add(new Change(string(n, "itemName"), string(n, "quantity")));
        }
        return new HandoffIntent(true, string(root, "recipientName"), string(root, "recipientEmail"), string(root, "recipientPhone"),
                string(root, "sourceReference"), flag(root, "copyItemsFromSource"), flag(root, "recipientFromSource"),
                string(root, "title"), string(root, "purpose"), string(root, "category"), string(root, "dueDate"), items, changes,
                optionalFlag(root, "saveDraft"));
    }

    private static JsonNode array(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isArray()) throw new AiUnavailableException(Reason.INVALID_RESPONSE);
        return value;
    }

    private static String string(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isString()) throw new AiUnavailableException(Reason.INVALID_RESPONSE);
        return value.asString();
    }

    /** A flag the model may leave out (then it is false); present but not true or false is an unusable answer. */
    private static boolean optionalFlag(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) return false;
        return flag(node, field);
    }

    private static boolean flag(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isBoolean()) throw new AiUnavailableException(Reason.INVALID_RESPONSE);
        return value.asBoolean();
    }

    /**
     * The same request with every text made plain and bounded (control characters dropped, cut to the length the field may have), every quantity a
     * positive number that fits or empty, the day a real calendar date or empty, and the lists capped. Nothing is added: whatever was empty stays empty.
     */
    HandoffIntent cleaned() {
        List<Item> cleanItems = new ArrayList<>();
        for (Item i : items == null ? List.<Item>of() : items) {
            if (i == null || plain(i.name(), MAX_NAME).isEmpty() || cleanItems.size() >= MAX_ITEMS) continue;
            cleanItems.add(new Item(plain(i.name(), MAX_NAME), quantity(i.quantity()), plain(i.unit(), 30)));
        }
        List<Change> cleanChanges = new ArrayList<>();
        for (Change c : changes == null ? List.<Change>of() : changes) {
            if (c == null || plain(c.itemName(), MAX_NAME).isEmpty() || cleanChanges.size() >= MAX_ITEMS) continue;
            cleanChanges.add(new Change(plain(c.itemName(), MAX_NAME), quantity(c.quantity())));
        }
        return new HandoffIntent(supported, plain(recipientName, 200), plain(recipientEmail, 255), plain(recipientPhone, 40),
                plain(sourceReference, 40), copyItemsFromSource, recipientFromSource,
                plain(title, 200), plain(purpose, 2000), plain(category, 60), day(dueDate), cleanItems, cleanChanges, saveDraft);
    }

    static String plain(String text, int max) {
        String t = text == null ? "" : text.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max).trim();
    }

    /** A positive number that fits the quantity column, as plain text; empty for anything else (including "a few"). */
    static String quantity(String text) {
        String t = plain(text, MAX_QUANTITY_TEXT);
        if (t.isEmpty()) return "";
        try {
            BigDecimal value = new BigDecimal(t);
            if (value.signum() <= 0 || value.compareTo(MAX_QUANTITY) > 0 || value.scale() > 3) return "";
            return value.stripTrailingZeros().toPlainString();
        } catch (NumberFormatException e) {
            return "";
        }
    }

    private static String day(String text) {
        String t = plain(text, 10);
        try {
            return LocalDate.parse(t).toString().equals(t) ? t : "";
        } catch (RuntimeException e) {
            return "";
        }
    }
}
