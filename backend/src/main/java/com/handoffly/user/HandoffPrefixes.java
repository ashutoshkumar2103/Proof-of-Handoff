package com.handoffly.user;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The rule for the default handoff prefix of a new customer, as a pure function of the name and of the prefixes already in use: no database,
 * no clock, no randomness, so the same name against the same set of used prefixes always gives the same prefix. {@link UserService} asks it
 * under the lock that makes the choice safe; nothing else generates a prefix.
 *
 * <p>The order of preference is: two letters made from the name (for "Siam Traders" ST, then SI, SR, SA…; for one word, "Flipkart", FL, FT,
 * FI…), then any other two letters in alphabetical order, then three letters made from the name, then any three letters, and so on to five
 * letters (the longest a prefix may be), so a prefix is as short as the situation allows and one is always found.
 */
final class HandoffPrefixes {

    private static final int MIN_LENGTH = 2;
    private static final int MAX_LENGTH = 5;   // User.HANDOFF_PREFIX_REGEX
    private static final int LETTERS_CONSIDERED = 12;   // of a name: enough for every sensible choice, and keeps the combinations small

    /** Words that say what kind of business it is, not which one ("Siam Traders Pvt Ltd" is Siam Traders). Only dropped when something else remains. */
    private static final Set<String> NOISE = Set.of("THE", "AND", "OF", "PVT", "PRIVATE", "LTD", "LIMITED", "INC", "LLC", "LLP", "CO", "CORP", "CORPORATION");

    private HandoffPrefixes() {}

    /** The first prefix, in the order described above, that is not in {@code used} (compared in upper case). */
    static String firstFree(String name, Set<String> used) {
        List<String> words = words(name);
        List<String> letters = lettersOf(words);
        for (String candidate : fromTheName(words)) {
            if (!used.contains(candidate)) return candidate;
        }
        for (int length = MIN_LENGTH; length <= MAX_LENGTH; length++) {
            if (length > MIN_LENGTH && letters.size() >= length) {
                for (String candidate : combinations(letters, length)) {
                    if (!used.contains(candidate)) return candidate;
                }
            }
            long total = (long) Math.pow(26, length);
            for (long i = 0; i < total; i++) {
                String candidate = alphabetical(i, length);
                if (!used.contains(candidate)) return candidate;
            }
        }
        throw new IllegalStateException("Every handoff prefix of 2 to 5 letters is in use.");
    }

    /** The name's words in capitals, A to Z only (accents dropped, digits and symbols are separators), without the words that only describe a kind of business. */
    static List<String> words(String name) {
        String ascii = Normalizer.normalize(name == null ? "" : name, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toUpperCase(Locale.ROOT);
        List<String> words = new ArrayList<>();
        for (String word : ascii.split("[^A-Z]+")) {
            if (!word.isEmpty()) words.add(word);
        }
        List<String> meaningful = words.stream().filter((w) -> !NOISE.contains(w)).toList();
        return meaningful.isEmpty() ? words : meaningful;
    }

    private static List<String> lettersOf(List<String> words) {
        List<String> letters = new ArrayList<>();
        for (String word : words) {
            for (char c : word.toCharArray()) letters.add(String.valueOf(c));
        }
        return letters.subList(0, Math.min(letters.size(), LETTERS_CONSIDERED));
    }

    /** The two-letter prefixes the name suggests, most natural first, without repeats. */
    static List<String> fromTheName(List<String> words) {
        Set<String> found = new LinkedHashSet<>();
        if (words.isEmpty()) return List.of();
        String first = words.getFirst();
        if (words.size() >= 2) {
            String second = words.get(1);
            found.add("" + first.charAt(0) + second.charAt(0));                        // Siam Traders: ST
            if (first.length() > 1) found.add("" + first.charAt(0) + first.charAt(1)); // SI
            if (second.length() > 1) found.add("" + first.charAt(0) + second.charAt(1)); // SR
            if (words.size() >= 3) found.add("" + first.charAt(0) + words.get(2).charAt(0));
            found.add("" + first.charAt(0) + second.charAt(second.length() - 1));       // SS
        } else if (first.length() >= 2) {
            found.add("" + first.charAt(0) + first.charAt(1));                          // Flipkart: FL
            found.add("" + first.charAt(0) + first.charAt(first.length() - 1));         // FT
        }
        found.addAll(combinations(lettersOf(words), MIN_LENGTH));                       // then every pair of its letters, in order
        return List.copyOf(found);
    }

    /** Every {@code length} letters taken in order from the name's letters (first letters first), without repeats. */
    private static List<String> combinations(List<String> letters, int length) {
        Set<String> found = new LinkedHashSet<>();
        pick(letters, length, 0, "", found);
        return List.copyOf(found);
    }

    private static void pick(List<String> letters, int length, int from, String sofar, Set<String> found) {
        if (sofar.length() == length) {
            found.add(sofar);
            return;
        }
        for (int i = from; i < letters.size(); i++) {
            pick(letters, length, i + 1, sofar + letters.get(i), found);
        }
    }

    /** The {@code index}th prefix of that many letters in alphabetical order: AA, AB … AZ, BA … ZZ. */
    private static String alphabetical(long index, int length) {
        char[] chars = new char[length];
        long rest = index;
        for (int i = length - 1; i >= 0; i--) {
            chars[i] = (char) ('A' + rest % 26);
            rest /= 26;
        }
        return new String(chars);
    }
}
