package te.htmltopdf;

import io.vavr.control.Try;
import java.io.File;
import java.io.IOException;
import java.util.function.Function;
import net.jcip.annotations.GuardedBy;
import net.jcip.annotations.ThreadSafe;
import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.ExecuteWatchdog;
import org.apache.commons.exec.Executor;
import org.apache.commons.exec.PumpStreamHandler;
import te.htmltopdf.wkhtmltopdf.HtmlPumpStreamHandler;
import te.htmltopdf.wkhtmltopdf.TempFileGenerator;
import te.htmltopdf.wkhtmltopdf.WkHtmlToPdfBinaryResolver;
import te.htmltopdf.wkhtmltopdf.domain.OnDiskWritablePDF;
import te.htmltopdf.wkhtmltopdf.domain.exceptions.HtmlToPdfConversionException;

/**
 * Converts HTML documents to PDF documents.
 *
 * <p>Under the hood this uses Apache Exec and a synchronization-block to ensure a thread-safe,
 * non-locking interaction between this class and the command line.
 *
 * @see <a href="https://wkhtmltopdf.org/">wkhtmltopdf site</href>
 */
@ThreadSafe
@SuppressWarnings("WeakerAccess")
public class WkHtmlToPdfConverter {
    public static final int COMMAND_TIMEOUT_IN_MILLIS = 15_000;
    public static final String EXPECT_FILE_AS_STREAM_FROM_STD_IN = "-";
    private static final Object LOCK = new Object[0];

    //TODO: Check response code from wkhtmltopdf and output error message from console

    @GuardedBy("HtmlToPdfFileConverter.LOCK")
    protected final File wkHtmlToPdfBinary;

    protected final TempFileGenerator tempFileGenerator;

    public WkHtmlToPdfConverter() {
        this(new WkHtmlToPdfBinaryResolver().resolve(), new TempFileGenerator());
    }

    public WkHtmlToPdfConverter(File wkHtmlToPdfBinary, TempFileGenerator tempFileGenerator) {
        this.wkHtmlToPdfBinary = wkHtmlToPdfBinary;
        this.tempFileGenerator = tempFileGenerator;
    }

    /**
     * Generates a PDF from the provided HTML and writes it to a temporary file.
     *
     * <p>Instead of writing the HTML to file first, it is piped directly from memory into the
     * wkhtmltopdf binary, saving us some disk IO.  For more information, see the {@link
     * HtmlPumpStreamHandler}.
     *
     * <p>The resulting {@link OnDiskWritablePDF} implements {@link java.io.Closeable} to simplify deleting
     * the file on disk after we're done with it.
     *
     * <p>Usage example:
     * <pre>
     *   try (PdfFile pdfFile = htmlToPdfFileConverter.convert(html)) {
     *       ...
     *   } catch (IOException exception) {
     *       log.error("An error occurred in the HTML -> PDF conversion.", exception);
     *       throw exception;
     *   }
     * </pre>
     *
     * @return a {@link OnDiskWritablePDF} containing a reference to the PDF file that was created
     */
    public OnDiskWritablePDF tryToConvert(String html) throws IOException {
        return tryToConvert(html, File.createTempFile("pdf", ".pdf"));
    }

    /**
     * {@link #tryToConvert(String)} but also accepts a function to allow for customizing the
     * call to the `wkhtmltopdf` binary.
     *
     * @see #tryToConvert(String)
     * @see #tryToConvert(String, File, Function)
     */
    public OnDiskWritablePDF tryToConvert(String html, Function<CommandLine, CommandLine> commandCustomizer)
        throws HtmlToPdfConversionException {
        return tryToConvert(html, tempFileGenerator.generateTempOutputFile(), commandCustomizer);
    }

    /**
     * {@link #tryToConvert(String)} but also accepts the file to write the PDF to.
     *
     * @see #tryToConvert(String)
     * @see #tryToConvert(String, File, Function)
     */
    public OnDiskWritablePDF tryToConvert(String html, File outputFile) throws HtmlToPdfConversionException {
        return tryToConvert(html, outputFile, Function.identity());
    }

    /**
     * {@link #tryToConvert(String, File)} but also accepts a function to allow for customizing the
     * call to the `wkhtmltopdf` binary.
     *
     * @see #tryToConvert(String)
     * @see #tryToConvert(String, File)
     */
    public OnDiskWritablePDF tryToConvert(String html, File outputFile,
        Function<CommandLine, CommandLine> commandCustomizer) throws HtmlToPdfConversionException {
        synchronized (LOCK) {
            CommandLine command = commandCustomizer.apply(
                    createConversionCommand(outputFile)
            );

            return tryToExecuteCommand(command, html, outputFile);
        }
    }

    protected CommandLine createConversionCommand(File outputFile) {
        return new CommandLine(wkHtmlToPdfBinary)
                .addArgument(EXPECT_FILE_AS_STREAM_FROM_STD_IN)
                .addArgument(outputFile.getAbsolutePath());
    }

    protected OnDiskWritablePDF tryToExecuteCommand(CommandLine conversionCommand, String html,
        File outputFile) throws HtmlToPdfConversionException {
        DefaultExecutor executor = new DefaultExecutor();

        Try.withResources(() -> new HtmlPumpStreamHandler(html))
                .of(htmlStreamHandler -> executeCommand(executor, htmlStreamHandler, conversionCommand))
                .getOrElseThrow(ex -> new HtmlToPdfConversionException(executor, ex));

        return new OnDiskWritablePDF(outputFile);
    }

    //TODO: Test this
    protected int executeCommand(Executor executor, PumpStreamHandler streamHandler, CommandLine command) throws IOException {
        executor.setWatchdog(createCommandLineWatchdog());
        executor.setStreamHandler(streamHandler);
        return executor.execute(command);
    }

    protected ExecuteWatchdog createCommandLineWatchdog() {
        return new ExecuteWatchdog(COMMAND_TIMEOUT_IN_MILLIS);
    }
}
