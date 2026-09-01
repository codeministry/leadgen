package de.codeministry.leadgen.web;


import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

// Boot 4 moved the slice annotation out of `...test.autoconfigure.web.servlet`
// into the `spring-boot-webmvc-test` module. The old import compiles against
// Boot 3 and simply does not exist here.
@WebMvcTest(StatusController.class)
@TestPropertySource(properties = {"spring.application.name=lead-generation", "leadgen.version=0.1.0"})
class StatusControllerTest {

    @Autowired
    private MockMvcTester mvc;

    @Test
    void returnsApplicationNameAndVersion() {
        Assertions.assertThat(mvc.get().uri("/api/status"))
                .hasStatusOk()
                .bodyJson()
                .isEqualTo("{\"application\":\"lead-generation\",\"version\":\"0.1.0\"}");
    }
}
