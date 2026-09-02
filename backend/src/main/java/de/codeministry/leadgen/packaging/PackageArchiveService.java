package de.codeministry.leadgen.packaging;

import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.config.Directories;
import de.codeministry.leadgen.config.model.PipelineConfig;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Reads a finished package folder back out, as one file.
 *
 * <p>The package is and stays a folder on the machine that ran the pipeline — that is what
 * {@link PackagingService} builds and what its documentation describes. This only bundles
 * it so the operator can fetch it from a browser that is not on that machine. It is the
 * same act as opening the folder in a file manager, and deliberately not a send path: there
 * is no recipient here, no channel and no address, which is what {@code NothingIsSentTest}
 * reads the repository for.
 */
@Service
public class PackageArchiveService {

    private final ConfigRegistry config;
    private final JdbcClient jdbc;

    PackageArchiveService(ConfigRegistry config, DataSource dataSource) {
        this.config = config;
        this.jdbc = JdbcClient.create(dataSource);
    }

    /**
     * The folder built for one offer, or empty when the offer has none.
     *
     * <p>Empty is the honest and common answer: a package exists only for what cleared the
     * shortlist threshold, so most offers never get one.
     */
    public Optional<Path> folderFor(long offerId) {
        List<String> stored = jdbc.sql("SELECT package_dir FROM offer WHERE id = ?")
                .param(offerId)
                .query(String.class)
                .list();
        return stored.stream()
                .filter(dir -> dir != null && !dir.isBlank())
                .findFirst()
                .map(dir -> PackageArchive.resolve(outputDirectory(), dir));
    }

    /**
     * The same directory the packaging stage writes into, resolved the way every other
     * relative path in this application is: upwards from a working directory that is not
     * one thing.
     */
    private Path outputDirectory() {
        PipelineConfig.Packaging settings = config.snapshot().application().packaging();
        if (settings == null) {
            throw new PackageArchive.Rejected("no packaging output directory is configured");
        }
        return Directories.resolve(settings.outputDir());
    }
}
