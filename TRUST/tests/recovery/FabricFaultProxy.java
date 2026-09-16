import io.grpc.CallOptions;
import io.grpc.Context;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.ServerCalls;
import io.grpc.stub.StreamObserver;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.hyperledger.fabric.protos.gateway.CommitStatusRequest;
import org.hyperledger.fabric.protos.gateway.CommitStatusResponse;
import org.hyperledger.fabric.protos.gateway.SignedCommitStatusRequest;
import org.hyperledger.fabric.protos.gateway.SubmitRequest;

/** Test-only TLS proxy. Forwards signed requests to the real peer unchanged. */
public class FabricFaultProxy {
  static Path folder;
  static synchronized void log(String type, String tx, long block) {
    try {
      Files.writeString(folder.resolve("proxy.jsonl"),
          "{\"time\":\"" + Instant.now() + "\",\"type\":\"" + type
              + "\",\"txId\":\"" + tx + "\",\"block\":" + block + "}\n",
          StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    } catch (Exception e) { throw new RuntimeException(e); }
  }
  static synchronized boolean suppress() throws Exception {
    String mode = Files.readString(folder.resolve("mode")).trim();
    if (mode.equals("timeout-once")) {
      Files.writeString(folder.resolve("mode"), "pass");
      return true;
    }
    return mode.equals("hold");
  }
  static final MethodDescriptor.Marshaller<byte[]> BYTES = new MethodDescriptor.Marshaller<>() {
    public InputStream stream(byte[] value) { return new ByteArrayInputStream(value); }
    public byte[] parse(InputStream stream) {
      try { return stream.readAllBytes(); } catch (Exception e) { throw new RuntimeException(e); }
    }
  };
  public static void main(String[] args) throws Exception {
    folder = Path.of(args[0]);
    ManagedChannel upstream = NettyChannelBuilder.forTarget(args[1])
        .sslContext(GrpcSslContexts.forClient().trustManager(folder.resolve("upstream-ca.crt").toFile()).build())
        .overrideAuthority("peer0.org1.trust").build();
    var service = ServerServiceDefinition.builder("gateway.Gateway");
    for (String name : new String[]{"Evaluate", "Endorse", "Submit", "CommitStatus"}) {
      var method = MethodDescriptor.<byte[], byte[]>newBuilder()
          .setType(MethodDescriptor.MethodType.UNARY)
          .setFullMethodName("gateway.Gateway/" + name)
          .setRequestMarshaller(BYTES).setResponseMarshaller(BYTES).build();
      service.addMethod(method, ServerCalls.asyncUnaryCall((request, downstream) -> {
        final Context context = Context.current();
        try {
          if (name.equals("Submit")) log("submit", SubmitRequest.parseFrom(request).getTransactionId(), -1);
          ClientCalls.asyncUnaryCall(upstream.newCall(method, CallOptions.DEFAULT.withDeadlineAfter(25, TimeUnit.SECONDS)), request, new StreamObserver<byte[]>() {
            boolean blocked;
            public void onNext(byte[] response) {
              try {
                if (name.equals("CommitStatus")) {
                  var tx = CommitStatusRequest.parseFrom(SignedCommitStatusRequest.parseFrom(request).getRequest()).getTransactionId();
                  var status = CommitStatusResponse.parseFrom(response);
                  if (status.getResultValue() == 0 && suppress()) {
                    blocked = true;
                    log("valid-commit-withheld", tx, status.getBlockNumber());
                    context.addListener(c -> log("client-confirmation-cancelled", tx, status.getBlockNumber()), Runnable::run);
                    return;
                  }
                }
                downstream.onNext(response);
              } catch (Exception e) { downstream.onError(Status.INTERNAL.withDescription(e.getClass().getSimpleName()).asRuntimeException()); }
            }
            public void onError(Throwable e) { if (!blocked) downstream.onError(e); }
            public void onCompleted() { if (!blocked) downstream.onCompleted(); }
          });
        } catch (Exception e) { downstream.onError(Status.INTERNAL.asRuntimeException()); }
      }));
    }
    Server server = NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 37051))
        .useTransportSecurity(folder.resolve("proxy.crt").toFile(), folder.resolve("proxy.key").toFile())
        .addService(service.build()).build().start();
    Runtime.getRuntime().addShutdownHook(new Thread(() -> { server.shutdownNow(); upstream.shutdownNow(); }));
    log("ready", "", -1);
    server.awaitTermination();
  }
}
