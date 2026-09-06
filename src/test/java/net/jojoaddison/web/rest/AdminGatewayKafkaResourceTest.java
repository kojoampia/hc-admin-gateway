package net.jojoaddison.web.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import net.jojoaddison.broker.KafkaConsumer;
import net.jojoaddison.broker.OutboundEventPublisher;
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

    /**
     * Records the publisher's tasks without running them, which is what lets the case below assert
     * that the response does not wait for the broker — see {@code OutboundEventPublisherTest}.
     */
    private final List<Runnable> queued = new ArrayList<>();

    private final AdminGatewayKafkaResource resource = new AdminGatewayKafkaResource(
        new OutboundEventPublisher(streamBridge, queued::add),
        kafkaConsumer
    );

    /**
     * The binding name is asserted literally, not just "something was sent".
     *
     * <p>{@code StreamBridge.send} takes the binding as a string, so a typo or a rename in
     * {@code application.yml} is not a compile error — it is a message published to a binding
     * nothing consumes, with a successful {@code 204} returned to the caller. The api hit exactly
     * this: hc-admin-service#40 was "Give binding-out-0 the destination it was missing".
     *
     * <p>The 204 is now asserted <b>before</b> the send has run at all, which is the second thing this
     * case pins: since 2026-09-06 the handler hands the publish to an executor rather than doing it on
     * the event loop, because creating the output binding against an unreachable broker blocks for up
     * to a minute there (backlog item 39a).
     */
    @Test
    void publishesToTheBindingTheConfigurationDeclaresWithoutWaitingForIt() {
        when(streamBridge.send(eq("binding-out-0"), eq("hello"))).thenReturn(true);

        var response = resource.publish("hello").block(Duration.ofSeconds(5));

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verifyNoInteractions(streamBridge);

        queued.forEach(Runnable::run);
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
