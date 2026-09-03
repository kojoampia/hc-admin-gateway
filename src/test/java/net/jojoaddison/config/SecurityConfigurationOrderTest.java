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
}
