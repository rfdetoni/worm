package com.github.rfdetoni.worm.web;

import com.github.rfdetoni.worm.query.Pageable;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.util.Optional;

/**
 * Resolves controller method parameters of type {@link Pageable} from HTTP request parameters.
 * Recognized parameters: page (int), size (int), sort (property[,direction]).
 */
public class WormPageableArgumentResolver implements HandlerMethodArgumentResolver {

    private static final int DEFAULT_PAGE = 0;
    private static final int DEFAULT_SIZE = 20;

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return Pageable.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) throws Exception {

        int page = parseIntSafe(webRequest.getParameter("page"), DEFAULT_PAGE);
        int size = parseIntSafe(webRequest.getParameter("size"), DEFAULT_SIZE);

        Pageable.Sort sort = Optional.ofNullable(webRequest.getParameterValues("sort"))
                .map(this::parseSort)
                .orElse(Pageable.Sort.asc("id"));

        return Pageable.of(page, size, sort);
    }

    private int parseIntSafe(String raw, int fallback) {
        if (raw == null || raw.isEmpty()) return fallback;
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private Pageable.Sort parseSort(String[] sortParams) {
        if (sortParams == null || sortParams.length == 0 || sortParams[0] == null || sortParams[0].isEmpty()) {
            return Pageable.Sort.asc("id");
        }

        String[] parts = sortParams[0].split(",");
        String property = parts[0].trim();
        Pageable.Direction direction = (parts.length > 1 && parts[1] != null && !parts[1].isEmpty())
                ? Pageable.Direction.valueOf(parts[1].trim().toUpperCase())
                : Pageable.Direction.ASC;

        return Pageable.Sort.by(property, direction);
    }
}

