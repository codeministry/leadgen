package de.codeministry.leadgen.packaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a stored {@code package_dir} is allowed to mean, and what leaves as a zip.
 *
 * <p>The value comes out of the database, which sounds trustworthy and is not: it was
 * written by a run that may have had a different output directory, and a row is editable by
 * anything with a psql prompt. This is the download endpoint's whole attack surface, so it
 * is tested rather than argued about.
 */
class PackageArchiveTest {

    @TempDir
    Path base;

    private Path folder;

    @BeforeEach
    void buildAPackage() throws IOException {
        folder = Files.createDirectories(base.resolve("2026-09-02_acme_java-dev"));
        Files.writeString(folder.resolve("cover_letter.txt"), "Sehr geehrte Damen und Herren", StandardCharsets.UTF_8);
        Files.writeString(folder.resolve("meta.json"), "{\"offerId\":42}", StandardCharsets.UTF_8);
    }

    @Test
    void resolvesAStoredPathByItsFolderName() {
        assertThat(PackageArchive.resolve(base, folder.toString())).isEqualTo(folder);
    }

    /**
     * The container writes `/packages/…` and a process on the host reads `./packages`, so
     * the stored prefix is the one part of the value that cannot be trusted to still mean
     * anything. Only the folder name is.
     */
    @Test
    void ignoresTheStoredPrefix() {
        assertThat(PackageArchive.resolve(base, "/packages/2026-09-02_acme_java-dev")).isEqualTo(folder);
    }

    @Test
    void refusesATraversal() {
        assertThatThrownBy(() -> PackageArchive.resolve(base, "../../etc/passwd"))
                .isInstanceOf(PackageArchive.Rejected.class)
                .hasMessageContaining("does not exist");
    }

    @Test
    void refusesAnAbsolutePathOutsideTheOutputDirectory() {
        assertThatThrownBy(() -> PackageArchive.resolve(base, "/etc"))
                .isInstanceOf(PackageArchive.Rejected.class)
                .hasMessageContaining("does not exist");
    }

    @Test
    void refusesAFolderThatIsNotThere() {
        assertThatThrownBy(() -> PackageArchive.resolve(base, "2026-01-01_gone_nothing"))
                .isInstanceOf(PackageArchive.Rejected.class)
                .hasMessageContaining("does not exist");
    }

    @Test
    void refusesAValueThatNamesNoFolder() {
        assertThatThrownBy(() -> PackageArchive.resolve(base, "  "))
                .isInstanceOf(PackageArchive.Rejected.class);
        assertThatThrownBy(() -> PackageArchive.resolve(base, "/"))
                .isInstanceOf(PackageArchive.Rejected.class);
    }

    @Test
    void zipsEveryRegularFileUnderNamesRelativeToTheFolder() throws IOException {
        Files.createDirectories(folder.resolve("original"));
        Files.writeString(folder.resolve("original/offer.txt"), "the ad as it was", StandardCharsets.UTF_8);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PackageArchive.writeZip(folder, out);

        assertThat(entriesOf(out))
                .containsExactlyInAnyOrder("cover_letter.txt", "meta.json", "original/offer.txt");
    }

    @Test
    void writesTheContentUnchanged() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PackageArchive.writeZip(folder, out);

        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if ("meta.json".equals(entry.getName())) {
                    assertThat(new String(zip.readAllBytes(), StandardCharsets.UTF_8))
                            .isEqualTo("{\"offerId\":42}");
                    return;
                }
            }
        }
        throw new AssertionError("meta.json was not in the archive");
    }

    private static List<String> entriesOf(ByteArrayOutputStream out) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                names.add(entry.getName());
            }
        }
        return names;
    }
}
