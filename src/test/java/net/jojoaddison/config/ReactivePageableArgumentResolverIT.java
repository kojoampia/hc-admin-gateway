package net.jojoaddison.config;

import static org.assertj.core.api.Assertions.assertThat;

import net.jojoaddison.IntegrationTest;
import net.jojoaddison.security.AuthoritiesConstants;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.ReactivePageableHandlerMethodArgumentResolver;
import org.springframework.data.web.ReactiveSortHandlerMethodArgumentResolver;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Guards the two argument resolvers {@link WebConfigurer} declares by hand.
 *
 * <p>They carried a {@code // TODO: remove when this is supported in spring-boot} from the JHipster generator, and on
 * 2026-09-03 that was re-read against Boot 4.1.0 / Spring Cloud 2025.1.2 rather than carried further. It is still true:
 * Boot's {@code DataWebAutoConfiguration} (spring-boot-data-commons 4.1.0, the renamed
 * {@code SpringDataWebAutoConfiguration}) is annotated {@code @ConditionalOnWebApplication(type = SERVLET)} and
 * {@code @ConditionalOnClass(WebMvcConfigurer.class)}, so it cannot apply to this reactive gateway — and across every
 * jar on this project's classpath the only class that so much as names either reactive resolver is the resolver's own
 * class file. Nothing but {@link WebConfigurer} constructs them.
 *
 * <p><b>Why a test and not just a note.</b> Deleting the two beans and finding the build green would not have meant Boot
 * supplies them; it could equally have meant nothing exercises a paginated reactive endpoint. The two tests that touch
 * one ({@code UserResourceIT#getAllUsers}, {@code PublicUserResourceIT#getAllPublicUsers}) request {@code ?sort=id,desc}
 * and then assert only the body, so they never read a bound value and would pass against a resolver that quietly
 * produced an unsorted, default {@code Pageable}. This class asserts the bound values themselves, which is what makes
 * the deletion falsifiable.
 *
 * <p><b>Verified by inversion, and the two beans are not symmetrical.</b> Both counts below are out of a 120-test
 * {@code clean verify} and both include {@code webConfigurerIsTheOnlySourceOfTheResolvers}, which reads the bean
 * inventory and so goes red for either removal — count it on both sides or neither. Removing the <b>pageable</b> bean
 * fails <b>four</b> of the five cases here — {@code bindsPageSizeAndSortFromTheQuery},
 * {@code bindsPageableDefaultsWhenTheQueryIsEmpty}, {@code aBoundSortIsWhatTheUserEndpointValidates} and the inventory
 * — plus both pre-existing ITs, six in all, and every one but the inventory fails with {@code 500} rather than a
 * silently-unpaged {@code 200}. Removing the <b>sort</b> bean fails <b>two</b>: {@code bindsASortArgument} and the
 * inventory, nothing else in the suite. The three {@code Pageable} cases still pass, and that is neither Boot
 * supplying the resolver nor this test missing the code path — it is that
 * {@link ReactivePageableHandlerMethodArgumentResolver}'s no-arg constructor builds its own sort resolver rather than
 * injecting the bean. So a bare {@code Sort} parameter is the only thing that reads the second bean, and no endpoint
 * takes one today. That is why it is asserted here and nowhere else.
 *
 * <p>The echo controller below exists because no shipping endpoint hands a {@code Pageable} back, and it is
 * <b>nested inside this class deliberately</b>. What keeps it out of every other integration-test context is not its
 * being declared as a bean rather than component-scanned — this paragraph said that until 2026-09-03 and it named the
 * wrong mechanism. Boot's {@code TestTypeExcludeFilter}, which is the filter {@code @SpringBootApplication}'s component
 * scan consults, matches a class whose {@code getEnclosingClassName()} matches, recursing outwards; so <em>any</em>
 * class nested in a JUnit test class is excluded whatever stereotype it carries. Read out of
 * {@code spring-boot-test-4.1.0.jar}, not recalled.
 *
 * <p>The reason that is worth knowing is the trap. Promote {@code PageableEchoController} to a top-level class under
 * {@code src/test} — a plausible tidy-up, and one nothing here would fail on — and it is scanned normally, and
 * {@code /api/test/**} appears in every integration-test context in this module.
 */
@AutoConfigureWebTestClient(timeout = IntegrationTest.DEFAULT_TIMEOUT)
@WithMockUser(authorities = AuthoritiesConstants.ADMIN)
@Import(ReactivePageableArgumentResolverIT.PageableEchoConfiguration.class)
@IntegrationTest
class ReactivePageableArgumentResolverIT {

    private static final String PAGEABLE_ECHO_URL = "/api/test/pageable-echo";

    private static final String SORT_ECHO_URL = "/api/test/sort-echo";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void webConfigurerIsTheOnlySourceOfTheResolvers() {
        assertThat(applicationContext.getBeanNamesForType(ReactivePageableHandlerMethodArgumentResolver.class)).containsExactly(
            "reactivePageableHandlerMethodArgumentResolver"
        );
        assertThat(applicationContext.getBeanNamesForType(ReactiveSortHandlerMethodArgumentResolver.class)).containsExactly(
            "reactiveSortHandlerMethodArgumentResolver"
        );
    }

    @Test
    void bindsPageSizeAndSortFromTheQuery() {
        webTestClient
            .get()
            .uri(PAGEABLE_ECHO_URL + "?page=2&size=7&sort=login,desc&sort=email,asc")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(String.class)
            .isEqualTo("page=2;size=7;sort=login: DESC,email: ASC");
    }

    @Test
    void bindsPageableDefaultsWhenTheQueryIsEmpty() {
        webTestClient
            .get()
            .uri(PAGEABLE_ECHO_URL)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(String.class)
            .isEqualTo("page=0;size=20;sort=UNSORTED");
    }

    @Test
    void bindsASortArgument() {
        webTestClient
            .get()
            .uri(SORT_ECHO_URL + "?sort=login,desc")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(String.class)
            .isEqualTo("login: DESC");
    }

    /**
     * The shipping surface reads the bound sort and rejects on it, so this fails on a resolver that binds nothing —
     * an unsorted {@code Pageable} passes {@code UserResource#onlyContainsAllowedProperties} and answers 200.
     */
    @Test
    void aBoundSortIsWhatTheUserEndpointValidates() {
        webTestClient.get().uri("/api/admin/users?sort=login,desc").exchange().expectStatus().isOk();
        webTestClient.get().uri("/api/admin/users?sort=password,desc").exchange().expectStatus().isBadRequest();
    }

    @TestConfiguration
    static class PageableEchoConfiguration {

        @Bean
        PageableEchoController pageableEchoController() {
            return new PageableEchoController();
        }
    }

    @RestController
    static class PageableEchoController {

        @GetMapping(PAGEABLE_ECHO_URL)
        Mono<String> echoPageable(Pageable pageable) {
            return Mono.just("page=%d;size=%d;sort=%s".formatted(pageable.getPageNumber(), pageable.getPageSize(), pageable.getSort()));
        }

        @GetMapping(SORT_ECHO_URL)
        Mono<String> echoSort(Sort sort) {
            return Mono.just(sort.toString());
        }
    }
}
