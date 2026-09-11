package net.jojoaddison.management;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Service;

/**
 * How many accounts exist on this gateway right now, split by whether they have ever been activated.
 *
 * <h2>⚠ These are the console's own staff accounts, and the dashboard must say so</h2>
 *
 * <p><b>This is not the estate's registration figure and is roughly two orders of magnitude smaller
 * than it.</b> There is no self-registration on this gateway at all — {@code /api/register} and
 * {@code /api/activate} were removed, and {@code SecurityConfiguration} records why — so every row
 * counted here was created by an administrator through {@code /api/admin/users}. The number an
 * operator usually means by "registrations" is patients and clinicians signing up on hc-patient and
 * hc-professional, which arrives over Kafka and is counted by hc-admin-service's
 * {@code directory.registrations} instead.
 *
 * <p>Item 75's first decision is the one being obeyed here: the two readings are different
 * populations, and presenting them as one figure makes the staff-account number look alarmingly
 * small. They share the dashboard and are on separate rows with separate captions, under separate
 * metric names, deliberately.
 *
 * <h2>The name is hc-patient's, and that was decided rather than drifted into</h2>
 *
 * <p>{@code account.registrations{state}} is what hc-patient's gateway publishes for exactly this
 * population — its own {@code User} store, split on {@code activated}. hc-professional publishes the
 * same two numbers under {@code security.registration.accounts}, which is more internally consistent
 * with the {@code security.*} family and is <b>not</b> what this follows: a panel comparing the three
 * gateways has to select one series name, and matching the majority spelling costs nothing where a
 * union of two costs every panel that is ever written against it. Publishing both names was also
 * considered and rejected — two names for one number is two things to drift.
 *
 * <h2>Two states, and there is no third one here</h2>
 *
 * <p>{@code User.activated} is a primitive {@code boolean}, so "nobody has said" is not a state this
 * store can be in. That is the whole difference from hc-admin-service's directory metric, whose
 * {@code DirectoryLink.activated} is a boxed {@code Boolean} and whose third bucket is real. The two
 * must not be folded into one metric name for that reason among others.
 *
 * <p>{@code not-activated} means <b>never</b> activated: nothing in this gateway sets the flag back
 * to false, so the count only falls when an account activates or is deleted. Read it as a backlog of
 * accounts an administrator created and nobody ever signed into, not as an alert — a threshold on the
 * absolute number is a threshold on how long the console has been live.
 *
 * <h2>Absent rather than zero, and a timestamp so stale is not read as healthy</h2>
 *
 * <p>Nothing is registered until the store has been read once, so a gateway that cannot reach MongoDB
 * reports <em>no series</em> rather than a confident zero — "there are no accounts" and "nobody has
 * looked" are different facts and must not arrive as the same number. {@code
 * account.registrations.sampled.timestamp} carries when the last successful read happened, because a
 * gauge that stops updating otherwise reads as a steady value rather than as a sampler that died.
 * Both rules are hc-patient's and are copied with their reasoning rather than their code.
 *
 * @see net.jojoaddison.service.RegistrationMetricsSampler the scheduled read that feeds this
 */
@Service
public class RegistrationMetersService {

    public static final String REGISTRATIONS_METER_NAME = "account.registrations";
    public static final String REGISTRATIONS_METER_DESCRIPTION =
        "Gateway accounts that exist right now, by whether they have ever been activated.";
    public static final String REGISTRATIONS_METER_BASE_UNIT = "accounts";
    public static final String REGISTRATIONS_METER_STATE_DIMENSION = "state";

    /** The account has been activated. Once true it stays true. */
    public static final String STATE_ACTIVATED = "activated";

    /** The account has never been activated. */
    public static final String STATE_NOT_ACTIVATED = "not-activated";

    /** Epoch seconds of the last successful sample, so a sampler that stopped is distinguishable from a flat number. */
    public static final String SAMPLED_AT_METER_NAME = "account.registrations.sampled.timestamp";
    public static final String SAMPLED_AT_METER_DESCRIPTION = "When the account population was last read from the user store.";
    public static final String SAMPLED_AT_METER_BASE_UNIT = "seconds";

    private final MeterRegistry registry;

    private final AtomicLong activated = new AtomicLong(0);
    private final AtomicLong notActivated = new AtomicLong(0);
    private final AtomicLong sampledAt = new AtomicLong(0);

    /** False until the first successful sample, which is what keeps an unread store off the dashboard entirely. */
    private final AtomicBoolean registered = new AtomicBoolean(false);

    public RegistrationMetersService(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * Publishes a fresh reading of the account population.
     *
     * <p>Called from the sampler's own thread, never from a request. The gauges read the {@link
     * AtomicLong}s when the registry is exported, so nothing queries MongoDB on the export path.
     *
     * @param activated accounts with {@code activated == true}.
     * @param notActivated accounts with {@code activated == false}.
     */
    public void recordPopulation(long activated, long notActivated) {
        this.activated.set(activated);
        this.notActivated.set(notActivated);
        this.sampledAt.set(Instant.now().getEpochSecond());
        registerOnce();
    }

    /** The last reading of the activated population, for tests. */
    public long activatedCount() {
        return activated.get();
    }

    /** The last reading of the never-activated population, for tests. */
    public long notActivatedCount() {
        return notActivated.get();
    }

    private void registerOnce() {
        if (!registered.compareAndSet(false, true)) {
            return;
        }
        registrationsGaugeForStateBuilder(STATE_ACTIVATED, activated).register(registry);
        registrationsGaugeForStateBuilder(STATE_NOT_ACTIVATED, notActivated).register(registry);
        Gauge.builder(SAMPLED_AT_METER_NAME, sampledAt, AtomicLong::doubleValue)
            .baseUnit(SAMPLED_AT_METER_BASE_UNIT)
            .description(SAMPLED_AT_METER_DESCRIPTION)
            .register(registry);
    }

    private Gauge.Builder<AtomicLong> registrationsGaugeForStateBuilder(String state, AtomicLong value) {
        return Gauge.builder(REGISTRATIONS_METER_NAME, value, AtomicLong::doubleValue)
            .baseUnit(REGISTRATIONS_METER_BASE_UNIT)
            .description(REGISTRATIONS_METER_DESCRIPTION)
            .tag(REGISTRATIONS_METER_STATE_DIMENSION, state);
    }
}
