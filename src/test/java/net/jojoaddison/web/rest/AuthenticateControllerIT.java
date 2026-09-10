package net.jojoaddison.web.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.domain.LoginAttempt;
import net.jojoaddison.domain.LoginOutcome;
import net.jojoaddison.domain.User;
import net.jojoaddison.repository.UserRepository;
import net.jojoaddison.web.rest.vm.LoginVM;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Integration tests for the {@link AuthenticateController} REST controller.
 */
@AutoConfigureWebTestClient(timeout = IntegrationTest.DEFAULT_TIMEOUT)
@IntegrationTest
class AuthenticateControllerIT {

    @Autowired
    private ObjectMapper om;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void testAuthorize() throws Exception {
        User user = new User();
        user.setLogin("user-jwt-controller");
        user.setEmail("user-jwt-controller@example.com");
        user.setActivated(true);
        user.setPassword(passwordEncoder.encode("test"));

        userRepository.save(user).block();

        LoginVM login = new LoginVM();
        login.setUsername("user-jwt-controller");
        login.setPassword("test");
        webTestClient
            .post()
            .uri("/api/authenticate")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(om.writeValueAsBytes(login))
            .exchange()
            .expectStatus()
            .isOk()
            .expectHeader()
            .valueMatches("Authorization", "Bearer .+")
            .expectBody()
            .jsonPath("$.id_token")
            .isNotEmpty();
    }

    /**
     * The token has to carry the account's database id, not just its login.
     *
     * <p>hc-admin-service attributes every document write to whoever made it, and its domain models
     * reference accounts by id — the seed data puts {@code a0eebc99-…-a11} in {@code createdBy}, and
     * CLAUDE.md names those ids a contract shared with hc-patient-ms and hc-professional-service.
     * That service runs with {@code skipUserManagement: true} and has no route to this service's
     * user collection, so if this claim goes missing it can only fall back to writing
     * {@code system}. Silently, and only visible much later as an audit trail that attributes
     * nothing to anyone.
     */
    @Test
    void tokenCarriesTheUserIdClaim() throws Exception {
        User user = new User();
        user.setLogin("user-jwt-uid-claim");
        user.setEmail("user-jwt-uid-claim@example.com");
        user.setActivated(true);
        user.setPassword(passwordEncoder.encode("test"));

        User saved = userRepository.save(user).block();
        assertThat(saved).isNotNull();

        LoginVM login = new LoginVM();
        login.setUsername("user-jwt-uid-claim");
        login.setPassword("test");

        String body = new String(
            webTestClient
                .post()
                .uri("/api/authenticate")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(om.writeValueAsBytes(login))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .returnResult()
                .getResponseBody(),
            StandardCharsets.UTF_8
        );

        String token = om.readTree(body).get("id_token").asText();
        // Decode the payload directly rather than through a JwtDecoder: this asserts what is on the
        // wire, which is the thing the other service parses.
        String payload = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);
        JsonNode claims = om.readTree(payload);

        assertThat(claims.get("sub").asText()).isEqualTo("user-jwt-uid-claim");
        assertThat(claims.hasNonNull("uid")).as("uid claim present").isTrue();
        assertThat(claims.get("uid").asText()).isEqualTo(saved.getId());
    }

    @Test
    void testAuthorizeWithRememberMe() throws Exception {
        User user = new User();
        user.setLogin("user-jwt-controller-remember-me");
        user.setEmail("user-jwt-controller-remember-me@example.com");
        user.setActivated(true);
        user.setPassword(passwordEncoder.encode("test"));

        userRepository.save(user).block();

        LoginVM login = new LoginVM();
        login.setUsername("user-jwt-controller-remember-me");
        login.setPassword("test");
        login.setRememberMe(true);
        webTestClient
            .post()
            .uri("/api/authenticate")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(om.writeValueAsBytes(login))
            .exchange()
            .expectStatus()
            .isOk()
            .expectHeader()
            .valueMatches("Authorization", "Bearer .+")
            .expectBody()
            .jsonPath("$.id_token")
            .isNotEmpty();
    }

    /**
     * A deactivated account is refused, and refused the same way a wrong password is.
     *
     * <p>This path existed and was covered only at the service layer, by {@code
     * DomainUserDetailsServiceIT} asserting that {@code DomainUserDetailsService} throws
     * {@code UserNotActivatedException}. Nothing checked what that becomes over HTTP. The only
     * failure case here used an unknown login with a wrong password, which exercises a different
     * branch entirely — the user is never found, so the activation check is never reached.
     *
     * <p>What that left uncovered is a real regression shape: {@code UserNotActivatedException}
     * extends {@code AuthenticationException}, and if it ever stopped being translated — a changed
     * exception handler, a reordered filter — the natural failure is a {@code 500}, which leaks that
     * the account exists, or worse a {@code 200} with a token for an account somebody deliberately
     * switched off.
     *
     * <p>The assertion deliberately includes "no token in the body". A 401 with a usable
     * {@code id_token} beside it would satisfy a status-only check and be exactly the bug worth
     * fearing.
     */
    @Test
    void aDeactivatedAccountIsRefused() throws Exception {
        User user = new User();
        user.setLogin("deactivated-jwt-controller");
        user.setEmail("deactivated-jwt-controller@example.com");
        user.setActivated(false);
        user.setPassword(passwordEncoder.encode("test"));

        userRepository.save(user).block();

        LoginVM login = new LoginVM();
        login.setUsername("deactivated-jwt-controller");
        login.setPassword("test");
        webTestClient
            .post()
            .uri("/api/authenticate")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(om.writeValueAsBytes(login))
            .exchange()
            .expectStatus()
            .isUnauthorized()
            .expectHeader()
            .doesNotExist("Authorization")
            .expectBody()
            .jsonPath("$.id_token")
            .doesNotExist();
    }

    @Test
    void testAuthorizeFails() throws Exception {
        LoginVM login = new LoginVM();
        login.setUsername("wrong-user");
        login.setPassword("wrong password");
        webTestClient
            .post()
            .uri("/api/authenticate")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(om.writeValueAsBytes(login))
            .exchange()
            .expectStatus()
            .isUnauthorized()
            .expectHeader()
            .doesNotExist("Authorization")
            .expectBody()
            .jsonPath("$.id_token")
            .doesNotExist();
    }

    // --- the authentication record (backlog item 75) --------------------------------------------

    /**
     * <b>A failed sign-in is written down, and until backlog item 75 nothing anywhere recorded one.</b>
     *
     * <p>Not a counter: measured before that item, {@code AuthenticateController} referenced
     * {@code SecurityMetersService} zero times, and that class counts JWT <em>token-validation</em>
     * failures — {@code invalid-signature}, {@code expired}, {@code unsupported}, {@code malformed} —
     * of which a wrong password is none. This gateway persisted exactly {@code User} and
     * {@code Authority}. So a wrong password left no trace on this stack in any form, which is the
     * defect this case exists to keep closed.
     *
     * <p>Driven end to end through the HTTP endpoint rather than against
     * {@code LoginAttemptRecorder}: the recorder's own unit test asserts what it stores, and what this
     * asserts is that a real refusal reaches it at all. The failure arrives as an <em>error signal</em>
     * on the way to {@code ExceptionTranslator}, so it is hooked with {@code doOnError} and not a
     * {@code catch} — a hook written the natural way records nothing and looks entirely correct.
     */
    @Test
    void aFailedSignInIsRecordedWithTheLoginThatWasTried() throws Exception {
        String tried = "item-75-no-such-account";
        postCredentials(tried, "definitely wrong").expectStatus().isUnauthorized();

        LoginAttempt recorded = awaitAttemptFor(tried);
        assertThat(recorded.getOutcome()).isEqualTo(LoginOutcome.FAILED);
        assertThat(recorded.getLogin()).isEqualTo(tried);
    }

    /**
     * And a successful one, so the two are distinguishable in the store.
     *
     * <p>The success row is not decoration. A failure count with no denominator answers nothing —
     * twenty failures is an ordinary afternoon on a busy console and an incident on a quiet one — and
     * this is also the row that says whether whoever was guessing eventually got in.
     */
    @Test
    void aSuccessfulSignInIsRecordedAsSuccessAndNotAsAFailure() throws Exception {
        User user = new User();
        user.setLogin("item-75-good-account");
        user.setEmail("item-75-good-account@example.com");
        user.setActivated(true);
        user.setPassword(passwordEncoder.encode("test"));
        userRepository.save(user).block();

        postCredentials("item-75-good-account", "test").expectStatus().isOk();

        LoginAttempt recorded = awaitAttemptFor("item-75-good-account");
        assertThat(recorded.getOutcome()).isEqualTo(LoginOutcome.SUCCEEDED);
    }

    // The other half of the recording contract — that a store which refuses the write does not break
    // signing in — is LoginRecordFailureIT rather than a case here. It needs the repository bean
    // replaced, and @MockitoBean is a property of the context: doing it in this class would replace it
    // for the two cases above too, which read the real collection and would then assert nothing.

    @Autowired
    private ReactiveMongoTemplate mongoTemplate;

    private WebTestClient.ResponseSpec postCredentials(String username, String password) throws Exception {
        LoginVM login = new LoginVM();
        login.setUsername(username);
        login.setPassword(password);
        return webTestClient
            .post()
            .uri("/api/authenticate")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(om.writeValueAsBytes(login))
            .exchange();
    }

    /**
     * The row for one login, waited for rather than read immediately.
     *
     * <p>The write is deliberately not on the response's path — that is the whole design — so it is
     * genuinely not there yet when the {@code 200} or the {@code 401} arrives. Polling is the honest
     * assertion of "eventually recorded"; a bare read would be a race that passes on a fast machine
     * and reports a missing security signal on a loaded one.
     */
    private LoginAttempt awaitAttemptFor(String login) {
        return await()
            .atMost(Duration.ofSeconds(10))
            .until(
                () -> mongoTemplate.find(Query.query(Criteria.where("login").is(login)), LoginAttempt.class).blockFirst(),
                Objects::nonNull
            );
    }
}
