package agent_backend;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import agent_backend.persistence.*;
import agent_backend.persistence.jdbc.JdbcToolRecoveryStore;
import agent_backend.security.TaskIdentity;
import agent_backend.task.*;
import agent_backend.tool.*;
import agent_backend.tool.result.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties="agent.scheduler.enabled=false")
@Import(ToolRecoveryMySqlTests.Adapters.class)
@Testcontainers(disabledWithoutDocker=true)
class ToolRecoveryMySqlTests {
    // These tests exercise the underlying recovery contract with a fake write adapter, not MVP admission.
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    agent_backend.security.ToolAccess access;
    @Container static final MySQLContainer mysql = new MySQLContainer("mysql:8.4")
            .withCommand("--log-bin-trust-function-creators=1");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> mysql.getJdbcUrl()
                + (mysql.getJdbcUrl().contains("?") ? "&" : "?")
                + "connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }
    @TestConfiguration static class Adapters {
        @Bean FakeWrite fakeWrite() { return new FakeWrite(); }
        @Bean ManualWrite manualWrite() { return new ManualWrite(); }
    }
    public record Input(String value) {}
    public record Output(String receipt) {}
    static class ManualWrite implements AgentTool<Input,Output> {
        public String name() { return "manual_write"; }
        public String description() { return "Test manual adapter"; }
        public Class<Input> inputType() { return Input.class; }
        public Class<Output> outputType() { return Output.class; }
        public Input inputExample() { return new Input("test"); }
        public Output outputExample() { return new Output("receipt"); }
        public Output execute(Input input) { throw new IllegalStateException("ambiguous write"); }
    }
    static class FakeWrite extends ManualWrite {
        final Map<String,Output> external = new ConcurrentHashMap<>();
        final AtomicInteger effects = new AtomicInteger(), lookups = new AtomicInteger();
        volatile String version = "1";
        volatile CountDownLatch entered, release;
        public String name() { return "idempotent_write"; }
        public RecoveryPolicy recoveryPolicy() { return RecoveryPolicy.IDEMPOTENT; }
        public String recoveryVersion() { return version; }
        @Override public Output execute(Input input, ToolExecutionContext context) {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            external.computeIfAbsent(context.idempotencyKey(), key -> {
                effects.incrementAndGet(); return new Output("receipt-" + key);
            });
            throw new IllegalStateException("Response lost after committing the external write");
        }
        @Override public Reconciliation reconcile(Input input, ToolRecoveryContext context) {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            lookups.incrementAndGet();
            if (entered != null) {
                entered.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            }
            var output = external.get(context.idempotencyKey());
            return output == null
                    ? new Reconciliation(Reconciliation.Outcome.INDETERMINATE, null, "Not found is not authoritative proof")
                    : new Reconciliation(Reconciliation.Outcome.SUCCEEDED, output, "Authoritative external receipt");
        }
    }
    @Autowired TaskStore tasks;
    @Autowired StepStore steps;
    @Autowired ToolInvocationStore calls;
    @Autowired TaskLeaseManager leases;
    @Autowired ToolCall dispatcher;
    @Autowired ToolRecoveryService recovery;
    @Autowired JdbcToolRecoveryStore recoveryStore;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired FakeWrite fake;
    TaskIdentity identity;
    record Fixture(String run, String step, String invocation, TaskLease lease) {}
    static String id() { return UUID.randomUUID().toString(); }
    @BeforeEach void setup() {
        identity = TestIdentity.login("recovery-owner");
        fake.external.clear(); fake.effects.set(0); fake.lookups.set(0);
        fake.version="1"; fake.entered=null; fake.release=null;
    }
    @AfterEach void cleanup() { if (fake.release != null) fake.release.countDown(); TestIdentity.clear(); }
    Fixture unknown(String tool) {
        String run=id(), step=id();
        tasks.createOwned(run, "test", identity.userId());
        var lease=tasks.start(new TaskLease(run,id()),60000);
        steps.create(lease,step,1);
        String invocation=calls.createBatch(lease,step,List.of(new ToolInvocation("protocol",tool,Map.of("value","input")))).getFirst();
        calls.start(lease,invocation);
        tasks.fail(lease,"worker interrupted");
        return new Fixture(run,step,invocation,lease);
    }
    ToolRecoveryService.Request lookup(String requestId) {
        return new ToolRecoveryService.Request(UUID.fromString(requestId),ToolRecoveryService.Action.RECONCILE,null,null,null);
    }
    ToolRecoveryService.Request confirm(String requestId, Reconciliation.Outcome outcome) {
        return new ToolRecoveryService.Request(UUID.fromString(requestId),ToolRecoveryService.Action.CONFIRM,outcome,
                "Operator checked authoritative receipt",outcome==Reconciliation.Outcome.SUCCEEDED
                        ? mapper.valueToTree(new Output("verified")) : null);
    }

    @Test void lostExternalResponseIsUnknownAndReconciliationDoesNotRepeatWrite() {
        String run=id(), step=id();
        tasks.createOwned(run,"test",identity.userId());
        try (var session=leases.acquire(run)) {
            steps.create(session.lease(),step,1);
            var context=new ToolExecutionContext(session,step,identity.userId());
            var executor=new SequentialToolBatchExecutor(dispatcher,calls);
            assertThrows(IllegalStateException.class,()->executor.execute(context,List.of(
                    new ToolInvocation("same-provider-id",fake.name(),Map.of("value","input")))));
            var invocation=calls.findByStep(step).getFirst();
            assertEquals(agent_backend.persistence.model.ToolCallStatus.UNKNOWN,invocation.status());
            assertEquals(1,fake.effects.get());
            tasks.fail(session.lease(),"uncertain tool");
            var key=recovery.get(run,invocation.invocationId()).call().idempotencyKey();
            assertEquals(invocation.invocationId(),key);
            // Contract test of the external fixture: replaying the SAME key cannot duplicate its effect.
            assertThrows(IllegalStateException.class,()->fake.execute(new Input("input"),context.forInvocation(key)));
            assertEquals(1,fake.effects.get());
            String request=id();
            var view=recovery.recover(run,invocation.invocationId(),lookup(request));
            assertEquals("SUCCEEDED",view.call().status());
            assertEquals("UNKNOWN",view.executions().getFirst().get("status")); // original history preserved
            assertEquals("SUCCEEDED",view.recoveries().getFirst().get("outcome"));
            assertEquals(identity.userId(),view.recoveries().getFirst().get("actor_id"));
            assertEquals(agent_backend.persistence.model.TaskStatus.FAILED,tasks.find(run).orElseThrow().status());
            recovery.recover(run,invocation.invocationId(),lookup(request));
            assertEquals(1,fake.lookups.get());
            assertEquals(1,fake.effects.get());
            assertThrows(LeaseLostException.class,()->calls.finish(session.lease(),invocation.invocationId(),
                    new ToolSuccess<>("same-provider-id",fake.name(),new Output("stale"))));
        }
    }

    @Test void manualOnlyRejectsAutomaticLookupAndRequiresAuditedConfirmation() {
        var f=unknown("manual_write");
        var error=assertThrows(ResponseStatusException.class,()->recovery.recover(f.run,f.invocation,lookup(id())));
        assertEquals(409,error.getStatusCode().value());
        assertTrue(recovery.get(f.run,f.invocation).recoveries().isEmpty());
        var view=recovery.recover(f.run,f.invocation,confirm(id(),Reconciliation.Outcome.SUCCEEDED));
        assertEquals("SUCCEEDED",view.call().status());
        assertEquals("CONFIRM",view.recoveries().getFirst().get("action"));
        assertEquals(0,fake.effects.get());
    }

    @Test void notExecutedConfirmationDoesNotAutomaticallyRestartAnything() {
        var f=unknown("manual_write");
        var view=recovery.recover(f.run,f.invocation,confirm(id(),Reconciliation.Outcome.NOT_EXECUTED));
        assertEquals("UNKNOWN",view.call().status());
        assertEquals("NOT_EXECUTED",view.call().resolution());
        assertEquals(1,view.executions().size());
        assertThrows(ResponseStatusException.class,()->recovery.recover(f.run,f.invocation,confirm(id(),Reconciliation.Outcome.SUCCEEDED)));
        assertEquals(agent_backend.persistence.model.TaskStatus.FAILED,tasks.find(f.run).orElseThrow().status());
    }

    @Test void missingExternalResultRemainsUnknownAndChangedAdapterCannotQuery() {
        var f=unknown(fake.name());
        var view=recovery.recover(f.run,f.invocation,lookup(id()));
        assertEquals("UNKNOWN",view.call().status());
        assertEquals("INDETERMINATE",view.call().resolution());
        fake.version="2";
        recovery.recover(f.run,f.invocation,lookup(id()));
        assertEquals(1,fake.lookups.get());
    }

    @Test void concurrentRecoveryDoesOnlyOneExternalLookup() throws Exception {
        var f=unknown(fake.name());
        fake.entered=new CountDownLatch(1); fake.release=new CountDownLatch(1);
        try (var pool=Executors.newSingleThreadExecutor()) {
            var first=pool.submit(()-> {
                TestIdentity.login("recovery-owner");
                try { return recovery.recover(f.run,f.invocation,lookup(id())); }
                finally { TestIdentity.clear(); }
            });
            assertTrue(fake.entered.await(5,TimeUnit.SECONDS));
            try {
                assertEquals(409,assertThrows(ResponseStatusException.class,
                        ()->recovery.recover(f.run,f.invocation,lookup(id()))).getStatusCode().value());
            } finally { fake.release.countDown(); }
            first.get(10,TimeUnit.SECONDS);
        }
        assertEquals(1,fake.lookups.get());
        assertEquals(1,recovery.get(f.run,f.invocation).recoveries().size());
    }

    @Test void expiredRecoveryCannotOverwriteItsReplacement() {
        var f=unknown(fake.name());
        String old=id(), next=id();
        recoveryStore.begin(f.run,f.invocation,identity,old,"RECONCILE","a".repeat(64));
        jdbc.update("UPDATE tool_recovery_attempt SET lease_until=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE request_id=?",old);
        recoveryStore.begin(f.run,f.invocation,identity,next,"RECONCILE","b".repeat(64));
        assertEquals(409,assertThrows(ResponseStatusException.class,()->recoveryStore.finish(f.run,f.invocation,identity,old,
                new Reconciliation(Reconciliation.Outcome.SUCCEEDED,new Output("stale"),"old lookup"))).getStatusCode().value());
        recoveryStore.finish(f.run,f.invocation,identity,next,
                new Reconciliation(Reconciliation.Outcome.IN_PROGRESS,null,"still running externally"));
        assertEquals("UNKNOWN",recovery.get(f.run,f.invocation).call().status());
        assertEquals("EXPIRED",recovery.get(f.run,f.invocation).recoveries().getFirst().get("status"));
    }

    @Test void recoveryEventFailureRollsBackResolutionAndAuditCompletion() {
        var f=unknown("manual_write");
        String request=id();
        recoveryStore.begin(f.run,f.invocation,identity,request,"CONFIRM","a".repeat(64));
        long before=jdbc.queryForObject("SELECT last_event_seq FROM agent_task WHERE task_id=?",Long.class,f.run);
        jdbc.execute("CREATE TRIGGER reject_recovery BEFORE INSERT ON run_event FOR EACH ROW BEGIN "
                +"IF NEW.run_id='"+f.run+"' AND NEW.event_type='tool.recovery.completed' THEN "
                +"SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='forced'; END IF; END");
        try {
            assertThrows(org.springframework.dao.DataAccessException.class,()->recoveryStore.finish(f.run,f.invocation,identity,request,
                    new Reconciliation(Reconciliation.Outcome.SUCCEEDED,new Output("result"),"proof")));
            var view=recovery.get(f.run,f.invocation);
            assertEquals("UNKNOWN",view.call().status());
            assertEquals("RUNNING",view.recoveries().getFirst().get("status"));
            assertEquals(before,jdbc.queryForObject("SELECT last_event_seq FROM agent_task WHERE task_id=?",Long.class,f.run));
        } finally { jdbc.execute("DROP TRIGGER reject_recovery"); }
    }

    @Test void ownershipAndRequestIdConflictsAreEnforced() {
        var f=unknown("manual_write");
        TestIdentity.login("other-owner");
        assertEquals(404,assertThrows(ResponseStatusException.class,()->recovery.get(f.run,f.invocation)).getStatusCode().value());
        assertEquals(404,assertThrows(ResponseStatusException.class,
                ()->recovery.recover(f.run,f.invocation,confirm(id(),Reconciliation.Outcome.SUCCEEDED))).getStatusCode().value());
        TestIdentity.login("recovery-owner");
        String request=id();
        recovery.recover(f.run,f.invocation,confirm(request,Reconciliation.Outcome.SUCCEEDED));
        assertEquals(409,assertThrows(ResponseStatusException.class,
                ()->recovery.recover(f.run,f.invocation,confirm(request,Reconciliation.Outcome.NOT_EXECUTED))).getStatusCode().value());
    }

    @Test void invalidManualOutputDoesNotCreateAClaim() {
        var f=unknown("manual_write");
        var request=new ToolRecoveryService.Request(UUID.randomUUID(),ToolRecoveryService.Action.CONFIRM,
                Reconciliation.Outcome.SUCCEEDED,"checked",mapper.readTree("{}"));
        assertEquals(400,assertThrows(ResponseStatusException.class,
                ()->recovery.recover(f.run,f.invocation,request)).getStatusCode().value());
        assertTrue(recovery.get(f.run,f.invocation).recoveries().isEmpty());
    }

    @Test void identicalArgumentsAndProviderIdsInDifferentRunsHaveDifferentKeys() {
        var one=unknown(fake.name()); var two=unknown(fake.name());
        assertNotEquals(recovery.get(one.run,one.invocation).call().idempotencyKey(),
                recovery.get(two.run,two.invocation).call().idempotencyKey());
        assertEquals(RecoveryPolicy.IDEMPOTENT,recovery.get(one.run,one.invocation).call().policy());
        assertEquals(RecoveryPolicy.MANUAL_ONLY,new ManualWrite().recoveryPolicy());
    }

    @Test void executionAttemptAndEventFailuresRollbackToolState() {
        String run=id(),step=id();
        tasks.createOwned(run,"test",identity.userId());
        var lease=tasks.start(new TaskLease(run,id()),60000);
        steps.create(lease,step,1);
        String invocation=calls.createBatch(lease,step,List.of(
                new ToolInvocation("protocol","manual_write",Map.of("value","input")))).getFirst();
        long before=jdbc.queryForObject("SELECT last_event_seq FROM agent_task WHERE task_id=?",Long.class,run);
        jdbc.execute("CREATE TRIGGER reject_execution BEFORE INSERT ON tool_execution_attempt FOR EACH ROW BEGIN "
                +"IF NEW.invocation_id='"+invocation+"' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='forced'; END IF; END");
        try {
            assertThrows(org.springframework.dao.DataAccessException.class,()->calls.start(lease,invocation));
            assertEquals(agent_backend.persistence.model.ToolCallStatus.PENDING,calls.findByStep(step).getFirst().status());
            assertEquals(before,jdbc.queryForObject("SELECT last_event_seq FROM agent_task WHERE task_id=?",Long.class,run));
            assertTrue(recovery.get(run,invocation).executions().isEmpty());
        } finally { jdbc.execute("DROP TRIGGER reject_execution"); }
        calls.start(lease,invocation);
        long started=jdbc.queryForObject("SELECT last_event_seq FROM agent_task WHERE task_id=?",Long.class,run);
        jdbc.execute("CREATE TRIGGER reject_execution BEFORE UPDATE ON tool_execution_attempt FOR EACH ROW BEGIN "
                +"IF NEW.invocation_id='"+invocation+"' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='forced'; END IF; END");
        try {
            assertThrows(org.springframework.dao.DataAccessException.class,()->calls.finish(lease,invocation,
                    new ToolSuccess<>("protocol","manual_write",new Output("ok"))));
            assertEquals(agent_backend.persistence.model.ToolCallStatus.RUNNING,calls.findByStep(step).getFirst().status());
            assertEquals("RUNNING",recovery.get(run,invocation).executions().getFirst().get("status"));
            assertEquals(started,jdbc.queryForObject("SELECT last_event_seq FROM agent_task WHERE task_id=?",Long.class,run));
        } finally { jdbc.execute("DROP TRIGGER reject_execution"); }
        tasks.fail(lease,"cleanup");
    }

    @Test void liveRunAndExpiredIdentityCannotResolveAnInvocation() {
        String run=id(),step=id();
        tasks.createOwned(run,"test",identity.userId());
        var lease=tasks.start(new TaskLease(run,id()),60000);
        steps.create(lease,step,1);
        String invocation=calls.createBatch(lease,step,List.of(
                new ToolInvocation("protocol","manual_write",Map.of("value","input")))).getFirst();
        calls.start(lease,invocation);
        calls.finish(lease,invocation,new ToolUncertain<>("protocol","manual_write","timeout"));
        assertEquals(409,assertThrows(ResponseStatusException.class,
                ()->recovery.recover(run,invocation,confirm(id(),Reconciliation.Outcome.SUCCEEDED))).getStatusCode().value());
        tasks.fail(lease,"unknown");
        String request=id();
        recoveryStore.begin(run,invocation,identity,request,"CONFIRM","a".repeat(64));
        var expired=new TaskIdentity(identity.userId(),java.time.Instant.EPOCH);
        assertEquals(401,assertThrows(ResponseStatusException.class,()->recoveryStore.finish(run,invocation,expired,request,
                new Reconciliation(Reconciliation.Outcome.SUCCEEDED,new Output("bad"),"proof"))).getStatusCode().value());
        TestIdentity.clear();
        assertEquals(401,assertThrows(ResponseStatusException.class,()->recovery.get(run,invocation)).getStatusCode().value());
    }
}
