import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;

/** Read-only task evidence for the isolated recovery database. */
public class DatabaseProbe {
  public static void main(String[] args) throws Exception {
    if (!args[0].matches("jdbc:opengauss://127.0.0.1:25432/trust_recovery_[a-z0-9_]+"))
      throw new IllegalArgumentException("Recovery database required");
    Class.forName("org.opengauss.Driver");
    try (var c = DriverManager.getConnection(args[0], "trust_app", System.getenv("TRUST_DB_PASSWORD"))) {
      String sql = switch (args[1]) {
        case "tasks" -> "SELECT event_id,state,attempts,lease_token,lease_until,last_error FROM trust_data.tasks ORDER BY event_id";
        case "evidence" -> "SELECT id,cid,sha256,storage_state FROM trust_data.evidence ORDER BY id";
        default -> throw new IllegalArgumentException("Unknown read-only probe");
      };
      var result = new ArrayList<Object>();
      try (var s = c.createStatement(); var r = s.executeQuery(sql)) {
        while (r.next()) {
          var row = new LinkedHashMap<String,Object>();
          for (int i = 1; i <= r.getMetaData().getColumnCount(); i++) row.put(r.getMetaData().getColumnLabel(i), r.getString(i));
          result.add(row);
        }
      }
      System.out.println(new ObjectMapper().writeValueAsString(result));
    }
  }
}
