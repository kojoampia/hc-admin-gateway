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
        http.securityMatcher(
            new NegatedServerWebExchangeMatcher(
                new OrServerWebExchangeMatcher(pathMatchers("/app/**", "/i18n/**", "/content/**", "/swagger-ui/**"))
            )
        )
            .cors(withDefaults())
            .csrf(csrf -> csrf.disable())
            .headers(headers ->
                headers
                    .contentSecurityPolicy(csp -> csp.policyDirectives(jHipsterProperties.getSecurity().getContentSecurityPolicy()))
                    .frameOptions(frameOptions -> frameOptions.mode(Mode.DENY))
                    .referrerPolicy(referrer ->
                        referrer.policy(ReferrerPolicyServerHttpHeadersWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)
                    )
                    .permissionsPolicy(permissions ->
                        permissions.policy(
                            "camera=(), fullscreen=(self), geolocation=(), gyroscope=(), magnetometer=(), microphone=(), midi=(), payment=(), sync-xhr=()"
                        )
                    )
            )
            .authorizeExchange(authz ->
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
                    // --- the authentication record --------------------------------------------------
                    //
                    // /api/auth-activity is ADMIN ALONE, which is NARROWER than everything else this
                    // console reads — an operator reaches the whole entity surface of the api,
                    // patient contact addresses included, and is refused here.
                    //
                    // That is backlog item 75's first safeguard rather than a preference. The response
                    // carries `topFailedLogins`: logins AS THEY WERE ENTERED, which on a failure are by
                    // definition mostly not the account holder — an attacker's guesses, a typo, or a
                    // password pasted into the wrong box. LoginAttempt's javadoc argues in full why
                    // that value is stored at all and what carries the exception to item 43's rule;
                    // this line is one of the four things carrying it.
                    //
                    // It MUST sit above the blanket /api/** rule. Below it, `authenticated()` decides
                    // first — and every account in the estate holds ROLE_USER while all three gateways
                    // share one signing key, so "authenticated" here means every token in the network.
                    // SecurityConfigurationOrderTest pins the position; AuthActivityResourceIT pins the
                    // decision for an admin, an operator, a plain user and an anonymous caller.
                    .pathMatchers("/api/auth-activity/**").hasAuthority(AuthoritiesConstants.ADMIN)
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
                    // --- the sibling GATEWAY prefixes (item 122) ------------------------------------
                    //
                    // /services/hcpatientgateway/** and /services/hcprofessionalgateway/** are proxied
                    // to another product's GATEWAY, not its service. The routes are env vars in
                    // deploy/prod-server/compose.yml and quality/compose.yml, like every other route
                    // here; Consul is enabled in production but the discovery locator is not, so
                    // routing is static in both.
                    //
                    // ⚠ ROLE_ADMIN ALONE, ON EVERY VERB — deliberately NOT the ADMIN-or-OPERATOR-on-GET
                    // split the four rules around this one use. Two reasons, and the first is the one
                    // that would be missed:
                    //
                    //   1. The far side is @PreAuthorize(ADMIN) on every handler under /api/admin
                    //      (UserResource in both sibling gateways). Admitting an operator here would
                    //      relay a caller the sibling then refuses — a guard that disagrees with the
                    //      server, which teaches the wrong rule and reads as a sibling outage.
                    //   2. The response carries `login` and `email` exactly as they were entered. That
                    //      is item 75's argument for /api/auth-activity/** being admin-alone, one
                    //      product along.
                    //
                    // NO HttpMethod QUALIFIER, and that absence is the rule. A GET-scoped
                    // hasAuthority here would let HEAD fall through to the blanket /services/** rule
                    // below — Spring dispatches HEAD to a @GetMapping handler, and a body-less read of
                    // an account path is still an existence oracle. The route's own Method=GET
                    // predicate already refuses HEAD; this is the second layer, not a restatement.
                    // The rule ships from gateway/ and the route from deploy/, with no shared gate,
                    // so each layer must hold alone — a HEAD that 404s today is the route's predicate
                    // working, not evidence that this rule's breadth is redundant.
                    //
                    // WHAT THIS RULE CANNOT DO is what the professionalservice comment above says at
                    // length: it discriminates by AUTHORITY, never by issuer. One shared signing key,
                    // no `iss` claim anywhere, so ADMIN here means an admin on ANY of the three stacks.
                    //
                    // It sits BELOW the readiness and v3/api-docs carve-outs for the same reason the
                    // professionalservice rules do, and ABOVE the blanket /services/** rules or it is
                    // never evaluated at all.
                    .pathMatchers("/services/hcpatientgateway/**").hasAuthority(AuthoritiesConstants.ADMIN)
                    .pathMatchers("/services/hcprofessionalgateway/**").hasAuthority(AuthoritiesConstants.ADMIN)
                    // Mirrors the downstream service's own read/write split (see the api's
                    // SecurityConfiguration). The service enforces this itself — this is the outer
                    // half of defence in depth, not the only gate.
                    //
                    // ⚠ IT DOES NOT MIRROR THE API'S ROLE_VENDOR CARVE-OUT, AND THAT IS A DECISION
                    // (backlog item 87, decided 2026-09-12) RATHER THAN AN OVERSIGHT.
                    //
                    // hc-admin's api gained `GET /api/vendors` for ROLE_VENDOR in item 31, scoped
                    // server-side to the caller's own row. This rule is deliberately NOT widened to
                    // match, so a ROLE_VENDOR token reaching this gateway is 403 before the api is
                    // asked. The precedent is four screens up in this same file: the api's
                    // `/api/professionals/me/**` carve-out is not mirrored here either, because a
                    // clinician has no business in the admin console and reaches their roster through
                    // hc-professional's own gateway. A vendor is the same shape.
                    //
                    // WHY IT IS SAFE TODAY, which is the half a reader cannot see from here:
                    // hc-vendor's `application.yml` defaults `adminservice.base-url` to
                    // `http://localhost:5507` with `vendors-path: /api/vendors` — STRAIGHT AT THE
                    // SERVICE, not through this gateway. So the carve-out item 31 built sits on the
                    // path hc-vendor actually uses, and this rule is never in that request's way.
                    //
                    // WHAT WOULD CHANGE IT, and the cost if nobody notices: the moment a deployment
                    // points that base URL at this gateway with the `/services/adminservice` prefix —
                    // which hc-vendor's own config comment describes as a supported shape —
                    // `GET /api/vendors/by-account` starts returning 403 from HERE, and
                    // `AdminVendorClient` surfaces it as DIRECTORY_ERROR(403). That is
                    // INDISTINGUISHABLE from the ROLE_VENDOR grant having been removed in the api, so
                    // an operator debugging it will read item 31's authority rule and find nothing
                    // wrong with it. Check which URL the caller used before touching the api.
                    //
                    // It is the same misdirection as the 401 trap item 31 records one layer along: an
                    // unshared JWT_BASE64_SECRET also surfaces as a DIRECTORY_ERROR from this stack
                    // about a rule that is perfectly correct.
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
