package dev.forexsignals.guard;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CLAUDE.md rule 1, SIGNALS ONLY: this test fails the build if any code could build or send a cTrader
 * order message, or if anything asks for the "trading" OAuth scope.
 *
 * What it checks:
 *  1. Hand-written Java (main and test, this file excluded) never mentions a banned message name or
 *     its payload-type constant, not even in comments.
 *  2. The vendored .proto files define none of the banned messages (they were removed when vendoring).
 *  3. No generated Java class exists for a banned message.
 *  4. No source or config file asks for the "trading" OAuth scope.
 * The names are assembled from fragments so a plain grep of the repo finds them only in docs.
 */
class ReadOnlyGuardTest {

    // Banned message names, assembled at runtime ("ProtoOA" + suffix).
    private static final List<String> BANNED_MESSAGES = List.of(
            "ProtoOA" + "NewOrderReq",
            "ProtoOA" + "CancelOrderReq",
            "ProtoOA" + "AmendOrderReq",
            "ProtoOA" + "AmendPositionSLTPReq",
            "ProtoOA" + "ClosePositionReq",
            "ProtoOA" + "MarginCallUpdateReq");

    // Matching payload-type enum constants (still present in the enum; must never be used by our code).
    private static final List<String> BANNED_PAYLOAD_TYPES = List.of(
            "PROTO_OA_" + "NEW_ORDER_REQ",
            "PROTO_OA_" + "CANCEL_ORDER_REQ",
            "PROTO_OA_" + "AMEND_ORDER_REQ",
            "PROTO_OA_" + "AMEND_POSITION_SLTP_REQ",
            "PROTO_OA_" + "CLOSE_POSITION_REQ",
            "PROTO_OA_" + "MARGIN_CALL_UPDATE_REQ");

    // "scope=trading", "scope: trading", scope="trading" and similar.
    private static final Pattern TRADING_SCOPE = Pattern.compile("scope\\W{0,4}" + "trad" + "ing\\b", Pattern.CASE_INSENSITIVE);

    private static Path repoRoot() {
        String prop = System.getProperty("forexsignals.repoRoot");
        Path root = prop != null ? Path.of(prop) : Path.of("..").toAbsolutePath().normalize();
        assertThat(root.resolve("CLAUDE.md")).as("repo root must contain CLAUDE.md").exists();
        return root;
    }

    private static List<Path> files(Path dir, String... extensions) throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        for (String e : extensions) if (n.endsWith(e)) return true;
                        return false;
                    })
                    .toList();
        }
    }

    @Test
    void handWrittenJavaNeverMentionsOrderMessages() throws IOException {
        Path root = repoRoot();
        List<Path> sources = new ArrayList<>();
        sources.addAll(files(root.resolve("engine/src/main/java"), ".java"));
        sources.addAll(files(root.resolve("engine/src/test/java"), ".java"));
        sources.removeIf(p -> p.getFileName().toString().equals("ReadOnlyGuardTest.java"));
        assertThat(sources).as("the guard must actually scan something").isNotEmpty();

        List<String> violations = new ArrayList<>();
        for (Path p : sources) {
            String text = Files.readString(p, StandardCharsets.UTF_8);
            for (String banned : BANNED_MESSAGES) if (text.contains(banned)) violations.add(root.relativize(p) + ": " + banned);
            for (String banned : BANNED_PAYLOAD_TYPES) if (text.contains(banned)) violations.add(root.relativize(p) + ": " + banned);
        }
        assertThat(violations).as("SIGNALS ONLY: order messages referenced in code").isEmpty();
    }

    @Test
    void vendoredProtoDefinesNoOrderMessages() throws IOException {
        Path root = repoRoot();
        List<Path> protos = files(root.resolve("engine/src/main/proto"), ".proto");
        assertThat(protos).as("vendored cTrader protos present").isNotEmpty();

        List<String> violations = new ArrayList<>();
        for (Path p : protos) {
            String text = Files.readString(p, StandardCharsets.UTF_8);
            for (String banned : BANNED_MESSAGES) {
                if (Pattern.compile("(?m)^\\s*message\\s+" + banned + "\\s*\\{").matcher(text).find()) {
                    violations.add(root.relativize(p) + " defines " + banned);
                }
            }
        }
        assertThat(violations).as("SIGNALS ONLY: order messages defined in vendored protos").isEmpty();
    }

    @Test
    void noGeneratedClassForOrderMessages() throws IOException {
        Path generated = repoRoot().resolve("engine/target/generated-sources");
        List<Path> javaFiles = files(generated, ".java");
        // generate-sources always runs before test-compile in a normal build, so the generated tree exists.
        assertThat(javaFiles).as("generated protobuf sources present").isNotEmpty();

        List<String> violations = new ArrayList<>();
        for (Path p : javaFiles) {
            String name = p.getFileName().toString();
            for (String banned : BANNED_MESSAGES) {
                if (name.equals(banned + ".java") || name.equals(banned + "OrBuilder.java")) violations.add(name);
            }
        }
        assertThat(violations).as("SIGNALS ONLY: generated order message classes").isEmpty();
    }

    @Test
    void nothingRequestsTheTradingScope() throws IOException {
        Path root = repoRoot();
        List<Path> scanned = new ArrayList<>();
        scanned.addAll(files(root.resolve("engine/src/main"), ".java", ".xml", ".properties", ".sql"));
        scanned.addAll(files(root.resolve("config"), ".xml", ".csv"));
        Path example = root.resolve("conf.example.xml");
        if (Files.exists(example)) scanned.add(example);
        assertThat(scanned).isNotEmpty();

        List<String> violations = new ArrayList<>();
        for (Path p : scanned) {
            if (TRADING_SCOPE.matcher(Files.readString(p, StandardCharsets.UTF_8)).find()) violations.add(root.relativize(p).toString());
        }
        assertThat(violations).as("OAuth scope must be the read-only 'accounts' scope").isEmpty();
    }

    @Test
    void guardDetectsAViolationWhenOneIsPresent() {
        // Self-check: the patterns really match what they are meant to catch.
        assertThat(TRADING_SCOPE.matcher("scope=" + "trading").find()).isTrue();
        assertThat(TRADING_SCOPE.matcher("scope=accounts").find()).isFalse();
        String sample = "var r = " + BANNED_MESSAGES.getFirst() + ".newBuilder();";
        assertThat(BANNED_MESSAGES.stream().anyMatch(sample::contains)).isTrue();
    }
}
