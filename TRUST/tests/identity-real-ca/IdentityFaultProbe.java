import com.bproject.trust.adapters.fabric.*;
import com.bproject.trust.identity.IntegrationSettings;
import com.bproject.trust.provisioning.*;
import com.bproject.trust.shared.json.Json;
import com.bproject.trust.wallet.WalletKeyStore;
import java.nio.file.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Operator-only real CA/openGauss/Gateway fault harness; test application must be stopped. */
public class IdentityFaultProbe {
  public static void main(String[] args) throws Exception {
    String root = System.getenv("TRUST_ROOT"), url = System.getenv("TRUST_DB_URL");
    if (!"/srv/b-project-identity-test/TRUST".equals(root)
        || !"jdbc:opengauss://127.0.0.1:25432/trust_iam_dev_test".equals(url))
      throw new IllegalArgumentException("Dedicated test instance only");
    String phase = args[0], scenario = args[1];
    if (!Set.of("outage", "timeout", "database", "restart").contains(scenario))
      throw new IllegalArgumentException("Unknown scenario");
    var ds = new DriverManagerDataSource(url, System.getenv("TRUST_DB_USER"), System.getenv("TRUST_DB_PASSWORD"));
    var properties = new Properties(); properties.setProperty("currentSchema", "trust_data");
    ds.setConnectionProperties(properties);
    var db = new JdbcTemplate(ds);
    var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    var service = new FabricIdentityService(db, tx);
    var settings = new IdentityProviderSettings(root, new IntegrationSettings(root)) {
      @Override public Settings read() {
        var original = super.read();
        var organizations = new HashMap<>(original.organizations());
        var org = organizations.get("B-PROJECT");
        organizations.put("B-PROJECT", new Organization(org.mspId(), org.caId(), org.affiliation(),
            org.attributes(), "https://127.0.0.1:39154", root + "/runtime/real-ca-faults/proxy.crt",
            org.enrollmentRootFile(), org.registrarHome()));
        return new Settings(original.clientBinary(), original.clientVersion(), original.clientSha256(),
            original.clients(), organizations);
      }
    };
    var caller = settings.read().clients().stream().filter(c -> c.issuer().equals("identity-acceptance")).findFirst().orElseThrow();
    String run = System.getenv("TRUST_FAULT_RUN_ID");
    if (run == null || !run.matches("[a-z0-9-]{1,32}")) throw new IllegalArgumentException("Run ID required");
    String user = "real-ca-fault-" + run + "-" + scenario;
    String id = (String) service.provision(caller, user + "-v1",
        new FabricIdentityService.Command(caller.issuer(), caller.tenant(), user, "B-PROJECT", 1, "ACTIVE", "SYNC")).get("identityId");
    if (phase.equals("process")) {
      var ca = new OfficialCaClient(settings, new WalletKeyStore(root), root);
      var network = new GatewayIdentityNetwork(root, "127.0.0.1:27051", "peer0.org1.trust", "trust-iam-dev-test", "evidence");
      new IdentityWorker(db, tx, settings, ca, network, service).process(id);
    } else if (phase.equals("deny-update") || phase.equals("allow-update")) {
      var migrate = new DriverManagerDataSource(url, "trust_iam_test_migrate", System.getenv("TRUST_TEST_MIGRATE_PASSWORD"));
      new JdbcTemplate(migrate).execute(phase.equals("deny-update")
          ? "REVOKE UPDATE ON trust_data.fabric_certificate FROM trust_iam_test_app"
          : "GRANT UPDATE ON trust_data.fabric_certificate TO trust_iam_test_app");
    } else if (!phase.equals("status")) throw new IllegalArgumentException("Unknown phase");
    var row = db.queryForMap("SELECT status,lease_until FROM fabric_identity WHERE id=?", id);
    var certificates = db.queryForList("SELECT version,key_ref,state,fingerprint,network_state FROM fabric_certificate WHERE identity_id=? ORDER BY version", id);
    var result = new LinkedHashMap<String,Object>();
    result.put("scenario", scenario);result.put("identityId", id);result.put("status", row.get("status"));
    result.put("leaseUntil", row.get("lease_until"));result.put("certificates", certificates);
    result.put("custodyPresent", certificates.size()==1 && new WalletKeyStore(root).contains((String)certificates.get(0).get("key_ref")));
    System.out.println(Json.write(result));
  }
}
