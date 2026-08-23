package com.minigame.platform.shared.config;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.View;

import java.util.Set;

@Controller
public class SpaForwardController implements ErrorController {
    private static final Set<String> BACKEND_NAMESPACES = Set.of("api", "ws", "actuator");
    private static final View EMPTY_ERROR_VIEW = (model, request, response) -> {
        // The response status is the complete non-SPA error contract.
    };

    @RequestMapping("/error")
    public ModelAndView forward(HttpServletRequest request, HttpServletResponse response) {
        var status = errorStatus(request);
        var path = errorPath(request);
        if (status == HttpServletResponse.SC_NOT_FOUND
                && "GET".equals(request.getMethod())
                && isUiPath(path)) {
            response.setStatus(HttpServletResponse.SC_OK);
            return new ModelAndView("forward:/index.html");
        }
        response.setStatus(status);
        return new ModelAndView(EMPTY_ERROR_VIEW);
    }

    private static int errorStatus(HttpServletRequest request) {
        var value = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        return value instanceof Integer status ? status : HttpServletResponse.SC_NOT_FOUND;
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
