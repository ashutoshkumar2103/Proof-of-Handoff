package com.handoffly.handoff;

import com.handoffly.handoff.dto.AssistantPreviewResponse;
import com.handoffly.handoff.dto.AssistantPreviewResponse.Clarification;
import com.handoffly.handoff.dto.AssistantPreviewResponse.Draft;
import com.handoffly.handoff.dto.AssistantPreviewResponse.Note;
import com.handoffly.handoff.dto.AssistantPreviewResponse.Option;
import com.handoffly.handoff.dto.AssistantPreviewResponse.Status;
import com.handoffly.handoff.dto.HandoffTemplateResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Turns what the customer asked for ({@link HandoffIntent}) into a draft preview. There is no AI in here and nothing is saved: given the same request
 * (and, for a copy, the same source handoff) it gives the same preview. The customer's earlier handoffs are looked at in ONE case only: when the request
 * itself points at one ("duplicate quotation 12", "the same items as AK-12"). A recipient and the items are taken exactly as the customer said them —
 * HandOffly has no recipient or product records to match them against, and a new name or item is nothing to warn about. The rules that keep it honest:
 * <ul>
 *   <li>a handoff that is copied gives only what the existing duplicate ({@link HandoffTemplateResponse}) gives — never its reference, status, history or returns;</li>
 *   <li>a reference that matches no handoff, or several, is a question, never a guess; so is a quantity change that fits several items;</li>
 *   <li>nothing is invented: an email, a phone number, a quantity or a recipient that the request does not give stays empty, with a note;</li>
 *   <li>an answer to a question is accepted only if it names an option this class offered.</li>
 * </ul>
 */
public final class HandoffDraftResolver {

    /** The recipient of a handoff that is being copied. */
    public record Recipient(String name, String email, String phone) {}

    /** One of the customer's handoffs that the request points at: its reference, what a duplicate takes from it, and who it was for. */
    public record Source(String reference, HandoffTemplateResponse template, Recipient recipient) {}

    /** What a new draft starts with when the request does not say otherwise: who the customer is. */
    public record Account(String displayName, String organization) {}

    static final String SKIP = "skip";
    private static final int MAX_OPTIONS = 6;

    static final String UNSUPPORTED_MESSAGE = "I can only help to prepare a new handoff, for example: \"Create a handoff for Rahul with 2 pens and 3 markers\" "
            + "or \"Duplicate quotation 12 with the same person\".";

    private HandoffDraftResolver() {}

    /** One row of the draft's items while it is being worked out. */
    private static final class Row {
        String name;
        String quantity;
        String unit;

        Row(String name, String quantity, String unit) {
            this.name = name;
            this.quantity = quantity;
            this.unit = unit;
        }
    }

    /** @param findSources finds the customer's own handoffs a written reference means; called only when the request names one */
    public static AssistantPreviewResponse resolve(HandoffIntent request, Account account,
                                                   Function<String, List<Source>> findSources, Map<String, String> choices) {
        HandoffIntent intent = request.cleaned();
        if (!intent.supported()) {
            return new AssistantPreviewResponse(Status.UNSUPPORTED, UNSUPPORTED_MESSAGE, null, List.of(), List.of(), intent, false);
        }
        Map<String, String> chosen = choices == null ? Map.of() : choices;
        List<Note> notes = new ArrayList<>();
        List<Clarification> questions = new ArrayList<>();

        Source source = source(intent, chosen, findSources, notes, questions);

        // the header: what a duplicate takes from the source, else who the customer is; then whatever the request itself says
        String title = source == null ? "" : source.template().title();
        String category = source == null ? "" : orEmpty(source.template().category());
        String purpose = source == null ? "" : orEmpty(source.template().purpose());
        String senderName = source != null && !orEmpty(source.template().senderName()).isBlank() ? source.template().senderName() : account.displayName();
        String senderOrg = source != null && !orEmpty(source.template().senderOrganization()).isBlank() ? source.template().senderOrganization() : orEmpty(account.organization());
        if (!intent.title().isEmpty()) title = intent.title();
        if (!intent.category().isEmpty()) category = intent.category();
        if (!intent.purpose().isEmpty()) purpose = intent.purpose();

        // who it is for: the source's recipient when asked for ("the same person"), otherwise the name as the customer said it
        Recipient recipient = intent.recipientFromSource() && !intent.sourceReference().isEmpty()
                ? (source == null ? new Recipient("", "", "") : source.recipient())   // while the handoff itself is in question, so is this
                : new Recipient(intent.recipientName(), "", "");
        String recipientEmail = recipient.email();
        if (!intent.recipientEmail().isEmpty()) recipientEmail = intent.recipientEmail();   // an address the message itself gave
        String recipientPhone = !intent.recipientPhone().isEmpty() ? intent.recipientPhone() : recipient.phone();

        List<Row> rows = new ArrayList<>();
        if (intent.copyItemsFromSource() && source != null) {
            source.template().items().forEach(i -> rows.add(new Row(i.name(), i.quantity().stripTrailingZeros().toPlainString(), orEmpty(i.unit()))));
        }
        for (HandoffIntent.Item typed : intent.items()) {
            rows.add(new Row(typed.name(), typed.quantity(), typed.unit()));   // exactly as said: a new item is just an item
        }
        applyChanges(intent, chosen, rows, notes, questions);

        if (title.isEmpty()) {
            title = recipient.name().isEmpty() ? "New handoff" : HandoffIntent.plain("Handoff for " + recipient.name(), 200);
            notes.add(new Note("title", "The title is a suggestion. Change it if you like."));
        }
        if (recipient.name().isEmpty()) {
            notes.add(new Note("recipientName", "No recipient yet. A handoff can't be submitted without one."));
        }
        if (recipientEmail.isEmpty()) {
            notes.add(new Note("recipientEmail", "The recipient's email isn't given yet. You can save the draft without it, but it must be added before the handoff can be submitted."));
        }
        if (rows.isEmpty()) {
            notes.add(new Note("items", "No items yet. Add at least one before the handoff can be submitted."));
        }
        List<String> noQuantity = rows.stream().filter(r -> r.quantity.isEmpty()).map(r -> r.name).toList();
        if (!noQuantity.isEmpty()) {
            notes.add(new Note("items", "A quantity is needed for: " + String.join(", ", noQuantity) + "."));
        }

        Draft draft = new Draft(title, category, purpose, senderName, senderOrg, recipient.name(), recipientEmail, recipientPhone, intent.dueDate(),
                rows.stream().map(r -> new AssistantPreviewResponse.Item(r.name, r.quantity, r.unit)).toList());
        boolean ready = questions.isEmpty();
        return new AssistantPreviewResponse(ready ? Status.READY : Status.NEEDS_INPUT,
                ready ? "Here is your draft. Check it, change anything you like, then save it. Nothing is saved until you do."
                        : "I need an answer before the draft is finished. Everything else I understood is already filled in.",
                draft, notes, questions, intent, intent.saveDraft());
    }

    // ------------------------------------------------------------------ the handoff being copied

    private static Source source(HandoffIntent intent, Map<String, String> chosen, Function<String, List<Source>> find,
                                 List<Note> notes, List<Clarification> questions) {
        String ref = intent.sourceReference();
        if (ref.isEmpty()) return null;
        String pick = chosen.get("source");
        if (SKIP.equals(pick)) {
            notes.add(new Note("items", "Carrying on without \"" + ref + "\"."));
            return null;
        }
        List<Source> found = find.apply(ref);
        if (pick != null) {
            List<Source> picked = found.stream().filter(s -> s.reference().equalsIgnoreCase(pick)).toList();
            if (picked.size() == 1) return picked.get(0);
        }
        if (found.size() == 1) return found.get(0);
        List<Option> options = new ArrayList<>();
        found.stream().limit(MAX_OPTIONS).forEach(s -> options.add(new Option(s.reference(), s.reference() + " · " + s.template().title())));
        options.add(new Option(SKIP, "Carry on without it"));
        questions.add(new Clarification("source", found.isEmpty()
                ? "I couldn't find a handoff \"" + ref + "\" in your account."
                : "More than one of your handoffs matches \"" + ref + "\". Which one?", options));
        return null;
    }

    private static void applyChanges(HandoffIntent intent, Map<String, String> chosen, List<Row> rows, List<Note> notes, List<Clarification> questions) {
        for (HandoffIntent.Change change : intent.changes()) {
            if (change.quantity().isEmpty()) {
                notes.add(new Note("items", "No usable quantity was given for \"" + change.itemName() + "\", so it was left as it is."));
                continue;
            }
            String key = key(change.itemName());
            List<Integer> matches = new ArrayList<>();
            for (int i = 0; i < rows.size(); i++) if (key(rows.get(i).name).equals(key)) matches.add(i);
            if (matches.isEmpty()) {
                List<String> targetWords = words(change.itemName());
                for (int i = 0; i < rows.size(); i++) if (words(rows.get(i).name).containsAll(targetWords)) matches.add(i);
            }
            String id = "change:" + key;
            String pick = chosen.get(id);
            if (pick != null && matches.stream().anyMatch(i -> String.valueOf(i).equals(pick))) {
                rows.get(Integer.parseInt(pick)).quantity = change.quantity();
            } else if (matches.size() == 1) {
                rows.get(matches.get(0)).quantity = change.quantity();
            } else if (matches.isEmpty()) {
                notes.add(new Note("items", "I couldn't find an item \"" + change.itemName() + "\" to change, so nothing was changed."));
            } else {
                List<Option> options = new ArrayList<>();
                matches.stream().limit(MAX_OPTIONS).forEach(i -> options.add(new Option(String.valueOf(i),
                        rows.get(i).name + " (now " + (rows.get(i).quantity.isEmpty() ? "no quantity" : rows.get(i).quantity) + ")")));
                questions.add(new Clarification(id, "More than one item fits \"" + change.itemName() + "\". Which one should be " + change.quantity() + "?", options));
            }
        }
    }

    // ------------------------------------------------------------------ comparing names

    /** The words of an item name in plain lower case, each as a singular ("Markers" and "marker" are the same item). */
    static List<String> words(String name) {
        List<String> words = new ArrayList<>();
        for (String w : name.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (!w.isEmpty()) words.add(singular(w));
        }
        return words;
    }

    static String key(String name) {
        return String.join(" ", words(name));
    }

    private static String singular(String w) {
        if (w.length() > 4 && w.endsWith("ies")) return w.substring(0, w.length() - 3) + "y";
        if (w.length() > 3 && w.endsWith("s") && !w.endsWith("ss")) return w.substring(0, w.length() - 1);
        return w;
    }

    private static String orEmpty(String text) {
        return text == null ? "" : text;
    }
}
