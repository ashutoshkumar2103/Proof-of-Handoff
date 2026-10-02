package com.handoffly.common.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PublicCodeTest {

    @Test
    void accountStaffAndTicketIdsKeepTwoDigitsAndGrowPastThat() {
        assertThat(PublicCode.account(1)).isEqualTo("CUS-01");
        assertThat(PublicCode.account(9)).isEqualTo("CUS-09");
        assertThat(PublicCode.account(10)).isEqualTo("CUS-10");
        assertThat(PublicCode.account(42)).isEqualTo("CUS-42");
        assertThat(PublicCode.account(1_234_567)).isEqualTo("CUS-1234567");
        assertThat(PublicCode.ticket(1)).isEqualTo("TKT-01");
        assertThat(PublicCode.ticket(7)).isEqualTo("TKT-07");
        assertThat(PublicCode.ticket(123)).isEqualTo("TKT-123");
        assertThat(PublicCode.staff(1)).isEqualTo("STAFF-01");
        assertThat(PublicCode.staff(12)).isEqualTo("STAFF-12");
    }

    @Test
    void handoffReferencesAreThePrefixAndTheCustomersOwnNumber() {
        assertThat(PublicCode.handoff("AV", 1)).isEqualTo("AV-1");
        assertThat(PublicCode.handoff("HO", 14)).isEqualTo("HO-14");
    }
}
