package net.jojoaddison.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import net.jojoaddison.JavaSourceText;
import org.junit.jupiter.api.Test;

/**
 * Where the {@code /services/professionalservice/**} rules sit in the chain — asserted by reading
 * {@link SecurityConfiguration}'s source, because no request can tell.
 *
 * <p><strong>Why this is not an integration test.</strong> Delete both professionalservice matchers
 * from {@code SecurityConfiguration} and every one of {@code GatewayAuthorizationIT}'s cases still
 * passes, including the eight added for the cross-stack prefix. That is not an oversight in them: the
 * blanket {@code /services/**} rules decide the new prefix <em>identically</em> — {@code ADMIN}, or
 * {@code ADMIN}/{@code OPERATOR} on {@code GET} — so a black-box authorization test cannot
 * distinguish the explicit rule from its absence. Nothing that sends a request can.
 *
 * <p>Which makes the explicit rule exactly the kind of thing that gets tidied away. It exists because
 * the blanket rules are there to mirror <em>hc-admin-service's</em> read/write split: a future change
 * following that service would move them and take the cross-stack prefix with it, silently, on a
 * green build. A guard that cannot see the difference cannot stop that.
 *
 * <p><strong>Position is the other half, and it is load-bearing in both directions.</strong> Above
 * the {@code readiness} and {@code v3/api-docs} carve-outs, the new matchers swallow them: anonymous
 * readiness on this prefix starts answering {@code 401} and an orchestrator marks the route
 * permanently unhealthy, while api-docs opens from admin-only to any operator. Grouping all the
 * professionalservice rules together reads as a tidy-up and is the plausible way that happens.
 * Below the blanket rules, the explicit rules are unreachable and pin nothing at all.
 * {@code GatewayAuthorizationIT} covers the observable half of that — readiness and api-docs on this
 * prefix — and this covers the half no request reaches.
 *
 * <p>The idiom is the estate's, not a new one: {@code BrandTermsTest} in this repository reads
 * {@code src/main/resources} for the same reason, and the console's {@code global-styles.spec.ts}
 * pins which stylesheet a class is declared in by reading both files. Source-reading guards are
 * brittle by nature, so every assertion below fails with the rule it was looking for spelled out.
 */
class SecurityConfigurationOrderTest {

    private static final Path SOURCE = Path.of("src/main/java/net/jojoaddison/config/SecurityConfiguration.java");

    /** {@code .pathMatchers(...)} and its arguments. No argument list here contains a nested paren. */
    private static final Pattern PATH_MATCHERS = Pattern.compile("\\.pathMatchers\\(([^)]*)\\)");

    private static final String PROFESSIONAL = "/services/professionalservice/**";
    private static final String READINESS = "/services/*/management/health/readiness";
    private static final String API_DOCS = "/services/*/v3/api-docs";
    private static final String BLANKET = "/services/**";

    /** Backlog item 75 — admin-only, and above the rule below it. */
    private static final String AUTH_ACTIVITY = "/api/auth-activity/**";

    private static final String BLANKET_API = "/api/**";

    /**
     * An absent rule has no position, and reporting that as a position sends the reader looking for a
     * move that never happened. Both ordering cases state the distinction before comparing indices —
     * the first inversion run of this class reported a deleted rule as "now ABOVE the readiness
     * carve-out", which is how this constant came to exist.
     */
    private static final String ABSENT_NOT_MISPLACED =
        "%s is not stated in SecurityConfiguration at all, so it has no position to check. This is " +
        "deletion rather than misplacement — see theCrossStackPrefixIsPinnedExplicitly, which is the " +
        "case that describes what deleting it costs.";

    /**
     * The matcher argument lists of the {@code authorizeExchange} block, in the order the chain
     * evaluates them.
     *
     * <p>Two things are deliberately not done here. It reads from {@code authorizeExchange} onwards,
     * so the {@code securityMatcher} above it — which also calls {@code pathMatchers}, for
     * {@code /app/**} and friends — is not mistaken for an authorization rule. And it drops
     * whole-line comments <em>only</em>, rather than stripping comments generally: the block above the
     * professionalservice rules quotes every path string in this file, so a leaked comment would be
     * indistinguishable from a rule, and a regex for {@code /* ... *}{@code /} would match the
     * {@code /*} inside the literal {@code "/api/**"} and eat everything up to the {@code *}{@code /}
     * inside {@code "/services/*}{@code /management/..."} — silently deleting the readiness carve-out
     * this test exists to locate. {@link #theCommentBlockIsNotMistakenForRules()} is the check that
     * the filtering worked.
     */
    private static List<String> matchersInOrder() throws IOException {
        assertThat(SOURCE).as("SecurityConfiguration moved — this guard is reading nothing").isRegularFile();

        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
        int authorizeExchange = source.indexOf("authorizeExchange");
        assertThat(authorizeExchange)
            .as("no authorizeExchange block in SecurityConfiguration — the chain was restructured and this guard cannot read it")
            .isGreaterThan(-1);

        String rules = source
            .substring(authorizeExchange)
            .lines()
            .filter(line -> !line.strip().startsWith("//"))
            .collect(Collectors.joining("\n"));

        List<String> matchers = new ArrayList<>();
        Matcher found = PATH_MATCHERS.matcher(rules);
        while (found.find()) {
            matchers.add(found.group(1));
        }
        assertThat(matchers).as("no pathMatchers calls found — the extraction broke, not the configuration").isNotEmpty();
        return matchers;
    }

    /** First index of a matcher whose arguments name this path, or -1. */
    private static int indexOf(List<String> matchers, String path) {
        String quoted = '"' + path + '"';
        for (int i = 0; i < matchers.size(); i++) {
            if (matchers.get(i).contains(quoted)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The premise everything else rests on. If a comment leaked into the extraction there would be
     * more mentions than rules, and every ordering assertion below would be grading prose.
     */
    @Test
    void theCommentBlockIsNotMistakenForRules() throws IOException {
        List<String> professional = matchersInOrder().stream().filter(arguments -> arguments.contains(PROFESSIONAL)).toList();

        assertThat(professional)
            .as("expected exactly two %s matchers — the GET rule and the catch-all — but found %s", PROFESSIONAL, professional)
            .hasSize(2);
    }

    /**
     * <strong>The rule exists at all.</strong> This is the case that fails when the explicit matchers
     * are deleted and the blanket rules are left to cover the prefix — which changes no behaviour
     * today, passes all 127 other tests, and is precisely the regression this class was added for.
     */
    @Test
    void theCrossStackPrefixIsPinnedExplicitly() throws IOException {
        List<String> matchers = matchersInOrder();

        assertThat(indexOf(matchers, PROFESSIONAL))
            .as(
                "SecurityConfiguration no longer states %s explicitly. The blanket /services/** rules " +
                "still decide it the same way today, so nothing else in this suite can fail — which is " +
                "why this assertion exists. Those blanket rules mirror hc-admin-service's read/write " +
                "split; a change following that service would move them and take another product's " +
                "stack with them silently. Restore the rule rather than deleting this test.",
                PROFESSIONAL
            )
            .isGreaterThan(-1);
    }

    /**
     * <strong>Below the carve-outs.</strong> Both carve-outs are wildcards over the service segment,
     * so they already cover this prefix; a professionalservice matcher above them would shadow both.
     */
    @Test
    void theCrossStackPrefixSitsBelowTheReadinessAndApiDocsCarveOuts() throws IOException {
        List<String> matchers = matchersInOrder();
        int professional = indexOf(matchers, PROFESSIONAL);
        int readiness = indexOf(matchers, READINESS);
        int apiDocs = indexOf(matchers, API_DOCS);

        assertThat(professional).as(ABSENT_NOT_MISPLACED, PROFESSIONAL).isGreaterThan(-1);
        assertThat(readiness).as("the %s carve-out is gone — readiness ordering cannot be checked", READINESS).isGreaterThan(-1);
        assertThat(apiDocs).as("the %s carve-out is gone — api-docs ordering cannot be checked", API_DOCS).isGreaterThan(-1);

        assertThat(professional)
            .as(
                "%s is now ABOVE the %s carve-out, so it shadows it. Anonymous readiness on this " +
                "prefix answers 401 instead of passing, and an orchestrator marks the route " +
                "permanently unhealthy — which no test that sends an authenticated request will notice.",
                PROFESSIONAL,
                READINESS
            )
            .isGreaterThan(readiness);

        assertThat(professional)
            .as(
                "%s is now ABOVE the %s carve-out, so it shadows it. That carve-out is admin-only and " +
                "the rule above it admits an operator on GET, so api-docs for another product's stack " +
                "becomes readable by every operator in the estate.",
                PROFESSIONAL,
                API_DOCS
            )
            .isGreaterThan(apiDocs);
    }

    /**
     * <strong>Above the blanket rules.</strong> Below them it would never be evaluated: the blanket
     * matchers already match this prefix, so the explicit rules would be dead code that reads as a
     * guarantee.
     */
    @Test
    void theCrossStackPrefixSitsAboveTheBlanketServicesRules() throws IOException {
        List<String> matchers = matchersInOrder();
        int professional = indexOf(matchers, PROFESSIONAL);
        int blanket = indexOf(matchers, BLANKET);

        assertThat(professional).as(ABSENT_NOT_MISPLACED, PROFESSIONAL).isGreaterThan(-1);
        assertThat(blanket).as("the blanket %s rules are gone — the whole /services surface is now unguarded", BLANKET).isGreaterThan(-1);

        assertThat(professional)
            .as(
                "%s is now BELOW the blanket %s rules, which already match it — so the explicit rules " +
                "are unreachable and pin nothing, while still reading like a guarantee.",
                PROFESSIONAL,
                BLANKET
            )
            .isLessThan(blanket);
    }

    // --- the authentication record (backlog item 75) --------------------------------------------

    /**
     * <strong>{@code /api/auth-activity} is stated, and it is admin-only.</strong>
     *
     * <p>Unlike the cross-stack rules above, deleting this one <em>is</em> observable — the blanket
     * {@code /api/**} matcher below it says {@code authenticated()}, so {@code AuthActivityResourceIT}
     * would go red for an operator and a plain user. This case is not redundant with it: it reads the
     * authority as well as the position, so a rule widened <em>in place</em> —
     * {@code hasAnyAuthority(ADMIN, OPERATOR)}, which is the shape every other read on this console
     * has — is caught here too rather than by one assertion in one file.
     *
     * <p>Why it is admin-alone is {@code LoginAttempt}'s first safeguard: the response names logins
     * exactly as they were entered.
     */
    @Test
    void theAuthenticationRecordIsStatedAndIsAdminOnly() throws IOException {
        String rule = ruleFor(AUTH_ACTIVITY);

        assertThat(rule)
            .as(
                "SecurityConfiguration no longer states %s. It is ROLE_ADMIN alone — narrower than every " +
                "other read on this console — because the response carries logins as they were entered " +
                "(LoginAttempt's first safeguard). Without the rule the blanket /api/** matcher decides " +
                "it, and that says authenticated(), which across three gateways sharing one signing key " +
                "means every token in the estate.",
                AUTH_ACTIVITY
            )
            .isNotNull();

        assertThat(rule)
            .as(
                "%s is no longer ROLE_ADMIN alone. An operator reads the whole entity surface of " +
                "hc-admin-service and is deliberately refused here.",
                AUTH_ACTIVITY
            )
            .contains("hasAuthority(AuthoritiesConstants.ADMIN)")
            .doesNotContain("hasAnyAuthority");
    }

    /**
     * <strong>Above the blanket {@code /api/**} rule.</strong> Below it the explicit rule is never
     * evaluated and the endpoint is reachable by any authenticated caller — which is not a smaller
     * version of the intended rule, it is its opposite.
     */
    @Test
    void theAuthenticationRecordSitsAboveTheBlanketApiRule() throws IOException {
        List<String> matchers = matchersInOrder();
        int authActivity = indexOf(matchers, AUTH_ACTIVITY);
        int blanketApi = indexOf(matchers, BLANKET_API);

        assertThat(authActivity).as(ABSENT_NOT_MISPLACED, AUTH_ACTIVITY).isGreaterThan(-1);
        assertThat(blanketApi).as("the blanket %s rule is gone — this ordering cannot be checked", BLANKET_API).isGreaterThan(-1);

        assertThat(authActivity)
            .as(
                "%s is now BELOW the blanket %s rule, so authenticated() decides it first and the " +
                "admin-only rule is dead code that reads as a guarantee. Every account in the estate " +
                "holds ROLE_USER.",
                AUTH_ACTIVITY,
                BLANKET_API
            )
            .isLessThan(blanketApi);
    }

    /**
     * The whole {@code .pathMatchers("<path>")…} chain up to the next {@code .pathMatchers}, or null
     * when the path is not stated at all.
     *
     * <p>Needed because {@link #matchersInOrder()} captures only the argument list, and the question
     * here is what authority follows it.
     *
     * <h2>⚠ Read over comment-stripped, whitespace-collapsed source, and it failed BOTH ways without
     * that</h2>
     *
     * <p>It used to read the file raw, which is wrong in two opposite directions at once:
     *
     * <ul>
     *   <li><b>Fail-open.</b> A comment quoting the full matcher — this file's own habit, and the
     *       block above the professionalservice rules quotes every path string in
     *       {@code SecurityConfiguration} — sits above the real rule. Raw {@code indexOf} finds the
     *       comment first and grades <em>it</em>, so a real rule widened to
     *       {@code hasAnyAuthority(ADMIN, OPERATOR)} underneath a comment still saying
     *       {@code hasAuthority(...ADMIN)} would pass.</li>
     *   <li><b>Fail-closed, and this one is reachable today.</b> A purely cosmetic four-line wrap of
     *       {@code hasAuthority(AuthoritiesConstants.ADMIN)} — identical semantics, still admin-only —
     *       turns the guard red claiming the rule "is no longer ROLE_ADMIN alone", which is simply
     *       false. Prettier formats Java in this repository and backlog item 66 records that the
     *       committed Java does not match the pinned Prettier, so that false alarm is not
     *       hypothetical. A guard that cries wolf on a reformat is a guard somebody deletes.</li>
     * </ul>
     *
     * <p>{@code // prettier-ignore} on the {@code authorizeExchange} block is what has kept the second
     * case from firing so far. That is a comment one tidy-up away from being removed, and it is not a
     * reason to depend on formatting.
     *
     * <p>Stripping and collapsing is also what lets the path be found at all when the matcher itself
     * is wrapped: {@code .pathMatchers(\n    "/api/auth-activity/**"\n)} contains no
     * {@code .pathMatchers("/api/auth-activity/**")} to {@code indexOf}, so the raw form would have
     * reported the rule <em>missing</em> rather than misformatted.
     *
     * <p>{@link net.jojoaddison.JavaSourceText} does the stripping — the same lexer
     * {@code LoginAttemptNeverLoggedTest} uses, shared rather than copied because a second one is a
     * second thing to drift.
     */
    private static String ruleFor(String path) throws IOException {
        return ruleIn(Files.readString(SOURCE, StandardCharsets.UTF_8), path);
    }

    /**
     * The reading itself, over any source — so the two cases below grade the rule's own reader rather
     * than a second copy written beside it. That convention is why this is split at all.
     */
    private static String ruleIn(String rawSource, String path) {
        String source = JavaSourceText.collapseWhitespace(JavaSourceText.withoutComments(rawSource));
        int start = source.indexOf(".pathMatchers(\"" + path + "\")");
        if (start < 0) {
            return null;
        }
        int next = source.indexOf(".pathMatchers(", start + 1);
        return next < 0 ? source.substring(start) : source.substring(start, next);
    }

    /**
     * <b>A cosmetic reformat must not change the verdict.</b> The fail-closed half.
     *
     * <p>Identical semantics, wrapped across four lines the way a formatter would. Before the
     * stripping this returned a rule the assertion could not match and the guard cried
     * "no longer ROLE_ADMIN alone" about a rule that was exactly that — and worse, the wrapped
     * {@code .pathMatchers(...)} could not be located at all, so it reported the rule missing.
     */
    @Test
    void aCosmeticReformatDoesNotChangeWhatTheRuleSays() {
        String wrapped =
            """
            authorizeExchange(authz -> authz
                .pathMatchers(
                    "/api/auth-activity/**"
                )
                    .hasAuthority(
                        AuthoritiesConstants.ADMIN
                    )
                .pathMatchers("/api/**").authenticated()
            );
            """;

        assertThat(ruleIn(wrapped, AUTH_ACTIVITY))
            .as("a wrapped matcher must still be found and still read as admin-only")
            .isNotNull()
            .contains("hasAuthority(AuthoritiesConstants.ADMIN)")
            .doesNotContain("hasAnyAuthority");
    }

    /**
     * <b>A comment quoting the matcher must not be graded as the matcher.</b> The fail-open half, and
     * the more dangerous one.
     *
     * <p>This file's own habit is to quote every path string in prose above the rules, so the comment
     * comes first in the file. Reading raw text, {@code indexOf} finds the quotation and grades it —
     * so a real rule widened underneath a comment that still says the old thing would pass, which is
     * the exact shape of regression this class exists to catch.
     */
    @Test
    void aCommentQuotingTheMatcherIsNotGradedAsTheMatcher() {
        String quoted =
            """
            authorizeExchange(authz -> authz
                // It stays .pathMatchers("/api/auth-activity/**").hasAuthority(AuthoritiesConstants.ADMIN) forever.
                .pathMatchers("/api/auth-activity/**").hasAnyAuthority(AuthoritiesConstants.ADMIN, AuthoritiesConstants.OPERATOR)
                .pathMatchers("/api/**").authenticated()
            );
            """;

        assertThat(ruleIn(quoted, AUTH_ACTIVITY))
            .as("the real rule has been widened to admit an operator, and the comment above it must not hide that")
            .contains("hasAnyAuthority");
    }
}
