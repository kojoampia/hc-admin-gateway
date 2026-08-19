package net.jojoaddison.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * The filter that hands the caller's token to the service behind the gateway.
 *
 * <p>It was at 7.8% coverage with no test of its own, and it is <b>live</b>: {@code
 * application.yml} declares it as a default filter under {@code
 * spring.cloud.gateway.server.webflux}. It used to sit one namespace level up, where the property
 * bound but nothing read it — so for a while the filter genuinely did nothing and its absence of
 * tests cost nothing. That is no longer true, and everything a downstream service knows about the
 * caller arrives through this class.
 *
 * <p>Unit-level rather than an IT on purpose. The behaviours that matter are all about one request:
 * whether the header is rewritten, whether an unsigned token gets through, whether an anonymous
 * request is passed along untouched. Booting a gateway and a downstream service to observe those
 * would test routing at the same time and report both as one result.
 */
class JWTRelayGatewayFilterFactoryTest {

    private static final String SECRET_TOKEN = "a.valid.token";

    private final ReactiveJwtDecoder jwtDecoder = mock(ReactiveJwtDecoder.class);
    private final JWTRelayGatewayFilterFactory factory = new JWTRelayGatewayFilterFactory(jwtDecoder);

    private static Jwt anyJwt() {
        return new Jwt(SECRET_TOKEN, Instant.now(), Instant.now().plusSeconds(60), Map.of("alg", "HS512"), Map.of("sub", "admin"));
    }

    /** Captures the exchange the chain was ultimately called with, so the outgoing headers can be read. */
    private static final class CapturingChain implements GatewayFilterChain {

        private final AtomicReference<ServerWebExchange> seen = new AtomicReference<>();

        @Override
        public Mono<Void> filter(ServerWebExchange exchange) {
            seen.set(exchange);
            return Mono.empty();
        }

        ServerWebExchange exchange() {
            return seen.get();
        }
    }

    @Test
    void relaysAValidTokenToTheDownstreamService() {
        when(jwtDecoder.decode(anyString())).thenReturn(Mono.just(anyJwt()));
        GatewayFilter filter = factory.apply((Object) null);
        CapturingChain chain = new CapturingChain();

        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/services/hcadminservice/api/professionals").header(AUTHORIZATION, "Bearer " + SECRET_TOKEN)
        );

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chain.exchange()).isNotNull();
        assertThat(chain.exchange().getRequest().getHeaders().getFirst(AUTHORIZATION)).isEqualTo("Bearer " + SECRET_TOKEN);
    }

    /**
     * <b>The token is verified before it is relayed, not merely copied.</b> The filter forwards the
     * caller's credential to a service that trusts this gateway; if a token that fails to decode
     * were passed on regardless, the gateway would be laundering an unverified bearer token into the
     * trusted network. The chain must not be reached at all.
     */
    @Test
    void refusesToRelayATokenThatDoesNotDecode() {
        when(jwtDecoder.decode(anyString())).thenReturn(Mono.error(new BadJwtException("bad signature")));
        GatewayFilter filter = factory.apply((Object) null);
        CapturingChain chain = new CapturingChain();

        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/services/hcadminservice/api/professionals").header(AUTHORIZATION, "Bearer " + SECRET_TOKEN)
        );

        StepVerifier.create(filter.filter(exchange, chain)).expectError(BadJwtException.class).verify();
        assertThat(chain.exchange()).as("the chain must not run when the token fails to decode").isNull();
    }

    /**
     * An anonymous request passes straight through. The gateway has open paths — readiness probes,
     * the sign-in call itself — and this filter is a default filter applied to every route, so
     * rejecting a missing header here would close them.
     */
    @Test
    void passesAnonymousRequestsThroughUntouched() {
        GatewayFilter filter = factory.apply((Object) null);
        CapturingChain chain = new CapturingChain();

        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/services/hcadminservice/management/health/readiness")
        );

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chain.exchange()).isNotNull();
        assertThat(chain.exchange().getRequest().getHeaders().getFirst(AUTHORIZATION)).isNull();
    }

    /**
     * A header that is present but not a Bearer token is rejected rather than passed along or
     * silently treated as anonymous. Basic auth is disabled on this gateway, so anything else
     * arriving in that header is a caller doing something unintended.
     */
    @Test
    void rejectsAnAuthorizationHeaderThatIsNotABearerToken() {
        GatewayFilter filter = factory.apply((Object) null);
        CapturingChain chain = new CapturingChain();

        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/services/hcadminservice/api/professionals").header(AUTHORIZATION, "Basic dXNlcjpwYXNz")
        );

        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> filter.filter(exchange, chain).block());
        assertThat(chain.exchange()).isNull();
    }
}
