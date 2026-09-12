package com.metatron.workforce.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.execution.ExecutionAttemptContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Authenticated client for the separate Worker execution sandbox container.
 * The sandbox container receives no Workforce/GitHub/LLM/Telegram credentials and mounts only
 * execution workspaces. Attempt/workspace identity is transport attribution, not authority.
 */
public final class WorkerExecutionSandboxService {
    public static final String TOKEN_HEADER = "X-Metatron-Sandbox-Token";

    public record SandboxResult(
            boolean success,int exitCode,boolean timedOut,boolean outputTruncated,String output,
            String workspaceKey,String executable,long durationMillis,String attemptId,long attemptFencingToken,String workspaceRef) {
        public SandboxResult {
            output=output==null?"":output;workspaceKey=workspaceKey==null?"":workspaceKey;executable=executable==null?"":executable;
            attemptId=attemptId==null?"":attemptId;workspaceRef=workspaceRef==null?"":workspaceRef;
        }
        public SandboxResult(boolean success,int exitCode,boolean timedOut,boolean outputTruncated,String output,String workspaceKey,String executable,long durationMillis){this(success,exitCode,timedOut,outputTruncated,output,workspaceKey,executable,durationMillis,"",0,"");}
    }

    private final HttpClient http;private final URI endpoint;private final String token;private final WorkerRuntimeProfileBindingService profiles;private final ObjectiveWorkspaceService workspaces;private final ObjectMapper json;
    public WorkerExecutionSandboxService(HttpClient http,URI endpoint,String token,WorkerRuntimeProfileBindingService profiles,ObjectiveWorkspaceService workspaces,ObjectMapper json){this.http=Objects.requireNonNull(http);this.endpoint=Objects.requireNonNull(endpoint);this.token=token==null?"":token.trim();this.profiles=Objects.requireNonNull(profiles);this.workspaces=Objects.requireNonNull(workspaces);this.json=Objects.requireNonNull(json);}
    public boolean provisioned(){return !token.isBlank();}
    public SandboxResult run(String workerId,String objectiveId,String executable,List<String> args){return run(workerId,objectiveId,"",executable,args);}

    public SandboxResult run(String workerId,String objectiveId,String workingDirectory,String executable,List<String> args){
        if(!provisioned())throw new IllegalStateException("sandbox-execution-token-not-provisioned");
        WorkerRuntimeProfileBindingService.Binding binding=profiles.requireBinding(workerId);WorkerRuntimeProfileBindingService.ToolProfile profile=binding.profile();requireAllowedExecutable(profile,executable);
        ObjectiveWorkspaceService.ObjectiveWorkspace workspace=workspaces.provision(objectiveId,workerId);
        ExecutionAttemptContext.Binding attempt=ExecutionAttemptContext.current().orElse(null);
        String relative=workingDirectory==null?"":workingDirectory.trim();String sandboxWorkingDirectory=relative;
        if(workspace.workspaceRef().startsWith("execution-workspace:")) sandboxWorkingDirectory="repos/primary"+(relative.isBlank()?"":"/"+relative);
        String attemptId=attempt==null?"":attempt.attemptId();long attemptFence=attempt==null?0:attempt.fencingToken();
        Map<String,Object> body=new java.util.LinkedHashMap<>();body.put("workerId",workerId);body.put("objectiveId",objectiveId);body.put("workspaceKey",workspace.workspaceKey());body.put("workspaceRef",workspace.workspaceRef());body.put("attemptId",attemptId);body.put("attemptFencingToken",attemptFence);body.put("workingDirectory",sandboxWorkingDirectory);body.put("executable",executable);body.put("args",args==null?List.of():List.copyOf(args));body.put("timeoutSeconds",profile.maxProcessSeconds());body.put("maxOutputBytes",profile.maxOutputBytes());
        try{
            HttpRequest request=HttpRequest.newBuilder(endpoint.resolve("/run")).timeout(Duration.ofSeconds(profile.maxProcessSeconds()+10L)).header("Content-Type","application/json").header(TOKEN_HEADER,token).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            HttpResponse<String> response=http.send(request,HttpResponse.BodyHandlers.ofString());if(response.statusCode()!=200)throw new IllegalStateException("sandbox execution HTTP "+response.statusCode());SandboxResult result=json.readValue(response.body(),SandboxResult.class);
            if(!workspace.workspaceKey().equals(result.workspaceKey()))throw new IllegalStateException("sandbox workspace attribution mismatch");if(!executable.equals(result.executable()))throw new IllegalStateException("sandbox executable attribution mismatch");
            if(!attemptId.isBlank()){
                if(!attemptId.equals(result.attemptId())||attemptFence!=result.attemptFencingToken())throw new IllegalStateException("sandbox execution attempt attribution mismatch");
                if(!workspace.workspaceRef().equals(result.workspaceRef()))throw new IllegalStateException("sandbox workspace reference attribution mismatch");
            }
            return result;
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException("sandbox execution interrupted",interrupted);}catch(Exception failure){if(failure instanceof RuntimeException runtime)throw runtime;throw new IllegalStateException("sandbox execution failed",failure);}
    }
    private static void requireAllowedExecutable(WorkerRuntimeProfileBindingService.ToolProfile profile,String executable){require(executable,"executable");if(!profile.allowedExecutables().contains(executable))throw new SecurityException("runtime profile denies executable: "+executable);}
    private static String require(String value,String field){if(value==null||value.isBlank())throw new IllegalArgumentException(field+" required");return value.trim();}
}
