package net.jojoaddison.management;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The sign-in counters behind the Grafana dashboard.
 *
 * <p>The last case is the one that matters most and is not about arithmetic: it asserts that this
 * meter family's <b>whole</b> label space is two literal strings. A metric label is a series key in a
 * store shared by six products, so a subject in one is unbounded cardinality and an identifier
 * outside every safeguard {@code LoginAttempt} argues for at once.
 */
class LoginMetersServiceTest {

    private static final String LOGINS_METER_EXPECTED_NAME = "security.authentication.logins";

    private MeterRegistry meterRegistry;

    private LoginMetersService loginMetersService;

    @BeforeEach
    void setup() {
        meterRegistry = new SimpleMeterRegistry();
        loginMetersService = new LoginMetersService(meterRegistry);
    }

    @Test
    void bothOutcomeCountersAreCreatedUpFront() {
        meterRegistry.get(LOGINS_METER_EXPECTED_NAME).tag("outcome", "success").counter();
        meterRegistry.get(LOGINS_METER_EXPECTED_NAME).tag("outcome", "refused").counter();

        Collection<Counter> counters = meterRegistry.find(LOGINS_METER_EXPECTED_NAME).counters();

        assertThat(counters).hasSize(2);
    }

    /**
     * The name the exporter publishes is {@code security_authentication_logins_total}, and it only
     * comes out that way because the base unit repeats the last word of the name — a unit the
     * translation does not already see gets appended, which is how hc-patient's failure family became
     * {@code …_failed_logins_failures_total}. Pinned here because a change to either string silently
     * renames the series every dashboard panel selects.
     */
    @Test
    void theMeterCarriesTheBaseUnitTheExporterExpects() {
        Meter.Id id = meterRegistry.get(LOGINS_METER_EXPECTED_NAME).tag("outcome", "success").counter().getId();

        assertThat(id.getBaseUnit()).isEqualTo("logins");
        assertThat(id.getName()).isEqualTo("security.authentication.logins");
    }

    @Test
    void trackingASuccessIncrementsOnlyTheSuccessCounter() {
        loginMetersService.trackLoginSuccess();

        assertThat(counterFor("success").count()).isEqualTo(1);
        assertThat(counterFor("refused").count()).isZero();
    }

    @Test
    void trackingARefusalIncrementsOnlyTheRefusedCounter() {
        loginMetersService.trackLoginRefused();

        assertThat(counterFor("refused").count()).isEqualTo(1);
        assertThat(counterFor("success").count()).isZero();
    }

    /**
     * ⚠ The hard constraint of backlog item 80, asserted on the registry rather than trusted to the
     * call sites: this family has exactly one dimension and exactly two values in it, both compile-time
     * constants. A third value arriving here — an exception class, a login, a message — is the defect
     * this case exists to catch, and it would be invisible in every other assertion in this file.
     */
    @Test
    void theLabelSpaceIsTwoLiteralsAndNothingElse() {
        loginMetersService.trackLoginSuccess();
        loginMetersService.trackLoginRefused();

        List<Tag> tags = meterRegistry
            .find(LOGINS_METER_EXPECTED_NAME)
            .counters()
            .stream()
            .flatMap(counter -> counter.getId().getTags().stream())
            .toList();

        assertThat(tags).extracting(Tag::getKey).containsOnly("outcome");
        assertThat(tags).extracting(Tag::getValue).containsExactlyInAnyOrder("success", "refused");
    }

    private Counter counterFor(String outcome) {
        return meterRegistry.get(LOGINS_METER_EXPECTED_NAME).tag("outcome", outcome).counter();
    }
}
