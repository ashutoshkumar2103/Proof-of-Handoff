package com.handoffly.user;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Upgrades a database that already holds customers and handoffs (schema V9, shaped like the real
 * dev database: gaps in handoff ids, codes exactly HO-&lt;id&gt;) to the latest schema and checks
 * that nothing existing was changed, lost or broken. Also covers the later step that makes support
 * staff a separate identity: a customer that the earlier design had promoted to a staff role is an
 * ordinary customer again, and support-ticket messages recorded earlier stay intact.
 */
class AccountMigrationTest {

    private static final String URL =
            "jdbc:h2:mem:account_migration;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1";
    private static final String TOKEN_HASH = "a".repeat(64);

    private static Flyway flyway(String target) {
        var config = Flyway.configure().dataSource(URL, "sa", "").locations("classpath:db/migration");
        return (target == null ? config : config.target(target)).load();
    }

    private static List<String> column(Connection c, String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    private static void exec(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    @Test
    void existingDataSurvivesTheUpgradeAndNumberingContinuesAfterIt() throws Exception {
        flyway("9").migrate();
        String now = "TIMESTAMP '2026-09-28 19:42:00'";

        try (Connection c = DriverManager.getConnection(URL, "sa", "")) {
            for (long id : new long[]{1, 9, 12}) {
                exec(c, "INSERT INTO app_user (id, version, created_at, updated_at, email, password_hash, display_name, role, enabled) "
                        + "VALUES (" + id + ", 0, " + now + ", " + now + ", 'u" + id + "@example.test', 'hash', 'User " + id + "', "
                        + (id == 12 ? "'ADMIN'" : "'USER'") + ", TRUE)");
            }
            // Owner 1 has HO-1, HO-3, HO-6; owner 9 has HO-14 (ids of other, since-deleted accounts left gaps).
            for (long[] h : new long[][]{{1, 1}, {3, 1}, {6, 1}, {14, 9}}) {
                exec(c, "INSERT INTO handoff (id, version, created_at, updated_at, public_code, owner_user_id, title, sender_name, "
                        + "recipient_name, recipient_email, status) VALUES (" + h[0] + ", 0, " + now + ", " + now + ", 'HO-" + h[0] + "', "
                        + h[1] + ", 'Handoff " + h[0] + "', 'Sender', 'Recipient', 'r@example.test', 'CLOSED')");
            }
            exec(c, "INSERT INTO recipient_link (id, version, created_at, updated_at, handoff_id, token_hash, expires_at, revoked) "
                    + "VALUES (1, 0, " + now + ", " + now + ", 6, '" + TOKEN_HASH + "', TIMESTAMP '2030-01-01 00:00:00', FALSE)");
            exec(c, "INSERT INTO audit_event (id, version, created_at, updated_at, handoff_id, event_type, actor_type, message) "
                    + "VALUES (1, 0, " + now + ", " + now + ", 6, 'HANDOFF_CREATED', 'USER', 'Handoff created as draft.')");
        }

        flyway("11").migrate();   // the account and ticket schema as it was before staff became their own identity
        try (Connection c = DriverManager.getConnection(URL, "sa", "")) {
            exec(c, "INSERT INTO support_ticket (id, version, created_at, updated_at, ticket_code, account_user_id, category, "
                    + "contact_method, subject, description, status, message_count) VALUES (1, 0, " + now + ", " + now
                    + ", 'TKT-000001', 1, 'GENERAL', 'TICKET', 'Old subject', 'Old description', 'OPEN', 1)");
            exec(c, "INSERT INTO support_ticket_message (id, version, created_at, updated_at, ticket_id, author_user_id, "
                    + "author_role, body) VALUES (1, 0, " + now + ", " + now + ", 1, 1, 'CUSTOMER', 'Old customer message')");
        }

        flyway("12").migrate();   // support staff as their own identity — still with the old SUPPORT / ADMIN roles
        try (Connection c = DriverManager.getConnection(URL, "sa", "")) {
            String staffSql = "INSERT INTO support_staff (id, version, created_at, updated_at, staff_code, name, email, password_hash, role, active) VALUES ";
            exec(c, staffSql + "(101, 0, " + now + ", " + now + ", 'STAFF-000101', 'Old Support', 'old.support@example.test', 'hash-101', 'SUPPORT', TRUE)");
            exec(c, staffSql + "(102, 0, " + now + ", " + now + ", 'STAFF-000102', 'Gone Support', 'gone.support@example.test', 'hash-102', 'SUPPORT', FALSE)");
            exec(c, staffSql + "(103, 0, " + now + ", " + now + ", 'STAFF-000103', 'The Admin', 'the.admin@example.test', 'hash-103', 'ADMIN', TRUE)");
            exec(c, "INSERT INTO support_audit_event (id, version, created_at, updated_at, staff_id, customer_id, event_type, previous_value, new_value, reason) "
                    + "VALUES (1, 0, " + now + ", " + now + ", 101, 1, 'PLAN_CHANGED', 'MONTHLY', 'YEARLY', 'Old change')");
        }

        flyway("15").migrate();   // + V13 (staff roles), V14 (short IDs), V15 (payments)
        try (Connection c = DriverManager.getConnection(URL, "sa", "")) {
            // A plan a customer paid for before subscription history existed.
            exec(c, "INSERT INTO payment (id, version, created_at, updated_at, provider, plan, amount, currency, token_hash, paid_at, "
                    + "expires_at, redeemed_at, redeemed_by_user_id, plan_before) VALUES (1, 0, " + now + ", " + now
                    + ", 'DEMO', 'QUARTERLY', 549, 'INR', '" + "b".repeat(64) + "', " + now + ", TIMESTAMP '2030-01-01 00:00:00', "
                    + now + ", 9, 'MONTHLY')");
        }

        flyway(null).migrate();   // everything after V15: V16 (sessions, password reset), V17 (subscription lifecycle), V20 (no plan until activated)

        try (Connection c = DriverManager.getConnection(URL, "sa", "")) {
            // Accounts: stable IDs, default plan and prefix, counters positioned after what exists.
            assertThat(column(c, "SELECT account_code FROM app_user ORDER BY id"))
                    .containsExactly("CUS-01", "CUS-09", "CUS-12");   // V14 dropped the padding zeros, kept the numbers
            assertThat(column(c, "SELECT subscription_plan FROM app_user")).containsOnly("MONTHLY");
            assertThat(column(c, "SELECT handoff_prefix FROM app_user")).containsOnly("HO");
            assertThat(column(c, "SELECT handoff_sequence FROM app_user ORDER BY id")).containsExactly("6", "14", "0");
            // Subscription lifecycle: the real start and end of existing plans were never recorded, so they stay empty
            // (no end date means no change in what anyone can use), and nobody is signed out by the upgrade.
            assertThat(column(c, "SELECT id FROM app_user WHERE plan_started_at IS NOT NULL OR plan_valid_until IS NOT NULL")).isEmpty();
            assertThat(column(c, "SELECT token_version FROM app_user")).containsOnly("0");
            // The history is filled only from what really happened: a plan change support made, and a plan paid for.
            // Nothing is invented for the customers who were never moved, and no end date is made up.
            assertThat(column(c, "SELECT user_id || ':' || previous_plan || '>' || new_plan || ':' || source || ':' "
                    + "|| COALESCE(CAST(staff_id AS VARCHAR), '-') || ':' || COALESCE(reason, '-') "
                    + "FROM subscription_history ORDER BY source"))
                    .containsExactly("9:MONTHLY>QUARTERLY:PAYMENT:-:-", "1:MONTHLY>YEARLY:STAFF:101:Old change");
            assertThat(column(c, "SELECT id FROM subscription_history WHERE valid_until IS NOT NULL")).isEmpty();

            // A customer the earlier design had promoted to a staff role is a plain customer again (staff are a
            // separate identity now); the account itself — Account ID, handoffs, everything — is untouched.
            assertThat(column(c, "SELECT role FROM app_user ORDER BY id")).containsExactly("USER", "USER", "USER");
            assertThat(column(c, "SELECT next_value FROM sequence_counter WHERE name = 'ACCOUNT'")).containsExactly("13");
            assertThat(column(c, "SELECT next_value FROM sequence_counter WHERE name = 'TICKET'")).containsExactly("1");
            assertThat(column(c, "SELECT next_value FROM sequence_counter WHERE name = 'STAFF'")).containsExactly("1");

            // Existing handoffs: same ids, same codes, same owners.
            assertThat(column(c, "SELECT id || ':' || public_code || ':' || owner_user_id FROM handoff ORDER BY id"))
                    .containsExactly("1:HO-1:1", "3:HO-3:1", "6:HO-6:1", "14:HO-14:9");
            // Recipient links and history are untouched.
            assertThat(column(c, "SELECT token_hash || ':' || handoff_id FROM recipient_link")).containsExactly(TOKEN_HASH + ":6");
            assertThat(column(c, "SELECT message FROM audit_event WHERE handoff_id = 6")).containsExactly("Handoff created as draft.");

            // References are unique per account, no longer globally: another account may reuse HO-1...
            exec(c, "INSERT INTO handoff (id, version, created_at, updated_at, public_code, owner_user_id, title, sender_name, "
                    + "recipient_name, recipient_email, status) VALUES (100, 0, TIMESTAMP '2026-10-01 10:00:00', TIMESTAMP '2026-10-01 10:00:00', "
                    + "'HO-1', 9, 'Same code, other account', 'S', 'R', 'r@example.test', 'DRAFT')");
            // ...but one account can never hold the same reference twice.
            assertThatThrownBy(() -> exec(c, "INSERT INTO handoff (id, version, created_at, updated_at, public_code, owner_user_id, title, "
                    + "sender_name, recipient_name, recipient_email, status) VALUES (101, 0, TIMESTAMP '2026-10-01 10:00:00', "
                    + "TIMESTAMP '2026-10-01 10:00:00', 'HO-1', 1, 'Duplicate', 'S', 'R', 'r@example.test', 'DRAFT')"))
                    .isInstanceOf(SQLException.class);

            // Account codes are unique too.
            assertThatThrownBy(() -> exec(c, "UPDATE app_user SET account_code = 'CUS-01' WHERE id = 9"))
                    .isInstanceOf(SQLException.class);

            // Staff roles: the retired SUPPORT becomes MANAGER (nobody loses a capability, nobody gains staff management);
            // ADMIN is kept; who is active stays as it was; Staff IDs keep their numbers (only the padding zeros go); emails and password hashes are untouched.
            assertThat(column(c, "SELECT staff_code || ':' || role || ':' || active || ':' || password_hash FROM support_staff WHERE id >= 101 ORDER BY id"))
                    .containsExactly("STAFF-101:MANAGER:TRUE:hash-101", "STAFF-102:MANAGER:FALSE:hash-102", "STAFF-103:ADMIN:TRUE:hash-103");
            // The ticket recorded earlier keeps its number under the shorter ID.
            assertThat(column(c, "SELECT ticket_code FROM support_ticket")).containsExactly("TKT-01");
            // The audit row written before survives with its links intact; the table can now also describe staff changes.
            assertThat(column(c, "SELECT customer_id || ':' || staff_id || ':' || previous_value || '>' || new_value FROM support_audit_event"))
                    .containsExactly("1:101:MONTHLY>YEARLY");
            exec(c, "INSERT INTO support_audit_event (id, version, created_at, updated_at, staff_id, target_staff_id, event_type, previous_value, new_value) "
                    + "VALUES (2, 0, " + now + ", " + now + ", 103, 102, 'STAFF_REACTIVATED', 'INACTIVE', 'ACTIVE')");
            exec(c, "INSERT INTO support_audit_event (id, version, created_at, updated_at, staff_id, target_staff_id, event_type, previous_value, new_value) "
                    + "VALUES (3, 0, " + now + ", " + now + ", 103, 101, 'STAFF_CREATED', NULL, 'MANAGER')");   // no previous value
            assertThatThrownBy(() -> exec(c, "INSERT INTO support_audit_event (id, version, created_at, updated_at, staff_id, customer_id, "
                    + "target_staff_id, event_type, new_value) VALUES (4, 0, " + now + ", " + now + ", 103, 1, 101, 'STAFF_CREATED', 'MANAGER')"))
                    .isInstanceOf(SQLException.class);   // two targets
            assertThatThrownBy(() -> exec(c, "INSERT INTO support_audit_event (id, version, created_at, updated_at, staff_id, event_type, new_value) "
                    + "VALUES (5, 0, " + now + ", " + now + ", 103, 'STAFF_CREATED', 'MANAGER')"))
                    .isInstanceOf(SQLException.class);   // no target

            // Support-ticket messages recorded earlier keep their customer author and have no staff author.
            assertThat(column(c, "SELECT body || ':' || author_user_id FROM support_ticket_message"))
                    .containsExactly("Old customer message:1");
            assertThat(column(c, "SELECT COUNT(*) FROM support_ticket_message WHERE author_staff_id IS NULL")).containsExactly("1");

            // From now on a reply has exactly one author: a customer OR a staff member.
            exec(c, "INSERT INTO support_staff (id, version, created_at, updated_at, staff_code, name, email, password_hash, role, active) "
                    + "VALUES (1, 0, " + now + ", " + now + ", 'STAFF-01', 'Agent', 'agent@example.test', 'hash', 'SUPPORT', TRUE)");
            exec(c, "INSERT INTO support_ticket_message (id, version, created_at, updated_at, ticket_id, author_staff_id, author_role, body) "
                    + "VALUES (2, 0, " + now + ", " + now + ", 1, 1, 'SUPPORT', 'Staff reply')");
            assertThatThrownBy(() -> exec(c, "INSERT INTO support_ticket_message (id, version, created_at, updated_at, ticket_id, "
                    + "author_user_id, author_staff_id, author_role, body) VALUES (3, 0, " + now + ", " + now + ", 1, 1, 1, 'SUPPORT', 'Two authors')"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> exec(c, "INSERT INTO support_ticket_message (id, version, created_at, updated_at, ticket_id, "
                    + "author_role, body) VALUES (4, 0, " + now + ", " + now + ", 1, 'SUPPORT', 'No author')"))
                    .isInstanceOf(SQLException.class);
            // A staff email is unique, like a customer's.
            assertThatThrownBy(() -> exec(c, "INSERT INTO support_staff (id, version, created_at, updated_at, staff_code, name, email, "
                    + "password_hash, role, active) VALUES (2, 0, " + now + ", " + now + ", 'STAFF-02', 'Other', 'agent@example.test', 'hash', 'ADMIN', TRUE)"))
                    .isInstanceOf(SQLException.class);

            // New customers no longer carry a role of their own: the legacy column simply takes its default.
            exec(c, "INSERT INTO app_user (id, version, created_at, updated_at, email, password_hash, display_name, enabled, account_code) "
                    + "VALUES (50, 0, " + now + ", " + now + ", 'u50@example.test', 'hash', 'User 50', TRUE, 'CUS-50')");
            assertThat(column(c, "SELECT role FROM app_user WHERE id = 50")).containsExactly("USER");

            // V20: an account may have no plan at all (existing ones, asserted above, keep theirs), and the first plan a customer
            // ever pays for is recorded as replacing nothing.
            exec(c, "INSERT INTO app_user (id, version, created_at, updated_at, email, password_hash, display_name, enabled, account_code, "
                    + "subscription_plan) VALUES (51, 0, " + now + ", " + now + ", 'u51@example.test', 'hash', 'User 51', TRUE, 'CUS-51', NULL)");
            assertThat(column(c, "SELECT COALESCE(subscription_plan, 'NONE') FROM app_user WHERE id = 51")).containsExactly("NONE");
            String payment = "INSERT INTO payment (id, version, created_at, updated_at, provider, plan, amount, currency, token_hash, paid_at, "
                    + "expires_at, redeemed_at, redeemed_by_user_id, plan_before) VALUES ";
            String paidAndExpiring = now + ", TIMESTAMP '2030-01-01 00:00:00', ";
            exec(c, payment + "(2, 0, " + now + ", " + now + ", 'DEMO', 'YEARLY', 1999, 'INR', '" + "c".repeat(64) + "', " + paidAndExpiring
                    + now + ", 51, NULL)");
            // The constraint still guards what it always did: a redemption is recorded in full or not at all.
            assertThatThrownBy(() -> exec(c, payment + "(3, 0, " + now + ", " + now + ", 'DEMO', 'YEARLY', 1999, 'INR', '" + "d".repeat(64)
                    + "', " + paidAndExpiring + now + ", NULL, NULL)")).isInstanceOf(SQLException.class);   // redeemed, but by nobody
            assertThatThrownBy(() -> exec(c, payment + "(4, 0, " + now + ", " + now + ", 'DEMO', 'YEARLY', 1999, 'INR', '" + "e".repeat(64)
                    + "', " + paidAndExpiring + "NULL, NULL, 'MONTHLY')")).isInstanceOf(SQLException.class);   // not redeemed, yet has a result

            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM handoff")) {
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getInt(1)).isEqualTo(5);   // 4 original + the one deliberately added; the duplicate was refused
                }
            }
        }
    }
}
