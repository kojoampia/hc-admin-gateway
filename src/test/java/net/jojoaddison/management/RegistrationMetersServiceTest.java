package net.jojoaddison.management;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The account-population gauges, including the property that is easiest to lose: <b>absent rather
 * than zero</b> until the store has actually been read.
 */
class RegistrationMetersServiceTest {

    private static final String REGISTRATIONS_METER_EXPECTED_NAME = "account.registrations";
    private static final String SAMPLED_AT_METER_EXPECTED_NAME = "account.registrations.sampled.timestamp";

    private MeterRegistry meterRegistry;

    private RegistrationMetersService registrationMetersService;

    @BeforeEach
    void setup() {
        meterRegistry = new SimpleMeterRegistry();
        registrationMetersService = new RegistrationMetersService(meterRegistry);
    }

    /**
     * A gateway that cannot reach MongoDB must publish <em>no series</em>, not a confident zero.
     * "There are no accounts" and "nobody has looked" are different facts and a dashboard cannot tell
     * them apart once they arrive as the same number.
     */
    @Test
    void nothingIsRegisteredBeforeTheFirstSuccessfulSample() {
        assertThat(meterRegistry.find(REGISTRATIONS_METER_EXPECTED_NAME).gauges()).isEmpty();
        assertThat(meterRegistry.find(SAMPLED_AT_METER_EXPECTED_NAME).gauge()).isNull();
    }

    @Test
    void aSampleRegistersBothStatesAndTheTimestamp() {
        long before = Instant.now().getEpochSecond();

        registrationMetersService.recordPopulation(7, 3);

        assertThat(gaugeFor("activated").value()).isEqualTo(7);
        assertThat(gaugeFor("not-activated").value()).isEqualTo(3);
        assertThat(meterRegistry.get(SAMPLED_AT_METER_EXPECTED_NAME).gauge().value()).isGreaterThanOrEqualTo(before);
    }

    /**
     * There are two states and there is no third one. {@code User.activated} is a primitive
     * {@code boolean}, so "nobody has said" is not reachable here — unlike hc-admin-service's
     * directory metric, whose third bucket is real. Pinned so the two families cannot quietly
     * converge.
     */
    @Test
    void thereAreExactlyTwoStates() {
        registrationMetersService.recordPopulation(1, 1);

        assertThat(meterRegistry.find(REGISTRATIONS_METER_EXPECTED_NAME).gauges())
            .extracting(gauge -> gauge.getId().getTag("state"))
            .containsExactlyInAnyOrder("activated", "not-activated");
    }

    @Test
    void aLaterSampleMovesTheSameGaugesRatherThanRegisteringMore() {
        registrationMetersService.recordPopulation(7, 3);
        registrationMetersService.recordPopulation(9, 1);

        assertThat(meterRegistry.find(REGISTRATIONS_METER_EXPECTED_NAME).gauges()).hasSize(2);
        assertThat(gaugeFor("activated").value()).isEqualTo(9);
        assertThat(gaugeFor("not-activated").value()).isEqualTo(1);
    }

    /** The unit is what turns {@code account.registrations} into {@code account_registrations_accounts} on the wire. */
    @Test
    void theGaugesCarryTheBaseUnitTheExporterExpects() {
        registrationMetersService.recordPopulation(1, 0);

        assertThat(gaugeFor("activated").getId().getBaseUnit()).isEqualTo("accounts");
        assertThat(meterRegistry.get(SAMPLED_AT_METER_EXPECTED_NAME).gauge().getId().getBaseUnit()).isEqualTo("seconds");
    }

    private Gauge gaugeFor(String state) {
        return meterRegistry.get(REGISTRATIONS_METER_EXPECTED_NAME).tag("state", state).gauge();
    }
}
