import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

@DisableCachingByDefault(because = "Generated source is inexpensive to recreate")
public abstract class GenerateConformanceTestsTask extends DefaultTask {
    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getSpecsDirectory();

    @OutputDirectory
    public abstract DirectoryProperty getGeneratedSourcesDirectory();

    @TaskAction
    public void generate() throws IOException {
        Path specsPath = getSpecsDirectory().get().getAsFile().toPath();
        Path outputPath = getGeneratedSourcesDirectory().get().getAsFile().toPath();

        if (Files.exists(outputPath)) {
            try (Stream<Path> paths = Files.walk(outputPath)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            } catch (UncheckedIOException e) {
                throw e.getCause();
            }
        }
        Files.createDirectories(outputPath);

        List<String> specFiles;
        try (Stream<Path> paths = Files.walk(specsPath)) {
            specFiles = paths
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".yaml") || path.toString().endsWith(".yml"))
                .map(path -> specsPath.relativize(path).toString().replace(File.separatorChar, '/'))
                .sorted()
                .toList();
        }

        String header = "package io.specmatic.conformance_tests\nimport org.junit.jupiter.api.DisplayName\n";
        StringBuilder classes = new StringBuilder();
        for (String relativePath : specFiles) {
            String className = generateClassName(relativePath);
            String displayName = relativePath.substring(0, relativePath.indexOf('.'));
            classes.append("\n@DisplayName(\"").append(displayName).append("\")\n")
                .append("class ").append(className).append(" : AbstractConformanceTest(\"")
                .append(relativePath).append("\")\n");
        }

        Files.writeString(outputPath.resolve("ConformanceTests.kt"), header + "\n" + classes);
        getLogger().lifecycle("Generated {} conformance test classes", specFiles.size());
    }

    private String generateClassName(String relativePath) {
        StringBuilder className = new StringBuilder("S");
        String[] segments = relativePath.split("/");
        for (int index = 0; index < segments.length; index++) {
            String segment = segments[index].replaceFirst("\\.(yaml|yml)$", "");
            if (index > 0) className.append('_');
            for (String part : segment.split("-")) {
                if (!part.isEmpty()) {
                    className.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
                }
            }
        }
        return className.append("Test").toString();
    }
}
