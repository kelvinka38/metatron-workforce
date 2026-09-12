package com.metatron.workforce.workers.audit;

import com.metatron.workforce.gateway.GatewayEgressClient;
import com.metatron.workforce.runtime.RepositoryCredentialAuthority;
import com.metatron.workforce.workers.Worker;
import com.metatron.workforce.workers.WorkerContext;
import com.metatron.workforce.workers.WorkerResult;

import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Real, read-only GitHub repository audit using governed Gateway egress. */
public final class RepositoryAuditWorker implements Worker {
    private static final Pattern REPOSITORY = Pattern.compile("(?i)(?:https?://github\\.com/)?([a-z0-9_.-]+)/([a-z0-9_.-]+)");
    private static final Pattern DEFAULT_BRANCH = Pattern.compile("\\\"default_branch\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern SHA = Pattern.compile("\\\"sha\\\"\\s*:\\s*\\\"([0-9a-f]{40})\\\"");
    private static final Pattern TREE_PATH = Pattern.compile("\\\"path\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final int MAX_FILES = 80;
    private static final int MAX_BYTES = 2_000_000;

    private final GatewayEgressClient egress;
    private final boolean authenticated;

    /** Direct construction has no admitted authorization and therefore fails closed on external access. */
    public RepositoryAuditWorker() {
        this("");
    }

    /** Production constructor: authorization is preserved from the admitted execution request. */
    public RepositoryAuditWorker(String authorizationReference) {
        String token = RepositoryCredentialAuthority.resolveProcessToken();
        this.egress = new GatewayEgressClient(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                "https://api.github.com", token, authorizationReference);
        this.authenticated = !token.isBlank();
    }

    RepositoryAuditWorker(HttpClient http, String apiBase) {
        this(http, apiBase, "");
    }

    RepositoryAuditWorker(HttpClient http, String apiBase, String githubToken) {
        this.egress = new GatewayEgressClient(http, apiBase, githubToken, "TEST-OBSERVATION-AUTHORIZATION");
        this.authenticated = githubToken != null && !githubToken.isBlank();
    }

    @Override public WorkerResult execute(WorkerContext context) {
        Instant completedAt=Instant.now();
        String target=extractRepository(context.objective());
        if(target==null) return failed(context,"repository target missing; objective must contain owner/repo or github.com/owner/repo",completedAt);
        List<String> crossingEvidence = new ArrayList<>();
        try {
            GatewayEgressClient.EgressResponse metadata=get("/repos/"+target,"application/vnd.github+json", crossingEvidence);
            if(metadata.denied()) return failed(context,"Gateway egress denied repository metadata: "+metadata.denialReason(),completedAt);
            if(metadata.statusCode()!=200) return failed(context,"repository metadata HTTP "+metadata.statusCode()+" target="+target,completedAt);
            String branch=capture(DEFAULT_BRANCH,metadata.body());
            if(branch==null) return failed(context,"GitHub response missing default_branch target="+target,completedAt);
            GatewayEgressClient.EgressResponse commit=get("/repos/"+target+"/commits/"+encode(branch),"application/vnd.github+json", crossingEvidence);
            if(commit.denied()) return failed(context,"Gateway egress denied commit read: "+commit.denialReason(),completedAt);
            if(commit.statusCode()!=200) return failed(context,"default branch commit HTTP "+commit.statusCode()+" target="+target+" branch="+branch,completedAt);
            String commitSha=capture(SHA,commit.body());
            if(commitSha==null) return failed(context,"GitHub response missing commit SHA target="+target,completedAt);

            GatewayEgressClient.EgressResponse tree=get("/repos/"+target+"/git/trees/"+commitSha+"?recursive=1","application/vnd.github+json", crossingEvidence);
            if(tree.denied()) return failed(context,"Gateway egress denied repository tree: "+tree.denialReason(),completedAt);
            if(tree.statusCode()!=200) return failed(context,"repository tree HTTP "+tree.statusCode()+" target="+target,completedAt);
            List<String> paths=paths(tree.body());
            if(paths.isEmpty()) return failed(context,"repository tree contains no auditable files target="+target,completedAt);

            List<String> selected=select(paths);
            int read=0, bytes=0, sot=0, docs=0, source=0, tests=0, todo=0, conflict=0;
            Set<String> observed=new LinkedHashSet<>();
            List<String> findings=new ArrayList<>();
            for(String path:selected){
                if(read>=MAX_FILES || bytes>=MAX_BYTES) break;
                GatewayEgressClient.EgressResponse file=get("/repos/"+target+"/contents/"+encodePath(path)+"?ref="+commitSha,"application/vnd.github.raw+json", crossingEvidence);
                if(file.denied()){ findings.add("EGRESS_DENIED "+path+" reason="+file.denialReason()); continue; }
                if(file.statusCode()!=200){ findings.add("UNREADABLE "+path+" HTTP="+file.statusCode()); continue; }
                String body=file.body(); read++; bytes+=body.getBytes(StandardCharsets.UTF_8).length; observed.add(path);
                String lower=path.toLowerCase();
                if(lower.contains("sot") || body.contains("SOURCE OF TRUTH") || body.contains("Source of Truth")) sot++;
                if(lower.endsWith(".md")||lower.endsWith(".txt")) docs++;
                if(lower.matches(".*\\.(java|kt|py|js|ts|go|rs)$")) source++;
                if(lower.contains("test")||lower.contains("spec")) tests++;
                todo+=count(body,"TODO")+count(body,"FIXME");
                conflict+=count(body,"<<<<<<< ")+count(body,">>>>>>> ");
            }
            if(read==0) return failed(context,"no repository content could be read target="+target,completedAt);
            if(conflict>0) findings.add("MERGE_CONFLICT_MARKERS="+conflict);
            if(todo>0) findings.add("TODO_FIXME_MARKERS="+todo);
            if(sot==0) findings.add("NO_SOT_MARKER_IN_AUDITED_CONTENT");
            String findingText=findings.isEmpty()?"NONE":String.join(" | ",findings);
            String evidence="Repository Audit Report\n"+
                    "task="+context.taskId()+"\nobjective="+context.objective()+"\nsource=gateway-egress/github-api\nauthenticated="+authenticated+
                    "\nrepository="+target+"\ndefaultBranch="+branch+"\ncommitSha="+commitSha+
                    "\ntreeHttpStatus="+tree.statusCode()+"\nrepositoryFilesObserved="+paths.size()+"\ncontentFilesRead="+read+
                    "\ncontentBytesRead="+bytes+"\nsotSignals="+sot+"\ndocumentFilesRead="+docs+"\nsourceFilesRead="+source+
                    "\ntestFilesRead="+tests+"\ngatewayEgressCrossings="+crossingEvidence.size()+
                    "\ngatewayEgressProvenance="+String.join(" | ",crossingEvidence)+
                    "\nfindings="+findingText+"\nobservedPaths="+String.join(",",observed)+
                    "\nobservedAt="+completedAt+"\nverdict=PASS\n";
            return new WorkerResult("RepositoryAuditWorker","PASS",evidence,completedAt);
        } catch(Exception e){ return failed(context,"GitHub read failed: "+e.getClass().getSimpleName()+": "+String.valueOf(e.getMessage()),completedAt); }
    }

    private GatewayEgressClient.EgressResponse get(String path,String accept,List<String> evidence)throws Exception{
        GatewayEgressClient.EgressResponse response=egress.get(path,accept);
        if(!response.provenance().isBlank()) evidence.add(response.provenance());
        return response;
    }
    private List<String> paths(String json){ List<String> out=new ArrayList<>(); Matcher m=TREE_PATH.matcher(json==null?"":json); while(m.find()) out.add(m.group(1)); return out; }
    private List<String> select(List<String> paths){
        List<String> priority=new ArrayList<>(), rest=new ArrayList<>();
        for(String p:paths){ String l=p.toLowerCase(); if(!auditable(l)) continue; if(l.contains("sot")||l.contains("architecture")||l.contains("boundary")||l.contains("ontology")||l.equals("readme.md")) priority.add(p); else rest.add(p); }
        LinkedHashSet<String> all=new LinkedHashSet<>(); all.addAll(priority); all.addAll(rest); return new ArrayList<>(all);
    }
    private boolean auditable(String l){ return l.endsWith(".md")||l.endsWith(".txt")||l.endsWith(".java")||l.endsWith(".kt")||l.endsWith(".py")||l.endsWith(".js")||l.endsWith(".ts")||l.endsWith(".go")||l.endsWith(".rs")||l.endsWith(".yml")||l.endsWith(".yaml")||l.endsWith(".json"); }
    private static String encode(String s){ return URLEncoder.encode(s,StandardCharsets.UTF_8).replace("+","%20"); }
    private static String encodePath(String p){ String[] parts=p.split("/"); StringBuilder b=new StringBuilder(); for(String x:parts){ if(b.length()>0)b.append('/'); b.append(encode(x)); } return b.toString(); }
    private static int count(String s,String needle){ int n=0,i=0; while((i=s.indexOf(needle,i))>=0){n++;i+=needle.length();} return n; }
    private static String extractRepository(String objective){ if(objective==null)return null; Matcher m=REPOSITORY.matcher(objective.trim()); if(!m.find())return null; String owner=m.group(1),repo=m.group(2).replaceAll("\\.git$",""); if(owner.equalsIgnoreCase("http")||owner.equalsIgnoreCase("https"))return null; return owner+"/"+repo; }
    private static String capture(Pattern p,String v){ Matcher m=p.matcher(v==null?"":v); return m.find()?m.group(1):null; }
    private static WorkerResult failed(WorkerContext c,String reason,Instant at){ return new WorkerResult("RepositoryAuditWorker","FAILED","Repository Audit Report\ntask="+c.taskId()+"\nobjective="+c.objective()+"\nobservedAt="+at+"\nverdict=FAILED\nreason="+reason+"\n",at); }
    private static String env(String n){String v=System.getenv(n);return v==null?"":v.trim();}
}
