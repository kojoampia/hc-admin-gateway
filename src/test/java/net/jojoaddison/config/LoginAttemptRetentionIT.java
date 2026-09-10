package net.jojoaddison.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.domain.LoginAttempt;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;

/**
 * {@link net.jojoaddison.domain.LoginAttempt}'s <b>fourth safeguard</b>: the collection expires
 * itself.
 *
 * <p>Asserted against a real MongoDB rather than against {@link LoginAttemptIndexes}' source,
 * because the property that matters is not "the code calls {@code expire}" — it is that the index
 * MongoDB is holding has a TTL on it. Those come apart in a way this class can see and a unit test
 * cannot: {@code createIndex} <b>refuses</b> to change {@code expireAfterSeconds} on an existing
 * index of the same key ({@code IndexOptionsConflict}), so correct code and a wrong retention is a
 * reachable state and is exactly the one {@code LoginAttemptIndexes} logs at {@code ERROR} about.
 *
 * <p>Why it needs a guard at all: this is the one safeguard with <b>no observable behaviour on any
 * timescale a test can wait for</b>. Delete the {@code expire(...)} call and every other test in
 * this repository still passes, the endpoint still answers, the screen still draws — and a
 * collection of logins as they were entered simply grows for ever. There is nothing to notice.
 */
@IntegrationTest
class LoginAttemptRetentionIT {

    @Autowired
    private ReactiveMongoTemplate mongoTemplate;

    @Autowired
    private ApplicationProperties applicationProperties;

    @Test
    void theAttemptCollectionExpiresItsOwnRows() {
        // The listener runs on ApplicationReadyEvent, which has already fired for this context.
        List<Document> indexes = mongoTemplate
            .execute(LoginAttempt.class, collection -> collection.listIndexes())
            .collectList()
            .block();

        assertThat(indexes).as("no indexes at all on login_attempt — the collection has never been written to").isNotNull();

        Document retention = indexes
            .stream()
            .filter(index -> LoginAttemptIndexes.RETENTION_INDEX.equals(index.getString("name")))
            .findFirst()
            .orElse(null);

        assertThat(retention)
            .as(
                "login_attempt has no index named %s, so NOTHING deletes these rows. The collection holds " +
                    "logins exactly as they were entered — LoginAttempt's fourth safeguard is this index and " +
                    "there is no sweep behind it.",
                LoginAttemptIndexes.RETENTION_INDEX
            )
            .isNotNull();

        assertThat(retention.get("expireAfterSeconds"))
            .as("the index exists but carries no TTL, which is an ordinary index wearing the retention index's name")
            .isNotNull();

        assertThat(((Number) retention.get("expireAfterSeconds")).longValue())
            .as("the TTL disagrees with application.auth-activity.retention-days")
            .isEqualTo(Duration.ofDays(applicationProperties.getAuthActivity().getRetentionDays()).toSeconds());

        assertThat(retention.get("key", Document.class).keySet())
            .as("the TTL must be on attempted_at — on any other field it expires rows by the wrong clock")
            .containsExactly("attempted_at");
    }

    /**
     * The window the screen reports must fit inside the retention the store enforces.
     *
     * <p>They are independent properties, and when they disagree the failure is silent in the
     * direction that matters: MongoDB deletes the older end of the window, and the endpoint reports a
     * short window under a long caption for ever. {@code AuthActivityService} warns at startup;
     * this fails the build, which is the right severity for a default nobody has overridden.
     */
    @Test
    void theDefaultWindowFitsInsideTheDefaultRetention() {
        ApplicationProperties.AuthActivity settings = applicationProperties.getAuthActivity();

        assertThat(settings.getWindowDays())
            .as("the auth-activity window is longer than the store keeps, so the figures cannot cover it")
            .isLessThanOrEqualTo(settings.getRetentionDays());
    }
}
