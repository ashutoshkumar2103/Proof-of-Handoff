package com.handoffly.documentcheck;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The deterministic limit on what AI Assist may suggest as "the same item": a typing variation, never a different thing. */
class ItemNameVariationTest {

    private static void same(String a, String b) {
        assertThat(ItemNameVariation.isVariation(a, b)).as(a + " ~ " + b).isTrue();
        assertThat(ItemNameVariation.isVariation(b, a)).as(b + " ~ " + a + " (either way round)").isTrue();
    }

    private static void different(String a, String b) {
        assertThat(ItemNameVariation.isVariation(a, b)).as(a + " !~ " + b).isFalse();
        assertThat(ItemNameVariation.isVariation(b, a)).as(b + " !~ " + a + " (either way round)").isFalse();
    }

    @Test
    void aTypingMistakeInOneWordIsAVariation() {
        same("Exam Pad", "Exam Ped");
        same("10th Science Book", "10th Senence Book");
        same("Projector", "Projecter");
        same("Stapler", "Stapeler");
    }

    @Test
    void singularAndPluralAreAVariation() {
        same("10th History Book", "10th History Books");
        same("Box", "Boxes");
        same("Battery", "Batteries");
    }

    @Test
    void onlySpacingDifferingIsAVariation() {
        same("Exam Pad", "ExamPad");
        same("Note book", "Notebook");
    }

    @Test
    void differentWordsAreNeverAVariationHoweverRelated() {
        different("10th Science Book", "10th History Book");
        different("Chair", "Table");
        different("Laptop", "Laptop Bag");
        different("Pen", "Pencil");
        different("Table", "Cable");
        different("Bat", "Cat");
        different("Exam Pad", "Exam Sheet");
    }

    @Test
    void aDifferentNumberOrSizeIsNeverAVariation() {
        different("Chair 1", "Chair 2");
        different("10th Science Book", "9th Science Book");
        different("Pen 0.5", "Pen 0.7");
        different("A4 Paper", "A3 Paper");
    }

    @Test
    void moreThanOneWordDifferingOrAWordTooShortIsNotAVariation() {
        different("Exam Pad Large", "Exan Ped Large");   // two words changed
        different("Go", "Do");                            // too short to be a typo
        different("Exam Pad", "Exam Pad Set");            // a word added
    }

    @Test
    void namesTheComparisonAlreadyTreatsAsEqualAreNotOfferedAsAVariation() {
        assertThat(ItemNameVariation.isVariation("Exam Pad", "exam  PAD")).isFalse();
        assertThat(ItemNameVariation.isVariation("Joker-Dress", "joker dress")).isFalse();
        assertThat(ItemNameVariation.isVariation("", "Pad")).isFalse();
    }

    @Test
    void theDistanceIsTheNumberOfLettersInsertedRemovedOrChanged() {
        assertThat(ItemNameVariation.distance("pad", "ped")).isEqualTo(1);
        assertThat(ItemNameVariation.distance("science", "senence")).isEqualTo(2);
        assertThat(ItemNameVariation.distance("book", "books")).isEqualTo(1);
        assertThat(ItemNameVariation.distance("same", "same")).isZero();
        assertThat(ItemNameVariation.distance("", "abc")).isEqualTo(3);
    }
}
