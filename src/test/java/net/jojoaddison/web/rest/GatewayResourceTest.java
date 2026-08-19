package net.jojoaddison.web.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.List;
import net.jojoaddison.web.rest.vm.RouteVM;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The route listing behind {@code GET /api/gateway/routes}.
 *
 * <p>Zero coverage before this class, from either phase. It is generated code that nobody edits,
 * which is exactly why it is worth pinning: it reads a route's identity by <b>string surgery</b> on
 * {@code predicate.toString()} and {@code route.getId()}, neither of which is a contract. Both are
 * debug representations belonging to Spring Cloud Gateway, and both have changed across its
 * releases. When they change again this endpoint does not fail to compile — it throws
 * {@link StringIndexOutOfBoundsException} at runtime, or silently reports the wrong service id.
 */
class GatewayResourceTest {

    private final RouteLocator routeLocator = mock(RouteLocator.class);
    private final DiscoveryClient discoveryClient = mock(DiscoveryClient.class);
    private final GatewayResource resource = new GatewayResource(routeLocator, discoveryClient);

    /**
     * A predicate whose {@code toString()} matches the shape Spring Cloud Gateway produces, because
     * that shape is the thing under test — a lambda's default {@code toString} has no brackets and
     * the parsing would blow up on it.
     */
    private static org.springframework.cloud.gateway.handler.AsyncPredicate<ServerWebExchange> predicateReading(String text) {
        return new org.springframework.cloud.gateway.handler.AsyncPredicate<>() {
            @Override
            public org.reactivestreams.Publisher<Boolean> apply(ServerWebExchange exchange) {
                return Mono.just(true);
            }

            @Override
            public String toString() {
                return text;
            }
        };
    }

    private static Route route(String id, String predicateText) {
        return Route.async().id(id).uri(URI.create("lb://whatever")).asyncPredicate(predicateReading(predicateText)).build();
    }

    @Test
    void reportsThePathAndServiceIdOfAnActiveRoute() {
        ReflectionTestUtils.setField(resource, "appName", "adminGateway");
        when(routeLocator.getRoutes()).thenReturn(
            Flux.just(
                route("ReactiveCompositeDiscoveryClient_HCADMINSERVICE", "Paths: [/services/hcadminservice/**], match trailing slash: true")
            )
        );
        when(discoveryClient.getInstances(anyString())).thenReturn(List.of());

        List<RouteVM> routes = resource.activeRoutes().getBody();

        assertThat(routes).hasSize(1);
        assertThat(routes.get(0).getPath()).isEqualTo("/services/hcadminservice/**");
        assertThat(routes.get(0).getServiceId()).isEqualTo("hcadminservice");
    }

    /**
     * The gateway does not list itself. It has a route to its own app, and including it would offer
     * an operator a route that loops back here.
     */
    @Test
    void excludesTheGatewaysOwnRoute() {
        ReflectionTestUtils.setField(resource, "appName", "adminGateway");
        when(routeLocator.getRoutes()).thenReturn(
            Flux.just(
                route("ReactiveCompositeDiscoveryClient_ADMINGATEWAY", "Paths: [/services/admingateway/**], match trailing slash: true"),
                route("ReactiveCompositeDiscoveryClient_HCADMINSERVICE", "Paths: [/services/hcadminservice/**], match trailing slash: true")
            )
        );
        when(discoveryClient.getInstances(anyString())).thenReturn(List.of());

        List<RouteVM> routes = resource.activeRoutes().getBody();

        assertThat(routes).extracting(RouteVM::getServiceId).containsExactly("hcadminservice");
    }

    @Test
    void reportsNothingWhenNoRoutesAreRegistered() {
        ReflectionTestUtils.setField(resource, "appName", "adminGateway");
        when(routeLocator.getRoutes()).thenReturn(Flux.empty());

        assertThat(resource.activeRoutes().getBody()).isEmpty();
    }
}
