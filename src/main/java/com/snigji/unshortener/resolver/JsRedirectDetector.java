package com.snigji.unshortener.resolver;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Static (non-executing) scan for JS redirect intent on a terminal 200 HTML
 * response: {@code window.location}/{@code location.href =}/{@code location.replace(},
 * a single auto-submitting form, or a {@code <noscript>} block containing a link.
 * No headless browser — see plan rationale. Extraction is best-effort, for logging;
 * the API only needs to know redirect intent was detected at all.
 */
public final class JsRedirectDetector {

    private static final Pattern LOCATION_REPLACE =
            Pattern.compile("location\\.replace\\s*\\(\\s*['\"]([^'\"]+)['\"]", Pattern.CASE_INSENSITIVE);
    private static final Pattern LOCATION_ASSIGN =
            Pattern.compile("(?:window\\.)?location(?:\\.href)?\\s*=\\s*['\"]([^'\"]+)['\"]", Pattern.CASE_INSENSITIVE);
    private static final Pattern LOCATION_PRESENCE =
            Pattern.compile("(?:window\\.location\\s*=|location\\.href\\s*=|location\\.replace\\s*\\()",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern NOSCRIPT =
            Pattern.compile("<noscript\\b[^>]*>(.*?)</noscript>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ANCHOR_HREF =
            Pattern.compile("<a\\b[^>]*\\bhref\\s*=\\s*['\"]([^'\"]+)['\"]", Pattern.CASE_INSENSITIVE);
    private static final Pattern FORM = Pattern.compile("<form\\b[^>]*>.*?</form>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern FORM_ACTION = Pattern.compile("\\baction\\s*=\\s*['\"]([^'\"]+)['\"]", Pattern.CASE_INSENSITIVE);
    private static final Pattern AUTO_SUBMIT =
            Pattern.compile("\\.submit\\s*\\(\\s*\\)", Pattern.CASE_INSENSITIVE);

    private JsRedirectDetector() {
    }

    /** Empty = no redirect intent detected. Present-but-blank = intent detected, no URL extracted. */
    public static Optional<String> detect(String html) {
        if (html == null || html.isEmpty()) {
            return Optional.empty();
        }
        Matcher replace = LOCATION_REPLACE.matcher(html);
        if (replace.find()) {
            return Optional.of(replace.group(1));
        }
        Matcher assign = LOCATION_ASSIGN.matcher(html);
        if (assign.find()) {
            return Optional.of(assign.group(1));
        }
        Matcher noscript = NOSCRIPT.matcher(html);
        if (noscript.find()) {
            Matcher href = ANCHOR_HREF.matcher(noscript.group(1));
            if (href.find()) {
                return Optional.of(href.group(1));
            }
        }
        Matcher form = FORM.matcher(html);
        if (form.find() && AUTO_SUBMIT.matcher(html).find()) {
            Matcher action = FORM_ACTION.matcher(form.group());
            if (action.find()) {
                return Optional.of(action.group(1));
            }
        }
        if (LOCATION_PRESENCE.matcher(html).find()) {
            return Optional.of("");
        }
        return Optional.empty();
    }
}
