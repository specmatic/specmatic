import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.tasks.Classpath;
import org.gradle.process.CommandLineArgumentProvider;

import java.io.File;
import java.util.List;

public abstract class JUnit5SystemExitArgumentProvider implements CommandLineArgumentProvider {
    @Classpath
    public abstract ConfigurableFileCollection getRuntimeClasspath();

    @Override
    public Iterable<String> asArguments() {
        File agent = getRuntimeClasspath().getFiles().stream()
            .filter(file -> file.getName().contains("junit5-system-exit"))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("JUnit 5 system-exit agent was not found on the test runtime classpath"));
        return List.of("-javaagent:" + agent.getAbsolutePath());
    }
}
