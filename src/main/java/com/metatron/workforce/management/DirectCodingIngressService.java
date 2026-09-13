package com.metatron.workforce.management;

import com.metatron.workforce.action.ActionFabric;
import com.metatron.workforce.action.GeneralWorkspaceActionCatalog;
import com.metatron.workforce.execution.ExecutionAttempt;
import com.metatron.workforce.execution.ExecutionAttemptContext;
import com.metatron.workforce.execution.ExecutionAttemptService;
import com.metatron.workforce.execution.governance.ExecutionGate;
import com.metatron.workforce.execution.governance.ExecutionIntent;
import com.metatron.workforce.execution.governance.ExecutionPermit;
import com.metatron.workforce.execution.governance.GovernanceAttemptBindingService;
import com.metatron.workforce.execution.governance.GovernancePlanService;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import com.metatron.workforce.runtime.CanonicalRepositoryScope;
import com.metatron.workforce.runtime.ObjectiveWorkspaceService;
import com.metatron.workforce.runtime.WorkerRuntimeProfileBindingService;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Authenticated direct MCP adapter onto the canonical Workforce coding substrate.
 * One direct objective owns one durable ExecutionAttempt session/workspace; individual tool calls
 * are actions inside that attempt, not independent Objective+Worker mutable workspaces.
 */
@Component
public final class DirectCodingIngressService {
    // Client identity is an opaque OAuth client_id authenticated by the MCP proxy.
    // Workforce must not maintain a vendor allowlist.
    private static final Pattern CLIENT_ID = Pattern.compile("^[\\x21-\\x7E]{1,200}$");
    private static final Pattern CANONICAL_OBJECTIVE = Pattern.compile("^direct-mcp:[0-9a-f]{32}:[0-9a-fA-F-]{36}$");
    private static final Pattern OBJECTIVE_UUID = Pattern.compile("^[0-9a-fA-F-]{36}$");
    private static final Set<String> ACTIONS = Set.of(
            "workspace.repository.materialize",
            "workspace.file.list", "workspace.file.search", "workspace.file.read",
            "workspace.file.patch", "workspace.file.write",
            "workspace.dependencies.install", "workspace.process.run", "workspace.shell.run",
            "workspace.git.status", "workspace.git.diff", "workspace.git.run",
            "workspace.build.run", "workspace.test.run", "workspace.github.pr.publish");
    private static final Duration SESSION_LEASE = Duration.ofHours(24);
    private static final String SESSION_STEP = "direct-coding-session";

    private final GeneralWorkspaceActionCatalog catalog;
    private final WorkerRuntimeProfileBindingService profiles;
    private final ObjectiveWorkspaceService workspaces;
    private final GovernancePlanService governancePlans;
    private final GovernanceAttemptBindingService governanceAttempts;
    private final ExecutionAttemptService attempts;
    private final ExecutionGate gate;

    public DirectCodingIngressService(GeneralWorkspaceActionCatalog catalog,
                                      WorkerRuntimeProfileBindingService profiles,
                                      ObjectiveWorkspaceService workspaces,
                                      GovernancePlanService governancePlans,
                                      GovernanceAttemptBindingService governanceAttempts,
                                      ExecutionAttemptService attempts,
                                      ExecutionGate gate) {
        this.catalog=Objects.requireNonNull(catalog);this.profiles=Objects.requireNonNull(profiles);this.workspaces=Objects.requireNonNull(workspaces);
        this.governancePlans=Objects.requireNonNull(governancePlans);this.governanceAttempts=Objects.requireNonNull(governanceAttempts);this.attempts=Objects.requireNonNull(attempts);this.gate=Objects.requireNonNull(gate);
    }

    public Result execute(String client, Command command) {
        String c=validateClient(client);Objects.requireNonNull(command,"command");String objective=validateObjective(c,command.objectiveId());String repository=validateRepository(command.repository());String action=validateAction(command.actionRef());String idempotency=require(command.idempotencyKey(),"idempotencyKey",512);Map<String,String> inputs=command.inputs()==null?Map.of():Map.copyOf(command.inputs());
        profiles.bind(GeneralWorkspaceAutonomousCapability.WORKER_ID,WorkerRuntimeProfileBindingService.GENERAL_ENGINEERING_PROFILE,GeneralWorkspaceAutonomousCapability.CAPABILITY,Instant.now());

        DirectSession session=ensureSession(c,objective,repository);
        ExecutionAttemptContext.bind(session.attempt());
        try {
            ObjectiveWorkspaceService.ObjectiveWorkspace workspace=workspaces.provisionForAttempt(session.attempt().attemptId(),session.attempt().fencingToken(),objective,GeneralWorkspaceAutonomousCapability.WORKER_ID);
            requireRepositoryContinuity(workspace,repository,action,inputs);
            ActionFabric fabric=new ActionFabric(catalog.actions(GeneralWorkspaceAutonomousCapability.WORKER_ID,GeneralWorkspaceAutonomousCapability.AUTHORIZATION_REFERENCE,objective),gate);
            ActionFabric.Consequence consequence=fabric.consequenceOf(action);
            String assignment=session.attempt().assignmentRef();
            ActionFabric.ActionRequest request=new ActionFabric.ActionRequest(action,GeneralWorkspaceAutonomousCapability.WORKER_ID,assignment,GeneralWorkspaceAutonomousCapability.AUTHORIZATION_REFERENCE,objective,SESSION_STEP,idempotency,consequence==ActionFabric.Consequence.MUTATING,inputs);
            ActionFabric.ActionObservation observation;
            String permitRef="";
            if(consequence==ActionFabric.Consequence.READ_ONLY){observation=fabric.execute(request);} else {
                ExecutionPermit permit=authorizeMutation(repository,action,session,inputs);permitRef=permit.permitId();observation=fabric.execute(request,permit);
            }
            attempts.checkpoint(session.attempt().attemptId(),session.attempt().fencingToken(),"direct-action:"+digest(idempotency+"|"+action).substring(0,24),Instant.now());
            return new Result(observation.success(),objective,repository,action,observation.summary(),observation.outputs(),observation.evidenceReferences(),permitRef,session.bound().plan().planId()+"@"+session.bound().plan().version(),observation.observedAt());
        } finally {
            ExecutionAttemptContext.clearIf(session.attempt().attemptId());
        }
    }

    private DirectSession ensureSession(String client,String objective,String repository){
        Instant now=Instant.now();attempts.reconcileExpired(now);
        ExecutionAttempt active=attempts.all().stream().filter(a->a.objectiveId().equals(objective)&&a.stepId().equals(SESSION_STEP)&&!a.terminal()).max(java.util.Comparator.comparingLong(ExecutionAttempt::fencingToken)).orElse(null);
        GovernancePlanService.BoundPlan bound=sessionPlan(objective,repository);
        if(active!=null){
            attempts.requireCurrent(active.attemptId(),active.fencingToken(),now);attempts.heartbeat(active.attemptId(),active.fencingToken(),SESSION_LEASE,now);governanceAttempts.bind(active,bound);return new DirectSession(active,bound);
        }
        int attemptNumber=attempts.all().stream().filter(a->a.objectiveId().equals(objective)&&a.stepId().equals(SESSION_STEP)).mapToInt(ExecutionAttempt::attemptNumber).max().orElse(0)+1;
        String clientRef=clientBinding(client);
        String assignment="assignment:direct-mcp:"+clientRef+":"+objective;
        ExecutionAttempt created=attempts.begin("dispatch:direct-mcp-session:"+digest(objective).substring(0,24),objective,SESSION_STEP,GeneralWorkspaceAutonomousCapability.WORKER_ID,assignment,GeneralWorkspaceAutonomousCapability.AUTHORIZATION_REFERENCE,"runtime:direct-mcp:"+clientRef,attemptNumber,SESSION_LEASE,now);
        governanceAttempts.bind(created,bound);return new DirectSession(created,bound);
    }

    private GovernancePlanService.BoundPlan sessionPlan(String objective,String repository){
        ExecutionWorkSpec work=new ExecutionWorkSpec(SESSION_STEP,"Execute governed isolated direct coding session for "+repository,authorityTarget(repository),GeneralWorkspaceAutonomousCapability.CAPABILITY,List.of(),ExecutionWorkSpec.Consequence.MUTATING,List.of("all repository mutations remain inside the attempt-owned execution workspace until governed publication"),List.of("action-observation:direct-coding-session"));
        return governancePlans.bindAuthorizedWork(objective,GeneralWorkspaceAutonomousCapability.WORKER_ID,work,"FOUNDER",GeneralWorkspaceAutonomousCapability.AUTHORIZATION_REFERENCE,Map.of());
    }

    private ExecutionPermit authorizeMutation(String repository,String action,DirectSession session,Map<String,String> inputs){
        Instant now=Instant.now();ExecutionAttempt attempt=attempts.requireCurrent(session.attempt().attemptId(),session.attempt().fencingToken(),now);
        return gate.authorize(new ExecutionIntent(attempt.objectiveId(),attempt.attemptId(),attempt.fencingToken(),GeneralWorkspaceAutonomousCapability.WORKER_ID,attempt.assignmentRef(),GeneralWorkspaceAutonomousCapability.AUTHORIZATION_REFERENCE,session.bound().plan().planId(),session.bound().plan().version(),SESSION_STEP,action,ActionFabric.Consequence.MUTATING,authorityTarget(repository),session.bound().snapshot().snapshotId(),session.bound().derivation().receiptId(),inputs,now));
    }

    private void requireRepositoryContinuity(ObjectiveWorkspaceService.ObjectiveWorkspace workspace,String repository,String action,Map<String,String> inputs){
        if("workspace.repository.materialize".equals(action)){if(!repository.equals(validateRepository(inputs.get("repository"))))throw new SecurityException("direct_repository_mismatch");return;}
        Path provenance=workspaces.resolve(workspace,".metatron-repository");if(!Files.isRegularFile(provenance,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(provenance))throw new IllegalStateException("direct_repository_session_not_open");
        Map<String,String> fields=new LinkedHashMap<>();for(String line:workspaces.read(workspace,".metatron-repository").lines().toList()){int split=line.indexOf('=');if(split>0)fields.put(line.substring(0,split).trim(),line.substring(split+1).trim());}
        if(!repository.equals(fields.get("repository")))throw new SecurityException("direct_repository_session_mismatch");
    }

    static String validateClient(String value){String v=require(value,"client",200);if(!CLIENT_ID.matcher(v).matches())throw new SecurityException("direct_client_invalid");return v;}
    static String clientBinding(String client){return digest(validateClient(client)).substring(0,32);}
    static String validateRepository(String value){String v=require(value,"repository",200);try{return CanonicalRepositoryScope.requireAllowed(v);}catch(SecurityException denied){throw new SecurityException("direct_repository_not_allowed",denied);}}
    static String authorityTarget(String repository){return "repository:"+validateRepository(repository);}
    static String validateObjective(String client,String value){
        String c=validateClient(client);String v=require(value,"objectiveId",256);
        String canonicalPrefix="direct-mcp:"+clientBinding(c)+":";
        if(CANONICAL_OBJECTIVE.matcher(v).matches()&&v.startsWith(canonicalPrefix))return v;
        // Generic backward-compatible adapter for pre-G15 clients; no vendor names or allowlist.
        if(c.indexOf(':')<0){String legacyPrefix="direct-mcp:"+c+":";if(v.startsWith(legacyPrefix)&&OBJECTIVE_UUID.matcher(v.substring(legacyPrefix.length())).matches())return v;}
        throw new SecurityException("direct_objective_not_allowed");
    }
    static String validateAction(String value){String v=require(value,"actionRef",160);if(!ACTIONS.contains(v))throw new SecurityException("direct_action_not_allowed");return v;}
    private static String require(String value,String field,int max){if(value==null)throw new IllegalArgumentException(field+" required");String v=value.trim();if(v.isEmpty()||v.length()>max||v.indexOf('\0')>=0||v.indexOf('\r')>=0||v.indexOf('\n')>=0)throw new IllegalArgumentException("invalid "+field);return v;}
    private static String digest(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}

    public record Command(String objectiveId,String repository,String actionRef,String idempotencyKey,Map<String,String> inputs){}
    public record Result(boolean ok,String objectiveId,String repository,String actionRef,String summary,Map<String,String> outputs,List<String> evidenceReferences,String executionPermit,String governancePlan,Instant observedAt){public Result{outputs=outputs==null?Map.of():Map.copyOf(outputs);evidenceReferences=evidenceReferences==null?List.of():List.copyOf(evidenceReferences);}}
    private record DirectSession(ExecutionAttempt attempt,GovernancePlanService.BoundPlan bound){}
}
