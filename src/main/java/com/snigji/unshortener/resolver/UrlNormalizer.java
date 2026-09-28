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
     * <p>Rebuilds from the <em>raw</em> (still percent-encoded) components and
     * reparses that string, rather than using the multi-arg {@link URI} constructor,
     * which takes decoded components and would silently mangle a redirect target
     * whose path or query carries encoded delimiters — e.g. a tracker URL embedding
     * another URL as {@code ?u=https%3A%2F%2Fx.com%2Fa%3Fb%3D1%26c%3D2} would have its
     * embedded query merged into the outer one.
     *
     * <p>Only call this for http(s) targets. Other schemes (intent://, market://)
     * have syntax (semicolon-delimited fragments) that this would corrupt, and are
     * never re-normalized — they're terminal the moment they're seen.
     */
    public static URI normalizeHttp(URI uri) {
        String host = uri.getHost();
        if (host == null) {
            // No parsed authority (opaque URI, or a host Java can't parse as one, e.g. one
            // containing '_') — nothing safe to normalize; hand it back untouched rather
            // than silently dropping the authority.
            return uri;
        }
        String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        if (("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443)) {
            port = -1;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(scheme).append("://");
        if (uri.getRawUserInfo() != null) {
            sb.append(uri.getRawUserInfo()).append('@');
        }
        sb.append(host.toLowerCase(Locale.ROOT));
        if (port != -1) {
            sb.append(':').append(port);
        }
        if (uri.getRawPath() != null) {
            sb.append(uri.getRawPath());
        }
        if (uri.getRawQuery() != null) {
            sb.append('?').append(uri.getRawQuery());
        }
        try {
            return new URI(sb.toString());
        } catch (URISyntaxException e) {
            LOG.warnf(e, "failed to re-parse normalized URI %s, returning original", sb);
            return uri;
        }
    }

    public static boolean isHttp(URI uri) {
        String scheme = uri.getScheme();
        return scheme != null && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"));
    }
}
