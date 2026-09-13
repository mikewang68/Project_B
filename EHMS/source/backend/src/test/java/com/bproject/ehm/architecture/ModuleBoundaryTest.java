package com.bproject.ehm.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleBoundaryTest {
    private static final List<String> FORBIDDEN_DOMAIN_DEPENDENCIES = List.of(
            "org.springframework", "com.mongodb", "jakarta.persistence", ".adapter."
    );

    @Test
    void domainCodeDoesNotDependOnFrameworksOrAdapters() throws IOException {
        Path sourceRoot = Path.of("src", "main", "java");
        List<String> violations = new ArrayList<>();
        try (var files = Files.walk(sourceRoot)) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> path.toString().replace('\\', '/').contains("/domain/"))
                    .forEach(path -> inspect(path, violations));
        }
        assertTrue(violations.isEmpty(), () -> "领域层存在基础设施依赖：" + violations);
    }

    @Test
    void webAdaptersDoNotUseMongoRepositoriesDirectly() throws IOException {
        Path sourceRoot = Path.of("src", "main", "java");
        List<String> violations = new ArrayList<>();
        try (var files = Files.walk(sourceRoot)) {
            files.filter(path -> path.toString().endsWith("Controller.java"))
                    .forEach(path -> {
                        try {
                            String source = Files.readString(path);
                            if (source.contains("MongoRepository") || source.contains("MongoTemplate")
                                    || source.contains("com.mongodb")) violations.add(path.toString());
                        } catch (IOException exception) {
                            throw new IllegalStateException(exception);
                        }
                    });
        }
        assertTrue(violations.isEmpty(), () -> "Controller直接依赖MongoDB：" + violations);
    }

    private void inspect(Path path, List<String> violations) {
        try {
            String source = Files.readString(path);
            for (String forbidden : FORBIDDEN_DOMAIN_DEPENDENCIES) {
                if (source.contains(forbidden)) violations.add(path + " -> " + forbidden);
            }
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
