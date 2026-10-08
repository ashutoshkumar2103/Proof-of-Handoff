package com.handoffly.documentcheck;

import java.util.Locale;

/**
 * The deterministic limit on what AI Assist may suggest to treat as the same item: two names are only a variation of one another when
 * they differ by case, punctuation, spacing, a plural, or one or two mistyped letters inside a single word. Different words are never
 * a variation, however alike their meaning ("Science Book" and "History Book", "Chair" and "Table", "Laptop" and "Laptop Bag"), and a
 * difference in a number never is ("Chair 1", "Chair 2", "10th" and "9th"). The AI proposes; this decides whether a proposal is even
 * allowed to be shown, so a suggestion can never be broader than a typing variation. It is a limit, not a matcher: it never finds a pair
 * by itself, and the customer still reviews every suggestion that passes.
 */
final class ItemNameVariation {

    private static final int MIN_WORD = 3;            // "Ped" is a word; "a" and "of" are too short to call a typo
    private static final int SHORT_WORD = 5;          // up to this many letters one wrong letter is the most that can be a typo
    private static final int MAX_TYPOS_SHORT = 1;
    private static final int MAX_TYPOS_LONG = 2;

    private ItemNameVariation() {}

    /** Whether {@code a} and {@code b} may be offered as the same item. Names already equal for the comparison (case, punctuation) are not "a variation": they match anyway. */
    static boolean isVariation(String a, String b) {
        String x = DocumentCheckService.normalizeName(a);
        String y = DocumentCheckService.normalizeName(b);
        if (x.isEmpty() || y.isEmpty() || x.equals(y)) return false;
        if (x.replace(" ", "").equals(y.replace(" ", ""))) return true;   // "Exam Pad" and "ExamPad": only the spacing differs

        String[] wordsX = x.split(" ");
        String[] wordsY = y.split(" ");
        if (wordsX.length != wordsY.length) return false;
        int different = 0;
        for (int i = 0; i < wordsX.length; i++) {
            if (wordsX[i].equals(wordsY[i])) continue;
            if (++different > 1 || !isVariationOfWord(wordsX[i], wordsY[i])) return false;   // one word may differ, and only as a typo or a plural
        }
        return different == 1;
    }

    private static boolean isVariationOfWord(String x, String y) {
        if (hasDigit(x) || hasDigit(y) || x.length() < MIN_WORD || y.length() < MIN_WORD) return false;
        if (isPlural(x, y) || isPlural(y, x)) return true;
        if (x.charAt(0) != y.charAt(0)) return false;   // typos keep the first letter; words that differ there ("Table", "Cable") are other words
        int longest = Math.max(x.length(), y.length());
        return distance(x, y) <= (longest <= SHORT_WORD ? MAX_TYPOS_SHORT : MAX_TYPOS_LONG);
    }

    /** {@code plural} is {@code single} with an s, es, or y→ies added. */
    private static boolean isPlural(String single, String plural) {
        return plural.equals(single + "s") || plural.equals(single + "es")
                || (single.endsWith("y") && plural.equals(single.substring(0, single.length() - 1) + "ies"));
    }

    private static boolean hasDigit(String word) {
        return word.chars().anyMatch(Character::isDigit);
    }

    /** Edit distance (insert, delete, change one letter) between two words. */
    static int distance(String a, String b) {
        String x = a.toLowerCase(Locale.ROOT);
        String y = b.toLowerCase(Locale.ROOT);
        int[] previous = new int[y.length() + 1];
        int[] current = new int[y.length() + 1];
        for (int j = 0; j <= y.length(); j++) previous[j] = j;
        for (int i = 1; i <= x.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= y.length(); j++) {
                int cost = x.charAt(i - 1) == y.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[y.length()];
    }
}
