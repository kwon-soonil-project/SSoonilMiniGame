package com.minigame.platform.shared.config;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorViewResolver;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.ModelAndView;

import java.util.Map;
import java.util.Set;

@Component
public class SpaErrorViewResolver implements ErrorViewResolver {
    private static final Set<String> BACKEND_NAMESPACES = Set.of("api", "ws", "actuator");

    @Override
    public ModelAndView resolveErrorView(
            HttpServletRequest request,
            HttpStatus status,
            Map<String, Object> model
    ) {
        if (status != HttpStatus.NOT_FOUND || !"GET".equals(request.getMethod())) return null;
        var path = errorPath(request);
        if (!isUiPath(path)) return null;

        var view = new ModelAndView("forward:/index.html");
        view.setStatus(HttpStatus.OK);
        return view;
    }

    private static String errorPath(HttpServletRequest request) {
        var value = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        return value instanceof String path ? path : "";
    }

    private static boolean isUiPath(String path) {
        var normalized = path.startsWith("/") ? path.substring(1) : path;
        if (normalized.isBlank() || normalized.contains(".")) return false;
        return !BACKEND_NAMESPACES.contains(normalized.split("/", 2)[0]);
    }
}
