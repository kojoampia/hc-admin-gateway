package net.jojoaddison.config;

import static org.springframework.security.config.Customizer.withDefaults;
import static org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers.pathMatchers;

import net.jojoaddison.security.AuthoritiesConstants;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UserDetailsRepositoryReactiveAuthenticationManager;
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.header.ReferrerPolicyServerHttpHeadersWriter;
import org.springframework.security.web.server.header.XFrameOptionsServerHttpHeadersWriter.Mode;
import org.springframework.security.web.server.util.matcher.NegatedServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.OrServerWebExchangeMatcher;
import tech.jhipster.config.JHipsterProperties;

@Configuration
@EnableReactiveMethodSecurity
public class SecurityConfiguration {

    private final JHipsterProperties jHipsterProperties;

    public SecurityConfiguration(JHipsterProperties jHipsterProperties) {
        this.jHipsterProperties = jHipsterProperties;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public ReactiveAuthenticationManager reactiveAuthenticationManager(ReactiveUserDetailsService userDetailsService) {
        UserDetailsRepositoryReactiveAuthenticationManager authenticationManager = new UserDetailsRepositoryReactiveAuthenticationManager(
            userDetailsService
        );
        authenticationManager.setPasswordEncoder(passwordEncoder());
        return authenticationManager;
    }

    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
        http
            .securityMatcher(
                new NegatedServerWebExchangeMatcher(
                    new OrServerWebExchangeMatcher(pathMatchers("/app/**", "/i18n/**", "/content/**", "/swagger-ui/**"))
                )
            )
            .cors(withDefaults())
            .csrf(csrf -> csrf.disable())
            .headers(
                headers ->
                    headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(jHipsterProperties.getSecurity().getContentSecurityPolicy()))
                        .frameOptions(frameOptions -> frameOptions.mode(Mode.DENY))
                        .referrerPolicy(
                            referrer ->
                                referrer.policy(ReferrerPolicyServerHttpHeadersWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)
                        )
                        .permissionsPolicy(
                            permissions ->
                                permissions.policy(
                                    "camera=(), fullscreen=(self), geolocation=(), gyroscope=(), magnetometer=(), microphone=(), midi=(), payment=(), sync-xhr=()"
                                )
                        )
            )
            .authorizeExchange(
                authz ->
                    // prettier-ignore
                authz
                    .pathMatchers("/api/authenticate").permitAll()
                    // /api/register and /api/activate are deliberately absent. This is an internal
                    // administrative console: accounts are provisioned by an admin through
                    // /api/admin/users, and the handlers behind those two paths have been removed.
                    // Self-registration here granted ROLE_USER to anyone on the internet.
                    .pathMatchers("/api/account/reset-password/init").permitAll()
                    .pathMatchers("/api/account/reset-password/finish").permitAll()
                    .pathMatchers("/api/admin/**").hasAuthority(AuthoritiesConstants.ADMIN)
                    .pathMatchers("/api/**").authenticated()
                    .pathMatchers("/services/*/management/health/readiness").permitAll()
                    .pathMatchers("/services/*/v3/api-docs").hasAuthority(AuthoritiesConstants.ADMIN)
                    // --- the cross-stack prefix ----------------------------------------------------
                    //
                    // /services/professionalservice/** is proxied to hc-professional-service, which is
                    // another product's stack rather than this estate's own api. The route itself is
                    // not here — it is an env var in deploy/prod-server/compose.yml and again in
                    // quality/compose.yml, because Consul is disabled in both and routing is static.
                    //
                    // It is stated explicitly even though the blanket rules below say exactly the same
                    // thing today. That is the point: the blanket rules exist to mirror OUR api's
                    // read/write split, and if they were ever relaxed to follow it, the cross-stack
                    // prefix would be relaxed with them, silently. This pins it independently.
                    //
                    // It sits BELOW the two carve-outs above on purpose, so that readiness stays open
                    // and api-docs stays admin-only for this prefix exactly as for every other one.
                    //
                    // NEVER widen this to /api/**. That would swallow this gateway's own /api/account,
                    // /api/authenticate and /api/users and proxy authentication itself to another
                    // stack.
                    //
                    // WHAT THIS RULE CANNOT DO, and no rewriting of it will: it discriminates by
                    // AUTHORITY, never by issuer. All three gateways in the estate sign with one
                    // shared key, no token carries an `iss` claim and nothing validates one — so
                    // ADMIN/OPERATOR here means "an account holding that authority on ANY of the three
                    // stacks", not "an account on this one". hc-professional's mirror-image rule has
                    // the same property. Only issuing and validating `iss` would change it; do not
                    // read this as a tighter boundary than it is.
                    .pathMatchers(HttpMethod.GET, "/services/professionalservice/**")
                        .hasAnyAuthority(AuthoritiesConstants.ADMIN, AuthoritiesConstants.OPERATOR)
                    .pathMatchers("/services/professionalservice/**").hasAuthority(AuthoritiesConstants.ADMIN)
                    // Mirrors the downstream service's own read/write split (see the api's
                    // SecurityConfiguration). The service enforces this itself — this is the outer
                    // half of defence in depth, not the only gate.
                    .pathMatchers(HttpMethod.GET, "/services/**")
                        .hasAnyAuthority(AuthoritiesConstants.ADMIN, AuthoritiesConstants.OPERATOR)
                    .pathMatchers("/services/**").hasAuthority(AuthoritiesConstants.ADMIN)
                    .pathMatchers("/v3/api-docs/**").hasAuthority(AuthoritiesConstants.ADMIN)
                    .pathMatchers("/management/health").permitAll()
                    .pathMatchers("/management/health/**").permitAll()
                    .pathMatchers("/management/info").permitAll()
                    .pathMatchers("/management/prometheus").permitAll()
                    .pathMatchers("/management/**").hasAuthority(AuthoritiesConstants.ADMIN)
            )
            .httpBasic(basic -> basic.disable())
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(withDefaults()));
        return http.build();
    }
}
