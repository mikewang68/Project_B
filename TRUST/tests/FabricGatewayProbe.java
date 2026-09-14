package com.bproject.trust;

import com.bproject.trust.adapters.fabric.FabricClient;
import com.bproject.trust.archiving.AttestationRecord;
import com.bproject.trust.shared.json.Json;
import java.util.*;

/** Real Java SDK/TLS/endorsement/commit check, independent of database and application tunnels. */
public class FabricGatewayProbe {
  public static void main(String[] args) throws Exception {
    FabricClient client =
        new FabricClient(args[0], "127.0.0.1:27051", "peer0.org1.trust", "trust", "evidence");
    try {
      var original = client.find("B-PROJECT", args[1]);
      if (original == null) throw new IllegalStateException("Fixture not found");
      var record = new HashMap<>(original);
      record.remove("txId");
      record.remove("ledgerIdentity");
      String id = UUID.randomUUID().toString();
      record.put("id", id);
      record.put("rootId", id);
      record.put("version", 1);
      record.put("supersedesId", "");
      record.put("submittedBy", "java-gateway-probe");
      var receipt = client.submit(record, tx -> {});
      AttestationRecord.assertMatches(record, receipt);
      AttestationRecord.assertMatches(record, client.find("B-PROJECT", id));
      System.out.println(
          Json.write(
              Map.of(
                  "status",
                  "PASS",
                  "scope",
                  "Real Java SDK, TLS, dual endorsement, valid commit and readback",
                  "receipt",
                  receipt)));
    } finally {
      client.close();
    }
  }
}
