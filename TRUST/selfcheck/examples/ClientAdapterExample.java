import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.regex.*;

/** Java 17 source-file example: java ClientAdapterExample.java connection.json request.json. */
public class ClientAdapterExample {
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .proxy(new ProxySelector() {
                public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                public void connectFailed(URI uri, SocketAddress address, java.io.IOException error) { }
            })
            .connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();

    enum BusinessState { PENDING, COMPLETED }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected connection.json request.json");
        String connection = Files.readString(Path.of(args[0]), StandardCharsets.UTF_8);
        require(field(connection, "mode").equals("local-simulation"), "Not a local simulation");
        require(field(connection, "scenario").equals("normal"), "Example supports normal only");
        URI base = URI.create(field(connection, "baseUrl"));
        require(base.getScheme().equals("http") && base.getHost().equals("127.0.0.1")
                && base.getPort() > 0 && base.getUserInfo() == null && base.getQuery() == null
                && base.getFragment() == null
                && base.getPath().equals("/api/v1/integrations/evidence/events"), "Not an allowed loopback URL");
        String token = field(connection, "token");
        require(token.matches("selfcheck-[A-Za-z0-9-]+"), "Not a synthetic credential");
        byte[] raw = Files.readAllBytes(Path.of(args[1]));

        // Adaptation point: replace call() with your actual client. Export raw from real business code.
        BusinessState businessState = BusinessState.PENDING;
        HttpResponse<String> submitted = call(base, token, raw);
        require(submitted.statusCode() == 202, "Expected accepted HTTP 202");
        require(field(submitted.body(), "status").equals("ACCEPTED"), "Expected ACCEPTED receipt");
        // Adaptation point: read your own business record here, not the example variable.
        require(businessState == BusinessState.PENDING, "202 must not mark business evidence complete");
        String id = field(submitted.body(), "id");
        String number = field(submitted.body(), "sourceEventId");
        require(id.matches("[0-9a-f-]{36}"), "Unexpected receipt id");
        require(number.matches("[A-Za-z0-9._-]{1,120}") && !number.equals(".") && !number.equals(".."),
                "Unexpected source event number");

        HttpResponse<String> byNumber = call(URI.create(base + "/by-source-event/" + number), token, null);
        require(byNumber.statusCode() == 200 && field(byNumber.body(), "id").equals(id), "Original-number query mismatch");
        require(field(byNumber.body(), "status").equals("PROCESSING"), "Expected PROCESSING");
        require(businessState == BusinessState.PENDING, "PROCESSING must remain pending");

        HttpResponse<String> byId = call(URI.create(base + "/" + id), token, null);
        require(byId.statusCode() == 200 && field(byId.body(), "id").equals(id), "ID query mismatch");
        require(field(byId.body(), "status").equals("COMMITTED"), "Expected simulated COMMITTED");
        businessState = BusinessState.COMPLETED;
        // Adaptation point: invoke and assert your actual receipt-to-business-state transition.
        require(businessState == BusinessState.COMPLETED, "Business state transition failed");
        System.out.println("Local simulation caller example passed; also check the simulator report.");
    }

    private static HttpResponse<String> call(URI target, String token, byte[] body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + token);
        if (body == null) request.GET();
        else request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body));
        HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        require(response.headers().firstValue("X-Trust-Selfcheck").isPresent(), "Missing simulation marker");
        return response;
    }

    // Only extracts unescaped ASCII fields from the generated flat simulator records.
    // This deliberately is not a general JSON parser. Production clients must use their normal JSON library.
    private static String field(String document, String name) {
        Matcher key = Pattern.compile("\\\"" + Pattern.quote(name) + "\\\"\\s*:").matcher(document);
        require(key.find(), "Missing fixture field: " + name);
        int at = key.end();
        require(!key.find(), "Duplicate fixture field: " + name);
        Matcher value = Pattern.compile("\\s*\\\"([A-Za-z0-9_:/.-]+)\\\"").matcher(document.substring(at));
        require(value.lookingAt(), "Unsupported fixture field encoding: " + name);
        return value.group(1);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
