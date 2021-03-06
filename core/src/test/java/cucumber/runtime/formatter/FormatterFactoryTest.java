package cucumber.runtime.formatter;

import cucumber.runtime.CucumberException;
import cucumber.runtime.Utils;
import cucumber.runtime.io.UTF8OutputStreamWriter;
import gherkin.formatter.Formatter;
import gherkin.formatter.model.Result;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URL;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

public class FormatterFactoryTest {
    private FormatterFactory fc = new FormatterFactory();

    @Test
    public void instantiates_null_formatter() {
        Formatter formatter = fc.create("null");
        assertEquals(NullFormatter.class, formatter.getClass());
    }

    @Test
    public void instantiates_junit_formatter_with_file_arg() throws IOException {
        Formatter formatter = fc.create("junit:" + File.createTempFile("cucumber", "xml"));
        assertEquals(JUnitFormatter.class, formatter.getClass());
    }

    @Test
    public void instantiates_html_formatter_with_dir_arg() throws IOException {
        Formatter formatter = fc.create("html:" + TempDir.createTempDirectory().getAbsolutePath());
        assertEquals(HTMLFormatter.class, formatter.getClass());
    }

    @Test
    public void fails_to_instantiate_html_formatter_without_dir_arg() throws IOException {
        try {
            fc.create("html");
            fail();
        } catch (CucumberException e) {
            assertEquals("You must supply an output argument to html. Like so: html:output", e.getMessage());
        }
    }

    @Test
    public void instantiates_pretty_formatter_with_file_arg() throws IOException {
        Formatter formatter = fc.create("pretty:" + Utils.toURL(TempDir.createTempFile().getAbsolutePath()));
        assertEquals(CucumberPrettyFormatter.class, formatter.getClass());
    }

    @Test
    public void instantiates_pretty_formatter_without_file_arg() {
        Formatter formatter = fc.create("pretty");
        assertEquals(CucumberPrettyFormatter.class, formatter.getClass());
    }

    @Test
    public void instantiates_usage_formatter_without_file_arg() {
        Formatter formatter = fc.create("usage");
        assertEquals(UsageFormatter.class, formatter.getClass());
    }

    @Test
    public void instantiates_usage_formatter_with_file_arg() throws IOException {
        Formatter formatter = fc.create("usage:" + TempDir.createTempFile().getAbsolutePath());
        assertEquals(UsageFormatter.class, formatter.getClass());
    }
    
    @Test
    public void formatter_does_not_buffer_its_output() throws IOException {
        PrintStream previousSystemOut = System.out;
        OutputStream mockSystemOut = new ByteArrayOutputStream();
        
        try {
            System.setOut(new PrintStream(mockSystemOut));
            
            // Need to create a new formatter factory here since we need it to pick up the new value of System.out
            fc = new FormatterFactory();
            
            ProgressFormatter formatter = (ProgressFormatter) fc.create("progress");
            
            formatter.result(new Result("passed", null, null));
            
            assertThat(mockSystemOut.toString(), is(not("")));
        } finally {
            System.setOut(previousSystemOut);
        }
    }

    @Test
    public void instantiates_single_custom_appendable_formatter_with_stdout() {
        WantsAppendable formatter = (WantsAppendable) fc.create("cucumber.runtime.formatter.FormatterFactoryTest$WantsAppendable");
        assertThat(formatter.out, is(instanceOf(PrintStream.class)));
        try {
            fc.create("cucumber.runtime.formatter.FormatterFactoryTest$WantsAppendable");
            fail();
        } catch (CucumberException expected) {
            assertEquals("Only one formatter can use STDOUT. If you use more than one formatter you must specify output path with FORMAT:PATH_OR_URL", expected.getMessage());
        }
    }

    @Test
    public void instantiates_custom_appendable_formatter_with_stdout_and_file() throws IOException {
        WantsAppendable formatter = (WantsAppendable) fc.create("cucumber.runtime.formatter.FormatterFactoryTest$WantsAppendable");
        assertThat(formatter.out, is(instanceOf(PrintStream.class)));

        WantsAppendable formatter2 = (WantsAppendable) fc.create("cucumber.runtime.formatter.FormatterFactoryTest$WantsAppendable:" + TempDir.createTempFile().getAbsolutePath());
        assertEquals(UTF8OutputStreamWriter.class, formatter2.out.getClass());
    }

    @Test
    public void instantiates_custom_url_formatter() throws IOException {
        WantsUrl formatter = (WantsUrl) fc.create("cucumber.runtime.formatter.FormatterFactoryTest$WantsUrl:halp");
        assertEquals(new URL("file:halp/"), formatter.out);
    }

    @Test
    public void instantiates_custom_url_formatter_with_http() throws IOException {
        WantsUrl formatter = (WantsUrl) fc.create("cucumber.runtime.formatter.FormatterFactoryTest$WantsUrl:http://halp/");
        assertEquals(new URL("http://halp/"), formatter.out);
    }

    @Test
    public void instantiates_custom_uri_formatter_with_ws() throws IOException, URISyntaxException {
        WantsUri formatter = (WantsUri) fc.create("cucumber.runtime.formatter.FormatterFactoryTest$WantsUri:ws://halp/");
        assertEquals(new URI("ws://halp/"), formatter.out);
    }

    @Test
    public void instantiates_custom_file_formatter() throws IOException {
        WantsFile formatter = (WantsFile) fc.create("cucumber.runtime.formatter.FormatterFactoryTest$WantsFile:halp.txt");
        assertEquals(new File("halp.txt"), formatter.out);
    }

    public static class WantsAppendable extends StubFormatter {
        public final Appendable out;

        public WantsAppendable(Appendable out) {
            this.out = out;
        }
    }

    public static class WantsUrl extends StubFormatter {
        public final URL out;

        public WantsUrl(URL out) {
            this.out = out;
        }
    }

    public static class WantsUri extends StubFormatter {
        public final URI out;

        public WantsUri(URI out) {
            this.out = out;
        }
    }

    public static class WantsFile extends StubFormatter {
        public final File out;

        public WantsFile(File out) {
            this.out = out;
        }
    }
}
