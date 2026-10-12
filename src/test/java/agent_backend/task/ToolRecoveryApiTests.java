package agent_backend.task;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ToolRecoveryApiTests {
    final ToolRecoveryService service=mock(ToolRecoveryService.class);
    final String run=UUID.randomUUID().toString(), invocation=UUID.randomUUID().toString();
    final String path="/runs/"+run+"/tools/"+invocation+"/recovery";
    final MockMvc mvc=MockMvcBuilders.standaloneSetup(new ToolRecoveryController(service))
            .setControllerAdvice(new TaskApiExceptionHandler()).build();

    @Test void malformedRequestsDoNotReachService() throws Exception {
        for(String body : new String[]{"{}", "{\"requestId\":\"bad\",\"action\":\"CONFIRM\"}",
                "{\"requestId\":\""+UUID.randomUUID()+"\",\"action\":\"RETRY\"}"}) {
            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/runs/bad/tools/"+invocation+"/recovery")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void recoveryPostPassesExplicitRequestAndReadDoesNotMutate() throws Exception {
        var request=UUID.randomUUID();
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\""+request+"\",\"action\":\"RECONCILE\"}")).andExpect(status().isOk());
        verify(service).recover(run,invocation,new ToolRecoveryService.Request(request,
                ToolRecoveryService.Action.RECONCILE,null,null,null));
        clearInvocations(service);
        mvc.perform(get(path)).andExpect(status().isOk());
        verify(service).get(run,invocation);
        verifyNoMoreInteractions(service);
    }
    @Test void storageFailureIs503AndConflictIs409() throws Exception {
        when(service.get(run,invocation)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("down"));
        mvc.perform(get(path)).andExpect(status().isServiceUnavailable());
        doThrow(new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.CONFLICT)).when(service).get(run,invocation);
        mvc.perform(get(path)).andExpect(status().isConflict());
    }
}
