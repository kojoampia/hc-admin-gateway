package net.jojoaddison.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import net.jojoaddison.web.rest.errors.ExceptionTranslator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.data.web.ReactivePageableHandlerMethodArgumentResolver;
import org.springframework.data.web.ReactiveSortHandlerMethodArgumentResolver;
import org.springframework.util.CollectionUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.web.reactive.result.method.HandlerMethodArgumentResolver;
import org.springframework.web.server.WebExceptionHandler;
import tech.jhipster.config.JHipsterProperties;
import tech.jhipster.web.rest.errors.ReactiveWebExceptionHandler;

/**
 * Configuration of web application with Servlet 3.0 APIs.
 */
@Configuration
public class WebConfigurer implements WebFluxConfigurer {

    private final Logger log = LoggerFactory.getLogger(WebConfigurer.class);

    private final JHipsterProperties jHipsterProperties;

    public WebConfigurer(JHipsterProperties jHipsterProperties) {
        this.jHipsterProperties = jHipsterProperties;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        CorsConfiguration config = jHipsterProperties.getCors();
        if (!CollectionUtils.isEmpty(config.getAllowedOrigins()) || !CollectionUtils.isEmpty(config.getAllowedOriginPatterns())) {
            log.debug("Registering CORS filter");
            source.registerCorsConfiguration("/api/**", config);
            source.registerCorsConfiguration("/management/**", config);
            source.registerCorsConfiguration("/v3/api-docs", config);
            source.registerCorsConfiguration("/swagger-ui/**", config);
            source.registerCorsConfiguration("/*/api/**", config);
            source.registerCorsConfiguration("/services/*/api/**", config);
            source.registerCorsConfiguration("/*/management/**", config);
        }
        return source;
    }

    /*
     * These two carried the generator's "TODO: remove when this is supported in spring-boot" for several major versions
     * without anyone re-reading it. Re-checked 2026-09-03 against Spring Boot 4.1.0 / Spring Cloud 2025.1.2
     * (spring-data-commons 4.1.0, spring-webflux 7.0.8): Boot still does not supply them for WebFlux, so they stay.
     *
     * What Boot supplies is servlet-only. SpringDataWebAutoConfiguration is now DataWebAutoConfiguration, in the
     * spring-boot-data-commons module; it is on this classpath and it is annotated
     * @ConditionalOnWebApplication(type = SERVLET) and @ConditionalOnClass(WebMvcConfigurer.class). This gateway is
     * reactive and carries no spring-webmvc, so neither condition can hold. Nothing else registers the reactive pair
     * either: across every jar on the resolved classpath the only class that so much as names
     * ReactivePageableHandlerMethodArgumentResolver or ReactiveSortHandlerMethodArgumentResolver is the resolver's own
     * class file in spring-data-commons. This class is their only source.
     *
     * ReactivePageableArgumentResolverIT is what makes that falsifiable rather than merely asserted — a green build
     * after deleting a bean would not have meant Boot had taken over, only that nothing exercised the binding. It also
     * records the asymmetry found by removing each in turn: the pageable bean is what UserResource and
     * PublicUserResource depend on, while the sort bean is reached only by a bare Sort parameter, which no endpoint
     * takes today. Removing the sort bean leaves Pageable binding intact, because the resolver above constructs its own
     * sort resolver internally rather than injecting this one.
     */
    @Bean
    HandlerMethodArgumentResolver reactivePageableHandlerMethodArgumentResolver() {
        return new ReactivePageableHandlerMethodArgumentResolver();
    }

    @Bean
    HandlerMethodArgumentResolver reactiveSortHandlerMethodArgumentResolver() {
        return new ReactiveSortHandlerMethodArgumentResolver();
    }

    @Bean
    @Order(-2) // The handler must have precedence over WebFluxResponseStatusExceptionHandler and Spring Boot's ErrorWebExceptionHandler
    public WebExceptionHandler problemExceptionHandler(ObjectMapper mapper, ExceptionTranslator problemHandling) {
        return new ReactiveWebExceptionHandler(problemHandling, mapper);
    }

    @Bean
    ObjectMapper objectMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }
}
