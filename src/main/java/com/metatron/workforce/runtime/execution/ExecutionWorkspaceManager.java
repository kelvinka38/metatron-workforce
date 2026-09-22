package com.metatron.workforce.runtime.execution;

import com.metatron.workforce.execution.ExecutionAttempt;
import com.metatron.workforce.execution.ExecutionAttemptService;
import com.metatron.workforce.runtime.CanonicalRepositoryScope;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Canonical mutable workspace authority for governed ExecutionAttempts.
 * Workspace identity is attempt-scoped, never Objective/Worker-scoped.
 */
public final class ExecutionWorkspaceManager {
    private final Path root;
    private final ExecutionAttemptService attempts;
    private final ExecutionWorkspaceBindingStore store;
    private final Map<String,ExecutionWorkspaceBinding> bindings = new LinkedHashMap<>();

    public ExecutionWorkspaceManager(Path root, ExecutionAttemptService attempts, ExecutionWorkspaceBindingStore store) {
        this.root = Objects.requireNonNull(root,"root").toAbsolutePath().normalize();
        this.attempts = Objects.requireNonNull(attempts,"attempts");
        this.store = Objects.requireNonNull(store,"store");
        bindings.putAll(store.load());
    }

    public synchronized ExecutionWorkspaceBinding allocate(String attemptId,long attemptFence,Instant at) {
        ExecutionAttempt attempt = attempts.requireCurrent(attemptId,attemptFence,Objects.requireNonNull(at,"at"));
        ExecutionWorkspaceBinding existing = bindings.get(attemptId);
        if(existing!=null) {
            if(existing.attemptFencingToken()!=attemptFence) throw new SecurityException("EXECUTION_WORKSPACE_STALE: attempt fence mismatch");
            if(existing.status()==ExecutionWorkspaceBinding.Status.DISPOSED) throw new IllegalStateException("EXECUTION_WORKSPACE_STALE: workspace disposed");
            verifyIdentity(existing);
            return existing;
        }
        try {
            Files.createDirectories(root);
            if(Files.isSymbolicLink(root)) throw new SecurityException("execution workspace root cannot be symlink");
            String key = stableKey(attemptId);
            Path path = root.resolve(key).normalize();
            if(!path.startsWith(root)) throw new SecurityException("execution workspace escaped root");
            if(Files.exists(path,LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException("execution workspace path already exists without binding: "+path);
            Files.createDirectory(path);
            Files.createDirectories(path.resolve("repos"));
            Files.createDirectories(path.resolve("build"));
            Files.createDirectories(path.resolve("cache-bindings"));
            Files.createDirectories(path.resolve("artifacts"));
            Files.createDirectories(path.resolve("evidence"));
            Files.createDirectories(path.resolve("logs"));
            String workspaceId="execution-workspace:"+key;
            atomicWrite(path.resolve(".metatron-execution-workspace"),
                    "workspaceId="+workspaceId+"\nattemptId="+attemptId+"\nattemptFence="+attemptFence+"\nworkerId="+attempt.workerId()+"\nobjectiveId="+attempt.objectiveId()+"\nstepId="+attempt.stepId()+"\n");
            carryForwardSuccessfulPrimaryWorkspace(attempt.objectiveId(), attempt.workerId(), path);
            List<ExecutionRepositoryComponent> carried = carryForwardCommittedComponents(attempt.objectiveId(), attempt.workerId(), path);
            ExecutionWorkspaceBinding binding = new ExecutionWorkspaceBinding(workspaceId,attemptId,attemptFence,
                    attempt.workerId(),attempt.objectiveId(),attempt.stepId(),path.toString(),1,
                    ExecutionWorkspaceBinding.Status.ALLOCATED,carried,at,at,null,null);
            bindings.put(attemptId,binding); persist(); return binding;
        } catch(IOException e) { throw new IllegalStateException("cannot allocate execution workspace",e); }
    }

    public synchronized Optional<ExecutionWorkspaceBinding> get(String attemptId){return Optional.ofNullable(bindings.get(attemptId));}

    /** Read-only: the existing (never re-allocated) workspace binding of the most recent SUCCEEDED
     * attempt for a logical objective+step, for callers that must inspect completed work without
     * claiming current attempt ownership (allocate() requires an active attempt and rejects terminal
     * ones by design). */
    public synchronized Optional<ExecutionWorkspaceBinding> latestBindingForStep(String objectiveId,String stepId,String workerId){
        ExecutionAttempt attempt=attempts.latestSucceededForStep(objectiveId,stepId).orElse(null);
        if(attempt==null||!attempt.workerId().equals(workerId)) return Optional.empty();
        return get(attempt.attemptId());
    }

    public synchronized ExecutionWorkspaceBinding requireActive(String attemptId,long attemptFence,Instant at){
        attempts.requireCurrent(attemptId,attemptFence,Objects.requireNonNull(at,"at"));
        ExecutionWorkspaceBinding binding=requireBinding(attemptId);
        if(binding.attemptFencingToken()!=attemptFence) throw new SecurityException("EXECUTION_WORKSPACE_STALE: attempt fence mismatch");
        if(!binding.mutable()) throw new SecurityException("EXECUTION_WORKSPACE_STALE: workspace not mutable: "+binding.status());
        verifyIdentity(binding);
        return binding;
    }

    public synchronized ExecutionWorkspaceBinding requireVersion(String attemptId,long expectedVersion){
        ExecutionWorkspaceBinding b=requireBinding(attemptId);
        if(b.stateVersion()!=expectedVersion) throw new IllegalStateException("EXECUTION_WORKSPACE_STALE: expected="+expectedVersion+" actual="+b.stateVersion());
        return b;
    }

    public synchronized ExecutionWorkspaceBinding registerRepository(String attemptId,long attemptFence,String repository,
                                                                      String requestedRef,String componentId,String branchRef,Instant at){
        ExecutionWorkspaceBinding b=requireActive(attemptId,attemptFence,at);
        String repo=CanonicalRepositoryScope.requireAllowed(repository);
        String component=cleanComponent(componentId);
        String rel="repos/"+component;
        for(ExecutionRepositoryComponent current:b.repositories()){
            if(current.componentId().equals(component)){
                if(current.repository().equals(repo)&&current.requestedRef().equals(normalizeRef(requestedRef))) return b;
                throw new IllegalStateException("WORKSPACE_PROVENANCE_CONFLICT: component already bound");
            }
            if(current.relativePath().equals(rel)) throw new IllegalStateException("WORKSPACE_PROVENANCE_CONFLICT: overlapping repository path");
        }
        Path path=resolveRoot(b).resolve(rel).normalize();
        if(!path.startsWith(resolveRoot(b))) throw new SecurityException("repository component path escaped execution workspace");
        try{Files.createDirectories(path);}catch(IOException e){throw new IllegalStateException("cannot create repository component",e);}
        List<ExecutionRepositoryComponent> components=new ArrayList<>(b.repositories());
        components.add(new ExecutionRepositoryComponent(component,repo,normalizeRef(requestedRef),"",clean(branchRef),rel,
                ExecutionRepositoryComponent.Status.DECLARED,"","",""));
        ExecutionWorkspaceBinding next=copy(b,b.stateVersion()+1,ExecutionWorkspaceBinding.Status.MATERIALIZING,components,at,b.sealedAt(),b.retentionUntil());
        bindings.put(attemptId,next); persist(); return next;
    }

    public synchronized ExecutionWorkspaceBinding markMaterialized(String attemptId,long attemptFence,String componentId,
                                                                    String resolvedBaseSha,String localBaselineSha,Instant at){
        ExecutionWorkspaceBinding b=requireActive(attemptId,attemptFence,at);
        List<ExecutionRepositoryComponent> components=replace(b.repositories(),componentId,
                b.requireComponent(componentId).withMaterialized(resolvedBaseSha.toLowerCase(),localBaselineSha.toLowerCase()));
        ExecutionWorkspaceBinding next=copy(b,b.stateVersion()+1,ExecutionWorkspaceBinding.Status.READY,components,at,b.sealedAt(),b.retentionUntil());
        bindings.put(attemptId,next); persist(); return next;
    }

    public synchronized ExecutionWorkspaceBinding markDirty(String attemptId,long attemptFence,String componentId,Instant at){
        ExecutionWorkspaceBinding b=requireActive(attemptId,attemptFence,at);
        List<ExecutionRepositoryComponent> components=replace(b.repositories(),componentId,b.requireComponent(componentId).withStatus(ExecutionRepositoryComponent.Status.DIRTY));
        ExecutionWorkspaceBinding next=copy(b,b.stateVersion()+1,b.status(),components,at,b.sealedAt(),b.retentionUntil());
        bindings.put(attemptId,next); persist(); return next;
    }

    public synchronized ExecutionWorkspaceBinding markCommitted(String attemptId,long attemptFence,String componentId,String headSha,Instant at){
        ExecutionWorkspaceBinding b=requireActive(attemptId,attemptFence,at);
        List<ExecutionRepositoryComponent> components=replace(b.repositories(),componentId,b.requireComponent(componentId).withCommitted(headSha));
        ExecutionWorkspaceBinding next=copy(b,b.stateVersion()+1,b.status(),components,at,b.sealedAt(),b.retentionUntil());
        bindings.put(attemptId,next); persist(); return next;
    }

    public synchronized ExecutionWorkspaceBinding markProposed(String attemptId,long attemptFence,String componentId,String pullRequestRef,Instant at){
        ExecutionWorkspaceBinding b=requireActive(attemptId,attemptFence,at);
        List<ExecutionRepositoryComponent> components=replace(b.repositories(),componentId,b.requireComponent(componentId).withProposal(pullRequestRef));
        ExecutionWorkspaceBinding next=copy(b,b.stateVersion()+1,b.status(),components,at,b.sealedAt(),b.retentionUntil());
        bindings.put(attemptId,next); persist(); return next;
    }

    public synchronized ExecutionWorkspaceBinding seal(String attemptId,long attemptFence,Instant at){
        ExecutionWorkspaceBinding b=requireBinding(attemptId);
        if(b.attemptFencingToken()!=attemptFence) throw new SecurityException("EXECUTION_WORKSPACE_STALE: attempt fence mismatch");
        List<ExecutionRepositoryComponent> components=b.repositories().stream().map(c->c.withStatus(ExecutionRepositoryComponent.Status.SEALED)).toList();
        ExecutionWorkspaceBinding next=copy(b,b.stateVersion()+1,ExecutionWorkspaceBinding.Status.SEALED,components,at,at,b.retentionUntil());
        bindings.put(attemptId,next); persist(); return next;
    }

    public synchronized ExecutionWorkspaceBinding retain(String attemptId,long expectedVersion,Instant retentionUntil,Instant at){
        ExecutionWorkspaceBinding b=requireVersion(attemptId,expectedVersion);
        if(retentionUntil==null||!retentionUntil.isAfter(at)) throw new IllegalArgumentException("retentionUntil must be future");
        ExecutionWorkspaceBinding next=copy(b,b.stateVersion()+1,ExecutionWorkspaceBinding.Status.RETAINED,b.repositories(),at,b.sealedAt(),retentionUntil);
        bindings.put(attemptId,next); persist(); return next;
    }

    public synchronized ExecutionWorkspaceBinding dispose(String attemptId,long expectedVersion,Instant at){
        ExecutionWorkspaceBinding b=requireVersion(attemptId,expectedVersion);
        ExecutionAttempt attempt=attempts.find(attemptId).orElseThrow(()->new IllegalArgumentException("execution attempt not found: "+attemptId));
        if(!attempt.terminal()) throw new SecurityException("active execution workspace cannot be disposed");
        deleteTree(resolveRoot(b));
        ExecutionWorkspaceBinding next=copy(b,b.stateVersion()+1,ExecutionWorkspaceBinding.Status.DISPOSED,b.repositories(),at,b.sealedAt(),b.retentionUntil());
        bindings.put(attemptId,next); persist(); return next;
    }

    /**
     * Root-cause fix for the orphaned-workspace disk leak (2026-09-14): a small number of workspace
     * bindings reference an attemptId with NO corresponding ExecutionAttempt record at all (not merely
     * non-terminal -- genuinely absent from the store, most plausibly from the legacy
     * ObjectiveWorkspaceService root predating this attempt-scoped manager). dispose() above requires
     * attempts.find(attemptId) to succeed, so no existing reconciliation path -- including the
     * reconcileExpired-frequency fix that shipped earlier today -- can ever reach these; they were
     * confirmed stuck at a constant count across 30+ independent 15s reconciler ticks with zero
     * incoming/outgoing activity. This is a deliberately separate, narrower method rather than relaxing
     * dispose()'s precondition, so every other caller of dispose() keeps requiring a real terminal
     * attempt. It re-verifies the attempt is still absent at call time (defense in depth against a
     * caller racing a fresh provision under the same id) before deleting anything.
     */
    public synchronized ExecutionWorkspaceBinding reclaimOrphaned(String attemptId,long expectedVersion,Instant at){
        ExecutionWorkspaceBinding b=requireVersion(attemptId,expectedVersion);
        if(attempts.find(attemptId).isPresent()) throw new IllegalStateException("execution attempt exists; not orphaned: "+attemptId);
        deleteTree(resolveRoot(b));
        ExecutionWorkspaceBinding next=copy(b,b.stateVersion()+1,ExecutionWorkspaceBinding.Status.DISPOSED,b.repositories(),at,b.sealedAt(),b.retentionUntil());
        bindings.put(attemptId,next); persist(); return next;
    }


    public Path rootPath(ExecutionWorkspaceBinding binding){verifyIdentity(binding);return resolveRoot(binding);}
    public Path repositoryPath(ExecutionWorkspaceBinding binding,String componentId){
        ExecutionRepositoryComponent c=binding.requireComponent(componentId);
        Path p=resolveRoot(binding).resolve(c.relativePath()).normalize();
        if(!p.startsWith(resolveRoot(binding))) throw new SecurityException("component path escaped workspace");
        rejectExistingSymlinks(resolveRoot(binding),p); return p;
    }
    public Path buildPath(ExecutionWorkspaceBinding binding,String componentId){
        String c=cleanComponent(componentId); Path p=resolveRoot(binding).resolve("build").resolve(c).normalize();
        if(!p.startsWith(resolveRoot(binding))) throw new SecurityException("build path escaped workspace");
        try{Files.createDirectories(p);}catch(IOException e){throw new IllegalStateException("cannot create build path",e);} return p;
    }

    private ExecutionWorkspaceBinding requireBinding(String attemptId){ExecutionWorkspaceBinding b=bindings.get(attemptId);if(b==null)throw new IllegalStateException("EXECUTION_WORKSPACE_REQUIRED: "+attemptId);return b;}
    private Path resolveRoot(ExecutionWorkspaceBinding b){Path p=Path.of(b.rootPath()).toAbsolutePath().normalize();if(!p.startsWith(root))throw new SecurityException("workspace binding root outside configured root");return p;}
    private void verifyIdentity(ExecutionWorkspaceBinding b){
        Path path=resolveRoot(b); Path identity=path.resolve(".metatron-execution-workspace");
        if(!Files.isRegularFile(identity,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(identity)) throw new SecurityException("execution workspace identity missing/unsafe");
        try{String body=Files.readString(identity);if(!body.contains("attemptId="+b.attemptId()+"\n")||!body.contains("workspaceId="+b.workspaceId()+"\n"))throw new SecurityException("execution workspace identity mismatch");}
        catch(IOException e){throw new IllegalStateException("cannot verify execution workspace identity",e);}
    }
    private static List<ExecutionRepositoryComponent> replace(List<ExecutionRepositoryComponent> list,String componentId,ExecutionRepositoryComponent replacement){
        boolean found=false;List<ExecutionRepositoryComponent> out=new ArrayList<>();for(var c:list){if(c.componentId().equals(componentId)){out.add(replacement);found=true;}else out.add(c);}if(!found)throw new IllegalArgumentException("repository component not found: "+componentId);return List.copyOf(out);
    }
    private static ExecutionWorkspaceBinding copy(ExecutionWorkspaceBinding b,long version,ExecutionWorkspaceBinding.Status status,List<ExecutionRepositoryComponent> components,Instant updated,Instant sealed,Instant retention){
        return new ExecutionWorkspaceBinding(b.workspaceId(),b.attemptId(),b.attemptFencingToken(),b.workerId(),b.objectiveId(),b.stepId(),b.rootPath(),version,status,components,b.createdAt(),updated,sealed,retention);
    }
    private static String normalizeRef(String ref){return ref==null||ref.isBlank()?"main":ref.trim();}
    private static String clean(String value){return value==null?"":value.trim();}
    private static String cleanComponent(String id){String v=id==null?"":id.trim();if(!v.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,79}"))throw new IllegalArgumentException("invalid componentId");return v;}
    private static String stableKey(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)),0,16);}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static void atomicWrite(Path target,String body)throws IOException{Path tmp=Files.createTempFile(target.getParent(),".metatron-exec-",".tmp");try{Files.writeString(tmp,body,StandardCharsets.UTF_8);try{Files.move(tmp,target,StandardCopyOption.ATOMIC_MOVE);}catch(java.nio.file.AtomicMoveNotSupportedException ignored){Files.move(tmp,target);}}finally{Files.deleteIfExists(tmp);}}
    private static void rejectExistingSymlinks(Path base,Path target){Path current=base;for(Path part:base.relativize(target)){current=current.resolve(part);if(Files.exists(current,LinkOption.NOFOLLOW_LINKS)&&Files.isSymbolicLink(current))throw new SecurityException("workspace symlink traversal denied");}}
    private static void deleteTree(Path path){try{if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))return;if(Files.isSymbolicLink(path))throw new SecurityException("workspace root symlink denied");try(var stream=Files.walk(path)){for(Path p:stream.sorted(java.util.Comparator.reverseOrder()).toList()){if(Files.isSymbolicLink(p))Files.deleteIfExists(p);else Files.deleteIfExists(p);}}}catch(IOException e){throw new IllegalStateException("cannot dispose execution workspace",e);}}
    private void persist(){store.save(bindings);}

    /**
     * Carry the canonical primary workspace forward only from the most recent SUCCEEDED attempt for
     * the same Objective and Worker. Execution roots remain attempt-scoped/fenced; failed, abandoned or
     * fenced work is never a source. This is the durable handoff boundary that lets a Work graph split
     * production, verification and delivery into separate governed attempts without losing files.
     */
    private void carryForwardSuccessfulPrimaryWorkspace(String objectiveId,String workerId,Path newRoot){
        ExecutionWorkspaceBinding source=null;
        for(ExecutionWorkspaceBinding candidate:bindings.values()){
            if(!candidate.objectiveId().equals(objectiveId)||!candidate.workerId().equals(workerId)) continue;
            if(candidate.status()==ExecutionWorkspaceBinding.Status.DISPOSED) continue;
            ExecutionAttempt sourceAttempt=attempts.find(candidate.attemptId()).orElse(null);
            if(sourceAttempt==null||sourceAttempt.status()!=ExecutionAttempt.Status.SUCCEEDED) continue;
            if(source==null||candidate.updatedAt().isAfter(source.updatedAt())) source=candidate;
        }
        if(source==null) return;
        Path sourceRoot;
        try{sourceRoot=resolveRoot(source);}catch(SecurityException invalid){return;}
        Path from=sourceRoot.resolve("repos").resolve("primary").normalize();
        if(!from.startsWith(sourceRoot)||!Files.isDirectory(from,LinkOption.NOFOLLOW_LINKS)) return;
        Path to=newRoot.resolve("repos").resolve("primary").normalize();
        if(!to.startsWith(newRoot)) throw new SecurityException("carried primary workspace escaped execution root");
        try{copyPrimaryTree(from,to);}
        catch(IOException e){throw new IllegalStateException("cannot carry forward successful primary workspace",e);}
    }

    private static void copyPrimaryTree(Path from,Path to)throws IOException{
        try(var stream=Files.walk(from)){
            for(Path source:stream.sorted().toList()){
                if(Files.isSymbolicLink(source)) throw new SecurityException("carry-forward source contains symlink: "+source);
                Path relative=from.relativize(source);
                if(relative.toString().equals(".metatron-workspace")) continue;
                Path target=to.resolve(relative);
                if(Files.isDirectory(source,LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(target);
                else{
                    Files.createDirectories(target.getParent());
                    Files.copy(source,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    /**
     * Root-cause fix for GITHUB-PUBLISH-EMPTY-DELTA: workspace identity is intentionally attempt-scoped
     * (see class doc), but that means a later ExecutionAttempt for the same Objective/Worker (e.g. a
     * "publish GitHub proposal" step run after a separate "commit" attempt) always starts from a blank
     * re-materialized workspace and can never observe Git history a prior attempt already committed,
     * so GitHubWorkspaceProposalPublisher.publish() deterministically fails with
     * "proposal requires a committed work-product delta" every time commit and publish land in different
     * attempts. This carries forward only components with real committed history (COMMITTED/PROPOSED/
     * SEALED) from the most recently updated non-disposed binding for the same objectiveId+workerId,
     * by copying (never moving) the component's directory tree into the newly allocated attempt
     * workspace before it is returned. Attempts for different Objectives or different Workers remain
     * fully isolated, and an Objective's first attempt behaves exactly as before (no prior binding to
     * carry forward from).
     */
    private List<ExecutionRepositoryComponent> carryForwardCommittedComponents(String objectiveId,String workerId,Path newRoot){
        ExecutionWorkspaceBinding source=null;
        for(ExecutionWorkspaceBinding candidate:bindings.values()){
            if(!candidate.objectiveId().equals(objectiveId)||!candidate.workerId().equals(workerId)) continue;
            if(candidate.status()==ExecutionWorkspaceBinding.Status.DISPOSED) continue;
            if(source==null||candidate.updatedAt().isAfter(source.updatedAt())) source=candidate;
        }
        if(source==null) return List.of();
        Path sourceRoot;
        try{ sourceRoot=resolveRoot(source); }catch(SecurityException invalid){ return List.of(); }
        if(!Files.isDirectory(sourceRoot,LinkOption.NOFOLLOW_LINKS)) return List.of();
        List<ExecutionRepositoryComponent> carried=new ArrayList<>();
        for(ExecutionRepositoryComponent component:source.repositories()){
            if(component.status()!=ExecutionRepositoryComponent.Status.COMMITTED
                    &&component.status()!=ExecutionRepositoryComponent.Status.PROPOSED
                    &&component.status()!=ExecutionRepositoryComponent.Status.SEALED) continue;
            Path from=sourceRoot.resolve(component.relativePath()).normalize();
            if(!from.startsWith(sourceRoot)||!Files.isDirectory(from,LinkOption.NOFOLLOW_LINKS)) continue;
            Path to=newRoot.resolve(component.relativePath()).normalize();
            if(!to.startsWith(newRoot)) continue;
            try{ copyTree(from,to); }
            catch(IOException e){ throw new IllegalStateException("cannot carry forward committed repository component: "+component.componentId(),e); }
            carried.add(component);
        }
        return List.copyOf(carried);
    }

    private static void copyTree(Path from,Path to)throws IOException{
        try(var stream=Files.walk(from)){
            for(Path source:stream.sorted().toList()){
                if(Files.isSymbolicLink(source)) throw new SecurityException("carry-forward source contains symlink: "+source);
                Path relative=from.relativize(source);
                Path target=to.resolve(relative);
                if(Files.isDirectory(source,LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(target);
                else { Files.createDirectories(target.getParent()); Files.copy(source,target,StandardCopyOption.COPY_ATTRIBUTES); }
            }
        }
    }
}
