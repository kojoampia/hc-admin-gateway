package net.jojoaddison.service;

import net.jojoaddison.management.RegistrationMetersService;
import net.jojoaddison.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Reads the account population off the user store on a timer and hands it to
 * {@link RegistrationMetersService}.
 *
 * <h2>⚠ Why this is a sampler and not a gauge closure</h2>
 *
 * <p>The obvious shape — {@code Gauge.builder(name, () -> userRepository.countByActivated(true).block())}
 * — is a defect twice over in this application. Micrometer polls a gauge's function on whichever
 * thread is exporting, so the two counts would run on the export path and every export would pay for
 * two database round trips; and {@code block()} anywhere in a WebFlux gateway is one refactor away
 * from a Netty event loop, which BlockHound fails the build for and rightly. Sampling on a scheduled
 * thread and letting the gauges read a plain {@code AtomicLong} keeps the query off both.
 *
 * <h2>Why it lives in {@code service} and the meters live in {@code management}</h2>
 *
 * <p>{@code TechnicalStructureTest}'s layer rule lets {@code Service} reach {@code Persistence} and
 * lets nothing outside the declared layers reach it at all — {@code ..management..} is not a declared
 * layer, so a metrics class that injected {@code UserRepository} would fail the build. That split is
 * not tidiness: the query is a read of domain data and belongs where this codebase puts reads, and
 * the meter definitions stay where every other meter definition in this repository is.
 *
 * <h2>Cost</h2>
 *
 * <p>Two counted queries a minute against a collection holding the console's staff accounts. A minute
 * is well inside the resolution anybody reads this dashboard at — the population moves by single
 * accounts an administrator creates by hand — and it is a fixed 2,880 queries a day rather than two
 * per export, which is what a gauge closure would have cost.
 */
@Component
public class RegistrationMetricsSampler {

    private static final Logger LOG = LoggerFactory.getLogger(RegistrationMetricsSampler.class);

    /** Long enough for Mongock's change units to have run, short enough that a restart is not a visible gap. */
    static final long INITIAL_DELAY_MS = 20_000;

    static final long INTERVAL_MS = 60_000;

    private final UserRepository userRepository;

    private final RegistrationMetersService registrationMetersService;

    public RegistrationMetricsSampler(UserRepository userRepository, RegistrationMetersService registrationMetersService) {
        this.userRepository = userRepository;
        this.registrationMetersService = registrationMetersService;
    }

    /**
     * The scheduled tick. Subscribes and returns; the counts run on the driver's own threads.
     *
     * <p>The interval is a constant rather than a configuration property on purpose. It is not a knob
     * an operator turns after an incident — the two {@code application.auth-activity} properties are
     * that, and they exist for that reason — and adding a key here would mean writing its default in
     * two places, which is the shape item 58 closed.
     *
     * <p>Declared {@code void} on purpose. Spring subscribes to a reactive return value from
     * {@code @Scheduled} itself, so returning the {@link Mono} <em>and</em> subscribing to it here
     * would run every tick twice — once per subscriber, since this pipeline is cold.
     */
    @Scheduled(initialDelay = INITIAL_DELAY_MS, fixedDelay = INTERVAL_MS)
    public void sample() {
        sampleOnce().subscribe();
    }

    /**
     * One reading of the account population, as a cold {@link Mono} a test can await rather than
     * sleep on.
     *
     * <p>A failure updates nothing, which leaves the previous reading standing and the timestamp
     * gauge falling behind — that pairing is what makes a dead sampler visible instead of making it
     * look like a flat number.
     *
     * <p><b>The warning names the exception's class and not the exception</b>, which is stricter than
     * this particular failure needs and is deliberate anyway. A counted query interpolates no user
     * document, so there is nothing identifying to leak here today — but that is a property of the
     * two calls above rather than of this log statement, and {@code LoginAttemptRecorder} argues at
     * length that a MongoDB failure's message embeds what it could not write. One rule in this
     * repository is worth more than two, one of which holds by luck.
     *
     * @return completion, whether the read succeeded or not.
     */
    Mono<Void> sampleOnce() {
        return userRepository
            .countByActivated(true)
            .zipWith(userRepository.countByActivated(false))
            .doOnNext(counts -> registrationMetersService.recordPopulation(counts.getT1(), counts.getT2()))
            .doOnError(error ->
                LOG.warn("Could not read the account population for the registration gauges ({}).", error.getClass().getName())
            )
            .onErrorComplete()
            .then();
    }
}
