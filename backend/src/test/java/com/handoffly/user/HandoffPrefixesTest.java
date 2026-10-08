package com.handoffly.user;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The rule for a new customer's default handoff prefix: a pure function of the name and of the prefixes in use, so it can be tested without a database. */
class HandoffPrefixesTest {

    private static String prefix(String name, String... used) {
        return HandoffPrefixes.firstFree(name, Set.of(used));
    }

    @Test
    void anOrganizationOrNameGivesTheInitialsOfItsFirstTwoWords() {
        assertThat(prefix("Siam Traders")).isEqualTo("ST");
        assertThat(prefix("Rahul Kumar")).isEqualTo("RK");
        assertThat(prefix("ABC Solutions")).isEqualTo("AS");
        assertThat(prefix("Priya Nair Sharma")).isEqualTo("PN");
    }

    @Test
    void aSingleNameGivesItsFirstTwoLettersNeverOne() {
        assertThat(prefix("Ram")).isEqualTo("RA");
        assertThat(prefix("Flipkart")).isEqualTo("FL");
        assertThat(prefix("Amazon")).isEqualTo("AM");
        assertThat(prefix("Q")).hasSize(2);   // even a one-letter name has two: the rest is filled in alphabetically
    }

    @Test
    void whenThePreferredPrefixIsTakenTheNextChoiceOfTheRuleIsUsedInTheSameOrderEveryTime() {
        assertThat(prefix("Siam Technologies", "ST")).isEqualTo("SI");
        assertThat(prefix("Siam Technologies", "ST", "SI")).isEqualTo("SE");
        assertThat(prefix("Siam Technologies", "ST", "SI", "SE")).isEqualTo("SS");
        assertThat(prefix("Flatkart", "FL")).isEqualTo("FT");
        assertThat(prefix("Flatkart", "FL", "FT")).isEqualTo("FA");
        assertThat(prefix("Ram", "RA")).isEqualTo("RM");

        // The same question, any number of times, has the same answer.
        for (int i = 0; i < 5; i++) {
            assertThat(prefix("Siam Technologies", "ST", "SI")).isEqualTo("SE");
        }
    }

    @Test
    void aPrefixIsNeverOneThatIsInUse() {
        Set<String> used = new HashSet<>();
        for (int i = 0; i < 40; i++) {
            String next = HandoffPrefixes.firstFree("Siam Traders", used);
            assertThat(used).doesNotContain(next);
            used.add(next);
        }
        assertThat(used).hasSize(40).allMatch(p -> p.matches("[A-Z]{2}"));
        assertThat(used).contains("ST", "SI");
    }

    @Test
    void wordsThatOnlySayWhatKindOfBusinessItIsAreLeftOut() {
        assertThat(prefix("Siam Traders Pvt Ltd")).isEqualTo("ST");
        assertThat(prefix("The Siam Traders Co.")).isEqualTo("ST");
        assertThat(prefix("Pvt Ltd")).isEqualTo("PL");   // nothing else to go by: the words are kept
    }

    @Test
    void lettersWithAccentsAreReadAsTheirPlainLetters() {
        assertThat(prefix("Ñandú Éxito")).isEqualTo("NE");
        assertThat(prefix("rahul   KUMAR")).isEqualTo("RK");
    }

    @Test
    void aNameWithNoLatinLettersStillGetsAPrefix() {
        assertThat(prefix("12345")).isEqualTo("AA");
        assertThat(prefix("李明")).isEqualTo("AA");
        assertThat(prefix("", "AA")).isEqualTo("AB");
        assertThat(HandoffPrefixes.firstFree(null, Set.of())).isEqualTo("AA");
    }

    @Test
    void whenEveryTwoLetterPrefixIsInUseARegistrationStillGetsAThreeLetterOne() {
        Set<String> every = new HashSet<>();
        for (char a = 'A'; a <= 'Z'; a++) {
            for (char b = 'A'; b <= 'Z'; b++) every.add("" + a + b);
        }
        assertThat(every).hasSize(676);

        assertThat(HandoffPrefixes.firstFree("Siam Traders", every)).isEqualTo("SIA");   // made from the name first
        every.add("SIA");
        assertThat(HandoffPrefixes.firstFree("Siam Traders", every)).isEqualTo("SIM");
        assertThat(HandoffPrefixes.firstFree("12345", every)).isEqualTo("AAA");           // nothing to go by: alphabetical
    }

    @Test
    void everyPrefixItGivesIsValidForACustomer() {
        for (String name : new String[]{"Siam Traders", "x", "Ñ", "ABC", "Rahul Kumar", "a b c d e f", "999 Bakery", "The Co"}) {
            assertThat(HandoffPrefixes.firstFree(name, Set.of())).as(name).matches(User.HANDOFF_PREFIX_REGEX);
        }
    }
}
