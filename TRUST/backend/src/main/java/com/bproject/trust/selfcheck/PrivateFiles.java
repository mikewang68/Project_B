package com.bproject.trust.selfcheck;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Reports and ephemeral connection credentials must stay under an explicit private directory. */
public final class PrivateFiles {
  private PrivateFiles() {}
  public static Path output(Path supplied) throws Exception {
    Path path = supplied.toAbsolutePath().normalize();
    boolean privateDirectory = false;
    for (Path part : path.getParent()) if (part.toString().equals(".local")) privateDirectory = true;
    if (!privateDirectory) throw new IllegalArgumentException("Output must be inside .local");
    for (Path ancestor=path; ancestor!=null; ancestor=ancestor.getParent())
      if (Files.isSymbolicLink(ancestor) || (Files.exists(ancestor,LinkOption.NOFOLLOW_LINKS)
          && !ancestor.toRealPath().equals(ancestor.toAbsolutePath().normalize())))
        throw new IllegalArgumentException("Linked output paths are not supported");
    Files.createDirectories(path.getParent());
    return path;
  }
  public static void write(Path supplied,String text) throws Exception {
    Path path=output(supplied);
    Path temp=Files.createTempFile(path.getParent(), ".selfcheck-", ".tmp");
    try {
      Files.writeString(temp,text,StandardCharsets.UTF_8);
      try { Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
      catch (AtomicMoveNotSupportedException e) { Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING); }
    } finally { Files.deleteIfExists(temp); }
  }
}
