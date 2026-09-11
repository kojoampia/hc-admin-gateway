package net.jojoaddison.management;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

/**
 * How many sign-ins this gateway granted and how many it refused — the time series behind the
 * Grafana dashboard backlog item 80 added.
 *
 * <h2>Why a counter as well as {@code login_attempt}, when item 75 chose a collection</h2>
 *
 * <p>Item 75 rejected Micrometer on a measurement that turned out to be wrong: it read the estate's
 * *"no {@code -javaagent} anywhere"* finding as *"no application metrics anywhere"*. Both halves of
 * that were checked again for item 80 and the first reproduces while the second does not — Mimir
 * holds 189 distinct metric names for {@code healthconnect/hc-admin-gateway}, because
 * {@code quality/compose.yml} exports OTLP through Micrometer and Spring Boot 4 needs no agent to do
 * it. So a counter here is neither invisible nor untestable.
 *
 * <p><b>The collection is still the source of truth and this is a projection beside it</b>, and the
 * division of labour is the safety property rather than a preference. {@link
 * net.jojoaddison.domain.LoginAttempt} carries <em>the login exactly as it was entered</em>, under
 * four safeguards — {@code ROLE_ADMIN} alone, never into a log, capped, TTL-bounded. <b>Not one of
 * those four protects a Prometheus label.</b> A metric leaves this process on a 60-second OTLP push
 * into a store nothing in this repository controls, read by every product on the box, and a label is
 * a series key: an attacker-supplied login in one is unbounded cardinality <em>and</em> item 43's
 * breach through a door item 43 never looked at. So the counters below carry <b>no subject at all</b>
 * — one bounded dimension, two literal values, both compile-time constants — and the
 * exact-login detail stays behind {@code GET /api/auth-activity} where its argument was made.
 *
 * <h2>The label values were read out of Mimir, not chosen</h2>
 *
 * <p>{@code security.authentication.logins} already exists on two sibling gateways, and the
 * {@code outcome} dimension takes exactly {@code success} and {@code refused} there — measured on
 * 2026-09-11 against the live quality Mimir rather than derived from their source, because the
 * exporter appends a base unit to some names and not others and only the store knows which. Adopting
 * {@code refused} rather than the more obvious {@code failed} is what makes
 * {@code sum by (outcome) (security_authentication_logins_total)} one query across three products
 * instead of a union of spellings.
 *
 * <h2>⚠ There is deliberately no {@code security.authentication.failed-logins{cause}} here</h2>
 *
 * <p>hc-patient publishes one and this gateway does not, because <b>this gateway does not know the
 * cause and has decided not to</b>. {@link net.jojoaddison.domain.LoginOutcome} has two values and
 * its javadoc argues why: an unknown login, a wrong password and a deactivated account are refused
 * identically by {@code /api/authenticate} so that the endpoint is not an oracle for which logins
 * exist, and a store — or a metric — that drew the distinction the endpoint refuses to draw would
 * undo that one layer down. Publishing the family anyway with a single synthetic cause would be a
 * second metric name for a number {@code outcome="refused"} already carries, which is the
 * two-copies-that-drift shape this backlog keeps closing.
 *
 * <h2>Why a sibling of {@link SecurityMetersService} rather than four more methods on it</h2>
 *
 * <p>That class is generator output wired to the JWT decoder, and every constant on it names one
 * meter with base unit {@code errors}. These have a different base unit and a different call graph —
 * the sign-in endpoint, not the token filter. The <em>shape</em> is copied deliberately: name
 * constants, a builder helper, one {@code track…()} per case.
 *
 * <h2>Counters, and per attempt</h2>
 *
 * <p>Both are monotonic counters over attempts, so {@code increase(…[5m])} of one sits beside
 * {@code increase(…[5m])} of the other on one panel over one window. That is the comparison the
 * dashboard was asked for, and it is the reason a gauge of "live sessions" was not built instead: a
 * level and an accumulation do not share an axis.
 */
@Service
public class LoginMetersService {

    public static final String LOGINS_METER_NAME = "security.authentication.logins";
    public static final String LOGINS_METER_DESCRIPTION = "Counts sign-in attempts by outcome, one per attempt.";
    public static final String LOGINS_METER_BASE_UNIT = "logins";
    public static final String LOGINS_METER_OUTCOME_DIMENSION = "outcome";

    /** A token was minted. */
    public static final String OUTCOME_SUCCESS = "success";

    /**
     * No token was minted, for any reason.
     *
     * <p>{@code refused} rather than {@code failed} because that is the value hc-professional's
     * gateway already publishes on this metric name — see the class javadoc.
     */
    public static final String OUTCOME_REFUSED = "refused";

    private final Counter loginSuccessCounter;
    private final Counter loginRefusedCounter;

    public LoginMetersService(MeterRegistry registry) {
        this.loginSuccessCounter = loginsCounterForOutcomeBuilder(OUTCOME_SUCCESS).register(registry);
        this.loginRefusedCounter = loginsCounterForOutcomeBuilder(OUTCOME_REFUSED).register(registry);
    }

    private Counter.Builder loginsCounterForOutcomeBuilder(String outcome) {
        return Counter.builder(LOGINS_METER_NAME)
            .baseUnit(LOGINS_METER_BASE_UNIT)
            .description(LOGINS_METER_DESCRIPTION)
            .tag(LOGINS_METER_OUTCOME_DIMENSION, outcome);
    }

    /** A sign-in minted a token. Per attempt: one person signing in three times is three. */
    public void trackLoginSuccess() {
        this.loginSuccessCounter.increment();
    }

    /** A sign-in was refused. Per attempt, and carrying neither the login nor the reason. */
    public void trackLoginRefused() {
        this.loginRefusedCounter.increment();
    }
}
