package com.minigame.platform.shared.config;

import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SpaForwardControllerTest {
    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new SpaForwardController())
            .build();

    @Test
    void forwardsExtensionlessUiRoutesToTheVueEntryPoint() throws Exception {
        mockMvc.perform(errorDispatch("/lobby"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));

        mockMvc.perform(errorDispatch("/rooms/123456"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
    }

    @Test
    void leavesTheRootEntryPointToSpringsStaticResourceHandler() throws Exception {
        var result = mockMvc.perform(get("/"))
                .andExpect(status().isNotFound())
                .andReturn();

        assertThat(result.getHandler()).isNull();
    }

    @Test
    void leavesVersionedAssetsToSpringsStaticResourceHandler() throws Exception {
        var result = mockMvc.perform(get("/assets/application.css"))
                .andExpect(status().isNotFound())
                .andReturn();

        assertThat(result.getHandler()).isNull();
    }

    @Test
    void doesNotForwardBackendOrStaticResourceNamespaces() throws Exception {
        mockMvc.perform(errorDispatch("/api/missing"))
                .andExpect(status().isNotFound());
        mockMvc.perform(errorDispatch("/ws/missing"))
                .andExpect(status().isNotFound());
        mockMvc.perform(errorDispatch("/actuator/missing"))
                .andExpect(status().isNotFound());
        mockMvc.perform(errorDispatch("/assets/missing.js"))
                .andExpect(status().isNotFound());
        mockMvc.perform(errorDispatch("/favicon.ico"))
                .andExpect(status().isNotFound());
    }

    @Test
    void forwardsOnlyGetRequestsThatOriginallyFailedWithNotFound() throws Exception {
        mockMvc.perform(get("/error")
                        .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/lobby")
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 500))
                .andExpect(status().isInternalServerError());
        mockMvc.perform(post("/error")
                        .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/lobby")
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 404))
                .andExpect(status().isNotFound());
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder errorDispatch(String path) {
        return get("/error")
                .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, path)
                .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 404);
    }
}
