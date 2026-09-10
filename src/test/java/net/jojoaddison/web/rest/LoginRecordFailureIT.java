package net.jojoaddison.web.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.domain.LoginAttempt;
import net.jojoaddison.domain.User;
import net.jojoaddison.repository.LoginAttemptRepository;
import net.jojoaddison.repository.UserRepository;
import net.jojoaddison.web.rest.vm.LoginVM;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/**
 * <b>A store that refuses the authentication record does not break signing in.</b>
 *
 * <p>Induced rather than reasoned about: {@link LoginAttemptRepository} is replaced with one that
 * errors on every save, and a correct sign-in still answers {@code 200} with a usable token.
 *
 * <p>This is the property that decides whether the recording is safe to deploy at all.
 * {@code LoginAttemptRecorder} is called from a {@code doOnNext}/{@code doOnError} inside
 * {@code AuthenticateController}, which runs on a <b>Netty event loop shared by every request on
 * it</b>. A write that threw would take the signal with it and turn a refused audit row into a
 * {@code 500} on a working password; a write the response waited for would turn a slow MongoDB into
 * a hung console for the whole estate. Both are the failure hc-admin-service measured at
 * <b>60.6 seconds</b> for a single {@code POST} against an absent broker, and the reason
 * {@code broker/OutboundEventPublisher} exists one package away. The recorder's javadoc argues why
 * failing closed — refusing to authenticate anyone we cannot audit — was rejected.
 *
 * <h2>Why this is a class of its own rather than a case in {@code AuthenticateControllerIT}</h2>
 *
 * <p>{@code @MockitoBean} is a property of the application context, not of a test method. Declaring
 * it there would replace the repository for that class's two recording cases as well — the ones that
 * read a real row back out of a real collection — and they would then pass while asserting nothing.
 * A guard disarmed by the guard beside it is exactly the shape this estate keeps finding, so the two
 * contexts are kept apart.
 */
@AutoConfigureWebTestClient(timeout = IntegrationTest.DEFAULT_TIMEOUT)
@IntegrationTest
class LoginRecordFailureIT {

    @Autowired
    private ObjectMapper om;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private LoginAttemptRepository loginAttemptRepository;

    @Test
    void aStoreThatRefusesTheRecordDoesNotBreakAWorkingSignIn() throws Exception {
        when(loginAttemptRepository.save(any(LoginAttempt.class))).thenReturn(
            Mono.error(new IllegalStateException("the store is refusing writes"))
        );

        User user = new User();
        user.setLogin("item-75-unrecordable");
        user.setEmail("item-75-unrecordable@example.com");
        user.setActivated(true);
        user.setPassword(passwordEncoder.encode("test"));
        userRepository.save(user).block();

        LoginVM login = new LoginVM();
        login.setUsername("item-75-unrecordable");
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
     * And the same for a refusal, which is the path that matters more.
     *
     * <p>A wrong password already ends in an error signal; a recorder that let a second error escape
     * on top of it would turn the {@code 401} an operator expects into a {@code 500} that says the
     * console is broken. The two paths reach the recorder through different operators
     * ({@code doOnNext} and {@code doOnError}) and neither is covered by the other.
     */
    @Test
    void aStoreThatRefusesTheRecordStillLetsARefusalBeARefusal() throws Exception {
        when(loginAttemptRepository.save(any(LoginAttempt.class))).thenReturn(
            Mono.error(new IllegalStateException("the store is refusing writes"))
        );

        LoginVM login = new LoginVM();
        login.setUsername("item-75-unrecordable-and-wrong");
        login.setPassword("definitely wrong");

        webTestClient
            .post()
            .uri("/api/authenticate")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(om.writeValueAsBytes(login))
            .exchange()
            .expectStatus()
            .isUnauthorized()
            .expectBody()
            .jsonPath("$.id_token")
            .doesNotExist();
    }
}
