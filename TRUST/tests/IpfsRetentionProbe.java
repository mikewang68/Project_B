import com.bproject.trust.adapters.ipfs.IpfsClient;
import java.time.Duration;
import java.util.Arrays;

/** Operator-invoked probe for an explicitly provisioned disposable loopback Kubo repository. */
public class IpfsRetentionProbe {
  public static void main(String[] args) throws Exception {
    if(args.length!=2 || !args[0].matches("http://127\\.0\\.0\\.1:[0-9]{4,5}")
        || !java.util.Set.of("online","offline").contains(args[1])) throw new IllegalArgumentException();
    if(args[1].equals("online")) {
      var root=java.nio.file.Files.createTempDirectory("trust-auth-permissions-");
      var config=root.resolve("runtime/secrets/integration.json");java.nio.file.Files.createDirectories(config.getParent());
      try {
        java.nio.file.Files.writeString(config,"{\"iamUrl\":\"http://127.0.0.1/iam\",\"iamMode\":\"real\",\"orgMap\":{},\"services\":[]}");
        var settings=new com.bproject.trust.identity.IntegrationSettings(root.toString());settings.initialize();
        java.nio.file.Files.setPosixFilePermissions(config,java.util.Set.of());
        if(java.nio.file.Files.isReadable(config)) throw new AssertionError("Probe must run as an unprivileged account");
        boolean rejected=false;try{settings.read();}catch(IllegalStateException expected){rejected=true;}
        if(!rejected) throw new AssertionError("Unreadable authentication configuration accepted");
        System.out.println("PASS real filesystem permission denial: authentication fails closed");
      } finally {
        java.nio.file.Files.setPosixFilePermissions(config,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        java.nio.file.Files.deleteIfExists(config);java.nio.file.Files.delete(config.getParent());java.nio.file.Files.delete(root.resolve("runtime"));java.nio.file.Files.delete(root);
      }
    }
    var ipfs=new IpfsClient(args[0],64L*1024*1024,null,Duration.ofSeconds(1),Duration.ofSeconds(3),Duration.ofSeconds(8),65536,33554432);
    byte[] original=new byte[256*1024];new java.util.Random(7721).nextBytes(original);
    long start=System.nanoTime();
    if(args[1].equals("offline")) {
      boolean failed=false;try {ipfs.add(original);} catch(Exception expected) {failed=true;}
      if(!failed || System.nanoTime()-start>java.util.concurrent.TimeUnit.SECONDS.toNanos(9)) throw new AssertionError("offline request was unbounded or succeeded");
      System.out.println("PASS isolated Kubo stopped: bounded failure");return;
    }
    String first=ipfs.add(original),replay=ipfs.add(original);
    if(!first.equals(replay)||!Arrays.equals(original,ipfs.cat(first))) throw new AssertionError("Kubo round trip mismatch");
    if(ipfs.usedBytes()<original.length) throw new AssertionError("repository accounting missing");
    System.out.println("PASS isolated Kubo: add/read-back/replay/hash/size");
  }
}
