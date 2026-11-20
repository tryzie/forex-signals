package dev.forexsignals.strategy;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2 criterion C8: strategy implementations may depend only on the strategy API (plus Candle/Timeframe and
 * java.util/java.lang). No I/O, no database, feed, engine or clock access, no own randomness, no mutable static state
 * (a static cache is a lookahead side channel across runs).
 */
class StrategyImportGuardTest {

    private static final Pattern IMPORT = Pattern.compile("(?m)^import\\s+(static\\s+)?([\\w.]+)");
    private static final List<String> ALLOWED = List.of("dev.forexsignals.strategy.api.", "dev.forexsignals.market.Candle",
            "dev.forexsignals.market.Timeframe", "java.util.", "java.lang.", "java.time.Instant", "java.time.Duration");
    private static final List<Pattern> FORBIDDEN_CODE = List.of(
            Pattern.compile("Instant\\.now\\(|System\\.currentTimeMillis|System\\.nanoTime|LocalDateTime\\.now|Clock\\."),
            Pattern.compile("new\\s+(java\\.util\\.)?Random\\(|ThreadLocalRandom|Math\\.random"),
            Pattern.compile("(?m)^\\s*(private|protected|public)?\\s*static\\s+(?!final)[\\w<>,\\[\\] ]+\\s+\\w+\\s*(=|;)"),
            Pattern.compile("Class\\.forName|getDeclaredField|setAccessible|java\\.io\\.|java\\.nio\\.|java\\.net\\.|java\\.sql\\."));

    @Test
    void strategiesOnlyUseTheStrategyApi() throws Exception {
        Path root = Path.of(System.getProperty("forexsignals.repoRoot", "..")).resolve("engine/src/main/java/dev/forexsignals/strategy/impl");
        List<Path> files;
        try (Stream<Path> s = Files.list(root)) { files = s.filter(p -> p.toString().endsWith(".java")).toList(); }
        assertThat(files).hasSizeGreaterThanOrEqualTo(8);
        List<String> violations = new ArrayList<>();
        for (Path f : files) {
            String src = Files.readString(f);
            Matcher m = IMPORT.matcher(src);
            while (m.find()) {
                String imp = m.group(2);
                if (ALLOWED.stream().noneMatch(imp::startsWith)) violations.add(f.getFileName() + " imports " + imp);
            }
            for (Pattern p : FORBIDDEN_CODE) if (p.matcher(src).find()) violations.add(f.getFileName() + " matches " + p.pattern());
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void theGuardSeesAViolation() {
        assertThat(FORBIDDEN_CODE.get(2).matcher("    private static double[] cache = new double[9];").find()).isTrue();
        assertThat(FORBIDDEN_CODE.get(2).matcher("    static final Map<String, Object> PARAMS = params();").find()).isFalse();
        assertThat(FORBIDDEN_CODE.get(0).matcher("var t = Instant.now();").find()).isTrue();
    }
}
