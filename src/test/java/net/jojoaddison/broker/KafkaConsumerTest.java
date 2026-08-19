package net.jojoaddison.broker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/**
 * The gateway half of the realtime bridge: messages arriving from Kafka, fanned out to whoever is
 * streaming.
 *
 * <p>Neither this class nor {@link KafkaProducer} nor {@code AdminGatewayKafkaResource} had any test
 * at all — the whole broker package was uncovered on both the unit and integration side. The api has
 * a {@code KafkaConsumerTest}; this side had nothing, despite being what the console's live audit
 * trail actually reads from.
 *
 * <p>These are plain unit tests over the sink. The Kafka wiring itself — that {@code
 * spring.cloud.function.definition} names this bean, and that the binding is attached — is
 * configuration, and is better checked by the deployed stack than by starting a broker here.
 */
class KafkaConsumerTest {

    @Test
    void deliversAMessageToASubscriber() {
        KafkaConsumer consumer = new KafkaConsumer();

        StepVerifier.create(consumer.getFlux())
            .then(() -> consumer.accept("first"))
            .expectNext("first")
            .then(() -> consumer.accept("second"))
            .expectNext("second")
            .thenCancel()
            .verify(Duration.ofSeconds(5));
    }

    /**
     * <b>The sink is unicast and buffers before anyone subscribes.</b> Worth pinning: a message
     * accepted before the first subscriber arrives is held, not dropped. If this were swapped for a
     * multicast sink without a buffer — a plausible "fix" when someone wants a second subscriber —
     * every message that arrived during startup would vanish, and the symptom is an audit trail that
     * is merely missing its first few entries.
     */
    @Test
    void buffersMessagesThatArriveBeforeAnyoneIsListening() {
        KafkaConsumer consumer = new KafkaConsumer();

        consumer.accept("sent before subscribing");

        StepVerifier.create(consumer.getFlux()).expectNext("sent before subscribing").thenCancel().verify(Duration.ofSeconds(5));
    }

    /**
     * And the other half of that trade-off, stated so it is a decision rather than a surprise:
     * unicast permits exactly one subscriber. A second one is rejected. If the gateway ever needs to
     * serve two concurrent SSE clients from this bean, the sink has to change — and this test is
     * what will say so.
     */
    @Test
    void allowsOnlyOneSubscriberAtATime() {
        KafkaConsumer consumer = new KafkaConsumer();
        consumer.getFlux().subscribe();

        assertThatExceptionOfType(IllegalStateException.class).isThrownBy(() -> consumer.getFlux().blockFirst(Duration.ofSeconds(1)));
    }

    @Test
    void theFluxIsTheSameStreamEveryTimeItIsAskedFor() {
        KafkaConsumer consumer = new KafkaConsumer();

        assertThat(consumer.getFlux()).isNotNull();
        StepVerifier.create(consumer.getFlux())
            .then(() -> consumer.accept("only"))
            .expectNext("only")
            .thenCancel()
            .verify(Duration.ofSeconds(5));
    }
}
