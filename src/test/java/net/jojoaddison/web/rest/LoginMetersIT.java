package net.jojoaddison.web.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import java.util.List;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.domain.User;
import net.jojoaddison.management.LoginMetersService;
import net.jojoaddison.repository.UserRepository;
import net.jojoaddison.web.rest.vm.LoginVM;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The sign-in counters, driven through the endpoint rather than called directly.
 *
 * <h2>Why this is an IT and not three more cases on {@code LoginMetersServiceTest}</h2>
 *
 * <p>The unit test proves the counters count. What it cannot prove is that anything <em>calls</em>
 * them, and that is the failure this repository has already had one domain along: item 75 found that
 * {@code AuthenticateController} referenced {@code SecurityMetersService} nowhere at all, so a whole
 * meters class existed, registered its series, and stayed at zero forever while a wrong password
 * went entirely unrecorded. A detached counter is green in every unit test ever written for it.
 *
 * <h2>⚠ The last case is backlog item 80's hard constraint, watched end to end</h2>
 *
 * <p>It drives a refusal with a distinctive login and then reads <b>the whole registry</b> — every
 * meter, every tag key and every tag value — asserting the string is nowhere in it. That is a
 * stronger assertion than "the login counters carry only {@code outcome}", because the constraint is
 * about what leaves this process on the OTLP push, and every meter in the registry leaves on it.
 * None of {@code LoginAttempt}'s four safeguards — {@code ROLE_ADMIN} alone, never logged, capped,
 * TTL-bounded — reaches a Prometheus label; two independent things go wrong if one ever carries a
 * login, and both are disqualifying: unbounded cardinality from an attacker-supplied string, and an
 * identifier in a store shared unauthenticated with five other products.
 */
@AutoConfigureWebTestClient(timeout = IntegrationTest.DEFAULT_TIMEOUT)
@IntegrationTest
class LoginMetersIT {

    private static final String LOGINS_METER_NAME = "security.authentication.logins";

    @Autowired
    private ObjectMapper om;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void aSuccessfulSignInIncrementsTheSuccessCounter() throws Exception {
        givenAccount("meters-success", "meters-success-password");

        double before = countFor(LoginMetersService.OUTCOME_SUCCESS);
        double refusedBefore = countFor(LoginMetersService.OUTCOME_REFUSED);

        authenticate("meters-success", "meters-success-password").expectStatus().isOk();

        assertThat(countFor(LoginMetersService.OUTCOME_SUCCESS)).isEqualTo(before + 1);
        assertThat(countFor(LoginMetersService.OUTCOME_REFUSED)).isEqualTo(refusedBefore);
    }

    @Test
    void aRefusedSignInIncrementsTheRefusedCounter() throws Exception {
        givenAccount("meters-refused", "meters-refused-password");

        double before = countFor(LoginMetersService.OUTCOME_REFUSED);
        double successBefore = countFor(LoginMetersService.OUTCOME_SUCCESS);

        authenticate("meters-refused", "not-the-password").expectStatus().isUnauthorized();

        assertThat(countFor(LoginMetersService.OUTCOME_REFUSED)).isEqualTo(before + 1);
        assertThat(countFor(LoginMetersService.OUTCOME_SUCCESS)).isEqualTo(successBefore);
    }

    /**
     * ⚠ No raw login may become a metric label — see the class javadoc. The login used here exists on
     * no account, which is the realistic shape: the interesting values in this field are strings an
     * attacker chose, not logins this gateway knows.
     */
    @Test
    void noMeterAnywhereInTheRegistryCarriesTheEnteredLogin() throws Exception {
        String enteredLogin = "unmistakable-login-that-must-not-become-a-label";

        authenticate(enteredLogin, "whatever").expectStatus().isUnauthorized();

        List<Meter.Id> ids = meterRegistry.getMeters().stream().map(Meter::getId).toList();

        assertThat(ids)
            .as("a metric name carrying an entered login")
            .noneMatch(id -> id.getName().contains(enteredLogin));
        assertThat(ids)
            .as("a metric tag carrying an entered login")
            .allSatisfy(id ->
                assertThat(id.getTags()).noneMatch(tag -> tag.getKey().contains(enteredLogin) || tag.getValue().contains(enteredLogin))
            );
    }

    /**
     * The whole label space of this family, measured on a context that has actually served sign-ins.
     * The unit test asserts the same thing about a registry nobody has driven; this one asserts it
     * about the registry that would be exported.
     */
    @Test
    void theLoginsFamilyCarriesOnlyTheTwoOutcomeLabels() throws Exception {
        authenticate("meters-label-space", "whatever").expectStatus().isUnauthorized();

        List<Tag> tags = meterRegistry
            .find(LOGINS_METER_NAME)
            .counters()
            .stream()
            .flatMap(counter -> counter.getId().getTags().stream())
            .toList();

        assertThat(tags).extracting(Tag::getKey).containsOnly(LoginMetersService.LOGINS_METER_OUTCOME_DIMENSION);
        assertThat(tags)
            .extracting(Tag::getValue)
            .containsExactlyInAnyOrder(LoginMetersService.OUTCOME_SUCCESS, LoginMetersService.OUTCOME_REFUSED);
    }

    private void givenAccount(String login, String password) {
        User user = new User();
        user.setLogin(login);
        user.setEmail(login + "@example.com");
        user.setActivated(true);
        user.setPassword(passwordEncoder.encode(password));
        userRepository.save(user).block();
    }

    private WebTestClient.ResponseSpec authenticate(String username, String password) throws Exception {
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

    private double countFor(String outcome) {
        return meterRegistry.get(LOGINS_METER_NAME).tag(LoginMetersService.LOGINS_METER_OUTCOME_DIMENSION, outcome).counter().count();
    }
}
