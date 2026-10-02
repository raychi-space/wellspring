package space.raychi.wellspring;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class LayeringTest {
    private static final Path SOURCE = Path.of("src/main/java/space/raychi/wellspring");

    @Test
    void controllersAndServicesCannotImportPersistenceFrameworksOrReverseTheLayers() throws Exception {
        for (Path file : sources()) {
            String source = Files.readString(file);
            String layer = SOURCE.relativize(file).getName(0).toString();
            if (List.of("controller", "service").contains(layer)) {
                assertThat(source).as(file.toString()).doesNotContain("import java.sql.",
                        "import javax.sql.", "import org.springframework.jdbc.", "import jakarta.persistence.");
            }
            if (layer.equals("controller")) {
                assertThat(source).as(file.toString()).doesNotContain("space.raychi.wellspring.mapper.",
                        "space.raychi.wellspring.entity.", "@Transactional", "public record ");
                assertThat(source).as(file.toString()).contains("ApiResponses.");
            }
            if (layer.equals("service")) {
                assertThat(source).as(file.toString()).doesNotContain("space.raychi.wellspring.controller.",
                        "import org.springframework.web.bind.annotation.", "ResponseEntity");
            }
            if (layer.equals("mapper")) {
                assertThat(source).as(file.toString()).doesNotContain("space.raychi.wellspring.controller.",
                        "space.raychi.wellspring.service.", "space.raychi.wellspring.dto.", "space.raychi.wellspring.api.");
            }
            if (layer.equals("dto")) {
                assertThat(source).as(file.toString()).doesNotContain("space.raychi.wellspring.entity.");
            }
            if (List.of("entity", "dto").contains(layer)) {
                assertThat(source).as(file.toString()).doesNotContain("space.raychi.wellspring.controller.",
                        "space.raychi.wellspring.service.", "space.raychi.wellspring.mapper.");
            }
        }
    }

    private static List<Path> sources() throws Exception {
        try (var files = Files.walk(SOURCE)) {
            return files.filter(path -> path.toString().endsWith(".java")).toList();
        }
    }
}
