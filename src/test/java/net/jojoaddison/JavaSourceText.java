package net.jojoaddison;

/**
 * Just enough Java lexing for the source-reading guards in this repository to grade <b>code</b>
 * rather than the prose about it.
 *
 * <h2>Why this is shared rather than copied</h2>
 *
 * <p>Two guards here read {@code src/main/java} as text: {@code LoginAttemptNeverLoggedTest}, which
 * refuses a log statement that names an entered login, and {@code SecurityConfigurationOrderTest},
 * which reads the authorization chain's order and authorities. Both need the same thing and both got
 * it wrong in opposite directions before this class existed:
 *
 * <ul>
 *   <li>The sweep scanned raw text, so a javadoc quoting {@code LOG.debug("… {}", attempt)} — the
 *       mistake the rule exists to prevent — failed the rule. Worse, a head inside a comment has no
 *       argument list of its own, so the forward scan ran on and graded <em>the next real
 *       statement</em>, which is fail-open.</li>
 *   <li>{@code ruleFor} did no stripping at all, which is fail-open the other way: a comment quoting
 *       a matcher above a widened real rule would be graded <em>as</em> the rule.</li>
 * </ul>
 *
 * <p>A second copy of a lexer is a second thing to drift, and these two guards protect decisions that
 * are supposed to hold together.
 *
 * <h2>⚠ Deliberately not a parser, and a regex is not an option</h2>
 *
 * <p>{@code /\*.*?\*}{@code /} matches the {@code /*} inside the literal {@code "/api/**"} and eats
 * everything up to the {@code *}{@code /} inside the next path string — silently deleting real code
 * from a file that is nothing but path strings. String literals are therefore copied through
 * untouched here, so no path can be mistaken for a comment opener.
 *
 * <p>This is enough Java to find the end of a literal and the end of a comment. It knows nothing
 * about generics, annotations or statements, and it should stay that way.
 */
public final class JavaSourceText {

    private JavaSourceText() {}

    /**
     * The source with every comment removed and every literal kept.
     *
     * <p>A comment is consumed <b>whole, from its opening {@code /}</b>, so an apostrophe inside one
     * — {@code // it's fine} — is never dispatched on as a character literal. That is what keeps the
     * two apart; it is <em>not</em> the order of the branches, which are mutually exclusive.
     *
     * <p><b>One order is load-bearing:</b> {@code """} is tested before {@code "}. Both guards are
     * true at the start of a text block, and reading one as an empty string leaves the scan inside
     * the block.
     *
     * <p>Newlines inside a removed block comment are preserved, so the code on either side of one
     * does not join into a single apparent statement.
     */
    public static String withoutComments(String source) {
        StringBuilder code = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '/' && source.startsWith("//", i)) {
                int newline = source.indexOf('\n', i);
                i = newline < 0 ? source.length() : newline;
            } else if (c == '/' && source.startsWith("/*", i)) {
                int close = source.indexOf("*/", i + 2);
                int end = close < 0 ? source.length() : close + 2;
                source
                    .substring(i, end)
                    .chars()
                    .filter(ch -> ch == '\n')
                    .forEach(ch -> code.append('\n'));
                i = end;
            } else if (source.startsWith("\"\"\"", i)) {
                int end = endOfTextBlock(source, i);
                end = end < 0 ? source.length() : end;
                code.append(source, i, end);
                i = end;
            } else if (c == '"' || c == '\'') {
                int end = endOfLiteral(source, i, c);
                end = end < 0 ? source.length() : end;
                code.append(source, i, end);
                i = end;
            } else {
                code.append(c);
                i++;
            }
        }
        return code.toString();
    }

    /**
     * Every run of whitespace removed.
     *
     * <p>For guards that compare against a written-out form: Prettier formats Java in this repository
     * and a cosmetic line wrap must not change a verdict. It collapses whitespace <em>inside</em>
     * string literals too, which is fine for both callers and would not be for a guard that compared
     * message text.
     */
    public static String collapseWhitespace(String source) {
        return source.replaceAll("\\s+", "");
    }

    /** The index just past a {@code "…"} or {@code '…'} literal opening at {@code open}, or {@code -1}. */
    public static int endOfLiteral(String source, int open, char quote) {
        int i = open + 1;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '\\') {
                i += 2;
            } else if (c == quote) {
                return i + 1;
            } else {
                i++;
            }
        }
        return -1;
    }

    /** The index just past a {@code """…"""} text block opening at {@code open}, or {@code -1}. */
    public static int endOfTextBlock(String source, int open) {
        int i = open + 3;
        while (i < source.length()) {
            if (source.charAt(i) == '\\') {
                i += 2;
            } else if (source.startsWith("\"\"\"", i)) {
                return i + 3;
            } else {
                i++;
            }
        }
        return -1;
    }
}
