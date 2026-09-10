package net.jojoaddison.service;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.jojoaddison.config.ApplicationProperties;
import net.jojoaddison.domain.LoginAttempt;
import net.jojoaddison.domain.LoginOutcome;
import net.jojoaddison.domain.User;
import net.jojoaddison.service.dto.AuthActivityDTO;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple4;

/**
 * The figures behind {@code GET /api/auth-activity}, counted from what is written down.
 *
 * <p><b>Nothing here is a counter and nothing is invented.</b> Both halves come from a collection:
 * the account split from {@code jhi_user}, everything else from {@code login_attempt}. That is
 * backlog item 75's "done when" condition — <em>every figure is derived from something actually
 * written down</em> — and it is why the recording had to exist before the screen could. The obvious
 * alternative, a Micrometer counter, is argued away in {@link LoginAttempt}'s javadoc: it would be
 * correct, invisible and untestable on this estate.
 *
 * <h2>⚠ The window is checked against retention, once, at startup</h2>
 *
 * <p>{@code window-days} and {@code retention-days} are independent properties and the failure when
 * they disagree is silent: with retention below the window, MongoDB deletes the older end of the
 * window and this endpoint reports a 30-day figure computed over whatever survived, indefinitely,
 * with nothing red anywhere. {@link #warnIfTheWindowOutrunsRetention()} is the whole of the defence,
 * and it is a log line rather than a refusal to start for the same reason
 * {@code LoginAttemptIndexes} does not fail the boot: an authentication gateway that will not start
 * is a worse outcome for the estate than a figure shorter than its caption.
 *
 * <h2>Reactive throughout, and no {@code block()} anywhere</h2>
 *
 * <p>This runs on a Netty event loop shared by every request on it. {@code ReactiveMongoTemplate}
 * and the driver underneath never block, and the four reads are composed with {@code zip} so they go
 * out concurrently — one round trip's latency rather than four, on a screen an administrator
 * refreshes.
 *
 * <h2>The aggregation results are read as {@link Document}s on purpose</h2>
 *
 * <p>Not mapped into records. Two of these pipelines group on a compound {@code _id}, and a mapping
 * failure there is silent in the worst direction — an unmapped field is a zero, and a zero on this
 * screen reads as "nothing is attacking us". Reading the keys by name is more code and it fails as a
 * missing key rather than as a plausible number.
 */
@Service
public class AuthActivityService {

    private static final Logger LOG = LoggerFactory.getLogger(AuthActivityService.class);

    /** The document fields, not the Java properties — {@link LoginAttempt} maps them explicitly. */
    private static final String ATTEMPTED_AT = "attempted_at";

    private static final String OUTCOME = "outcome";

    private static final String LOGIN = "login";

    private static final String DAY = "day";

    private static final String COUNT = "count";

    /** MongoDB's own name for a group key. */
    private static final String ID = "_id";

    private final ReactiveMongoTemplate mongoTemplate;

    /**
     * The three settings, copied rather than held as {@code ApplicationProperties.AuthActivity}.
     *
     * <p>{@code TechnicalStructureTest} forbids the service layer reaching {@code ..config..}, with
     * {@code ApplicationProperties} named as one of two exemptions — so holding the nested type would
     * work and would lean on an exemption for no reason. Three ints read once at construction is the
     * dependency this class actually has.
     */
    private final int windowDays;

    private final int retentionDays;

    private final int topFailedLogins;

    public AuthActivityService(ReactiveMongoTemplate mongoTemplate, ApplicationProperties applicationProperties) {
        this.mongoTemplate = mongoTemplate;
        this.windowDays = applicationProperties.getAuthActivity().getWindowDays();
        this.retentionDays = applicationProperties.getAuthActivity().getRetentionDays();
        this.topFailedLogins = applicationProperties.getAuthActivity().getTopFailedLogins();
    }

    /**
     * Warns, once, if the screen's window is longer than the store's retention.
     *
     * <p>At construction rather than inside {@link #activity()}: the condition cannot change without
     * a restart, and a warning on every request is a warning nobody reads.
     */
    @PostConstruct
    void warnIfTheWindowOutrunsRetention() {
        if (windowDays > retentionDays) {
            LOG.warn(
                "auth-activity asks for a {}-day window over a store that keeps {} days, so the sign-in figures " +
                    "report a short window under a long caption and nothing else says so. Raise " +
                    "application.auth-activity.retention-days or lower window-days.",
                windowDays,
                retentionDays
            );
        }
    }

    /** The whole response in one place — see {@link AuthActivityDTO} for what each half means. */
    public Mono<AuthActivityDTO> activity() {
        Instant from = Instant.now().minus(windowDays, ChronoUnit.DAYS);

        return Mono.zip(accounts(), totals(from), daily(from), failedLogins(from)).map(this::assemble);
    }

    private AuthActivityDTO assemble(
        Tuple4<
            AuthActivityDTO.GatewayAccounts,
            Map<LoginOutcome, Long>,
            List<AuthActivityDTO.DayCount>,
            List<AuthActivityDTO.FailedLogin>
        > parts
    ) {
        Map<LoginOutcome, Long> byOutcome = parts.getT2();
        AuthActivityDTO.LoginTotals logins = new AuthActivityDTO.LoginTotals(
            byOutcome.getOrDefault(LoginOutcome.SUCCEEDED, 0L),
            byOutcome.getOrDefault(LoginOutcome.FAILED, 0L),
            parts.getT3(),
            parts.getT4()
        );
        return new AuthActivityDTO(parts.getT1(), logins, windowDays, retentionDays);
    }

    /**
     * Console staff accounts, split by {@code User.activated}.
     *
     * <p>The total and the activated count, with {@code notActivated} as the <b>difference</b> rather
     * than a second predicate. Not a micro-optimisation: two independent predicates over a collection
     * that is being written to can disagree, and a chart whose two segments do not sum to the total
     * printed beside them is a defect this estate's dashboard has already had twice. Here they sum by
     * construction.
     */
    private Mono<AuthActivityDTO.GatewayAccounts> accounts() {
        Mono<Long> total = mongoTemplate.count(new Query(), User.class);
        Mono<Long> activated = mongoTemplate.count(new Query(Criteria.where("activated").is(true)), User.class);

        return Mono.zip(total, activated).map(counts ->
            new AuthActivityDTO.GatewayAccounts(counts.getT2(), counts.getT1() - counts.getT2())
        );
    }

    /** How many attempts of each outcome fell inside the window. */
    private Mono<Map<LoginOutcome, Long>> totals(Instant from) {
        Aggregation aggregation = Aggregation.newAggregation(
            Aggregation.match(Criteria.where(ATTEMPTED_AT).gte(from)),
            Aggregation.group(OUTCOME).count().as(COUNT)
        );

        return mongoTemplate
            .aggregate(aggregation, LoginAttempt.class, Document.class)
            .collectList()
            .map(rows -> {
                Map<LoginOutcome, Long> byOutcome = new EnumMap<>(LoginOutcome.class);
                for (Document row : rows) {
                    outcomeOf(row.getString(ID)).ifPresent(outcome -> byOutcome.merge(outcome, count(row), Long::sum));
                }
                return byOutcome;
            });
    }

    /**
     * One point per day of the window, oldest first, with the empty days filled in.
     *
     * <p>The aggregation returns only the days something happened, so this walks the window and looks
     * each day up — {@code AuthActivityDTO.DayCount} says why an omitted day is worse than a zero.
     * The walk is over {@code windowDays} entries, bounded by configuration rather than by data.
     *
     * <p>The day is cut in <b>UTC</b>, by {@code $dateToString} with an explicit timezone rather than
     * by the server's default. A series bucketed in one zone and labelled in another is off by one
     * for part of every day and says so nowhere.
     */
    private Mono<List<AuthActivityDTO.DayCount>> daily(Instant from) {
        Aggregation aggregation = Aggregation.newAggregation(Aggregation.match(Criteria.where(ATTEMPTED_AT).gte(from)), context ->
            new Document(
                "$group",
                new Document(ID, new Document(DAY, dateToDay()).append(OUTCOME, "$" + OUTCOME)).append(COUNT, new Document("$sum", 1))
            )
        );

        return mongoTemplate
            .aggregate(aggregation, LoginAttempt.class, Document.class)
            .collectList()
            .map(rows -> fillTheGaps(from, rows));
    }

    /** {@code $dateToString} over {@code attempted_at}, pinned to UTC. */
    private static Document dateToDay() {
        return new Document(
            "$dateToString",
            new Document("format", "%Y-%m-%d").append("date", "$" + ATTEMPTED_AT).append("timezone", "UTC")
        );
    }

    private List<AuthActivityDTO.DayCount> fillTheGaps(Instant from, List<Document> rows) {
        Map<String, Map<LoginOutcome, Long>> byDay = new HashMap<>();
        for (Document row : rows) {
            Document key = row.get(ID, Document.class);
            if (key == null) {
                continue;
            }
            String day = key.getString(DAY);
            long count = count(row);
            outcomeOf(key.getString(OUTCOME)).ifPresent(outcome ->
                byDay.computeIfAbsent(day, ignored -> new EnumMap<>(LoginOutcome.class)).merge(outcome, count, Long::sum)
            );
        }

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<AuthActivityDTO.DayCount> series = new ArrayList<>();
        for (LocalDate day = from.atZone(ZoneOffset.UTC).toLocalDate(); !day.isAfter(today); day = day.plusDays(1)) {
            Map<LoginOutcome, Long> counts = byDay.getOrDefault(day.toString(), Map.of());
            series.add(
                new AuthActivityDTO.DayCount(
                    day,
                    counts.getOrDefault(LoginOutcome.SUCCEEDED, 0L),
                    counts.getOrDefault(LoginOutcome.FAILED, 0L)
                )
            );
        }
        return series;
    }

    /**
     * The logins most often failed against inside the window, largest first.
     *
     * <p>Sorted and limited <b>in the aggregation</b> rather than after it. Doing it in Java would
     * mean shipping one row per distinct login ever attempted — a projection of an attacker-controlled
     * field, bounded by nothing but this collection's own retention — into the gateway's heap in order
     * to throw all but ten of them away.
     */
    private Mono<List<AuthActivityDTO.FailedLogin>> failedLogins(Instant from) {
        Aggregation aggregation = Aggregation.newAggregation(
            Aggregation.match(Criteria.where(ATTEMPTED_AT).gte(from).and(OUTCOME).is(LoginOutcome.FAILED.name())),
            Aggregation.group(LOGIN).count().as(COUNT),
            Aggregation.sort(Sort.Direction.DESC, COUNT),
            Aggregation.limit(topFailedLogins)
        );

        return mongoTemplate
            .aggregate(aggregation, LoginAttempt.class, Document.class)
            .collectList()
            .map(rows ->
                rows
                    .stream()
                    .filter(row -> row.getString(ID) != null)
                    .map(row -> failedLogin(row))
                    .toList()
            );
    }

    private static AuthActivityDTO.FailedLogin failedLogin(Document row) {
        return new AuthActivityDTO.FailedLogin(row.getString(ID), count(row));
    }

    /**
     * {@code $sum} answers with whichever numeric type MongoDB felt like, so this reads a
     * {@link Number} rather than casting. A {@code ClassCastException} here would surface as a broken
     * screen; a wrong number would not surface at all.
     */
    private static long count(Document row) {
        Object count = row.get(COUNT);
        return count instanceof Number number ? number.longValue() : 0L;
    }

    /**
     * The stored outcome, or empty for a value this enum does not have.
     *
     * <p>Empty rather than an exception: a row written by a newer version of this application is a
     * reason to under-report by one, not a reason for the whole screen to 500.
     */
    private static java.util.Optional<LoginOutcome> outcomeOf(String stored) {
        if (stored == null) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(LoginOutcome.valueOf(stored));
        } catch (IllegalArgumentException unknown) {
            return java.util.Optional.empty();
        }
    }
}
