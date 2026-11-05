import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

/** Trusted startup-only configuration. No identities or grants come from a request body. */
final class AccessPolicy {
    record Binding(String project, String area) {}
    record Principal(String id, String tokenSha256, Set<String> read, Set<String> write) {}
    final Map<String, Binding> devices;
    final List<Principal> principals;
    final String registryHash, policyHash;

    private AccessPolicy(Map<String, Binding> devices, List<Principal> principals) throws Exception {
        this.devices = Map.copyOf(devices); this.principals = List.copyOf(principals);
        List<Object> registry = new ArrayList<>();
        new TreeMap<>(devices).forEach((id, b) -> registry.add(List.of(id, b.project(), b.area())));
        registryHash = DeviceIngress.sha(DeviceIngress.JSON.writeValueAsBytes(registry));
        List<Object> grants = new ArrayList<>();
        principals.stream().sorted(Comparator.comparing(Principal::id)).forEach(p ->
                grants.add(List.of(p.id(), p.tokenSha256(), new TreeSet<>(p.read()), new TreeSet<>(p.write()))));
        policyHash = DeviceIngress.sha(DeviceIngress.JSON.writeValueAsBytes(List.of(registryHash, grants)));
    }
    static void config(boolean ok) { if (!ok) throw new IllegalArgumentException("INVALID_ACCESS_POLICY"); }
    static String id(JsonNode n, String key) {
        JsonNode value = n.get(key);
        config(value != null && value.isTextual() && value.textValue().matches("[A-Za-z0-9_.-]{1,63}"));
        return value.textValue();
    }
    static void keys(JsonNode n, String... allowed) {
        config(n.isObject()); Set<String> expected = Set.of(allowed); Set<String> actual = new HashSet<>();
        n.fieldNames().forEachRemaining(actual::add); config(actual.equals(expected));
    }
    static Set<String> grants(JsonNode n, Map<String, Binding> devices) {
        config(n != null && n.isArray()); Set<String> result = new HashSet<>();
        for (JsonNode value : n) config(value.isTextual() && devices.containsKey(value.textValue()) && result.add(value.textValue()));
        return Set.copyOf(result);
    }
    static AccessPolicy load(Path file) throws Exception {
        config(Files.isRegularFile(file) && Files.size(file) <= 256 * 1024);
        return parse(Files.readAllBytes(file));
    }
    static AccessPolicy parse(byte[] body) throws Exception {
        JsonNode root = DeviceIngress.parse(body); keys(root, "version", "devices", "principals");
        config(root.path("version").isIntegralNumber() && root.path("version").canConvertToInt() && root.path("version").asInt() == 1);
        config(root.path("devices").isArray() && !root.path("devices").isEmpty());
        config(root.path("principals").isArray() && !root.path("principals").isEmpty());
        Map<String, Binding> devices = new TreeMap<>();
        for (JsonNode n : root.path("devices")) {
            keys(n, "device_id", "project_id", "area_id"); String device = id(n, "device_id");
            config(device.matches("SIM-[A-Za-z0-9_.-]{1,59}"));
            config(devices.putIfAbsent(device, new Binding(id(n, "project_id"), id(n, "area_id"))) == null);
        }
        List<Principal> principals = new ArrayList<>(); Set<String> ids = new HashSet<>(), hashes = new HashSet<>();
        String emptyHash = DeviceIngress.sha(new byte[0]);
        for (JsonNode n : root.path("principals")) {
            keys(n, "principal_id", "token_sha256", "read_devices", "write_devices"); String principal = id(n, "principal_id");
            JsonNode hash = n.get("token_sha256");
            config(ids.add(principal) && hash.isTextual() && hash.textValue().matches("[a-f0-9]{64}")
                    && !hash.textValue().equals(emptyHash) && hashes.add(hash.textValue()));
            principals.add(new Principal(principal, hash.textValue(), grants(n.get("read_devices"), devices), grants(n.get("write_devices"), devices)));
        }
        return new AccessPolicy(devices, principals);
    }
    Principal authenticate(String token) throws Exception {
        if (token == null || !token.matches("[A-Za-z0-9._~-]{32,256}")) return null;
        byte[] candidate = DeviceIngress.sha(token.getBytes(StandardCharsets.UTF_8)).getBytes(StandardCharsets.US_ASCII);
        Principal found = null;
        for (Principal p : principals) if (MessageDigest.isEqual(candidate, p.tokenSha256().getBytes(StandardCharsets.US_ASCII))) found = p;
        return found;
    }
    void allow(Principal p, String device, boolean write) {
        if (p == null || !devices.containsKey(device) || !(write ? p.write() : p.read()).contains(device))
            throw new DeviceIngress.Problem(403, "DEVICE_SCOPE_DENIED");
    }
    void checkEvent(Principal p, JsonNode n) {
        checkEvent(p, n, false);
    }
    void checkEvent(Principal p, JsonNode n, boolean scopedAssets) {
        String device = DeviceIngress.text(n, "device_id"); allow(p, device, true); Binding b = devices.get(device);
        if ((n.has("project_id") && !n.path("project_id").asText().equals(b.project()))
                || (n.has("area_id") && !n.path("area_id").asText().equals(b.area())))
            throw new DeviceIngress.Problem(403, "DEVICE_BINDING_MISMATCH");
        for (JsonNode point : n.path("payload").path("points")) {
            JsonNode value = point.path("value");
            if (!scopedAssets && (value.has("sha256") || value.path("path").asText("").startsWith(DeviceIngress.BASE + "/assets")))
                throw new DeviceIngress.Problem(403, "ASSETS_DISABLED_IN_SCOPED_MODE");
        }
    }
    String cursorScope(Principal p, String device) { return "scope-v1:" + policyHash + ":" + p.id() + ":" + device; }
}
