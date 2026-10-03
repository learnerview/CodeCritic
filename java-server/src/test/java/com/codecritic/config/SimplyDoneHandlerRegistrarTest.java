package com.codecritic.config;

import com.codecritic.service.AnalysisService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.learnerview.simplydone4j.handler.HandlerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

/**
 * These three job-type identifiers are the contract between the submit endpoints, the queue
 * and the handler that eventually runs the analysis. They used to be declared twice — once
 * here and once in the coordinator's synchronous fallback — so a rename in one place could
 * leave jobs submitted under a type nothing ever handles, with no compile error and no test
 * failure. They are public now precisely so the second copy can be deleted.
 */
class SimplyDoneHandlerRegistrarTest {

    private HandlerRegistry handlerRegistry;
    private SimplyDoneHandlerRegistrar registrar;

    @BeforeEach
    void setUp() {
        handlerRegistry = new HandlerRegistry();
        registrar = new SimplyDoneHandlerRegistrar(handlerRegistry, mock(AnalysisService.class), new ObjectMapper());
    }

    @Test
    void jobTypeIdentifiersKeepTheirWireValues() {
        assertEquals("complexity-analysis", SimplyDoneHandlerRegistrar.COMPLEXITY_JOB);
        assertEquals("bug-detection", SimplyDoneHandlerRegistrar.BUGS_JOB);
        assertEquals("test-generation", SimplyDoneHandlerRegistrar.TESTS_JOB);
    }

    @Test
    void registersAHandlerForEveryDeclaredJobType() {
        registrar.run(new DefaultApplicationArguments(new String[0]));

        assertEquals(SimplyDoneHandlerRegistrar.JOB_TYPES, handlerRegistry.getHandlers().keySet());
    }

    @Test
    void everyDeclaredJobTypeResolvesToARunnableHandler() {
        registrar.run(new DefaultApplicationArguments(new String[0]));

        for (String jobType : SimplyDoneHandlerRegistrar.JOB_TYPES) {
            assertNotNull(handlerRegistry.getHandler(jobType), "no handler registered for " + jobType);
        }
    }
}
