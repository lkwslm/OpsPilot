package io.github.opspilot.sample.inventory;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
        "spring.profiles.active=production"
})
@AutoConfigureMockMvc
final class InventoryProductionRouteTest {
    @Autowired MockMvc mvc;
    @MockitoBean InventoryApplicationService service;
    @MockitoBean InventoryRepository repository;

    @Test
    void productionProfileHasNoFaultRoute() throws Exception {
        mvc.perform(post("/internal/faults/reset")).andExpect(status().isNotFound());
    }
}
