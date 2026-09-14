package com.bproject.trust.verification;

import com.bproject.trust.identity.CurrentIdentity;
import java.io.FilterInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class VerificationController {
  private final CurrentIdentity identity;
  private final VerificationService verification;

  public VerificationController(CurrentIdentity identity, VerificationService verification) {
    this.identity = identity;
    this.verification = verification;
  }

  @PostMapping("/events/{id}/verify")
  public Map<String, Object> verify(Authentication a, @PathVariable String id) {
    return verification.verify(id, identity.org(a), a.getName());
  }

  @PostMapping("/events/{id}/export")
  public ResponseEntity<InputStreamResource> export(Authentication a, @PathVariable String id)
      throws Exception {
    String name = "trust-" + UUID.fromString(id) + ".zip";
    Path file = verification.export(id, identity.org(a), a.getName());
    try {
      long size = Files.size(file);
      var stream =
          new FilterInputStream(Files.newInputStream(file)) {
            @Override
            public void close() throws IOException {
              try {
                super.close();
              } finally {
                Files.deleteIfExists(file);
              }
            }
          };
      return ResponseEntity.ok()
          .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + name)
          .contentType(MediaType.parseMediaType("application/zip"))
          .contentLength(size)
          .body(new InputStreamResource(stream));
    } catch (Exception e) {
      Files.deleteIfExists(file);
      throw e;
    }
  }
}
