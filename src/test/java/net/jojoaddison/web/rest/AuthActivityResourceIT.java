package net.jojoaddison.web.rest;

import static net.jojoaddison.security.jwt.JwtAuthenticationTestUtils.createTokenWithAuthorities;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.domain.LoginAttempt;
import net.jojoaddison.domain.LoginOutcome;
import net.jojoaddison.domain.User;
import net.jojoaddison.security.AuthoritiesConstants;
import net.jojoaddison.security.jwt.AuthenticationIntegrationTest;
import net.jojoaddison.service.dto.AuthActivityDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * {@code GET /api/auth-activity} — who may read it, and whether the figures come from the data.
 *
 * <h2>The authorities are the point of half of this class</h2>
 *
 * <p>{@link net.jojoaddison.domain.LoginAttempt}'s <b>first safeguard</b> is that this endpoint is
 * {@code ROLE_ADMIN} alone — narrower than everything else on the console, because the response
 * names logins as they were entered. Each of the four callers is asserted separately: an aggregate
 * "authorised callers get 200" cannot distinguish a rule that admits admins from one that admits
 * everybody.
 *
 * <p><b>An operator is the case that matters.</b> The rule this endpoint must not have is the
 * blanket {@code /api/** -> authenticated()} one immediately below it in
 * {@code SecurityConfiguration} — and if the explicit matcher were deleted, that is exactly what
 * would decide this path, silently, with every other test in the repository still green. All three
 * gateways in the estate share one signing key and every account holds {@code ROLE_USER}, so
 * "authenticated" here means every token in the network.
 * {@code SecurityConfigurationOrderTest} covers the ordering half, which no request can see.
 */
@AutoConfigureWebTestClient(timeout = IntegrationTest.DEFAULT_TIMEOUT)
@AuthenticationIntegrationTest
class AuthActivityResourceIT {

    private static final String PATH = "/api/auth-activity";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private ReactiveMongoTemplate mongoTemplate;

    @Value("${jhipster.security.authentication.jwt.base64-secret}")
    private String jwtKey;

    private String admin() {
        return createTokenWithAuthorities(jwtKey, "admin", AuthoritiesConstants.ADMIN, AuthoritiesConstants.USER);
    }

    private String operator() {
        return createTokenWithAuthorities(jwtKey, "operator", AuthoritiesConstants.OPERATOR, AuthoritiesConstants.USER);
    }

    private String plainUser() {
        return createTokenWithAuthorities(jwtKey, "user", AuthoritiesConstants.USER);
    }

    /**
     * {@code login_attempt} is emptied; {@code jhi_user} deliberately is not.
     *
     * <p>The first collection belongs to this feature alone, so clearing it makes the login figures
     * absolute rather than relative and a wrong number therefore says what it is. The second is
     * shared with every other suite in this repository and with {@code InitialSetupMigration}'s
     * seeded accounts — emptying it would make this class's fixture depend on execution order and
     * would leave whatever ran next facing a user collection somebody else had deleted. The account
     * assertions are <b>deltas</b> for that reason.
     */
    @BeforeEach
    void emptyTheAttemptsThisFeatureOwns() {
        mongoTemplate.remove(new Query(), LoginAttempt.class).block();
    }

    // --- safeguard 1: who may read this ---------------------------------------------------------

    @Test
    void anAdminMayReadTheAuthenticationActivity() {
        webTestClient
            .get()
            .uri(PATH)
            .headers(h -> h.setBearerAuth(admin()))
            .exchange()
            .expectStatus()
            .isOk();
    }

    /**
     * <b>An operator is refused</b>, unlike on every other read this console makes.
     *
     * <p>They may page the whole entity surface of hc-admin-service, patient contact addresses
     * included. They may not have this, because {@code topFailedLogins} carries logins as entered and
     * an operator has no operational need for them. Same shape as the patient CSV export, which item
     * 53 made admin-only over a screen an operator may read.
     */
    @Test
    void anOperatorMayNotReadTheAuthenticationActivity() {
        webTestClient
            .get()
            .uri(PATH)
            .headers(h -> h.setBearerAuth(operator()))
            .exchange()
            .expectStatus()
            .isForbidden();
    }

    /** Authentication alone is deliberately not enough, and is what the rule below this one gives. */
    @Test
    void aPlainUserMayNotReadTheAuthenticationActivity() {
        webTestClient
            .get()
            .uri(PATH)
            .headers(h -> h.setBearerAuth(plainUser()))
            .exchange()
            .expectStatus()
            .isForbidden();
    }

    @Test
    void anonymousMayNotReadTheAuthenticationActivity() {
        webTestClient.get().uri(PATH).exchange().expectStatus().isUnauthorized();
    }

    // --- the figures ---------------------------------------------------------------------------

    /**
     * Every number is counted from a collection, which is backlog item 75's "done when" clause.
     *
     * <p>The fixture is deliberately asymmetric — three failures against one login, one against
     * another, two successes, and one account of each activation state — so that a service returning
     * a constant, or transposing two fields, cannot pass. Equal counts would let
     * {@code succeeded}/{@code failed} be swapped with nothing to show for it.
     */
    @Test
    void theFiguresAreCountedFromTheCollections() {
        AuthActivityDTO before = read();

        saveUser("item-75-active-one", true);
        saveUser("item-75-active-two", true);
        saveUser("item-75-never-activated", false);

        saveAttempt("hammered", LoginOutcome.FAILED, 0);
        saveAttempt("hammered", LoginOutcome.FAILED, 0);
        saveAttempt("hammered", LoginOutcome.FAILED, 0);
        saveAttempt("mistyped", LoginOutcome.FAILED, 0);
        saveAttempt("item-75-active-one", LoginOutcome.SUCCEEDED, 0);
        saveAttempt("item-75-active-two", LoginOutcome.SUCCEEDED, 0);

        AuthActivityDTO after = read();

        // Two activated and one not, asserted as a delta against whatever the shared user collection
        // already held — and asserted separately, because a single "three more accounts" check would
        // pass on a service that put all three in one bucket.
        assertThat(after.accounts().activated() - before.accounts().activated())
            .as("newly activated accounts")
            .isEqualTo(2);
        assertThat(after.accounts().notActivated() - before.accounts().notActivated())
            .as("newly unactivated accounts")
            .isEqualTo(1);
        assertThat(after.accounts().activated() + after.accounts().notActivated())
            .as("the two buckets must sum to the collection, or a chart drawn from them disagrees with its own total")
            .isEqualTo(mongoTemplate.count(new Query(), User.class).block());

        webTestClient
            .get()
            .uri(PATH)
            .headers(h -> h.setBearerAuth(admin()))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody()
            .jsonPath("$.logins.succeeded")
            .isEqualTo(2)
            .jsonPath("$.logins.failed")
            .isEqualTo(4)
            // The login as entered, largest first — the part of the response that carries the
            // exception to item 43 and the reason the whole endpoint is admin-only.
            .jsonPath("$.logins.topFailedLogins[0].login")
            .isEqualTo("hammered")
            .jsonPath("$.logins.topFailedLogins[0].failures")
            .isEqualTo(3)
            .jsonPath("$.logins.topFailedLogins[1].login")
            .isEqualTo("mistyped");
    }

    /**
     * The window is real, and it is reported alongside the figures rather than assumed by a caption.
     *
     * <p>An attempt older than {@code window-days} is outside every login figure and inside none of
     * them — the account split is a standing total and is unaffected, which is the asymmetry
     * {@code AuthActivityDTO} states. Without this case a service ignoring the window entirely would
     * pass, and the screen would answer a slowly different question every day as the collection aged.
     */
    @Test
    void anAttemptOlderThanTheWindowIsOutsideTheFigures() {
        saveAttempt("long-ago", LoginOutcome.FAILED, 400);
        saveAttempt("today", LoginOutcome.FAILED, 0);

        webTestClient
            .get()
            .uri(PATH)
            .headers(h -> h.setBearerAuth(admin()))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody()
            .jsonPath("$.logins.failed")
            .isEqualTo(1)
            .jsonPath("$.windowDays")
            .isEqualTo(30)
            .jsonPath("$.retentionDays")
            .isEqualTo(90);
    }

    /**
     * The daily series covers the whole window, including the days nothing happened.
     *
     * <p>A series that omits empty days draws a quiet week as a straight line between two spikes,
     * which is the opposite of what it means — and it is the natural output of the aggregation, which
     * returns only the days it found rows for.
     *
     * <p><b>31 and not 30, knowingly.</b> The window is a span in hours rather than a run of calendar
     * days, so a 30-day window starting mid-morning touches 31 dates and <em>both</em> end days are
     * partial. The literal is asserted here because the alternative — dropping the partial first point
     * to get a rounder 30 — would break the invariant that makes the number right: the series sums to
     * {@code succeeded} and {@code failed}, both counted over the same filter, so a chart drawn from
     * it agrees with the totals printed above it. {@code AuthActivityDTO.daily} carries the full
     * argument, including why a caption reading "last 30 days" over 31 bars is the smaller inaccuracy.
     */
    @Test
    void theDailySeriesHasAPointForEveryDayOfTheWindow() {
        saveAttempt("today", LoginOutcome.SUCCEEDED, 0);

        webTestClient
            .get()
            .uri(PATH)
            .headers(h -> h.setBearerAuth(admin()))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody()
            .jsonPath("$.logins.daily.length()")
            .isEqualTo(31);
    }

    // --- fixture --------------------------------------------------------------------------------

    /** The endpoint's own answer, as the console would receive it. */
    private AuthActivityDTO read() {
        return webTestClient
            .get()
            .uri(PATH)
            .headers(h -> h.setBearerAuth(admin()))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(AuthActivityDTO.class)
            .returnResult()
            .getResponseBody();
    }

    private void saveUser(String login, boolean activated) {
        User user = new User();
        user.setLogin(login);
        user.setEmail(login + "@example.com");
        user.setActivated(activated);
        // Exactly 60 characters: User.password is @Size(min = 60, max = 60), a bcrypt hash's length.
        user.setPassword("$2a$10$00000000000000000000000000000000000000000000000000000");
        mongoTemplate.save(user).block();
    }

    private void saveAttempt(String login, LoginOutcome outcome, int daysAgo) {
        LoginAttempt attempt = new LoginAttempt();
        attempt.setLogin(login);
        attempt.setOutcome(outcome);
        attempt.setAttemptedAt(Instant.now().minus(daysAgo, ChronoUnit.DAYS));
        mongoTemplate.save(attempt).block();
    }
}
