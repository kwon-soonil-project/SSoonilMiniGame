package com.minigame.platform.shared.config;

import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.web.ErrorProperties;
import org.springframework.boot.webmvc.autoconfigure.error.BasicErrorController;
import org.springframework.boot.webmvc.error.DefaultErrorAttributes;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.TEXT_HTML;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SpaErrorViewResolverTest {
    private final SpaErrorViewResolver resolver = new SpaErrorViewResolver();
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new BasicErrorController(
            new DefaultErrorAttributes(),
            new ErrorProperties(),
            List.of(resolver)
    )).build();

    @Test
    void forwardsOnlyExtensionlessUiGetNotFoundToTheVueEntryPoint() throws Exception {
        mockMvc.perform(errorRequest("/rooms/123456", 404).accept(TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
    }

    @Test
    void preservesSpringBootJsonErrorsForApiNotFoundAndServerFailure() throws Exception {
        mockMvc.perform(errorRequest("/api/v1/missing", 404).accept(APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"));

        mockMvc.perform(errorRequest("/api/v1/failure", 500).accept(APPLICATION_JSON))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.error").value("Internal Server Error"));
    }

    @Test
    void doesNotResolveAssetsBackendNamespacesNonGetOrNonNotFoundErrors() {
        assertThat(resolve("GET", "/assets/application.js", HttpStatus.NOT_FOUND)).isNull();
        assertThat(resolve("GET", "/api/v1/missing", HttpStatus.NOT_FOUND)).isNull();
        assertThat(resolve("GET", "/ws/missing", HttpStatus.NOT_FOUND)).isNull();
        assertThat(resolve("GET", "/actuator/missing", HttpStatus.NOT_FOUND)).isNull();
        assertThat(resolve("POST", "/lobby", HttpStatus.NOT_FOUND)).isNull();
        assertThat(resolve("GET", "/lobby", HttpStatus.INTERNAL_SERVER_ERROR)).isNull();
    }

    private Object resolve(String method, String path, HttpStatus status) {
        var request = new MockHttpServletRequest(method, "/error");
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, path);
        return resolver.resolveErrorView(request, status, Map.of());
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder errorRequest(
            String path,
            int status
    ) {
        return get("/error")
                .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, path)
                .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, status);
    }
}
