package com.metatron.workforce.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.execution.ExecutionAttemptContext;
import com.metatron.workforce.execution.governance.GovernanceDeniedException;
import com.metatron.workforce.runtime.execution.ExecutionWorkspaceManager;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Materializes an immutable GitHub repository snapshot into an attempt-owned workspace without
 * exposing the GitHub credential to the Worker sandbox. Canonical scope remains fail-closed.
 */
public final class RepositoryWorkspaceMaterializationService {
    private static final Pattern REPOSITORY = Pattern.compile("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$");
    private static final Pattern REF = Pattern.compile("^[A-Za-z0-9._/-]{1,200}$");
    private static final int MAX_FILES = 20_000;
    private static final long MAX_ARCHIVE_BYTES = 80_000_000L;
    private static final long MAX_EXTRACTED_BYTES = 250_000_000L;
    public static final String PRIMARY_COMPONENT = "primary";

    public record MaterializedRepository(String repository,String requestedRef,String resolvedCommitSha,String workspaceRef,int files,long bytes) {
        public MaterializedRepository {
            repository=require(repository,"repository"); requestedRef=require(requestedRef,"requestedRef"); resolvedCommitSha=require(resolvedCommitSha,"resolvedCommitSha"); workspaceRef=require(workspaceRef,"workspaceRef");
            if(files<1||bytes<1)throw new IllegalArgumentException("materialized repository must contain content");
        }
    }

    private final HttpClient http;
    private final String githubToken;
    private final ObjectiveWorkspaceService workspaces;
    private final ExecutionWorkspaceManager executionWorkspaces;
    private final ObjectMapper json;
    private final URI apiBase;

    public RepositoryWorkspaceMaterializationService(HttpClient http,String githubToken,ObjectiveWorkspaceService workspaces,ObjectMapper json){
        this(http,githubToken,workspaces,null,json,URI.create("https://api.github.com/"));
    }
    public RepositoryWorkspaceMaterializationService(HttpClient http,String githubToken,ObjectiveWorkspaceService workspaces,ExecutionWorkspaceManager executionWorkspaces,ObjectMapper json){
        this(http,githubToken,workspaces,executionWorkspaces,json,URI.create("https://api.github.com/"));
    }
    RepositoryWorkspaceMaterializationService(HttpClient http,String githubToken,ObjectiveWorkspaceService workspaces,ObjectMapper json,URI apiBase){
        this(http,githubToken,workspaces,null,json,apiBase);
    }
    RepositoryWorkspaceMaterializationService(HttpClient http,String githubToken,ObjectiveWorkspaceService workspaces,ExecutionWorkspaceManager executionWorkspaces,ObjectMapper json,URI apiBase){
        this.http=Objects.requireNonNull(http,"http");this.githubToken=githubToken==null?"":githubToken.trim();this.workspaces=Objects.requireNonNull(workspaces,"workspaces");this.executionWorkspaces=executionWorkspaces;this.json=Objects.requireNonNull(json,"json");this.apiBase=Objects.requireNonNull(apiBase,"apiBase");
    }

    public boolean provisioned(){return !githubToken.isBlank();}

    public MaterializedRepository materialize(String workerId,String objectiveId,String repository,String ref){
        return materialize(workerId,objectiveId,repository,ref,PRIMARY_COMPONENT);
    }

    public MaterializedRepository materialize(String workerId,String objectiveId,String repository,String ref,String componentId){
        if(!provisioned())throw repositoryControlPlaneUnavailable("credential-not-provisioned");
        String repo=normalizeRepository(repository);String requestedRef=normalizeRef(ref);
        ObjectiveWorkspaceService.ObjectiveWorkspace workspace=workspaces.provision(objectiveId,workerId);
        ensureWorkspaceAvailable(workspace);
        try{
            String resolvedSha=resolveCommit(repo,requestedRef);
            registerAttemptComponent(repo,requestedRef,componentId);
            URI archive=resolveArchiveLocation(repo,resolvedSha);byte[] zip=downloadArchive(archive);Extraction extraction=extract(zip,workspace);
            Path provenance=workspaces.resolve(workspace,".metatron-repository");
            Files.writeString(provenance,"repository="+repo+"\nrequestedRef="+requestedRef+"\ncommitSha="+resolvedSha+"\ncomponentId="+componentId+"\n",StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
            return new MaterializedRepository(repo,requestedRef,resolvedSha,workspace.workspaceRef(),extraction.files(),extraction.bytes());
        }catch(GovernanceDeniedException denied){throw denied;}catch(IOException|InterruptedException failure){if(failure instanceof InterruptedException)Thread.currentThread().interrupt();throw new IllegalStateException("repository workspace materialization failed",failure);}
    }

    /** Called after the local immutable baseline commit/ref has been established in the sandbox. */
    public void recordLocalBaseline(MaterializedRepository materialized,String localBaselineSha){
        if(executionWorkspaces==null)return;
        ExecutionAttemptContext.current().ifPresent(ctx->executionWorkspaces.markMaterialized(ctx.attemptId(),ctx.fencingToken(),PRIMARY_COMPONENT,materialized.resolvedCommitSha(),localBaselineSha,Instant.now()));
    }

    private void registerAttemptComponent(String repository,String requestedRef,String componentId){
        if(executionWorkspaces==null)return;
        ExecutionAttemptContext.current().ifPresent(ctx->executionWorkspaces.registerRepository(ctx.attemptId(),ctx.fencingToken(),repository,requestedRef,componentId,"metatron/"+safeBranchToken(ctx.attemptId()),Instant.now()));
    }

    private String resolveCommit(String repo,String ref)throws IOException,InterruptedException{
        if(ref.matches("[0-9a-fA-F]{40}"))return ref.toLowerCase();
        HttpResponse<String> response=sendAuthenticated("repos/"+repo+"/commits/"+encodePathSegment(ref));
        if(response.statusCode()==401||response.statusCode()==403)throw repositoryControlPlaneUnavailable("commit-resolution-http-"+response.statusCode());
        if(response.statusCode()!=200)throw new IllegalStateException("repository commit resolution HTTP "+response.statusCode());
        JsonNode root=json.readTree(response.body());String sha=root.path("sha").asText("").trim().toLowerCase();if(!sha.matches("[0-9a-f]{40}"))throw new IllegalStateException("GitHub commit response missing immutable SHA");return sha;
    }
    private URI resolveArchiveLocation(String repo,String sha)throws IOException,InterruptedException{
        HttpRequest request=authenticatedRequest(apiBase.resolve("repos/"+repo+"/zipball/"+sha)).GET().build();HttpResponse<Void> response=http.send(request,HttpResponse.BodyHandlers.discarding());
        if(response.statusCode()==401||response.statusCode()==403)throw repositoryControlPlaneUnavailable("archive-resolution-http-"+response.statusCode());
        if(response.statusCode()!=302&&response.statusCode()!=301&&response.statusCode()!=307)throw new IllegalStateException("repository archive redirect HTTP "+response.statusCode());
        String location=response.headers().firstValue("Location").orElseThrow(()->new IllegalStateException("repository archive redirect missing Location"));URI uri=URI.create(location);
        if(!"https".equalsIgnoreCase(uri.getScheme())||!"codeload.github.com".equalsIgnoreCase(uri.getHost()))throw new SecurityException("untrusted repository archive redirect host");return uri;
    }
    private byte[] downloadArchive(URI archive)throws IOException,InterruptedException{
        HttpRequest request=HttpRequest.newBuilder(archive).timeout(Duration.ofSeconds(60)).header("Accept","application/zip").header("User-Agent","metatron-workforce").GET().build();HttpResponse<java.io.InputStream> response=http.send(request,HttpResponse.BodyHandlers.ofInputStream());
        if(response.statusCode()==401||response.statusCode()==403)throw repositoryControlPlaneUnavailable("archive-download-http-"+response.statusCode());if(response.statusCode()!=200)throw new IllegalStateException("repository archive download HTTP "+response.statusCode());
        try(var input=response.body();var out=new ByteArrayOutputStream()){byte[] buffer=new byte[32*1024];long total=0;int read;while((read=input.read(buffer))>=0){total+=read;if(total>MAX_ARCHIVE_BYTES)throw new IllegalStateException("repository archive exceeds byte budget");out.write(buffer,0,read);}return out.toByteArray();}
    }
    private Extraction extract(byte[] archive,ObjectiveWorkspaceService.ObjectiveWorkspace workspace)throws IOException{
        int files=0;long bytes=0;String archiveRoot=null;List<Path> wrappers=new ArrayList<>();
        try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(archive))){ZipEntry entry;byte[] buffer=new byte[32*1024];while((entry=zip.getNextEntry())!=null){String raw=entry.getName().replace('\\','/');int slash=raw.indexOf('/');if(slash<1)continue;String root=raw.substring(0,slash);if(archiveRoot==null)archiveRoot=root;if(!archiveRoot.equals(root))throw new SecurityException("repository archive has multiple roots");String relative=raw.substring(slash+1);if(relative.isBlank())continue;Path target=workspaces.resolve(workspace,relative);if(entry.isDirectory()){Files.createDirectories(target);continue;}files++;if(files>MAX_FILES)throw new IllegalStateException("repository archive exceeds file budget");Path parent=target.getParent();if(parent!=null)Files.createDirectories(parent);if(Files.exists(target,LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("repository archive would overwrite workspace content: "+relative);try(var output=Files.newOutputStream(target,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){int read;while((read=zip.read(buffer))>=0){bytes+=read;if(bytes>MAX_EXTRACTED_BYTES)throw new IllegalStateException("repository extraction exceeds byte budget");output.write(buffer,0,read);}}if(relative.equals("gradlew")||relative.equals("mvnw")){target.toFile().setExecutable(true,false);wrappers.add(target);}}}
        if(files==0)throw new IllegalStateException("repository archive contained no files");for(Path wrapper:wrappers)if(!Files.isRegularFile(wrapper,LinkOption.NOFOLLOW_LINKS))throw new SecurityException("repository wrapper extraction invalid");return new Extraction(files,bytes);
    }
    private void ensureWorkspaceAvailable(ObjectiveWorkspaceService.ObjectiveWorkspace workspace){List<String> existing=workspaces.list(workspace,"").stream().filter(path->!path.equals(".metatron-workspace")).toList();if(!existing.isEmpty())throw new IllegalStateException("objective workspace already contains materialized/work product content");}
    private HttpResponse<String> sendAuthenticated(String relative)throws IOException,InterruptedException{return http.send(authenticatedRequest(apiBase.resolve(relative)).GET().build(),HttpResponse.BodyHandlers.ofString());}
    private HttpRequest.Builder authenticatedRequest(URI uri){return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).header("Accept","application/vnd.github+json").header("Authorization","Bearer "+githubToken).header("X-GitHub-Api-Version","2022-11-28").header("User-Agent","metatron-workforce");}
    private static String normalizeRepository(String repository){String value=require(repository,"repository").trim();if(!REPOSITORY.matcher(value).matches()||value.contains(".."))throw new IllegalArgumentException("invalid GitHub repository");return CanonicalRepositoryScope.requireAllowed(value);}
    private static String normalizeRef(String ref){String value=ref==null||ref.isBlank()?"main":ref.trim();if(!REF.matcher(value).matches()||value.contains("..")||value.startsWith("/")||value.endsWith("/"))throw new IllegalArgumentException("invalid GitHub repository ref");return value;}
    private static String encodePathSegment(String value){return java.net.URLEncoder.encode(value,java.nio.charset.StandardCharsets.UTF_8).replace("+","%20");}
    private static GovernanceDeniedException repositoryControlPlaneUnavailable(String detail){return new GovernanceDeniedException("REPOSITORY_CONTROL_PLANE_UNAVAILABLE",detail);}
    private static String require(String value,String field){if(value==null||value.isBlank())throw new IllegalArgumentException(field+" required");return value;}
    private static String safeBranchToken(String attemptId){return Integer.toUnsignedString(attemptId.hashCode(),16);}
    private record Extraction(int files,long bytes){}
}
