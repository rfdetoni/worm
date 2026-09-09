package com.github.rfdetoni.worm.web;

import com.github.rfdetoni.worm.query.Pageable;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.util.Locale;
import java.util.Optional;

/**
 * Resolves controller method parameters of type {@link Pageable} from HTTP request parameters.
 * Recognized parameters: page (int), size (int), sort (property[,direction]).
 *
 * <p>HTTP page sizes are deliberately bounded to protect database and application memory from
 * accidental or abusive requests. Internal callers can still create larger {@link Pageable}
 * instances directly when a bulk operation is intentional.</p>
 */
public class WormPageableArgumentResolver implements HandlerMethodArgumentResolver {

    private static final int DEFAULT_PAGE = 0;
    private static final int DEFAULT_SIZE = 20;
    private static final int DEFAULT_MAX_SIZE = 200;

    private final int maxPageSize;

    public WormPageableArgumentResolver() {
        this(DEFAULT_MAX_SIZE);
    }

    public WormPageableArgumentResolver(int maxPageSize) {
        this.maxPageSize = Math.max(1, maxPageSize);
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return Pageable.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {

        int page = parseIntSafe(webRequest.getParameter("page"), DEFAULT_PAGE);
        int requestedSize = parseIntSafe(webRequest.getParameter("size"), DEFAULT_SIZE);
        int size = Math.min(Math.max(1, requestedSize), maxPageSize);

        Pageable.Sort sort = Optional.ofNullable(webRequest.getParameterValues("sort"))
                .map(this::parseSort)
                .orElse(Pageable.Sort.asc("id"));

        return Pageable.of(page, size, sort);
    }

    private int parseIntSafe(String raw, int fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private Pageable.Sort parseSort(String[] sortParams) {
        if (sortParams == null || sortParams.length == 0 || sortParams[0] == null || sortParams[0].isBlank()) {
            return Pageable.Sort.asc("id");
        }

        String[] parts = sortParams[0].split(",", 2);
        String property = parts[0].trim();
        Pageable.Direction direction = parseDirection(parts.length > 1 ? parts[1] : null);
        return Pageable.Sort.by(property, direction);
    }

    private Pageable.Direction parseDirection(String raw) {
        if (raw == null || raw.isBlank()) {
            return Pageable.Direction.ASC;
        }
        return "DESC".equals(raw.trim().toUpperCase(Locale.ROOT))
                ? Pageable.Direction.DESC
                : Pageable.Direction.ASC;
    }
}
