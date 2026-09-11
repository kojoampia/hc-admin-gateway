package net.jojoaddison.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.domain.User;
import net.jojoaddison.management.RegistrationMetersService;
import net.jojoaddison.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The registration gauges against a real user store.
 *
 * <p>The unit test proves the arithmetic against a mock. What only a real MongoDB proves is that
 * {@code countByActivated} is a query Spring Data can actually derive — a derived method name that
 * does not resolve fails at <em>context startup</em> rather than at the call, so a mocked repository
 * would go on passing while the application refused to start.
 */
@IntegrationTest
class RegistrationMetricsSamplerIT {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RegistrationMetricsSampler sampler;

    @Autowired
    private RegistrationMetersService registrationMetersService;

    @Autowired
    private MeterRegistry meterRegistry;

    /**
     * {@code User.password} is {@code @Size(min = 60, max = 60)} — the width of a BCrypt hash — and the
     * validator runs on the save, so a plain string fails the write rather than the assertion.
     */
    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void aSampleAgainstTheRealStorePublishesBothGaugesAndATimestamp() {
        User activated = new User();
        activated.setLogin("registration-gauge-activated");
        activated.setEmail("registration-gauge-activated@example.com");
        activated.setActivated(true);
        activated.setPassword(passwordEncoder.encode("not-used-here"));
        userRepository.save(activated).block();

        long before = Instant.now().getEpochSecond();

        sampler.sampleOnce().block();

        assertThat(meterRegistry.get("account.registrations").tag("state", "activated").gauge().value()).isGreaterThanOrEqualTo(1);
        assertThat(meterRegistry.find("account.registrations").tag("state", "not-activated").gauge()).isNotNull();
        assertThat(meterRegistry.get("account.registrations.sampled.timestamp").gauge().value()).isGreaterThanOrEqualTo(before);
        assertThat(registrationMetersService.activatedCount()).isGreaterThanOrEqualTo(1);
    }
}
