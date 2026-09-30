import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import java.util.Arrays;

/** Run using Java 17 source-file mode; no new runtime dependency. Never prints key material. */
class ProvisionWallet {
  public static void main(String[] args) throws Exception {
    if(args.length!=4 || !args[1].matches("[A-Za-z0-9_-]{1,100}"))
      throw new IllegalArgumentException("Usage: java ProvisionWallet.java PROJECT_ROOT KEY_REF CERT_PEM PRIVATE_KEY_PEM");
    Path root=Path.of(args[0],"runtime/secrets/wallets");Files.createDirectories(root);protect(root,true);
    Path directory=root.resolve(args[1]);if(Files.exists(directory))throw new IllegalArgumentException("Key reference already exists; use a new version");
    Path masterFile=root.resolve("master.key");SecureRandom random=new SecureRandom();
    if(!Files.exists(masterFile)) {byte[] fresh=new byte[32];random.nextBytes(fresh);Files.write(masterFile,fresh,StandardOpenOption.CREATE_NEW);protect(masterFile,false);Arrays.fill(fresh,(byte)0);}
    byte[] master=Files.readAllBytes(masterFile),plain=Files.readAllBytes(Path.of(args[3]));
    if(master.length!=32)throw new IllegalArgumentException("Invalid master key");
    byte[] nonce=new byte[12];random.nextBytes(nonce);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(master,"AES"),new GCMParameterSpec(128,nonce));cipher.updateAAD(args[1].getBytes(java.nio.charset.StandardCharsets.UTF_8));
    byte[] encrypted=cipher.doFinal(plain),output=new byte[nonce.length+encrypted.length];System.arraycopy(nonce,0,output,0,nonce.length);System.arraycopy(encrypted,0,output,nonce.length,encrypted.length);
    Files.createDirectory(directory);protect(directory,true);
    Files.copy(Path.of(args[2]),directory.resolve("certificate.pem"));Files.write(directory.resolve("private-key.enc"),output,StandardOpenOption.CREATE_NEW);
    protect(directory.resolve("certificate.pem"),false);protect(directory.resolve("private-key.enc"),false);
    Arrays.fill(master,(byte)0);Arrays.fill(plain,(byte)0);
    System.out.println("Provisioned immutable key reference: "+args[1]+". Register and review it in TRUST before use.");
  }
  private static void protect(Path p,boolean directory) throws Exception {
    if(!Files.getFileStore(p).supportsFileAttributeView("posix"))throw new IllegalStateException("Provision on the Linux application node with POSIX permissions");
    Files.setPosixFilePermissions(p,PosixFilePermissions.fromString(directory?"rwx------":"rw-------"));
  }
}
