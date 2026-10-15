package com.handoffly.auth.jwt;

import com.handoffly.common.config.HandOfflyProperties;
import com.handoffly.support.staff.SupportRole;
import com.handoffly.testsupport.ApiTestBase;
import com.handoffly.user.User;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A session ends after 15 minutes without activity, on the server: a token whose holder last reported activity longer ago is refused
 * on every protected endpoint, whatever the apps do. Time is a controlled clock (a token is signed "as of" a past moment), so nothing waits.
 */
class SessionInactivityTest extends ApiTestBase {

    private static final Duration LIMIT = JwtService.INACTIVITY_LIMIT;
    private static final String SECRET = "test-secret-key-that-is-at-least-32-bytes-long-for-hs256-ok";

    @Autowired
    private HandOfflyProperties properties;

    // ---------------------------------------------------------------- the rule itself, with a clock the test moves

    /** A clock the test moves by hand. */
    private static final class Mutable extends Clock {
        private Instant now = Instant.parse("2030-01-01T09:00:00Z");

        void advance(Duration d) { now = now.plus(d); }

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private JwtService serviceOn(Mutable clock) {
        HandOfflyProperties p = new HandOfflyProperties();
        p.getJwt().setSecret(SECRET);
        p.getJwt().setIssuer(properties.getJwt().getIssuer());
        p.getJwt().setExpirationMinutes(720);
        return new JwtService(p, clock);
    }

    private User someUser() throws Exception {
        return users.findById(register().id()).orElseThrow();
    }

    @Test
    void aSessionThatIsLeftAloneEndsAtTheLimitAndNotBefore() throws Exception {
        Mutable clock = new Mutable();
        JwtService jwt = serviceOn(clock);
        String token = jwt.issueForCustomer(someUser()).token();

        clock.advance(LIMIT.minusSeconds(1));
        assertThat(jwt.parseCustomer(token)).as("just inside the limit").isNotNull();

        clock.advance(Duration.ofMinutes(1).plusSeconds(2));   // the minute in which the apps report is allowed for: 16 minutes
        assertThat(jwt.parseCustomer(token)).as("past the limit and the reporting interval").isNull();
    }

    @Test
    void activityBeforeTheLimitKeepsTheSessionGoingButNotItsOverallLifetime() throws Exception {
        Mutable clock = new Mutable();
        JwtService jwt = serviceOn(clock);
        JwtService.IssuedToken first = jwt.issueForCustomer(someUser());
        String bearer = "Bearer " + first.token();

        String current = first.token();
        // Active every 10 minutes for 11 hours: far more than 15 minutes in all, never 15 minutes at once.
        for (int i = 0; i < 66; i++) {
            clock.advance(Duration.ofMinutes(10));
            JwtService.IssuedToken renewed = jwt.renewCustomer("Bearer " + current);
            assertThat(renewed).as("renewal %d", i).isNotNull();
            assertThat(renewed.expiresAt()).as("the overall expiry never moves").isEqualTo(first.expiresAt().truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
            current = renewed.token();
        }
        assertThat(jwt.parseCustomer(current)).isNotNull();

        // The very first token was never renewed by anything else, so it ended long ago.
        assertThat(jwt.parseCustomer(first.token())).isNull();
        assertThat(jwt.renewCustomer(bearer)).as("an ended session cannot be renewed").isNull();

        // And the overall lifetime still ends the session, however active the person is.
        clock.advance(Duration.ofMinutes(10));   // 11h10 since sign-in, 12h limit
        current = jwt.renewCustomer("Bearer " + current).token();
        clock.advance(Duration.ofMinutes(10));
        current = jwt.renewCustomer("Bearer " + current).token();
        clock.advance(Duration.ofMinutes(10));
        current = jwt.renewCustomer("Bearer " + current).token();
        assertThat(current).isNotNull();
        clock.advance(Duration.ofMinutes(100));
        assertThat(jwt.parseCustomer(current)).as("12 hours after signing in").isNull();
    }

    @Test
    void readingOrPollingNeverRestartsTheClock() throws Exception {
        Mutable clock = new Mutable();
        JwtService jwt = serviceOn(clock);
        String token = jwt.issueForCustomer(someUser()).token();

        // Parsing is all that every request does with a token: a poll every minute for 20 minutes changes nothing about it.
        for (int minute = 1; minute <= 20; minute++) {
            clock.advance(Duration.ofMinutes(1));
            boolean accepted = jwt.parseCustomer(token) != null;
            assertThat(accepted).as("minute %d", minute).isEqualTo(minute <= LIMIT.toMinutes() + 1);
        }
    }

    @Test
    void aTokenWithoutARecordedActivityIsJudgedByWhenItWasIssued() throws Exception {
        // Tokens issued before activity was recorded carry no 'act': they count as last active when issued, never as active forever.
        Mutable clock = new Mutable();
        JwtService jwt = serviceOn(clock);
        User user = someUser();
        assertThat(jwt.parseCustomer(legacyToken(user, clock.instant().minus(Duration.ofMinutes(5))))).isNotNull();
        assertThat(jwt.parseCustomer(legacyToken(user, clock.instant().minus(Duration.ofMinutes(30))))).isNull();
    }

    private String legacyToken(User user, Instant issuedAt) {
        return io.jsonwebtoken.Jwts.builder()
                .issuer(properties.getJwt().getIssuer())
                .audience().add("handoffly-customers").and()
                .subject(String.valueOf(user.getId()))
                .claim("email", user.getEmail())
                .claim("tv", user.getTokenVersion())
                .issuedAt(java.util.Date.from(issuedAt))
                .expiration(java.util.Date.from(issuedAt.plus(Duration.ofHours(12))))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .compact();
    }

    // ---------------------------------------------------------------- the API, with tokens signed as of a past moment

    private String tokenIdleFor(Duration idle, Account who) {
        Mutable past = new Mutable();
        past.now = Instant.now().minus(idle);
        return serviceOn(past).issueForCustomer(users.findById(who.id()).orElseThrow()).token();
    }

    private String staffTokenIdleFor(Duration idle, StaffAccount who) {
        Mutable past = new Mutable();
        past.now = Instant.now().minus(idle);
        return serviceOn(past).issueForStaff(who.id(), who.email()).token();
    }

    private record Token(String token) implements Bearer {
        @Override public String token() { return token; }
    }

    @Test
    void anIdleCustomerSessionCannotReachAnyProtectedApi() throws Exception {
        Account customer = register();
        Bearer active = new Token(tokenIdleFor(Duration.ofMinutes(14), customer));
        Bearer idle = new Token(tokenIdleFor(LIMIT.plusMinutes(2), customer));

        mvc.perform(as(active, get("/api/v1/auth/me"))).andExpect(status().isOk());
        for (String path : new String[]{"/api/v1/auth/me", "/api/v1/handoffs", "/api/v1/dashboard"}) {
            mvc.perform(as(idle, get(path))).andExpect(status().isUnauthorized());
        }
        // ...and it cannot bring itself back by reporting activity: the report is refused like anything else.
        mvc.perform(as(idle, post("/api/v1/auth/activity"))).andExpect(status().isUnauthorized());
        // The same account, signed in properly, is unaffected.
        mvc.perform(as(customer, get("/api/v1/auth/me"))).andExpect(status().isOk());
    }

    @Test
    void reportingActivityGivesBackTheSameSessionRenewedAndAnythingElseDoesNot() throws Exception {
        Account customer = register();
        Bearer nearlyIdle = new Token(tokenIdleFor(LIMIT.minusSeconds(20), customer));

        // An ordinary request is answered and does not hand out a new token.
        MvcResult plain = mvc.perform(as(nearlyIdle, get("/api/v1/auth/me"))).andExpect(status().isOk()).andReturn();
        assertThat(plain.getResponse().getContentAsString()).doesNotContain("\"token\"");

        MvcResult renewed = mvc.perform(as(nearlyIdle, post("/api/v1/auth/activity"))).andExpect(status().isOk()).andReturn();
        String json = renewed.getResponse().getContentAsString();
        String newToken = JsonPath.read(json, "$.token");
        assertThat(newToken).isNotEqualTo(nearlyIdle.token());
        long activityOfNew = activityOf(newToken);
        assertThat(Instant.now().getEpochSecond() - activityOfNew).as("the new token is active as of now").isBetween(0L, 5L);
        assertThat(expiryOf(newToken)).as("the overall expiry is the original one").isEqualTo(expiryOf(nearlyIdle.token()));
        // Both belong to the same account.
        mvc.perform(as(new Token(newToken), get("/api/v1/auth/me"))).andExpect(status().isOk());
    }

    @Test
    void activityNeedsASignedInSessionAndAStaffTokenIsNotACustomerOne() throws Exception {
        mvc.perform(post("/api/v1/auth/activity")).andExpect(status().isUnauthorized());
        StaffAccount staff = registerStaff(SupportRole.ADMIN);
        mvc.perform(as(staff, post("/api/v1/auth/activity"))).andExpect(status().isUnauthorized());
        Account customer = register();
        mvc.perform(as(customer, post("/api/v1/support/auth/activity"))).andExpect(status().isUnauthorized());
    }

    @Test
    void anIdleSupportSessionIsEndedToo() throws Exception {
        StaffAccount staff = registerStaff(SupportRole.ADMIN);
        Bearer active = new Token(staffTokenIdleFor(Duration.ofMinutes(14), staff));
        Bearer idle = new Token(staffTokenIdleFor(LIMIT.plusMinutes(2), staff));

        mvc.perform(as(active, get("/api/v1/support/auth/me"))).andExpect(status().isOk());
        mvc.perform(as(idle, get("/api/v1/support/auth/me"))).andExpect(status().isUnauthorized());
        mvc.perform(as(idle, get("/api/v1/support/dashboard"))).andExpect(status().isUnauthorized());
        mvc.perform(as(idle, post("/api/v1/support/auth/activity"))).andExpect(status().isUnauthorized());

        MvcResult renewed = mvc.perform(as(active, post("/api/v1/support/auth/activity"))).andExpect(status().isOk()).andReturn();
        String newToken = JsonPath.read(renewed.getResponse().getContentAsString(), "$.token");
        mvc.perform(as(new Token(newToken), get("/api/v1/support/auth/me"))).andExpect(status().isOk());
    }

    @Test
    void signingInAgainAfterAnIdleEndWorksAndRecipientLinksDoNotDependOnIt() throws Exception {
        Account customer = register();
        Bearer idle = new Token(tokenIdleFor(LIMIT.plusMinutes(5), customer));
        mvc.perform(as(idle, get("/api/v1/auth/me"))).andExpect(status().isUnauthorized());

        Account again = login(customer.email());
        mvc.perform(as(again, get("/api/v1/auth/me"))).andExpect(status().isOk());

        // The recipient's secure link is public and judged by its own token: a stale session header changes nothing about it.
        int withoutHeader = mvc.perform(get("/api/v1/r/not-a-real-link")).andReturn().getResponse().getStatus();
        int withIdleHeader = mvc.perform(as(idle, get("/api/v1/r/not-a-real-link"))).andReturn().getResponse().getStatus();
        assertThat(withIdleHeader).isEqualTo(withoutHeader).isNotEqualTo(401);
    }

    private static long activityOf(String token) {
        return ((Number) JsonPath.read(payload(token), "$.act")).longValue();
    }

    private static long expiryOf(String token) {
        return ((Number) JsonPath.read(payload(token), "$.exp")).longValue();
    }

    private static String payload(String token) {
        return new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
    }
}
