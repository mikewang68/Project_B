import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
/** Synthetic classifier checks; never connects to a database. */
public class EhmDemoScopePlanTest {
 static void check(boolean value){if(!value)throw new AssertionError("PLANNER_CLASSIFICATION_FAILED");}
 public static void main(String[] ignored)throws Exception{
  var json=new ObjectMapper();var assets=Set.of("SAMPLE-LIVE-EQ-01");
  check(EhmDemoScopePlan.selected("asset","SAMPLE-LIVE-EQ-01",json.readTree("{}"),assets));
  check(!EhmDemoScopePlan.selected("asset","SAMPLE-LIVE-EQ-010",json.readTree("{}"),assets));
  check(EhmDemoScopePlan.selected("health-assessment","uuid",json.readTree("{\"assetCode\":\"SAMPLE-LIVE-EQ-01\"}"),assets));
  check(!EhmDemoScopePlan.selected("health-assessment","uuid",json.readTree("{\"assetCode\":\"REAL-01\",\"description\":\"SAMPLE-LIVE-EQ-01\"}"),assets));
  check(EhmDemoScopePlan.selected("sample-telemetry","SAMPLE-LIVE-EQ-01-TEMP:timestamp",json.readTree("{}"),assets));
  check(!EhmDemoScopePlan.selected("sample-telemetry","SAMPLE-LIVE-EQ-010-TEMP:timestamp",json.readTree("{}"),assets));
  check(EhmDemoScopePlan.selected("spare-part","SAMPLE-LIVE-KIT-ROTATING",json.readTree("{}"),assets));
  check(EhmDemoScopePlan.selected("shift-handover","SAMPLE-LIVE-SHIFT-1",json.readTree("{}"),assets));
  check(!EhmDemoScopePlan.selected("shift-handover","MANUAL-01",json.readTree("{\"summary\":\"SAMPLE-LIVE-EQ-01\"}"),assets));
  check(!EhmDemoScopePlan.selected("audit","SAMPLE-LIVE-EQ-01",json.readTree("{\"assetCode\":\"SAMPLE-LIVE-EQ-01\"}"),assets));
  check(!EhmDemoScopePlan.selected("shared-dispatch-plan","SAMPLE-LIVE-EQ-01",json.readTree("{}"),assets));
  check(EhmDemoScopePlan.selected("simulation-control","control",json.readTree("{}"),assets));
  check(EhmDemoScopePlan.selected("simulation-fault-sample","SAMPLE-LIVE-EQ-01-C1-ALM",json.readTree("{}"),assets));
  check(!EhmDemoScopePlan.selected("simulation-fault-sample","SAMPLE-LIVE-EQ-010-C1-ALM",json.readTree("{}"),assets));
  check(!EhmDemoScopePlan.selected("simulation-fault-sample","REAL-C1-ALM",json.readTree("{}"),assets));
  System.out.println("DEMO_PLANNER_CLASSIFIER_15_CHECKS_PASSED; NO_DATABASE_CONNECTION");
 }
}
