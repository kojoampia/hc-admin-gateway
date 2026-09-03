package net.jojoaddison;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;

/**
 * Every message catalogue that ships is UTF-8, because that is the only encoding Spring Boot will
 * ever read them with.
 *
 * <p>This is the durable half of {@code docs/backlog.md} item 12. {@code messages_de.properties} was
 * ISO-8859-1 while {@code messages_fr.properties} was UTF-8, and nothing declared either — so the
 * four German values carrying an umlaut or an eszett arrived in a real mailbox as replacement
 * characters. {@code email.reset.title} is a <em>subject line</em>, so the corruption was the first
 * thing a German administrator saw. French was correct purely by having already been UTF-8.
 *
 * <p><strong>Boot's default is UTF-8 and the files did not get a vote.</strong>
 * {@code MessageSourceProperties} initialises {@code encoding} to {@link StandardCharsets#UTF_8} in
 * its constructor, and {@code application.yml} set only {@code basename}. The Java-8-era instinct
 * that "a properties file is ISO-8859-1" is the contract of
 * {@link java.util.Properties#load(java.io.InputStream)}, which is not what loads these — so it gives
 * exactly the wrong answer here. {@code spring.messages.encoding} is now set explicitly, and
 * {@link #encodingIsPinnedInConfigurationRatherThanInherited()} keeps it that way: the property is
 * redundant while every file is already UTF-8, and it is the only thing that makes the contract
 * legible to the next person adding a locale.
 *
 * <p><strong>It reads {@code src/main/resources} rather than the classpath, deliberately</strong> —
 * the same trap {@link BrandTermsTest} documents, and here it is worse. {@code src/test/resources/i18n/}
 * holds three-line ASCII stubs that shadow the real catalogues on the test classpath, and the German
 * stub does not define {@code email.reset.title} at all, so a test resolving that key through the
 * class loader falls back to the default catalogue and is handed a clean ASCII string. It would pass
 * against a corrupt file, forever.
 *
 * <p>The expected German text is written with {@code \\uXXXX} escapes rather than literal umlauts.
 * An expectation spelled with a literal {@code ü} would be vulnerable to precisely the corruption
 * this class exists to detect — re-save this file as ISO-8859-1 and both sides would rot together,
 * leaving the test green. An escape is the same character in any source encoding.
 */
class MessageCatalogueEncodingTest {

    private static final Path I18N = Path.of("src/main/resources/i18n");
    private static final Path APPLICATION_YML = Path.of("src/main/resources/config/application.yml");

    /** U+FFFD. What a decoder substitutes for a byte it cannot read, and never real copy. */
    private static final char REPLACEMENT = '�';

    /**
     * The signature of a double encode — UTF-8 bytes read as ISO-8859-1 and written back out as
     * UTF-8, which turns {@code ü} into {@code Ã¼}. The result is *valid* UTF-8, so the strict decode
     * below cannot see it; this is the trap an editor springs when it rewrites a file it was never
     * told the current encoding of. Neither character occurs in German or French copy.
     */
    private static final List<String> DOUBLE_ENCODED = List.of("Ã", "Â");

    @Test
    void findsEveryCatalogueThatShips() throws IOException {
        // Asserted rather than filtered, for BrandTermsTest's reason: a renamed catalogue dropping
        // silently out of coverage is the same as not checking it, and would leave this test green.
        assertThat(catalogues().map(path -> path.getFileName().toString()).sorted().toList()).containsExactly(
            "messages.properties",
            "messages_de.properties",
            "messages_en.properties",
            "messages_fr.properties"
        );
    }

    @Test
    void everyCatalogueIsValidUtf8() throws IOException {
        List<String> offences = new ArrayList<>();

        for (Path catalogue : catalogues().sorted().toList()) {
            byte[] bytes = Files.readAllBytes(catalogue);
            // REPORT, not REPLACE: the whole defect is that the lenient path substitutes a character
            // and carries on, which is what made this invisible in the first place.
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
            try {
                decoder.decode(ByteBuffer.wrap(bytes));
            } catch (CharacterCodingException e) {
                offences.add(
                    catalogue.getFileName() +
                    " is not UTF-8 (" +
                    e +
                    "). Spring Boot reads every catalogue as UTF-8; convert the file, do not change the encoding."
                );
            }
        }

        assertThat(offences).isEmpty();
    }

    @Test
    void noCatalogueValueIsAlreadyMojibake() throws IOException {
        List<String> offences = new ArrayList<>();

        for (Path catalogue : catalogues().sorted().toList()) {
            Properties properties = decodedAsTheApplicationWould(catalogue);
            for (String key : properties.stringPropertyNames()) {
                String value = properties.getProperty(key);
                String where = catalogue.getFileName() + " -> " + key;
                if (value.indexOf(REPLACEMENT) >= 0) {
                    offences.add(where + ": contains U+FFFD - the file is not UTF-8");
                }
                for (String marker : DOUBLE_ENCODED) {
                    if (value.contains(marker)) {
                        offences.add(where + ": contains \"" + marker + "\" - it looks double-encoded");
                    }
                }
            }
        }

        assertThat(offences).isEmpty();
    }

    /**
     * The behavioural half: the German password-reset subject, resolved the way {@code MailService}
     * resolves it, off the files that actually ship.
     *
     * <p>{@code MailService.sendEmailFromTemplateSync} takes the subject straight from
     * {@code MessageSource.getMessage(titleKey, null, locale)}, so this is the same call on the same
     * key. It is a {@link ReloadableResourceBundleMessageSource} over a {@code file:} basename rather
     * than Boot's classpath-based {@code ResourceBundleMessageSource} for one reason only — the
     * classpath here is the stub directory. Both decode through an {@code InputStreamReader} on the
     * configured charset, so the step under test is identical.
     */
    @Test
    void germanUmlautsSurviveTheDecodeThatBootActuallyPerforms() {
        MessageSource messages = messageSourceOverTheFilesThatShip();

        // "Abofonsa BridgeCare Admin Passwort zurücksetzen" - a subject line, so this is the first
        // thing the recipient reads, before they have opened anything.
        assertThat(messages.getMessage("email.reset.title", null, Locale.GERMAN)).isEqualTo(
            "Abofonsa BridgeCare Admin Passwort zurücksetzen"
        );

        // "Liebe Grüße," - carries both of the characters ISO-8859-1 and UTF-8 disagree about.
        assertThat(messages.getMessage("email.activation.text2", null, Locale.GERMAN)).isEqualTo("Liebe Grüße,");
        assertThat(messages.getMessage("email.reset.text2", null, Locale.GERMAN)).isEqualTo("Liebe Grüße,");

        assertThat(messages.getMessage("email.reset.text1", null, Locale.GERMAN)).startsWith("Für Ihren").contains("zurückzusetzen");

        // French was already UTF-8 and correct. Asserted so that a re-encode of the whole folder
        // cannot fix German by breaking the locale that was never broken.
        assertThat(messages.getMessage("error.title", null, Locale.FRENCH)).isEqualTo("Votre demande ne peut être traitée");
        assertThat(messages.getMessage("email.reset.title", null, Locale.FRENCH)).isEqualTo(
            "Abofonsa BridgeCare Admin Réinitialisation de mot de passe"
        );
    }

    @Test
    void encodingIsPinnedInConfigurationRatherThanInherited() throws IOException {
        String yml = Files.readString(APPLICATION_YML, StandardCharsets.UTF_8);

        // Inheriting Boot's UTF-8 default gives the same behaviour today. Declaring it is what turns
        // a catalogue added in the wrong encoding from silent mojibake into something a reader can
        // see, and what stops a future Boot default from moving this quietly.
        assertThat(yml)
            .as("spring.messages.encoding must stay declared - see this class's Javadoc")
            .containsPattern("(?m)^ {2}messages:\\n(?: {4}.*\\n)* {4}encoding: UTF-8$");
    }

    /**
     * A {@link MessageSource} over the catalogues in {@code src/main/resources}, configured the way
     * Boot configures the application's own: UTF-8, which is
     * {@code MessageSourceProperties}'s default and now also {@code application.yml}'s explicit value.
     *
     * <p>Absolute {@code file:} basename, because the classpath is the stub directory. Boot builds a
     * {@code ResourceBundleMessageSource} instead, but both decode through an
     * {@code InputStreamReader} on the configured charset, so the step under test is the same one.
     */
    private static MessageSource messageSourceOverTheFilesThatShip() {
        ReloadableResourceBundleMessageSource source = new ReloadableResourceBundleMessageSource();
        source.setBasename("file:" + I18N.toAbsolutePath().resolve("messages"));
        source.setDefaultEncoding(StandardCharsets.UTF_8.name());
        // Off, so a missing German key surfaces as a failure here rather than resolving out of
        // whatever locale the machine running the build happens to be set to.
        source.setFallbackToSystemLocale(false);
        return source;
    }

    /**
     * One catalogue's entries, decoded exactly as the running application decodes them: UTF-8, and
     * <em>lenient</em>, so a byte that is not valid UTF-8 becomes U+FFFD rather than throwing.
     * Leniency is the point — it reproduces the corruption instead of hiding it behind an exception,
     * which is what {@link #noCatalogueValueIsAlreadyMojibake()} then looks for.
     *
     * <p>{@link Properties#load(Reader)}, not {@code load(InputStream)}: the reader overload takes the
     * characters it is given, while the stream overload would impose ISO-8859-1 and quietly undo the
     * decode this method exists to perform.
     */
    private static Properties decodedAsTheApplicationWould(Path catalogue) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = new InputStreamReader(Files.newInputStream(catalogue), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    private static Stream<Path> catalogues() throws IOException {
        assertThat(I18N).as("the catalogue folder moved - this guard is reading nothing").isDirectory();
        return Files.list(I18N).filter(path -> path.getFileName().toString().endsWith(".properties"));
    }
}
