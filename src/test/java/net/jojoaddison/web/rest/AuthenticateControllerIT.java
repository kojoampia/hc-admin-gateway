package net.jojoaddison.web.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.domain.User;
import net.jojoaddison.repository.UserRepository;
import net.jojoaddison.web.rest.vm.LoginVM;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
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
}
