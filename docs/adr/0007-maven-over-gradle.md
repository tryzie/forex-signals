# ADR 0007: Maven over Gradle

- Status: Accepted
- Date: 2026-11-18
- Decider: project owner

## Context
The Phase 0 plan proposed Gradle (Kotlin DSL plus a version catalog). The owner knows Maven
well, and their other Java projects use it. The build needs are ordinary: one Java 25 module,
Protobuf code generation, unit and integration test separation, and a runnable fat jar.

## Decision
Use **Maven 3.9.x** through the **Maven Wrapper** (`mvnw`, committed, pinned to 3.9.16 in
`.mvn/wrapper/maven-wrapper.properties`).

- Root `pom.xml` (packaging `pom`) holds every dependency and plugin version in `<properties>`,
  plus `dependencyManagement` and `pluginManagement`. This replaces Gradle's `libs.versions.toml`.
  There is one module for now, `engine/`.
- Java level is set with `maven.compiler.release=25`.
- `maven-enforcer-plugin` enforces JDK `[25,26)`, Maven `[3.9.16,4)`, `dependencyConvergence`,
  `banDuplicatePomDependencyVersions`, and requires plugin versions to be pinned.
- Protobuf: `io.github.ascopes:protobuf-maven-plugin`, which is actively maintained. The older
  xolstice plugin has had no release since 2018. It downloads `protoc` from Maven Central for
  the host OS and architecture (linux-aarch_64 and osx-aarch_64 builds exist for the pinned
  protoc). The vendored `.proto` files stay in `engine/src/main/proto` through `<sourceDirectories>`.
- Surefire runs unit tests in `test`. Failsafe runs `*IT` in `integration-test`/`verify`.
  Tests that need feed credentials carry the JUnit tag `needs-feed` and are skipped
  (`Assumptions`) when the credentials are absent.
- `maven-shade-plugin` builds `engine/target/forex-signals.jar`. It uses
  `ServicesResourceTransformer` (Flyway's database plugins and Logback service loading),
  `ManifestResourceTransformer` (Main-Class) and `AppendingTransformer`/`ApacheLicense`/`ApacheNotice`,
  and excludes `META-INF/*.SF|*.DSA|*.RSA|*.EC` signature files and `module-info.class`.

## Consequences
- Builds look the same as the owner's other projects. Only one person maintains this,
  so familiarity matters more than Gradle's build speed.
- Maven 4 is still a release candidate (4.0.0-rc-7 on 2026-11-15). We stay on 3.9.x and look again once 4.0 is GA.
- Maven 3.x plugins at 4.0.0-beta are avoided. Every plugin is pinned to its latest **stable 3.x** release.
