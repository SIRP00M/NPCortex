package io.github.sirp00m.npcortex;
import com.sun.net.httpserver.HttpServer;
import io.github.sirp00m.npcortex.cplus.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

public final class CPlusTransportSelfTest {
    public static void main(String[] args) throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var count=new AtomicInteger();var payload=new java.util.concurrent.atomic.AtomicReference<String>();
        server.createContext("/v1/cplus/e3/report",x->{
            String body=new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
            if(payload.get()==null)payload.set(body);
            if(!payload.get().equals(body))throw new AssertionError("changed retry payload");
            if(count.incrementAndGet()==1){x.close();return;}
            byte[] reply="{\"state\":\"C2_ACCEPTED\"}".getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(200,reply.length);x.getResponseBody().write(reply);x.close();
        });
        var errors=new AtomicInteger();
        server.createContext("/v1/cplus/e3/reserve",x->{
            errors.incrementAndGet();x.getRequestBody().readAllBytes();byte[] b="{\"code\":\"conflict\"}".getBytes();
            x.sendResponseHeaders(409,b.length);x.getResponseBody().write(b);x.close();
        });
        server.createContext("/v1/cplus/e/status",x->{x.getRequestBody().readAllBytes();
            byte[] b=new byte[2000];x.sendResponseHeaders(200,b.length);x.getResponseBody().write(b);x.close();});
        server.start();
        try {
            URI base=URI.create("http://127.0.0.1:"+server.getAddress().getPort());String token="x".repeat(64);
            String reply=CPlusTransport.post(base.resolve("/v1/cplus/e3/report"),token,"{}",2,1024);
            if(!reply.contains("C2_ACCEPTED") || count.get()!=2)throw new AssertionError("retry");
            CPlusTransport.Rejected rejected=null;
            try{CPlusTransport.post(base.resolve("/v1/cplus/e3/reserve"),token,"{}",2,1024);throw new AssertionError("409 accepted");}
            catch(CPlusTransport.Rejected e){if(e.status!=409 || errors.get()!=1)throw new AssertionError("409 retried"); rejected=e;}
            try{CPlusTransport.post(base.resolve("/v1/cplus/e/status"),token,"{}",2,32);throw new AssertionError("oversize accepted");}
            catch(java.io.IOException expected){}
            CPlusF1Delivery.safeBackend("http://[::1]:8765");
            // Diagnostic codes classify causes without echoing private HTTP/error bodies.
            if(!"HTTP_409".equals(CPlusTransport.diagnosticCode(new java.util.concurrent.CompletionException(rejected))))
                throw new AssertionError("HTTP diagnostics");
            if(!"HTTP_TIMEOUT".equals(CPlusTransport.diagnosticCode(new java.net.http.HttpTimeoutException("private-timeout"))))
                throw new AssertionError("timeout diagnostics");
            if(!"CONNECT_FAILED".equals(CPlusTransport.diagnosticCode(new java.net.ConnectException("private-host"))))
                throw new AssertionError("connect diagnostics");
            if(!"OUTBOX_CONFLICT".equals(CPlusTransport.diagnosticCode(new IllegalStateException("C+ outbox HOLD",new java.io.IOException("outbox identity/payload conflict")))))
                throw new AssertionError("outbox diagnostics");
            if(!"OUTBOX_FILESYSTEM".equals(CPlusTransport.diagnosticCode(new java.nio.file.AccessDeniedException("private-path"))))
                throw new AssertionError("filesystem diagnostics");
            if(CPlusTransport.diagnosticCode(new IllegalStateException("private-secret",rejected)).contains("private"))
                throw new AssertionError("diagnostics leaked private error body");
            System.out.println("PASS shared transport: exact retry, no conflict retry, response bound, IPv6, safe diagnostics");
        }finally{server.stop(0);}
    }
}
