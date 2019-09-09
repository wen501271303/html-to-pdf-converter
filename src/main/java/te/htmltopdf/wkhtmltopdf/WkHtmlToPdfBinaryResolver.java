package te.htmltopdf.wkhtmltopdf;

import io.vavr.control.Try;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import te.htmltopdf.domain.exceptions.MakingFileExecutableException;

import java.io.File;
import java.nio.file.Files;

/**
 * Resolves the location of the wkhtmltopdf binary to use for converting.
 *
 * <p>By default, this will extract the binary for your OS to a temp. directory.
 * <br/>
 * Override this by setting the environment variable <code>WKHTMLTOPDF_BINARY</code> to the path of a wkhtmltopdf binary
 * you wish to use instead.
 */
public class WkHtmlToPdfBinaryResolver {
    private static final Logger log = LoggerFactory.getLogger(WkHtmlToPdfBinaryResolver.class);
    public static final String BINARY_ENV_VAR_NAME = "WKHTMLTOPDF_BINARY";

    protected final WkHtmlToPdfBinaryExtractor binaryExtractor;

    public WkHtmlToPdfBinaryResolver() {
        this.binaryExtractor = new WkHtmlToPdfBinaryExtractor();
    }

    /**
     * @return an executable wkhtmltopdf binary
     */
    public File resolve() {
        return makeExecutable(resolveBinaryFile());
    }

    protected File resolveBinaryFile() {
        if (isEnvironmentVariableSet()) {
            File binary = resolveBinaryFromEnvironmentVariable();

            if (binary.exists()) {
                log.info("Using binary from environment variable @ {}", binary.getAbsolutePath());
                return binary;
            } else {
                log.error("Binary specified in environment variable could not be found @ {}", binary.getAbsolutePath());
            }
        }

        return binaryExtractor.extract();
    }

    protected File resolveBinaryFromEnvironmentVariable() {
        return new File(System.getenv(BINARY_ENV_VAR_NAME));
    }

    protected boolean isEnvironmentVariableSet() {
        log.debug("{} system variable is set to {}", BINARY_ENV_VAR_NAME, System.getenv(BINARY_ENV_VAR_NAME));
        return StringUtils.isNotEmpty(System.getenv(BINARY_ENV_VAR_NAME));
    }

    protected File makeExecutable(File binary) {
        if (!Files.isExecutable(binary.toPath())) {
            Try.of(() -> binary.setExecutable(true))
                    .andThen(isExecutable -> {
                        if (!isExecutable) throw new MakingFileExecutableException(binary);
                    })
                    .onFailure(securityException -> {
                        throw new MakingFileExecutableException(binary, securityException);
                    })
                    .get();
        }

        return binary;
    }

}
