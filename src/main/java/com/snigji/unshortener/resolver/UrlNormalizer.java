package com.snigji.unshortener.resolver;

import jakarta.ws.rs.BadRequestException;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Pattern;

public final class UrlNormalizer {

    private static final Logger LOG = Logger.getLogger(UrlNormalizer.class);
    private static final Pattern HAS_SCHEME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*://.*");

    private UrlNormalizer() {
    }

    /**
     * Normalizes caller-supplied input: assumes {@code https://} when no scheme is
     * given, and rejects anything that isn't http(s) on input (redirect *targets*
     * with other schemes are handled separately — see {@link IntentUrlParser} and
     * the {@code Store} destination).
     */
    public static URI normalizeInput(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BadRequestException("url must not be blank");
        }
        String candidate = raw.trim();
        if (!HAS_SCHEME.matcher(candidate).matches()) {
            candidate = "https://" + candidate;
        }
        URI uri;
        try {
            uri = new URI(candidate);
        } catch (URISyntaxException e) {
            throw new BadRequestException("malformed url: " + e.getMessage(), e);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new BadRequestException("unsupported input scheme: " + scheme);
        }
        if (uri.getHost() == null) {
            throw new BadRequestException("url has no host");
        }
        URI normalized = normalizeHttp(uri);
        LOG.debugf("normalized input %s -> %s", raw, normalized);
        return normalized;
    }

    /**
     * Normalizes an http(s) URI for caching/loop-detection purposes: lowercases
     * scheme and host, strips default ports, drops the fragment. The path is left
     * exactly as-is — short codes are case-sensitive ({@code /AbC} != {@code /abc}).
     *
     * <p>Only call this for http(s) targets. Other schemes (intent://, market://)
     * have syntax (semicolon-delimited fragments) that this would corrupt, and are
     * never re-normalized — they're terminal the moment they're seen.
     */
    public static URI normalizeHttp(URI uri) {
        String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? null : uri.getHost().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        if (("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443)) {
            port = -1;
        }
        try {
            return new URI(scheme, uri.getUserInfo(), host, port, uri.getPath(), uri.getQuery(), null);
        } catch (URISyntaxException e) {
            return uri;
        }
    }

    public static boolean isHttp(URI uri) {
        String scheme = uri.getScheme();
        return scheme != null && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"));
    }
}
