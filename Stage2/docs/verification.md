# Local verification results

> Historical record of the original scaffolding task before Git integration. Statements about no Git operations or publication apply to that task only.

Verified October 26, 2026, Toronto time. Final verification: Windows 11 amd64, Microsoft OpenJDK **21.0.13.0.2**, Maven **3.9.9**, JavaFX **21.0.6**. The portable JDK is extracted only into `.tools/jdk21/jdk-21.0.13.0.2+1`. Its downloaded archive was checked against Microsoft's SHA-256 sidecar. Maven's archive was checked against Maven Central's SHA-512 sidecar; the wrapper also pins its SHA-256.

## Final commands actually executed

Working directory was this Stage2 folder. Session-only setup:

```powershell
$env:JAVA_HOME = (Resolve-Path '.tools/jdk21/jdk-21.0.13.0.2+1').Path
$env:MAVEN_USER_HOME = Join-Path $PWD '.mvn/user-home'
```

| Actual command | Observed result | Evidence |
| --- | --- | --- |
| `./mvnw.cmd -version` | Maven 3.9.9, Java 21.0.13.0.2, UTF-8, Windows amd64 | `.tools/java21-version.log` |
| `./mvnw.cmd -B clean compile` | BUILD SUCCESS; 89 main source files compiled with release 21 | `.tools/java21-compile-final.log` |
| `./mvnw.cmd -B test` | BUILD SUCCESS; 4 tests, 0 failures, 0 errors, 0 skipped | `.tools/java21-test.log`, `target/surefire-reports/` |
| `./mvnw.cmd -B exec:java '-Dexec.mainClass=com.photopilot.ui.cli.PhotoPilotCLI' '-Dexec.args=help status F01 F11 unknown exit'` | Help, all 11 statuses, explicit F01/F11 unavailable feedback, unknown-command feedback, clean exit; BUILD SUCCESS | `.tools/java21-cli.log` |
| `./mvnw.cmd -B javafx:run '-Djavafx.args=--smoke-test'` | Real JavaFX stage reported showing; 11 entries, button feedback asserted; GUI_SMOKE_OK, clean close, BUILD SUCCESS | `.tools/java21-gui.log` |
| Bundled Python executing `tools/verify_sources.py` | 87 UML classifiers, 267 fields, 184 operations preserved; 83 relationships recorded; 20 original sources unchanged | Script output and source-hashes.json |

The GUI check is runtime instrumentation after stage.show(), including firing the actual button action. It is not a manual visual review, full GUI workflow test or a demonstration video. The CLI was also exercised using injected input through EOF/exit in JUnit. No graphical classes are initialized by the CLI path.

## Earlier attempts and corrections

- Initial Maven download was blocked by the restricted network sandbox. The download was retried with reviewed network access, confined to `.tools`, and checksum-verified. No global Maven was installed.
- Wrapper generation executed successfully using local Maven: `./.tools/apache-maven-3.9.9/bin/mvn.cmd -B org.apache.maven.plugins:maven-wrapper-plugin:3.3.2:wrapper '-Dmaven=3.9.9' '-Dtype=only-script'`.
- The first build/test/CLI/GUI cycle used the pre-existing Temurin JDK 25 with release 21. Commands were the same wrapper commands above. All passed; Maven/JavaFX emitted native-access/deprecated-Unsafe warnings on JDK 25. That cycle alone was not treated as proof of Java 21 runtime compatibility.
- Windows console logs rendered a decorative dash incorrectly. The CLI/catalog separators were changed to ASCII and the final Java 21 CLI output verified readable.
- Final Java 21 clean compilation inside the restricted filesystem sandbox failed while javac closed a JavaFX archive: `java.nio.file.AccessDeniedException` for the local `javafx-base-21.0.6-win.jar`. Diagnostics used `./mvnw.cmd -B -e compile` (incremental/up-to-date, not accepted as a fresh compilation result) and `./mvnw.cmd -B -e clean compile` (reproduced the access error). Running the authorized clean build outside that sandbox succeeded without code or dependency changes, followed by successful tests/CLI/GUI. No unresolved Java compile error remains; sandbox-restricted execution may need the same local-file access allowance.
- The one-time UML parser initially needed a separator-parsing correction before source generation. The full structural audit subsequently passed. No source design document was changed to make Java compile.

## Limits

- All F01–F11 business behavior remains NOT IMPLEMENTED. Five pattern structures are represented, not counted as completed operational patterns.
- No Gemini, weather, catalog, location or EXIF integration was executed. No credentials were read or required. The source audit found no HTTP send/sendAsync calls, null return placeholders, or matching key/private-key patterns; this is a bounded static check, not a comprehensive security audit.
- No macOS, Apple Silicon, Linux or POSIX wrapper runtime test was performed. Native dependency selection is provided by OpenJFX's Maven metadata; those platforms still need real-machine verification.
- JSON persistence, approval/state invariants, deep composition ownership, ranking, deterministic calculations, metadata statistics, AI reasoning and generic response decoding remain deferred.
- No Git command, upload, public source publication, final Stage 2 report, demonstration video, fabricated commit record or claimed API verification was performed.

Normal Maven and Maven Wrapper are configured equivalently; a globally installed `mvn` command was not separately run. All final reported successes come from the actual local wrapper commands above.

## Pre-upload verification — October 26, 2026

After Git integration, `./mvnw.cmd -B clean verify` compiled 89 production Java files, ran 4 tests (0 failures, 0 errors, 0 skipped), and built the JAR successfully using JDK 21. The first offline attempt could not resolve the uncached Maven JAR plugin; allowing Maven Central downloads resolved it without source changes.

The CLI smoke command with `help status F01 F11 unknown exit` passed. `./mvnw.cmd -B -o javafx:run '-Djavafx.args=--smoke-test'` reported `GUI_SMOKE_OK` and closed cleanly. These verify bootstrap behavior only; business features and real provider integration remain unimplemented. Build outputs and downloaded caches remain excluded from Git.
