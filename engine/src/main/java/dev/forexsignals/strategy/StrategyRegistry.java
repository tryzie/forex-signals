package dev.forexsignals.strategy;

import dev.forexsignals.strategy.api.Strategy;
import dev.forexsignals.strategy.api.StrategyDef;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Finds every @StrategyDef class in dev.forexsignals.strategy.impl (adding a strategy = one file + its test). */
public final class StrategyRegistry {
    public static final String IMPL_PACKAGE = "dev.forexsignals.strategy.impl";

    private StrategyRegistry() {}

    public static List<Strategy> discover() {
        List<Strategy> out = new ArrayList<>();
        try (ScanResult scan = new ClassGraph().enableClasspath().enableClassInfo().enableAnnotationInfo().acceptPackages(IMPL_PACKAGE).scan()) {
            for (var ci : scan.getClassesWithAnnotation(StrategyDef.class.getName())) {
                Class<?> c;
                try {
                    c = Class.forName(ci.getName(), true, StrategyRegistry.class.getClassLoader());
                } catch (ClassNotFoundException e) {
                    throw new IllegalStateException(e);
                }
                if (!Strategy.class.isAssignableFrom(c)) throw new IllegalStateException(c + " is @StrategyDef but not a Strategy");
                try {
                    out.add((Strategy) c.getDeclaredConstructor().newInstance());
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("cannot instantiate " + c + " (needs a public no-arg constructor)", e);
                }
            }
        }
        out.sort(Comparator.comparing(s -> s.meta().id()));
        return out;
    }

    public static Strategy byName(List<Strategy> all, String name) {
        return all.stream().filter(s -> s.meta().name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown strategy " + name + "; known: " + all.stream().map(s -> s.meta().name()).toList()));
    }
}
