package se.kth.spork;

import com.github.gumtreediff.tree.ITree;
import gumtree.spoon.builder.SpoonGumTreeBuilder;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import se.kth.spork.spoon.printer.SporkPrettyPrinter;
import spoon.Launcher;
import spoon.reflect.declaration.CtClass;

/**
 * Utility methods for the test suite.
 *
 * @author Simon Larsén
 */
public class Util {
    public static final Path RESOURCES_BASE_DIR = Paths.get("src/test/resources");
    public static final Path CLEAN_MERGE_DIRPATH = RESOURCES_BASE_DIR.resolve("clean");
    public static final Path BOTH_MODIFIED_DIRPATH = CLEAN_MERGE_DIRPATH.resolve("both_modified");
    public static final Path LEFT_MODIFIED_DIRPATH = CLEAN_MERGE_DIRPATH.resolve("left_modified");
    public static final Path CONFLICT_DIRPATH = RESOURCES_BASE_DIR.resolve("conflict");
    public static final Path UNHANDLED_INCONSISTENCY_PATH =
            RESOURCES_BASE_DIR.resolve("unhandled_inconsistency");
    public static final Path CLEAN_LINEBASED_FALLBACK =
            RESOURCES_BASE_DIR.resolve("clean_linebased_fallback");
    public static final Path MISSING_TYPE_SCENARIO = RESOURCES_BASE_DIR.resolve("missing_type");

    private static Stream<? extends Arguments> getArgumentSourcesStream(File testDir) {
        return getArgumentSourcesStream(testDir, TestSources::fromTestDirectory);
    }

    private static Stream<? extends Arguments> getArgumentSourcesStream(
            File testDir, Function<File, TestSources> sourceGetter) {
        return Arrays.stream(testDir.listFiles())
                .filter(File::isDirectory)
                .filter(f -> !f.getName().startsWith("IGNORE"))
                .map(sourceGetter)
                .map(Arguments::of);
    }

    public static String read(Path path) throws IOException {
        return String.join("\n", Files.readAllLines(path, StandardCharsets.UTF_8));
    }

    public static ITree toGumTree(String clazz) {
        CtClass<?> spoonTree = Launcher.parseClass(clazz);
        SpoonGumTreeBuilder builder = new SpoonGumTreeBuilder();
        return builder.getTree(spoonTree);
    }

    public static List<Conflict> parseConflicts(Path path) throws IOException {
        return parseConflicts(read(path));
    }

    public static List<Conflict> parseConflicts(String string) {
        Pattern conflictPattern =
                Pattern.compile("(<<<<<<< LEFT.*?=======.*?>>>>>>> RIGHT)", Pattern.DOTALL);
        Matcher matcher = conflictPattern.matcher(string);

        List<Conflict> matches = new ArrayList<>();
        while (matcher.find()) {
            String match = matcher.group().trim();
            String[] parts = match.split("=======");
            assert parts.length == 2;

            String left = parts[0].replace(SporkPrettyPrinter.START_CONFLICT, "");
            String right = parts[1].replace(SporkPrettyPrinter.END_CONFLICT, "");
            Conflict conf = new Conflict();
            conf.left = left.trim();
            conf.right = right.trim();

            matches.add(conf);
        }
        return matches;
    }

    public static String keepLeftConflict(Path path) throws IOException {
        return keepLeftConflict(read(path));
    }

    /**
     * Take a string with conflicts and strip the conflict markers and the right revision, keep the
     * left revision.
     */
    public static String keepLeftConflict(String string) {
        Pattern rightConflictPattern = Pattern.compile("=======.*?>>>>>>> RIGHT", Pattern.DOTALL);
        Matcher rightConflictMatcher = rightConflictPattern.matcher(string);
        String rightRevStrippend = rightConflictMatcher.replaceAll("");

        Pattern leftConflictMarkerPattern = Pattern.compile(SporkPrettyPrinter.START_CONFLICT);
        Matcher leftMarkerMatcher = leftConflictMarkerPattern.matcher(rightRevStrippend);
        return leftMarkerMatcher.replaceAll("");
    }

    /** Provides test sources for scenarios where both left and right revisions are modified. */
    public static class BothModifiedSourceProvider implements ArgumentsProvider {
        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext extensionContext) {
            return getArgumentSourcesStream(BOTH_MODIFIED_DIRPATH.toFile());
        }
    }

    /** Provides test sources for scenarios where left is modified. */
    public static class LeftModifiedSourceProvider implements ArgumentsProvider {
        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext extensionContext) {
            return getArgumentSourcesStream(LEFT_MODIFIED_DIRPATH.toFile());
        }
    }

    /** Provides test sources for scenarios where right is modified. */
    public static class RightModifiedSourceProvider implements ArgumentsProvider {
        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext extensionContext) {
            return getArgumentSourcesStream(LEFT_MODIFIED_DIRPATH.toFile())
                    .map(
                            arg -> {
                                TestSources sources = (TestSources) arg.get()[0];
                                // swap left and right around to make this a "right modified" test
                                // case
                                Path left = sources.left;
                                sources.left = sources.right;
                                sources.right = left;
                                return Arguments.of(sources);
                            });
        }
    }

    public static class UnhandledInconsistencyProvider implements ArgumentsProvider {
        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext extensionContext) {
            return getArgumentSourcesStream(
                    UNHANDLED_INCONSISTENCY_PATH.toFile(),
                    TestSources::fromTestDirectoryWithoutExpected);
        }
    }

    public static class CleanLineBasedFallbackProvider implements ArgumentsProvider {
        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext extensionContext) {
            return getArgumentSourcesStream(
                    CLEAN_LINEBASED_FALLBACK.toFile(),
                    TestSources::fromTestDirectoryWithoutExpected);
        }
    }

    /** Provides test sources for scenarios where there are conflicts. */
    public static class ConflictSourceProvider implements ArgumentsProvider {
        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext extensionContext) {
            return getArgumentSourcesStream(CONFLICT_DIRPATH.toFile());
        }
    }

    public static class Conflict {
        String left;
        String right;

        @Override
        public String toString() {
            return "Conflict{" + "left='" + left + '\'' + ", right='" + right + '\'' + '}';
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Conflict conflict = (Conflict) o;
            return Objects.equals(left, conflict.left) && Objects.equals(right, conflict.right);
        }

        @Override
        public int hashCode() {
            return Objects.hash(left, right);
        }
    }

    public static class TestSources {
        public Path base;
        public Path left;
        public Path right;
        public Path expected;

        TestSources(Path base, Path left, Path right, Path expected) {
            this.base = base;
            this.left = left;
            this.right = right;
            this.expected = expected;
        }

        public static TestSources fromTestDirectory(File testDir) {
            Path path = testDir.toPath();
            return new TestSources(
                    path.resolve("Base.java"),
                    path.resolve("Left.java"),
                    path.resolve("Right.java"),
                    path.resolve("Expected.java"));
        }

        public static TestSources fromTestDirectoryWithoutExpected(File testDir) {
            Path path = testDir.toPath();
            return new TestSources(
                    path.resolve("Base.java"),
                    path.resolve("Left.java"),
                    path.resolve("Right.java"),
                    null);
        }

        @Override
        public String toString() {
            return base.getParent().getFileName().toString();
        }
    }
}
