package net.jojoaddison;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import net.jojoaddison.config.AsyncSyncConfiguration;
import net.jojoaddison.config.EmbeddedMongo;
import net.jojoaddison.config.JacksonConfiguration;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Base composite annotation for integration tests.
 *
 * <h2>Why there is no {@code @EmbeddedKafka} here</h2>
 *
 * <p>There was, from the generator's first commit, and it started a {@code confluentinc/cp-kafka}
 * container for <b>every</b> integration test in this repository, because
 * {@code KafkaTestContainersSpringContextCustomizerFactory} resolves the annotation with
 * {@code findMergedAnnotation}, which sees meta-annotations. Nothing said so; the customizer logs
 * "Warming up the kafka broker" per context and reads as a per-class decision.
 *
 * <p><b>This gateway declares no stream functions and no bindings at all</b> — {@code function
 * definition: ''} in both {@code src/main/resources/config/application.yml} and the test profile, which
 * is backlog item 40a's decision and not an omission — so the broker was not merely unasserted, it was
 * never contacted. Verified on a baseline run on 2026-09-09: the container took 11.5 seconds to start
 * and every {@code org.apache.kafka} line in the whole build came out of the container's own stdout
 * through {@code Slf4jLogConsumer}. Not one client-side line, on any of the fifteen classes that boot
 * a context. See docs/backlog.md item 17.
 *
 * <p>Nothing replaces it. There is no binder to substitute, because there is nothing bound;
 * hc-admin-service, which does have bindings, puts {@code TestChannelBinderConfiguration} on its own
 * copy of this annotation instead. {@code @EmbeddedKafka} itself still works and still starts a
 * container for a class that asks for one, and {@code BrokerOptInArchTest} asserts both that the
 * mechanism is intact and that nothing uses it.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(classes = { AdminGatewayApp.class, JacksonConfiguration.class, AsyncSyncConfiguration.class })
@EmbeddedMongo
public @interface IntegrationTest {
    // 5s is Spring's default
    // https://github.com/spring-projects/spring-framework/blob/main/spring-test/src/main/java/org/springframework/test/web/reactive/server/DefaultWebTestClient.java#L106
    String DEFAULT_TIMEOUT = "PT5S";

    String DEFAULT_ENTITY_TIMEOUT = "PT5S";
}
