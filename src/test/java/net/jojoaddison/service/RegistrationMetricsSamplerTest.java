package net.jojoaddison.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.jojoaddison.management.RegistrationMetersService;
import net.jojoaddison.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * What the sampler publishes, and — the case worth having — what it does <b>not</b> publish when the
 * store cannot be read.
 */
@ExtendWith(MockitoExtension.class)
class RegistrationMetricsSamplerTest {

    @Mock
    private UserRepository userRepository;

    private final RegistrationMetersService meters = new RegistrationMetersService(new SimpleMeterRegistry());

    private RegistrationMetricsSampler sampler() {
        return new RegistrationMetricsSampler(userRepository, meters);
    }

    @Test
    void oneSampleReadsBothStatesAndPublishesThem() {
        when(userRepository.countByActivated(true)).thenReturn(Mono.just(12L));
        when(userRepository.countByActivated(false)).thenReturn(Mono.just(4L));

        StepVerifier.create(sampler().sampleOnce()).verifyComplete();

        assertThat(meters.activatedCount()).isEqualTo(12);
        assertThat(meters.notActivatedCount()).isEqualTo(4);
    }

    /**
     * ⚠ A failed read must leave the previous reading standing rather than zeroing it, and must not
     * fail the tick. Zeroing would make an unreachable database read as "every account vanished",
     * which is an outage-shaped number produced by a monitoring bug; leaving it standing is what the
     * {@code sampled.timestamp} gauge exists to make visible.
     */
    @Test
    void aFailedReadLeavesThePreviousReadingStandingAndCompletesAnyway() {
        when(userRepository.countByActivated(true)).thenReturn(Mono.just(12L));
        when(userRepository.countByActivated(false)).thenReturn(Mono.just(4L));
        StepVerifier.create(sampler().sampleOnce()).verifyComplete();

        when(userRepository.countByActivated(true)).thenReturn(Mono.error(new IllegalStateException("mongo is away")));
        // lenient: zip cancels the second source as soon as the first errors, so whether this stub is
        // ever reached is a Reactor scheduling detail rather than part of the contract under test.
        lenient().when(userRepository.countByActivated(false)).thenReturn(Mono.just(4L));

        StepVerifier.create(sampler().sampleOnce()).verifyComplete();

        assertThat(meters.activatedCount()).isEqualTo(12);
        assertThat(meters.notActivatedCount()).isEqualTo(4);
    }

    /**
     * Nothing is published before a read succeeds, which is the sampler's half of the meters'
     * absent-rather-than-zero rule: a gateway that has never reached MongoDB reports no series.
     */
    @Test
    void aStoreThatNeverAnswersPublishesNothingAtAll() {
        when(userRepository.countByActivated(true)).thenReturn(Mono.error(new IllegalStateException("mongo is away")));
        lenient()
            .when(userRepository.countByActivated(false))
            .thenReturn(Mono.error(new IllegalStateException("mongo is away")));

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RegistrationMetersService freshMeters = new RegistrationMetersService(registry);

        StepVerifier.create(new RegistrationMetricsSampler(userRepository, freshMeters).sampleOnce()).verifyComplete();

        assertThat(registry.find("account.registrations").gauges()).isEmpty();
    }
}
