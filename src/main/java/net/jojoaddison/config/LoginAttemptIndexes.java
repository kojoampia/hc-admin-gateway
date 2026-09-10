package net.jojoaddison.config;

import java.time.Duration;
import net.jojoaddison.domain.LoginAttempt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

/**
 * The one index this gateway creates: the TTL index that <b>is</b> {@code login_attempt}'s retention.
 *
 * <h2>Why an index rather than a scheduled sweep</h2>
 *
 * <p>{@link LoginAttempt}'s fourth safeguard is a bounded lifetime for a collection of
 * attacker-supplied strings, and the difference between the two mechanisms is what happens when
 * nobody is watching. A {@code @Scheduled} delete is code that can be disabled by a profile, skipped
 * by a container that is restarted every night before it fires, or silently swallowed — and its
 * failure looks exactly like "nothing to delete". A TTL index is a property of the collection: it
 * survives a redeploy, it applies to rows written by anything, and it is visible to anyone with a
 * shell as {@code db.login_attempt.getIndexes()}.
 *
 * <h2>It is created here rather than declared on the document</h2>
 *
 * <p>The same reason hc-admin-service's {@code DirectoryLinkIndexes} gives, and it holds harder in
 * this repository: {@code spring.data.mongodb.auto-index-creation} is set in no configuration file
 * here, and its default has been {@code false} since Spring Data MongoDB 3.0. So the {@code @Indexed}
 * annotations already on {@link net.jojoaddison.domain.User}'s {@code login} and {@code email} are
 * <b>comments rather than constraints</b>, and an {@code @Indexed(expireAfter = …)} added to
 * {@code LoginAttempt} would be a retention policy that reads as enforced and is not. Switching
 * auto-index-creation on globally to fix that would create indexes on {@code jhi_user} and
 * {@code jhi_authority} that nobody has thought about, at startup, in every environment. One explicit
 * index for one collection is the smaller change.
 *
 * <h2>⚠ Changing {@code retention-days} does not change the index, and this says so at ERROR</h2>
 *
 * <p>MongoDB refuses {@code createIndex} for an existing key with a different
 * {@code expireAfterSeconds} — {@code IndexOptionsConflict}, error 85 — rather than updating it. So
 * an operator who edits the property, restarts, and reads a green log would otherwise be running the
 * old retention indefinitely, believing they had shortened it. That is the failure this class exists
 * to make loud: the message below names {@code collMod} and the value it was asked for.
 *
 * <p><b>A failure is reported, never fatal.</b> The first screen of this console is behind a login,
 * so refusing to start on an index problem turns a retention question into a total authentication
 * outage for the whole estate. The state after a failure is the state before this class existed —
 * rows accumulate and a window query is a collection scan — which is worth an ERROR and is not worth
 * an outage.
 */
@Component
public class LoginAttemptIndexes {

    /** Named rather than left to MongoDB's field-concatenation default, so a reader can find it. */
    static final String RETENTION_INDEX = "login_attempt_retention";

    private static final Logger LOG = LoggerFactory.getLogger(LoginAttemptIndexes.class);

    private final ReactiveMongoTemplate mongoTemplate;

    private final ApplicationProperties applicationProperties;

    public LoginAttemptIndexes(ReactiveMongoTemplate mongoTemplate, ApplicationProperties applicationProperties) {
        this.mongoTemplate = mongoTemplate;
        this.applicationProperties = applicationProperties;
    }

    /**
     * Creates the index if it is not there, on a started application.
     *
     * <p>Idempotent for an identical specification, so this runs on every boot and does nothing on
     * all but the first — see the class javadoc for the one specification change it cannot make.
     *
     * <p><b>It also serves the reads.</b> A TTL index is an ordinary single-field index that MongoDB
     * additionally sweeps, so {@code attempted_at >= window} — every query {@code AuthActivityService}
     * issues — is served by it. There is deliberately no second index: one more on
     * {@code (outcome, attempted_at)} would speed a group-by over a collection whose size this very
     * index bounds, at the cost of a write on every sign-in attempt on the console.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void createRetentionIndex() {
        int retentionDays = applicationProperties.getAuthActivity().getRetentionDays();
        try {
            mongoTemplate
                .indexOps(LoginAttempt.class)
                .createIndex(
                    new Index().on("attempted_at", Sort.Direction.ASC).expire(Duration.ofDays(retentionDays)).named(RETENTION_INDEX)
                )
                .block();
            LOG.debug("login_attempt rows expire {} days after attempted_at", retentionDays);
        } catch (RuntimeException e) {
            LOG.error(
                "Could not create the TTL index {} on login_attempt for {} days. Until it exists, NOTHING deletes " +
                    "these rows — the collection holds logins as they were entered and grows at the rate somebody can " +
                    "post to /api/authenticate. If this is an IndexOptionsConflict the index already exists with a " +
                    "different lifetime and MongoDB will not replace it: change it in place with " +
                    "db.runCommand({collMod: 'login_attempt', index: {name: '{}', expireAfterSeconds: {}}}).",
                RETENTION_INDEX,
                retentionDays,
                RETENTION_INDEX,
                Duration.ofDays(retentionDays).toSeconds(),
                e
            );
        }
    }
}
