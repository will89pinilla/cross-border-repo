package com.cobre.accounts.support;

import com.cobre.accounts.AccountsServiceApplication;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The whole service on a random port, on its own empty database with the given seed (spec §12.2,
 * end to end). Requests go through real HTTP with the JDK client, so Tomcat applies the multipart
 * limits of spec §6.2 exactly as in production.
 */
public final class ApiApp implements AutoCloseable {

    public static final JsonMapper JSON = JsonMapper.builder().build();

    private final FreshDatabase db;
    private final ConfigurableApplicationContext context;
    private final URI base;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private ApiApp(FreshDatabase db, ConfigurableApplicationContext context) {
        this.db = db;
        this.context = context;
        String port = context.getEnvironment().getProperty("local.server.port");
        if (port == null) {
            throw new IllegalStateException("The web server did not start");
        }
        this.base = URI.create("http://localhost:" + port);
    }

    /** @param seedPath e.g. {@code classpath:fixtures/F01-case-event/seed.json} */
    public static ApiApp start(String seedPath, String... extraArgs) {
        return start(FreshDatabase.create(), seedPath, List.of(), extraArgs);
    }

    public static ApiApp start(FreshDatabase db, String seedPath, List<ApplicationListener<?>> listeners,
            String... extraArgs) {
        return start(db, seedPath, builder -> builder.listeners(listeners.toArray(ApplicationListener[]::new)),
                extraArgs);
    }

    /** @param customizer e.g. listeners or initializers that observe the real service */
    public static ApiApp start(FreshDatabase db, String seedPath, Consumer<SpringApplicationBuilder> customizer,
            String... extraArgs) {
        List<String> args = new ArrayList<>(List.of(
                "--server.port=0",
                "--spring.datasource.url=" + db.url(),
                "--spring.datasource.username=" + PostgresIntegrationTest.APP_ROLE,
                "--spring.datasource.password=" + PostgresIntegrationTest.APP_PASSWORD,
                "--spring.flyway.user=" + PostgresIntegrationTest.OWNER_ROLE,
                "--spring.flyway.password=" + PostgresIntegrationTest.OWNER_PASSWORD,
                "--cbmm.seed.path=" + seedPath));
        args.addAll(List.of(extraArgs));
        SpringApplicationBuilder builder = new SpringApplicationBuilder(AccountsServiceApplication.class);
        customizer.accept(builder);
        ConfigurableApplicationContext context = builder.run(args.toArray(String[]::new));
        return new ApiApp(db, context);
    }

    public FreshDatabase db() {
        return db;
    }

    public ConfigurableApplicationContext context() {
        return context;
    }

    public JdbcClient jdbc() {
        return JdbcClient.create(db.appDataSource());
    }

    public int count(String table) {
        return jdbc().sql("SELECT count(*) FROM " + table).query(Integer.class).single();
    }

    // --- Requests ---------------------------------------------------------------------------

    /** One part of a multipart upload. */
    public record Part(String field, String fileName, byte[] content) {

        public static Part file(String fileName, byte[] content) {
            return new Part("files", fileName, content);
        }

        public static Part file(Path path) {
            try {
                return file(path.getFileName().toString(), Files.readAllBytes(path));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    public record Response(int status, String contentType, byte[] bytes) {

        public String body() {
            return new String(bytes, StandardCharsets.UTF_8);
        }

        public JsonNode json() {
            return JSON.readTree(bytes);
        }
    }

    public Response upload(List<Part> parts) {
        return upload(parts, Duration.ofSeconds(60));
    }

    /** @param timeout for long loads, such as the benchmark's (spec §12.1) */
    public Response upload(List<Part> parts, Duration timeout) {
        String boundary = "----cbmm" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (Part part : parts) {
            String header = "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"" + part.field() + "\""
                    + (part.fileName() == null ? "" : "; filename=\"" + part.fileName() + "\"") + "\r\n"
                    + "Content-Type: application/json\r\n\r\n";
            body.writeBytes(header.getBytes(StandardCharsets.UTF_8));
            body.writeBytes(part.content());
            body.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        body.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return send(HttpRequest.newBuilder(base.resolve("/v1/event-files"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())), timeout);
    }

    /** Uploads every file of {@code fixtures/<fixture>/loads/<load>}, by name. */
    public Response uploadLoad(String fixture, int load) {
        return upload(loadFiles(fixture, load).stream().map(Part::file).toList());
    }

    public Response get(String pathAndQuery) {
        return send(HttpRequest.newBuilder(base.resolve(pathAndQuery)).GET(), Duration.ofSeconds(60));
    }

    private Response send(HttpRequest.Builder request, Duration timeout) {
        try {
            HttpResponse<byte[]> response = http.send(request.timeout(timeout).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            return new Response(response.statusCode(),
                    response.headers().firstValue("Content-Type").orElse(""), response.body());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    // --- Fixtures ---------------------------------------------------------------------------

    public static Path fixture(String relativePath) {
        var url = ApiApp.class.getResource("/fixtures/" + relativePath);
        if (url == null) {
            throw new IllegalStateException("Missing fixture " + relativePath);
        }
        try {
            return Path.of(url.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    public static List<Path> loadFiles(String fixture, int load) {
        try (Stream<Path> files = Files.list(fixture(fixture + "/loads/" + load))) {
            return files.sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static JsonNode readJson(Path path) {
        try {
            return JSON.readTree(Files.readString(path));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        context.close();
    }
}
