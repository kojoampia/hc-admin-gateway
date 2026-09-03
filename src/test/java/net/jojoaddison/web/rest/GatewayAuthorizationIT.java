package net.jojoaddison.web.rest;

import static net.jojoaddison.security.jwt.JwtAuthenticationTestUtils.createTokenWithAuthorities;

import net.jojoaddison.IntegrationTest;
import net.jojoaddison.security.AuthoritiesConstants;
import net.jojoaddison.security.jwt.AuthenticationIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Who may reach a downstream service through this gateway.
 *
 * <p>The gateway's {@code /services/**} rules are the outer half of the read/write split that
 * hc-admin-service enforces on {@code /api/**}: a {@code GET} needs {@code ROLE_ADMIN} or
 * {@code ROLE_OPERATOR}, anything that writes needs {@code ROLE_ADMIN}, and a plain {@code
 * ROLE_USER} reaches nothing. <b>Nothing tested them before this class.</b> Every other suite here
 * either drives a controller on this application directly or asserts token validity in isolation,
 * so the whole proxying surface — the part that decides what an operator can do to somebody else's
 * data — was covered by nothing at all.
 *
 * <p>That is the same hole the api closed with {@code ApiAuthorizationIT}, and it closed it for a
 * reason worth repeating here: on that side, {@code /api/** -> authenticated()} survived for months
 * because every {@code *ResourceIT} runs with {@code addFilters = false} and could not have noticed.
 * A rule that no test exercises is a rule that can be widened silently.
 *
 * <h2>Why this asserts "not 401/403" rather than a success status</h2>
 *
 * There is no downstream service in a test context — Consul is off and nothing is registered — so an
 * authorised request cannot be proxied anywhere and comes back as a routing failure, typically
 * {@code 404} or {@code 503}. That is fine and is deliberately not asserted: <b>this class is about
 * the authorization decision, not about routing.</b> Refused means {@code 401} or {@code 403};
 * allowed means the request got past the security filter chain to a routing attempt, whatever that
 * attempt then produced. Asserting a specific downstream status would couple these tests to
 * discovery configuration that has nothing to do with the rule under test, and would fail for the
 * wrong reason the moment a route was added.
 */
@AutoConfigureWebTestClient(timeout = IntegrationTest.DEFAULT_TIMEOUT)
@AuthenticationIntegrationTest
class GatewayAuthorizationIT {

    private static final String SERVICE_GET = "/services/hcadminservice/api/professionals";
    private static final String SERVICE_WRITE = "/services/hcadminservice/api/professionals";

    /**
     * The cross-stack prefix — proxied to hc-professional-service, not to our own api. The rule
     * guarding it is written out explicitly in {@code SecurityConfiguration} rather than left to the
     * blanket {@code /services/**} rules, and these cases are what stop the two drifting apart.
     */
    private static final String PROFESSIONAL_GET = "/services/professionalservice/api/professionals";

    @Autowired
    private WebTestClient webTestClient;

    @Value("${jhipster.security.authentication.jwt.base64-secret}")
    private String jwtKey;

    private String admin() {
        return createTokenWithAuthorities(jwtKey, "admin", AuthoritiesConstants.ADMIN, AuthoritiesConstants.USER);
    }

    private String operator() {
        return createTokenWithAuthorities(jwtKey, "operator", AuthoritiesConstants.OPERATOR, AuthoritiesConstants.USER);
    }

    private String plainUser() {
        return createTokenWithAuthorities(jwtKey, "user", AuthoritiesConstants.USER);
    }

    /** No authorities at all — the shape a token from another service in the network can have. */
    private String noAuthorities() {
        return createTokenWithAuthorities(jwtKey, "noauth");
    }

    // --- reads ------------------------------------------------------------------------------

    @Test
    void anAdminMayReadThroughTheGateway() {
        expectAllowed(get(SERVICE_GET, admin()));
    }

    /**
     * The operator's whole reason for existing: read everything, change nothing. If this rule is
     * lost, the console's read-only role silently becomes useless or dangerous depending on which
     * way it breaks.
     */
    @Test
    void anOperatorMayReadThroughTheGateway() {
        expectAllowed(get(SERVICE_GET, operator()));
    }

    /**
     * Authentication alone is deliberately not enough. ROLE_USER is the baseline every seeded
     * account carries, so if it reached the service surface, every account in the network would.
     */
    @Test
    void aPlainUserMayNotReadThroughTheGateway() {
        expectRefused(get(SERVICE_GET, plainUser()));
    }

    @Test
    void aTokenWithNoAuthoritiesMayNotRead() {
        expectRefused(get(SERVICE_GET, noAuthorities()));
    }

    @Test
    void anonymousMayNotRead() {
        webTestClient.get().uri(SERVICE_GET).exchange().expectStatus().isUnauthorized();
    }

    // --- writes -----------------------------------------------------------------------------

    @Test
    void anAdminMayWriteThroughTheGateway() {
        expectAllowed(webTestClient.post().uri(SERVICE_WRITE).headers(h -> h.setBearerAuth(admin())).exchange());
    }

    /**
     * <b>The split, stated as a test.</b> The same principal that may GET this path must not POST,
     * PUT, PATCH or DELETE it. A single {@code hasAnyAuthority} covering all methods would pass
     * every read test above and quietly hand an operator write access to the whole entity surface.
     */
    @Test
    void anOperatorMayNotWriteThroughTheGateway() {
        expectRefused(webTestClient.post().uri(SERVICE_WRITE).headers(h -> h.setBearerAuth(operator())).exchange());
        expectRefused(webTestClient.put().uri(SERVICE_WRITE).headers(h -> h.setBearerAuth(operator())).exchange());
        expectRefused(webTestClient.patch().uri(SERVICE_WRITE).headers(h -> h.setBearerAuth(operator())).exchange());
        expectRefused(webTestClient.delete().uri(SERVICE_WRITE).headers(h -> h.setBearerAuth(operator())).exchange());
    }

    @Test
    void aPlainUserMayNotWriteThroughTheGateway() {
        expectRefused(webTestClient.post().uri(SERVICE_WRITE).headers(h -> h.setBearerAuth(plainUser())).exchange());
    }

    // --- the carve-outs around them -----------------------------------------------------------

    /**
     * Readiness is open on purpose: an orchestrator probes it without credentials. It sits above the
     * blanket rules, and if it slipped below them the probe would get a 401 and the service would be
     * reported permanently unhealthy.
     */
    @Test
    void readinessIsOpenWithoutAToken() {
        expectAllowed(webTestClient.get().uri("/services/hcadminservice/management/health/readiness").exchange());
    }

    @Test
    void apiDocsThroughTheGatewayAreAdminOnly() {
        expectAllowed(get("/services/hcadminservice/v3/api-docs", admin()));
        expectRefused(get("/services/hcadminservice/v3/api-docs", operator()));
        expectRefused(get("/services/hcadminservice/v3/api-docs", plainUser()));
    }

    /**
     * The ordering matters as much as the rules. {@code /services/*​/management/health/readiness} is
     * permitAll, but the rest of a service's management surface must not inherit that — it is
     * reached through the blanket {@code /services/**} rules like anything else.
     */
    @Test
    void theRestOfAServicesManagementSurfaceIsNotOpen() {
        webTestClient.get().uri("/services/hcadminservice/management/env").exchange().expectStatus().isUnauthorized();
        expectRefused(get("/services/hcadminservice/management/env", plainUser()));
    }

    // --- the cross-stack prefix ---------------------------------------------------------------

    /**
     * The same read/write split applies to another product's service as to our own. Written as its
     * own cases rather than trusted to the blanket rules: the blanket rules exist to mirror
     * <em>hc-admin-service</em>'s split, so a future change that follows that service would move them
     * — and would take the cross-stack prefix with it unless something pins it separately.
     */
    @Test
    void anAdminMayReachTheProfessionalStackThroughTheGateway() {
        expectAllowed(get(PROFESSIONAL_GET, admin()));
    }

    @Test
    void anOperatorMayReadTheProfessionalStackButNotWriteToIt() {
        expectAllowed(get(PROFESSIONAL_GET, operator()));
        expectRefused(webTestClient.post().uri(PROFESSIONAL_GET).headers(h -> h.setBearerAuth(operator())).exchange());
        expectRefused(webTestClient.put().uri(PROFESSIONAL_GET).headers(h -> h.setBearerAuth(operator())).exchange());
        expectRefused(webTestClient.patch().uri(PROFESSIONAL_GET).headers(h -> h.setBearerAuth(operator())).exchange());
        expectRefused(webTestClient.delete().uri(PROFESSIONAL_GET).headers(h -> h.setBearerAuth(operator())).exchange());
    }

    /**
     * {@code .authenticated()} is the rule this must never become. Every account in the estate holds
     * {@code ROLE_USER}, and all three stacks share one signing key — so authentication alone would
     * hand the whole professional surface to every token in the network.
     */
    @Test
    void aPlainUserMayNotReachTheProfessionalStack() {
        expectRefused(get(PROFESSIONAL_GET, plainUser()));
        expectRefused(webTestClient.post().uri(PROFESSIONAL_GET).headers(h -> h.setBearerAuth(plainUser())).exchange());
    }

    @Test
    void aTokenWithNoAuthoritiesMayNotReachTheProfessionalStack() {
        expectRefused(get(PROFESSIONAL_GET, noAuthorities()));
    }

    @Test
    void anonymousMayNotReachTheProfessionalStack() {
        webTestClient.get().uri(PROFESSIONAL_GET).exchange().expectStatus().isUnauthorized();
    }

    /**
     * The readiness carve-out, on the cross-stack prefix. {@link #readinessIsOpenWithoutAToken()}
     * asserts the same thing for {@code hcadminservice} and cannot cover this one: the carve-out is a
     * wildcard over the service segment, but the two explicit professionalservice matchers are not,
     * and if they were moved above it they would shadow it <em>for this prefix only</em>. The
     * hcadminservice case would stay green throughout.
     *
     * <p>What that costs is specific: an orchestrator probes readiness without credentials, gets
     * {@code 401}, and reports the route permanently unhealthy. Grouping every professionalservice
     * rule together is the plausible tidy-up that does it.
     */
    @Test
    void readinessOnTheProfessionalPrefixIsOpenWithoutAToken() {
        expectAllowed(webTestClient.get().uri("/services/professionalservice/management/health/readiness").exchange());
    }

    /**
     * The api-docs carve-out, on the cross-stack prefix, and the mirror of
     * {@link #apiDocsThroughTheGatewayAreAdminOnly()}. The operator case is the one that matters: the
     * carve-out is admin-only while the professionalservice {@code GET} rule admits an operator, so
     * moving those matchers above it opens another product's API description to every operator in the
     * estate — and opens it silently, because nothing else asserts this prefix against this path.
     *
     * <p>These two cases catch <b>misplacement</b>, not deletion. Deleting the explicit rules changes
     * no decision at all, here or anywhere a request can reach; {@code SecurityConfigurationOrderTest}
     * is what covers that.
     */
    @Test
    void apiDocsForTheProfessionalStackAreAdminOnly() {
        expectAllowed(get("/services/professionalservice/v3/api-docs", admin()));
        expectRefused(get("/services/professionalservice/v3/api-docs", operator()));
        expectRefused(get("/services/professionalservice/v3/api-docs", plainUser()));
    }

    /**
     * <b>The rule that must not be widened.</b> This gateway's own authentication surface lives under
     * {@code /api/**}, so a cross-stack matcher written as {@code /api/**} — or a route predicate
     * written that way — would proxy sign-in itself to another product. These three paths are the
     * ones that would be lost first, and they must keep answering here.
     */
    @Test
    void theCrossStackRuleDoesNotReachThisGatewaysOwnApi() {
        // permitAll on this gateway, and it stays that way — not swallowed by a /services/** rule.
        expectAllowed(webTestClient.post().uri("/api/authenticate").exchange());
        // Authenticated here, and reachable by a plain user, which nothing under /services/** is.
        expectAllowed(get("/api/account", plainUser()));
        // Admin-only here — refused for an operator, where the blanket /services/** GET rule admits one.
        expectRefused(get("/api/admin/users", operator()));
    }

    /**
     * The control the probe pair needs. A prefix with no route behind it must be refused by the same
     * rules and never quietly permitted — otherwise "403 for an operator" stops proving anything,
     * because a missing route and a guarded one would look alike.
     */
    @Test
    void anUnroutedServicePrefixIsGovernedByTheSameRules() {
        expectRefused(get("/services/nosuchservice/api/anything", plainUser()));
        webTestClient.get().uri("/services/nosuchservice/api/anything").exchange().expectStatus().isUnauthorized();
    }

    // --- helpers ----------------------------------------------------------------------------

    private WebTestClient.ResponseSpec get(String uri, String token) {
        return webTestClient.get().uri(uri).headers(h -> h.setBearerAuth(token)).exchange();
    }

    /**
     * Past the security filter chain. Anything that is not 401 or 403 means authorization allowed
     * the request through — see the note on this class about why the routing outcome is not pinned.
     */
    private void expectAllowed(WebTestClient.ResponseSpec response) {
        response
            .expectStatus()
            .value(status -> {
                if (status == HttpStatus.UNAUTHORIZED.value() || status == HttpStatus.FORBIDDEN.value()) {
                    throw new AssertionError("expected the request to be authorized, but the chain refused it with " + status);
                }
            });
    }

    private void expectRefused(WebTestClient.ResponseSpec response) {
        response
            .expectStatus()
            .value(status -> {
                if (status != HttpStatus.UNAUTHORIZED.value() && status != HttpStatus.FORBIDDEN.value()) {
                    throw new AssertionError(
                        "expected 401 or 403, got " + status + " — the request reached routing when it should not have"
                    );
                }
            });
    }
}
