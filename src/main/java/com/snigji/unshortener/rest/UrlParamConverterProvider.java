package com.snigji.unshortener.rest;

import com.snigji.unshortener.resolver.UrlNormalizer;
import jakarta.ws.rs.ext.ParamConverter;
import jakarta.ws.rs.ext.ParamConverterProvider;
import jakarta.ws.rs.ext.Provider;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.net.URI;

/**
 * Runs url query params through UrlNormalizer at parameter-binding time, so a
 * malformed url never reaches a resource method body. UrlNormalizer throws
 * BadRequestException itself, so no wrapping is needed here — it propagates
 * through RESTEasy's parameter conversion unchanged.
 *
 * <p>Registering any ParamConverterProvider makes RESTEasy Reactive consult it for
 * every parameter type in the deployment, not just URI. getConverter returns null
 * for anything else, which falls back to default handling — no effect on the
 * profile query param (still a plain String).
 */
@Provider
public class UrlParamConverterProvider implements ParamConverterProvider {

    private static final ParamConverter<URI> URL_CONVERTER = new ParamConverter<>() {
        @Override
        public URI fromString(String value) {
            return UrlNormalizer.normalizeInput(value);
        }

        @Override
        public String toString(URI value) {
            return value.toString();
        }
    };

    @Override
    @SuppressWarnings("unchecked")
    public <T> ParamConverter<T> getConverter(Class<T> rawType, Type genericType, Annotation[] annotations) {
        return rawType == URI.class ? (ParamConverter<T>) URL_CONVERTER : null;
    }
}
