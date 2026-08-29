package com.metatron.workforce.workers.audit;

import com.metatron.workforce.workers.WorkerContext;
import com.metatron.workforce.workers.WorkerResult;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class RepositoryAuditWorkerTest {
    private HttpServer server;
    @AfterEach void stop(){ if(server!=null)server.stop(0); }

    @Test void passRequiresTreeAndRealContentObservations() throws Exception {
        start(false,null);
        RepositoryAuditWorker w=new RepositoryAuditWorker(HttpClient.newHttpClient(),base());
        WorkerResult r=w.execute(new WorkerContext("A1","Audit kelvinka38/bios",Instant.now()));
        assertEquals("PASS",r.status());
        assertTrue(r.evidence().contains("Repository Audit Report"));
        assertTrue(r.evidence().contains("commitSha=0123456789abcdef0123456789abcdef01234567"));
        assertTrue(r.evidence().contains("repositoryFilesObserved=2"));
        assertTrue(r.evidence().contains("contentFilesRead=2"));
        assertTrue(r.evidence().contains("sotSignals=1"));
        assertTrue(r.evidence().contains("findings=TODO_FIXME_MARKERS=1"));
        assertTrue(r.evidence().contains("observedPaths=BIOS_SOT.md,src/Main.java"));
    }

    @Test void tokenIsUsedAndNeverLeaked() throws Exception {
        AtomicReference<String> auth=new AtomicReference<>(); start(true,auth);
        RepositoryAuditWorker w=new RepositoryAuditWorker(HttpClient.newHttpClient(),base(),"secret-token");
        WorkerResult r=w.execute(new WorkerContext("A2","Audit kelvinka38/bios",Instant.now()));
        assertEquals("PASS",r.status()); assertEquals("Bearer secret-token",auth.get());
        assertTrue(r.evidence().contains("authenticated=true")); assertFalse(r.evidence().contains("secret-token"));
    }

    @Test void treeFailureFailsClosed() throws Exception {
        server=HttpServer.create(new InetSocketAddress(0),0);
        server.createContext("/repos/kelvinka38/bios",e->respond(e,200,"{\"default_branch\":\"main\"}"));
        server.createContext("/repos/kelvinka38/bios/commits/main",e->respond(e,200,"{\"sha\":\"0123456789abcdef0123456789abcdef01234567\"}"));
        server.createContext("/repos/kelvinka38/bios/git/trees/0123456789abcdef0123456789abcdef01234567",e->respond(e,503,"{}")); server.start();
        WorkerResult r=new RepositoryAuditWorker(HttpClient.newHttpClient(),base()).execute(new WorkerContext("A3","Audit kelvinka38/bios",Instant.now()));
        assertEquals("FAILED",r.status()); assertTrue(r.evidence().contains("repository tree HTTP 503"));
    }

    @Test void missingTargetFailsClosed(){ WorkerResult r=new RepositoryAuditWorker().execute(new WorkerContext("A4","Audit repository",Instant.now())); assertEquals("FAILED",r.status()); }

    private void start(boolean capture,AtomicReference<String> auth)throws Exception{
        server=HttpServer.create(new InetSocketAddress(0),0);
        server.createContext("/repos/kelvinka38/bios",e->{if(capture)auth.set(e.getRequestHeaders().getFirst("Authorization"));respond(e,200,"{\"default_branch\":\"main\"}");});
        server.createContext("/repos/kelvinka38/bios/commits/main",e->respond(e,200,"{\"sha\":\"0123456789abcdef0123456789abcdef01234567\"}"));
        server.createContext("/repos/kelvinka38/bios/git/trees/0123456789abcdef0123456789abcdef01234567",e->respond(e,200,"{\"tree\":[{\"path\":\"BIOS_SOT.md\"},{\"path\":\"src/Main.java\"}]}"));
        server.createContext("/repos/kelvinka38/bios/contents/BIOS_SOT.md",e->respond(e,200,"# BIOS SOURCE OF TRUTH\ncanonical"));
        server.createContext("/repos/kelvinka38/bios/contents/src/Main.java",e->respond(e,200,"class Main {} // TODO verify")); server.start();
    }
    private String base(){return "http://127.0.0.1:"+server.getAddress().getPort();}
    private static void respond(com.sun.net.httpserver.HttpExchange e,int status,String body)throws java.io.IOException{byte[] b=body.getBytes(java.nio.charset.StandardCharsets.UTF_8);e.sendResponseHeaders(status,b.length);try(var o=e.getResponseBody()){o.write(b);}}
}
