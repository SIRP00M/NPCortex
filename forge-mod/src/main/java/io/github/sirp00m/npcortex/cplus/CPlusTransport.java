package io.github.sirp00m.npcortex.cplus;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;

/** Shared local C+ HTTP transport. Call on bounded IO workers only.
 * Retry only exact metadata requests with backend idempotency; never navigation.
 */
public final class CPlusTransport {
    private CPlusTransport() {}
    private static final HttpClient HTTP=HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();
    private static final Set<String> RETRY=Set.of("/v1/cplus/e3/reserve",
            "/v1/cplus/e3/report","/v1/cplus/e3/finalize","/v1/cplus/e3/cancel-begin",
            "/v1/cplus/e3/cancel-ack","/v1/cplus/e3/abandon","/v1/cplus/e3/status",
            // Stage 4 world-effect routes are idempotent by (task, evidence hash); one retry is safe.
            "/v1/cplus/we/reserve","/v1/cplus/we/start","/v1/cplus/we/finalize","/v1/cplus/we/cancel",
            "/v1/cplus/we/hold","/v1/cplus/we/reconcile","/v1/cplus/we/status",
            // Stage 4 native routes: idempotent by (task, canonical evidence hash); IDENTICAL bytes are re-sent, never new evidence.
            "/v1/cplus/wn/bind","/v1/cplus/wn/saved","/v1/cplus/wn/reserve","/v1/cplus/wn/start","/v1/cplus/wn/finalize",
            "/v1/cplus/wn/cancel","/v1/cplus/wn/hold","/v1/cplus/wn/repair","/v1/cplus/wn/reconcile","/v1/cplus/wn/status",
            "/v1/cplus/wn/active",
            // PAP-G2 player-action routes (place_block / use_block): the same idempotency by (task, canonical evidence hash).
            "/v1/cplus/pa/reserve","/v1/cplus/pa/start","/v1/cplus/pa/finalize","/v1/cplus/pa/cancel","/v1/cplus/pa/hold",
            "/v1/cplus/pa/reconcile","/v1/cplus/pa/status","/v1/cplus/pa/active");
    public static final class Rejected extends IOException {
        public final int status;
        Rejected(int status,String detail){super("C+ HTTP="+status+" "+detail);this.status=status;}
    }
    public static String post(URI uri,String token,String body,int seconds,int limit) throws IOException {
        CPlusF1Delivery.safeBackend(URI.create(uri.getScheme()+"://"+uri.getRawAuthority()).toString());
        if(!uri.getPath().startsWith("/v1/cplus/") || uri.getQuery()!=null || uri.getFragment()!=null)
            throw new IOException("invalid C+ route");
        if(token==null || token.length()<24 || token.length()>512 || token.contains("\n") || token.contains("\r"))
            throw new IOException("invalid local credential");
        byte[] encoded=body.getBytes(StandardCharsets.UTF_8);
        if(encoded.length>24000)throw new IOException("C+ request exceeds 24000 bytes");
        HttpRequest request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(seconds))
                .header("Content-Type","application/json").header("X-NPCortex-CPlus-Token",token)
                .POST(HttpRequest.BodyPublishers.ofByteArray(encoded)).build();
        int attempts=RETRY.contains(uri.getPath())?2:1;
        for(int i=0;;i++) {
            try {
                // ofByteArray consumer imposes a byte limit while receiving, not after allocation.
                HttpResponse<byte[]> response=HTTP.send(request,info -> new LimitedBodySubscriber(limit));
                String result=new String(response.body(),StandardCharsets.UTF_8);
                if(response.statusCode()!=200)throw new Rejected(response.statusCode(),
                        result.replaceAll("[\\r\\n\\p{Cntrl}]"," ").substring(0,Math.min(result.length(),240)));
                return result;
            }catch(Rejected e){throw e;}
            catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("C+ interrupted",e);}
            catch(IOException e){if(i+1>=attempts || Thread.currentThread().isInterrupted())throw e;}
        }
    }
    private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final java.util.concurrent.CompletableFuture<byte[]> result=new java.util.concurrent.CompletableFuture<>();
        private final java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
        private final int limit; private java.util.concurrent.Flow.Subscription subscription;
        LimitedBodySubscriber(int limit){this.limit=limit;}
        public java.util.concurrent.CompletionStage<byte[]> getBody(){return result;}
        public void onSubscribe(java.util.concurrent.Flow.Subscription s){subscription=s;s.request(1);}
        public void onNext(java.util.List<java.nio.ByteBuffer> chunks){
            for(var b:chunks){if(b.remaining()>limit-bytes.size()){
                subscription.cancel();result.completeExceptionally(new IOException("C+ response exceeds byte budget"));return;}
                byte[] copy=new byte[b.remaining()];b.get(copy);bytes.writeBytes(copy);}
            subscription.request(1);
        }
        public void onError(Throwable e){result.completeExceptionally(e);}
        public void onComplete(){result.complete(bytes.toByteArray());}
    }
    /** A bounded, non-sensitive operator code. Do not surface exception messages:
     * server HTTP bodies, file paths or credentials could be included in them.
     * This reports failure type only; never changes transport retry/fencing.
     */
    public static String diagnosticCode(Throwable failure) {
        boolean outbox = false;
        for (int depth=0;failure!=null && depth<12;depth++,failure=failure.getCause()) {
            if (failure instanceof Rejected rejected)
                return "HTTP_"+rejected.status;
            if (failure instanceof java.net.http.HttpTimeoutException)
                return "HTTP_TIMEOUT";
            if (failure instanceof java.net.ConnectException || failure instanceof java.net.UnknownHostException)
                return "CONNECT_FAILED";
            if (failure instanceof java.nio.file.FileSystemException || failure instanceof java.nio.file.AccessDeniedException)
                return "OUTBOX_FILESYSTEM";
            String message=failure.getMessage();
            if (message!=null && message.startsWith("C+ outbox HOLD:")) outbox=true;
            if (failure instanceof IOException && message!=null) {
                if (message.contains("outbox identity/payload conflict"))return "OUTBOX_CONFLICT";
                if (message.contains("outbox unsafe"))return "OUTBOX_UNSAFE";
                if (message.contains("C+ response exceeds byte budget"))return "RESPONSE_OVERSIZE";
                if (message.contains("invalid C+ route") || message.contains("invalid local credential")
                        || message.contains("C+ request exceeds"))return "INVALID_LOCAL_REQUEST";
            }
        }
        return outbox?"OUTBOX_IO":"TRANSPORT_UNKNOWN";
    }
    public static String exchange(URI uri,String token,String json,int seconds,int limit){
        try{return post(uri,token,json,seconds,limit);}
        catch(IOException e){throw new IllegalStateException(e.getMessage(),e);}
    }
}
