package com.snigji.unshortener.resolver;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Static scan for {@code <meta http-equiv="refresh" content="0;url=...">}.
 * Handles comments, quoted/unquoted attribute values, either attribute order,
 * entity-escaped URLs, and case-insensitive {@code url=}.
 */
public final class MetaRefreshScanner {

    private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    private static final Pattern META_TAG = Pattern.compile("<meta\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTTP_EQUIV_REFRESH =
            Pattern.compile("http-equiv\\s*=\\s*(['\"]?)refresh\\1", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONTENT_QUOTED =
            Pattern.compile("content\\s*=\\s*(['\"])(.*?)\\1", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONTENT_UNQUOTED =
            Pattern.compile("content\\s*=\\s*([^\\s>]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern URL_IN_CONTENT =
            Pattern.compile("url\\s*=\\s*['\"]?\\s*([^'\">]+)", Pattern.CASE_INSENSITIVE);

    private MetaRefreshScanner() {
    }

    public static Optional<String> findRefreshUrl(String html) {
        if (html == null || html.isEmpty()) {
            return Optional.empty();
        }
        String cleaned = COMMENT.matcher(html).replaceAll("");
        Matcher tagMatcher = META_TAG.matcher(cleaned);
        while (tagMatcher.find()) {
            String tag = tagMatcher.group();
            if (!HTTP_EQUIV_REFRESH.matcher(tag).find()) {
                continue;
            }
            String content = extractContent(tag);
            if (content == null) {
                continue;
            }
            Matcher urlMatcher = URL_IN_CONTENT.matcher(content);
            if (urlMatcher.find()) {
                String url = unescapeEntities(urlMatcher.group(1).trim());
                if (!url.isEmpty()) {
                    return Optional.of(url);
                }
            }
        }
        return Optional.empty();
    }

    private static String extractContent(String tag) {
        Matcher quoted = CONTENT_QUOTED.matcher(tag);
        if (quoted.find()) {
            return quoted.group(2);
        }
        Matcher unquoted = CONTENT_UNQUOTED.matcher(tag);
        return unquoted.find() ? unquoted.group(1) : null;
    }

    private static String unescapeEntities(String s) {
        return s.replace("&amp;", "&").replace("&#38;", "&").replace("&#x26;", "&");
    }
}
