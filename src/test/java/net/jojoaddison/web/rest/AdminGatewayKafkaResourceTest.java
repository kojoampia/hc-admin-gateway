package net.jojoaddison.web.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import net.jojoaddison.broker.KafkaConsumer;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.http.HttpStatus;
import reactor.test.StepVerifier;

/**
 * The gateway's Kafka endpoints: publish a message, and stream what arrives.
 *
 * <p>Untested before this class, along with the rest of the broker package. The endpoint that
 * matters is {@code /consume} — it is the stream the console's live audit trail reads, and the
 * failure mode when it breaks is a widget that reports itself connected and shows nothing, which is
 * indistinguishable from a quiet system.
 */
class AdminGatewayKafkaResourceTest {

    private final StreamBridge streamBridge = mock(StreamBridge.class);
    private final KafkaConsumer kafkaConsumer = new KafkaConsumer();
    private final AdminGatewayKafkaResource resource = new AdminGatewayKafkaResource(streamBridge, kafkaConsumer);

    /**
     * The binding name is asserted literally, not just "something was sent".
     *
     * <p>{@code StreamBridge.send} takes the binding as a string, so a typo or a rename in
     * {@code application.yml} is not a compile error — it is a message published to a binding
     * nothing consumes, with a successful {@code 204} returned to the caller. The api hit exactly
     * this: hc-admin-service#40 was "Give binding-out-0 the destination it was missing".
     */
    @Test
    void publishesToTheBindingTheConfigurationDeclares() {
        when(streamBridge.send(eq("binding-out-0"), eq("hello"))).thenReturn(true);

        var response = resource.publish("hello").block(Duration.ofSeconds(5));

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(streamBridge).send("binding-out-0", "hello");
    }

    @Test
    void streamsWhatArrivesFromTheBroker() {
        StepVerifier.create(resource.consume())
            .then(() -> kafkaConsumer.accept("an audit entry"))
            .expectNext("an audit entry")
            .thenCancel()
            .verify(Duration.ofSeconds(5));
    }

    /**
     * The stream is the consumer's, not a fresh one per request. If {@code consume()} ever built its
     * own sink, every subscriber would get an empty stream that never errors — the widget stays
     * connected and silent forever.
     */
    @Test
    void theStreamIsTheConsumersOwn() {
        assertThat(resource.consume()).isSameAs(kafkaConsumer.getFlux());
    }
}
