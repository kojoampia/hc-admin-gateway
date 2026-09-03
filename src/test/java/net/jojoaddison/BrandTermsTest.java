package net.jojoaddison;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The product is <strong>Abofonsa BridgeCare</strong>, and nothing this service sends to a person may
 * say otherwise.
 *
 * <p>This is the durable half of {@code docs/backlog.md} item 2. Six catalogue values had named
 * "Health Connect Admin" — the pre-BridgeCare product — through the whole brand pass, in production,
 * and they are the first words a new administrator ever reads from this system, because the creation
 * email is the only supported route to a working password. Nothing caught it: a rename is not a
 * compile error and a stale brand renders perfectly.
 *
 * <p>Ported from {@code hc-professional/web}'s {@code brand-terms.spec.ts}, which closed the same
 * defect in that product's footer on the same day. It lives here rather than in {@code app/} because
 * these strings are <em>gateway</em> resources: a Vitest spec in the console repository cannot see
 * them, so a guard there would have watched the wrong files and passed.
 *
 * <p><strong>It reads {@code src/main/resources} rather than the classpath, deliberately.</strong>
 * {@code src/test/resources/i18n/} carries three-line stubs that shadow the real catalogues on the
 * test classpath — that is what {@code MailServiceIT} asserts against — so a guard resolving
 * {@code i18n/messages_en.properties} through the class loader would grade the fixture and never
 * look at what ships.
 *
 * <p>Only <strong>values</strong> are judged. Keys, package names, the Consul service name and the
 * generator's {@code adminGateway} baseName are identifiers, and renaming those is a different and
 * much larger job. Comments cost nothing: {@link Properties} drops them from a catalogue and the
 * template scan strips {@code <!-- -->} before looking, so a genuine historical note naming the old
 * brand is free — which is better than an allowlist, because it cannot go stale.
 */
class BrandTermsTest {

    private static final Path I18N = Path.of("src/main/resources/i18n");
    private static final Path TEMPLATES = Path.of("src/main/resources/templates");

    /**
     * Every catalogue is read as ISO-8859-1, which is {@link Properties#load(InputStream)}'s contract
     * and is not a claim about the files — all four are UTF-8, and
     * {@link MessageCatalogueEncodingTest} is what enforces that. It does not matter here because
     * every denied term is pure ASCII, and the two encodings agree on ASCII: a mis-decoded umlaut can
     * neither hide nor invent a match.
     *
     * <p>This said "{@code messages_de.properties} is ISO-8859-1 and {@code messages_fr.properties} is
     * UTF-8" until 2026-09-03, which was true when it was written and is backlog item 12. The German
     * file was converted; this loader was deliberately left alone, because the reasoning above never
     * depended on which encoding the files were in.
     */
    private static final List<Denied> DENIED = List.of(
        new Denied("Health Connect", Pattern.compile("Health\\s+Connect"), "Abofonsa BridgeCare"),
        new Denied(
            "Jojo Addison Information Systems Consultancy",
            Pattern.compile("Jojo\\s+Addison\\s+Information\\s+Systems\\s+Consultancy"),
            "Jojo Addison Consultancy"
        ),
        // "BridgeCare" is only ever correct with "Abofonsa" in front of it; alone it reads as an
        // unrelated product. Where space forces a choice the short form is "Abofonsa".
        new Denied("BridgeCare without Abofonsa", Pattern.compile("(?<!Abofonsa\\s)BridgeCare"), "Abofonsa BridgeCare"),
        // The generator's baseName. It is a fine identifier and a poor product name, and it sat in
        // all six values of the default catalogue — the fallback every locale but de/en/fr resolves to.
        new Denied("adminGateway", Pattern.compile("adminGateway"), "Abofonsa BridgeCare Admin")
    );

    private record Denied(String term, Pattern pattern, String instead) {}

    @Test
    void findsEveryCatalogueThatShips() throws IOException {
        // Asserted rather than filtered. A moved or renamed catalogue silently dropping out of
        // coverage is the same failure as not checking it at all, and this test would still pass.
        assertThat(catalogues().map(path -> path.getFileName().toString()).sorted().toList()).containsExactly(
            "messages.properties",
            "messages_de.properties",
            "messages_en.properties",
            "messages_fr.properties"
        );
    }

    @Test
    void noCatalogueValueCarriesARetiredBrandTerm() throws IOException {
        List<String> offences = new ArrayList<>();

        for (Path catalogue : catalogues().sorted().toList()) {
            Properties properties = new Properties();
            try (InputStream in = Files.newInputStream(catalogue)) {
                properties.load(in);
            }
            properties
                .entrySet()
                .stream()
                .sorted(Comparator.comparing(entry -> (String) entry.getKey()))
                .forEach(entry -> offences.addAll(offences((String) entry.getValue(), catalogue.getFileName() + " -> " + entry.getKey())));
        }

        assertThat(offences).isEmpty();
    }

    @Test
    void noMailTemplateRendersARetiredBrandTerm() throws IOException {
        // The mail templates carry Thymeleaf's natural-templating fallback text, which `th:text`
        // replaces at runtime, so nothing here reaches a mailbox today. It is scanned anyway: the
        // next hardcoded line of copy goes into one of these files, not into a catalogue.
        Map<Path, String> templates = templates();

        assertThat(templates.keySet().stream().map(path -> path.getFileName().toString()).sorted().toList()).contains(
            "activationEmail.html",
            "creationEmail.html",
            "passwordResetEmail.html"
        );

        List<String> offences = templates
            .entrySet()
            .stream()
            .sorted(Comparator.comparing(entry -> entry.getKey().toString()))
            .flatMap(entry -> offences(renderedText(entry.getValue()), entry.getKey().getFileName().toString()).stream())
            .toList();

        assertThat(offences).isEmpty();
    }

    private static Stream<Path> catalogues() throws IOException {
        assertThat(I18N).as("the catalogue folder moved — this guard is reading nothing").isDirectory();
        return Files.list(I18N).filter(path -> path.getFileName().toString().endsWith(".properties"));
    }

    private static Map<Path, String> templates() throws IOException {
        assertThat(TEMPLATES).as("the template folder moved — this guard is reading nothing").isDirectory();
        try (Stream<Path> paths = Files.walk(TEMPLATES)) {
            return paths
                .filter(path -> path.getFileName().toString().endsWith(".html"))
                .collect(Collectors.toMap(path -> path, BrandTermsTest::read, (first, second) -> first, TreeMap::new));
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** What a template actually renders: no comments, no tags, entities and whitespace normalised. */
    private static String renderedText(String template) {
        return template
            .replaceAll("(?s)<!--.*?-->", " ")
            .replaceAll("<[^>]*>", " ")
            .replaceAll("(?i)&nbsp;", " ")
            .replaceAll("(?i)&[a-z]+;", " ")
            .replaceAll("\\s+", " ");
    }

    private static List<String> offences(String text, String where) {
        return DENIED.stream()
            .filter(denied -> denied.pattern().matcher(text).find())
            .map(denied -> where + ": says \"" + denied.term() + "\" - use \"" + denied.instead() + "\"")
            .toList();
    }
}
