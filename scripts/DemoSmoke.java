import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DemoSmoke {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(90);

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final Path repositoryRoot;
    private final Map<String, String> demoSettings;
    private final String baseUrl;

    private DemoSmoke(Path repositoryRoot) throws IOException {
        this.repositoryRoot = repositoryRoot;
        this.demoSettings = loadDemoSettings(repositoryRoot.resolve("demo.env"));
        this.baseUrl = setting("DEMO_BASE_URL", "http://127.0.0.1:8080")
                .replaceAll("/+$", "");
    }

    public static void main(String[] args) {
        try {
            DemoSmoke demo = new DemoSmoke(findRepositoryRoot());
            if (args.length == 1 && "--check-demo-ports".equals(args[0])) {
                demo.checkDemoPorts();
                return;
            }
            if (args.length == 1 && "--health-only".equals(args[0])) {
                demo.checkApplicationHealth();
                return;
            }
            if (args.length != 0) {
                throw new IllegalArgumentException(
                        "Usage: java scripts/DemoSmoke.java [--check-demo-ports|--health-only]"
                );
            }
            demo.runSmokeFlow();
        } catch (Exception exception) {
            String message = exception.getMessage();
            if (message == null || message.isBlank()) {
                message = exception.getClass().getSimpleName();
            }
            System.err.println("[FAIL] " + message);
            if ("true".equalsIgnoreCase(System.getenv("DEMO_DEBUG"))) {
                exception.printStackTrace(System.err);
            }
            System.exit(1);
        }
    }

    private void checkDemoPorts() throws IOException {
        int[] ports = {
                intSetting("MYSQL_PORT", 3307),
                intSetting("REDIS_PORT", 6380),
                intSetting("ROCKETMQ_NAMESERVER_PORT", 9876),
                intSetting("ROCKETMQ_BROKER_PORT", 10911),
                intSetting("ROCKETMQ_BROKER_FAST_PORT", 10909),
                intSetting("ROCKETMQ_METRICS_PORT", 5557),
                intSetting("AI_STUB_PORT", 8002)
        };
        for (int port : ports) {
            try (ServerSocket socket = new ServerSocket()) {
                socket.setReuseAddress(false);
                socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
            } catch (IOException exception) {
                throw new IOException(
                        "Required demo port " + port
                                + " is already occupied. Stop the conflicting service and retry.",
                        exception
                );
            }
        }
        System.out.println("[PASS] Required demo ports are available");
    }

    private void checkApplicationHealth() throws IOException, InterruptedException {
        final HttpResponse<String> response;
        try {
            response = send(HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/actuator/health"))
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build());
        } catch (IOException exception) {
            throw new IOException(
                    "Application is not reachable at " + baseUrl
                            + ". Start the demo profile and retry.",
                    exception
            );
        }
        requireStatus(response, 200, "application health");
        requireFieldValue(response.body(), "status", "UP", "application health");
        System.out.println("[PASS] Application health is UP");
    }

    private void runSmokeFlow() throws Exception {
        long startedAt = System.nanoTime();
        checkApplicationHealth();

        String username = requiredSetting("BOOTSTRAP_ADMIN_USERNAME");
        String password = requiredSetting("BOOTSTRAP_ADMIN_PASSWORD");
        String loginJson = "{\"username\":" + jsonString(username)
                + ",\"password\":" + jsonString(password) + "}";
        HttpResponse<String> login = postJson("/api/auth/login", loginJson, null);
        requireStatus(login, 200, "demo login");
        String token = requiredString(login.body(), "accessToken");
        requireFieldValue(login.body(), "role", "ADMIN", "demo login");
        System.out.println("[PASS] Demo administrator authenticated");

        String courseCode = "DEMO-" + Long.toUnsignedString(System.currentTimeMillis(), 36)
                .toUpperCase();
        String courseJson = "{"
                + "\"code\":" + jsonString(courseCode) + ","
                + "\"name\":\"Recruiting Demo Course\","
                + "\"teacherName\":\"Demo Teacher\","
                + "\"description\":\"Created by the reproducible smoke flow\""
                + "}";
        HttpResponse<String> created = postJson("/api/courses", courseJson, token);
        requireStatus(created, 201, "course creation");
        long courseId = requiredLong(created.body(), "data");

        HttpResponse<String> course = get("/api/courses/" + courseId, token);
        requireStatus(course, 200, "course read");
        requireFieldValue(course.body(), "code", courseCode, "course read");
        System.out.println("[PASS] Course created and read back (id=" + courseId + ")");

        Path csv = repositoryRoot.resolve("docs/examples/analysis-batch-sample.csv");
        if (!Files.isRegularFile(csv)) {
            throw new IOException("Demo CSV not found: " + csv);
        }
        int expectedRows = Math.max(0, Files.readAllLines(csv, StandardCharsets.UTF_8).size() - 1);
        HttpResponse<String> uploaded = postMultipart(
                "/api/courses/" + courseId + "/analysis-batches",
                csv,
                token
        );
        requireStatus(uploaded, 201, "batch upload");
        long batchId = requiredLong(uploaded.body(), "batchId");
        long totalCount = requiredLong(uploaded.body(), "totalCount");
        if (totalCount != expectedRows) {
            throw new IllegalStateException(
                    "Batch accepted " + totalCount + " rows, expected " + expectedRows
            );
        }
        System.out.println("[PASS] CSV batch submitted (batchId=" + batchId
                + ", rows=" + totalCount + ")");

        waitForCompletion(batchId, token, expectedRows);

        HttpResponse<String> results = get(
                "/api/analysis-batches/" + batchId + "/results?page=1&size=20",
                token
        );
        requireStatus(results, 200, "batch results");
        long resultTotal = requiredLong(results.body(), "total");
        int successful = countFieldValue(results.body(), "taskStatus", "SUCCESS");
        int deterministic = countFieldValue(
                results.body(),
                "sentimentSource",
                "performance_stub"
        );
        if (resultTotal != expectedRows
                || successful != expectedRows
                || deterministic != expectedRows) {
            throw new IllegalStateException(
                    "Unexpected results: total=" + resultTotal
                            + ", successful=" + successful
                            + ", deterministic=" + deterministic
            );
        }
        System.out.println("[PASS] Retrieved " + resultTotal
                + " deterministic analysis results");

        double elapsedSeconds = (System.nanoTime() - startedAt) / 1_000_000_000.0;
        System.out.printf("[PASS] Recruiting demo smoke flow completed in %.2f s%n", elapsedSeconds);
    }

    private void waitForCompletion(long batchId, String token, int expectedRows)
            throws IOException, InterruptedException {
        long deadline = System.nanoTime() + POLL_TIMEOUT.toNanos();
        String previousSnapshot = null;
        while (System.nanoTime() < deadline) {
            HttpResponse<String> progress = get(
                    "/api/analysis-batches/" + batchId,
                    token
            );
            requireStatus(progress, 200, "batch progress");
            String status = requiredString(progress.body(), "status");
            long waiting = requiredLong(progress.body(), "waitingCount");
            long processing = requiredLong(progress.body(), "processingCount");
            long success = requiredLong(progress.body(), "successCount");
            long failed = requiredLong(progress.body(), "failedCount");
            String snapshot = status + ":" + waiting + ":" + processing + ":" + success
                    + ":" + failed;
            if (!snapshot.equals(previousSnapshot)) {
                System.out.println("[INFO] Batch status=" + status
                        + " waiting=" + waiting
                        + " processing=" + processing
                        + " success=" + success
                        + " failed=" + failed);
                previousSnapshot = snapshot;
            }
            if ("COMPLETED".equals(status)) {
                if (success != expectedRows || failed != 0) {
                    throw new IllegalStateException(
                            "Completed batch has unexpected counts: success=" + success
                                    + ", failed=" + failed
                    );
                }
                System.out.println("[PASS] Asynchronous batch reached COMPLETED");
                return;
            }
            if ("FAILED".equals(status) || "PARTIAL_FAILED".equals(status)) {
                throw new IllegalStateException(
                        "Batch finished with status=" + status + ", failed=" + failed
                );
            }
            Thread.sleep(1_000);
        }
        throw new IllegalStateException(
                "Batch did not complete within " + POLL_TIMEOUT.toSeconds() + " seconds"
        );
    }

    private HttpResponse<String> get(String path, String token)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(REQUEST_TIMEOUT)
                .GET();
        authorize(request, token);
        return send(request.build());
    }

    private HttpResponse<String> postJson(String path, String json, String token)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        authorize(request, token);
        return send(request.build());
    }

    private HttpResponse<String> postMultipart(String path, Path file, String token)
            throws IOException, InterruptedException {
        String boundary = "courseinsight-demo-" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeAscii(body, "--" + boundary + "\r\n");
        writeAscii(
                body,
                "Content-Disposition: form-data; name=\"file\"; filename=\""
                        + file.getFileName() + "\"\r\n"
        );
        writeAscii(body, "Content-Type: text/csv; charset=UTF-8\r\n\r\n");
        body.write(Files.readAllBytes(file));
        writeAscii(body, "\r\n--" + boundary + "--\r\n");

        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
        authorize(request, token);
        return send(request.build());
    }

    private HttpResponse<String> send(HttpRequest request)
            throws IOException, InterruptedException {
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private void authorize(HttpRequest.Builder request, String token) {
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
    }

    private void requireStatus(
            HttpResponse<String> response,
            int expected,
            String operation) {
        if (response.statusCode() != expected) {
            throw new IllegalStateException(
                    operation + " returned HTTP " + response.statusCode()
                            + ": " + abbreviate(response.body())
            );
        }
    }

    private void requireFieldValue(
            String json,
            String field,
            String expected,
            String operation) {
        String actual = requiredString(json, field);
        if (!expected.equals(actual)) {
            throw new IllegalStateException(
                    operation + " returned " + field + "=" + actual
                            + ", expected " + expected
            );
        }
    }

    private static String requiredString(String json, String field) {
        Matcher matcher = Pattern.compile(
                "\\\"" + Pattern.quote(field) + "\\\"\\s*:\\s*\\\"([^\\\"]*)\\\""
        ).matcher(json);
        if (!matcher.find()) {
            throw new IllegalStateException(
                    "Response is missing string field '" + field + "': " + abbreviate(json)
            );
        }
        return matcher.group(1);
    }

    private static long requiredLong(String json, String field) {
        Matcher matcher = Pattern.compile(
                "\\\"" + Pattern.quote(field) + "\\\"\\s*:\\s*(\\d+)"
        ).matcher(json);
        if (!matcher.find()) {
            throw new IllegalStateException(
                    "Response is missing numeric field '" + field + "': " + abbreviate(json)
            );
        }
        return Long.parseLong(matcher.group(1));
    }

    private static int countFieldValue(String json, String field, String value) {
        Matcher matcher = Pattern.compile(
                "\\\"" + Pattern.quote(field) + "\\\"\\s*:\\s*\\\""
                        + Pattern.quote(value) + "\\\""
        ).matcher(json);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private String requiredSetting(String name) {
        String value = setting(name, null);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing demo setting " + name + " in demo.env");
        }
        return value;
    }

    private int intSetting(String name, int defaultValue) {
        String value = setting(name, String.valueOf(defaultValue));
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be an integer", exception);
        }
    }

    private String setting(String name, String defaultValue) {
        String environmentValue = System.getenv(name);
        if (environmentValue != null && !environmentValue.isBlank()) {
            return environmentValue;
        }
        return demoSettings.getOrDefault(name, defaultValue);
    }

    private static Map<String, String> loadDemoSettings(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IOException("Demo settings file not found: " + path);
        }
        Map<String, String> settings = new HashMap<>();
        for (String rawLine : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int separator = line.indexOf('=');
            if (separator <= 0) {
                throw new IOException("Invalid demo.env line: " + rawLine);
            }
            settings.put(line.substring(0, separator).trim(), line.substring(separator + 1));
        }
        return settings;
    }

    private static Path findRepositoryRoot() throws IOException {
        Path candidate = Path.of("").toAbsolutePath().normalize();
        for (int depth = 0; depth < 6 && candidate != null; depth++) {
            if (Files.isRegularFile(candidate.resolve("demo.env"))
                    && Files.isDirectory(candidate.resolve("courseinsight-server"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IOException("Run the demo from inside the CourseInsight repository");
    }

    private static String jsonString(String value) {
        return "\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                + "\"";
    }

    private static void writeAscii(ByteArrayOutputStream output, String value)
            throws IOException {
        output.write(value.getBytes(StandardCharsets.US_ASCII));
    }

    private static String abbreviate(String value) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 300 ? normalized : normalized.substring(0, 300) + "...";
    }
}
