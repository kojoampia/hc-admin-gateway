package net.jojoaddison.config;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import net.jojoaddison.IntegrationTest;
import net.jojoaddison.security.jwt.AuthenticationIntegrationTest;
import org.junit.jupiter.api.Test;

/**
 * Nothing here starts a Kafka broker, and the mechanism that would still works.
 *
 * <p>Two assertions, and they guard opposite mistakes. Backlog item 17. The same file is in
 * hc-admin-service, which has one composite annotation to this repository's two.
 *
 * <h2>Nothing opts in</h2>
 *
 * <p>{@code @EmbeddedKafka} sat on both {@code @IntegrationTest} and
 * {@code @AuthenticationIntegrationTest} from the generator's first commit and started a
 * {@code confluentinc/cp-kafka} container for every class that boots a context — for a gateway that
 * declares no stream functions and no bindings, in the test profile or in the one that ships, and
 * therefore never opens a client to it. Putting the annotation back on any class, directly or through
 * a composite, is a decision about what this suite is for rather than a tidy-up, so the rule fails and
 * says so. Stated on the meta-annotation because that is exactly how it hid the first time.
 *
 * <h2>The opt-in still resolves</h2>
 *
 * <p>A rule that forbids the annotation would go on passing if the wiring behind it quietly died, and
 * whoever next needs a real broker would find a fixture that silently gives them none. So the
 * resolution itself is asserted, including through a composite annotation, which is the case
 * {@code findMergedAnnotation} handles and a reader does not expect. This starts no container and
 * deliberately does not: exercising one end to end means paying for it on every run, which is the cost
 * this item removed. That gap is named rather than left implied — the end-to-end path was verified by
 * hand once, on 2026-09-09 in hc-admin-service, by restoring {@code @EmbeddedKafka} to a single class
 * and watching a container start for that class and for no other.
 *
 * <p><b>One thing that is true here and not in hc-admin-service.</b> There, adding {@code @EmbeddedKafka}
 * back to an {@code @IntegrationTest} class gets you a container that Spring Cloud Stream then ignores,
 * because that composite imports the in-memory binder and it goes on servicing every binding. This
 * gateway's composites import no binder, so the annotation really would put the Kafka binder in front
 * of a real broker. Do not carry that repository's extra step — excluding
 * {@code TestChannelBinderConfiguration} — over here. The class is on the test classpath (the pom keeps
 * the dependency) but nothing imports it, so the exclusion would subtract something that was never
 * added and read as though a binder had been dealt with when none was ever in the way.
 */
@AnalyzeClasses(packagesOf = IntegrationTest.class)
class BrokerOptInArchTest {

    // prettier-ignore
    @ArchTest
    static final ArchRule nothingAsksForARealBroker = noClasses()
        .that()
        // The fixtures below are the second assertion's subject and are meant to carry it.
        .doNotBelongToAnyOf(BrokerOptInArchTest.class)
        .should()
        .beMetaAnnotatedWith(EmbeddedKafka.class)
        .because(
            "@EmbeddedKafka starts a Kafka container for the whole test JVM and this gateway declares no "
            + "stream functions and no bindings, so nothing ever opens a client to it. If you genuinely need "
            + "one, say why here and accept that every context in the run pays for it (backlog item 17)"
        );

    @Test
    void theOptInStillResolvesDirectlyAndThroughAComposite() {
        assertThat(KafkaTestContainersSpringContextCustomizerFactory.wantsBroker(AsksDirectly.class)).isTrue();
        assertThat(KafkaTestContainersSpringContextCustomizerFactory.wantsBroker(AsksThroughAComposite.class)).isTrue();
        assertThat(KafkaTestContainersSpringContextCustomizerFactory.wantsBroker(AsksForNothing.class)).isFalse();
    }

    @Test
    void neitherCompositeAsksForOne() {
        assertThat(KafkaTestContainersSpringContextCustomizerFactory.wantsBroker(AnOrdinaryIntegrationTest.class)).isFalse();
        assertThat(KafkaTestContainersSpringContextCustomizerFactory.wantsBroker(AnAuthenticationIntegrationTest.class)).isFalse();
    }

    @EmbeddedKafka
    private static final class AsksDirectly {}

    @ComposedWithEmbeddedKafka
    private static final class AsksThroughAComposite {}

    private static final class AsksForNothing {}

    @IntegrationTest
    private static final class AnOrdinaryIntegrationTest {}

    @AuthenticationIntegrationTest
    private static final class AnAuthenticationIntegrationTest {}

    /**
     * Stands in for the two composites as they were until 2026-09-09: an annotation that carries
     * {@code @EmbeddedKafka} without saying so at the point of use.
     */
    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @EmbeddedKafka
    private @interface ComposedWithEmbeddedKafka {
    }
}
