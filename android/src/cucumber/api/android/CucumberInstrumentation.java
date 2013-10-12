package cucumber.api.android;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import android.os.Debug;
import android.os.Looper;
import android.util.Log;
import cucumber.api.CucumberOptions;
import cucumber.runtime.*;
import cucumber.runtime.Runtime;
import cucumber.runtime.android.AndroidFormatter;
import cucumber.runtime.android.AndroidObjectFactory;
import cucumber.runtime.android.AndroidResourceLoader;
import cucumber.runtime.android.DexClassFinder;
import cucumber.runtime.ClassFinder;
import cucumber.runtime.io.ResourceLoader;
import cucumber.runtime.java.JavaBackend;
import cucumber.runtime.java.ObjectFactory;
import cucumber.runtime.model.*;
import dalvik.system.DexFile;
import gherkin.formatter.Formatter;
import gherkin.formatter.Reporter;
import gherkin.formatter.model.*;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public class CucumberInstrumentation extends Instrumentation {
    public static final String REPORT_VALUE_ID = "InstrumentationTestRunner";
    public static final String REPORT_KEY_NUM_TOTAL = "numtests";
    public static final String REPORT_KEY_NUM_CURRENT = "current";
    public static final String REPORT_KEY_NAME_CLASS = "class";
    public static final String REPORT_KEY_NAME_TEST = "test";
    private static final String REPORT_KEY_COVERAGE_PATH = "coverageFilePath";
    public static final int REPORT_VALUE_RESULT_START = 1;
    public static final int REPORT_VALUE_RESULT_ERROR = -1;
    public static final int REPORT_VALUE_RESULT_FAILURE = -2;
    public static final String REPORT_KEY_STACK = "stack";
    public static final String OPTION_VALUE_SEPARATOR = "--";
    private static final String DEFAULT_COVERAGE_FILE_NAME = "coverage.ec";
    public static final int DEFAULT_DEBUGGER_TIMEOUT = 10000;
    public static final String TAG = "cucumber-android";

    private final Bundle results = new Bundle();
    private int debuggerTimeout;
    private boolean justCount;
    private int testCount;
    private boolean coverage;
    private String coverageFilePath;

    private RuntimeOptions runtimeOptions;
    private ResourceLoader resourceLoader;
    private ClassLoader classLoader;
    private Runtime runtime;
    private List<CucumberFeature> cucumberFeatures;

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);

        if (arguments != null) {
            String debug = arguments.getString("debug");
            if (debug != null) {
                try {
                    debuggerTimeout = Integer.parseInt(debug);
                } catch (NumberFormatException e) {
                    if (Boolean.parseBoolean(debug)) {
                        debuggerTimeout = DEFAULT_DEBUGGER_TIMEOUT;
                    }
                }
            }
            boolean logOnly = getBooleanArgument(arguments, "log");
            if (logOnly && arguments.getString("dryRun") == null) {
                arguments.putString("dryRun", "true");
            }
            justCount = getBooleanArgument(arguments, "count");
            coverage = getBooleanArgument(arguments, "coverage");
            coverageFilePath = arguments.getString("coverageFile");
        }
        Context context = getContext();
        classLoader = context.getClassLoader();

        String apkPath = context.getPackageCodePath();
        ClassFinder classFinder = new DexClassFinder(newDexFile(apkPath));

        Class<?> optionsAnnotatedClass = null;
        for (Class<?> clazz : classFinder.getDescendants(Object.class, context.getPackageName())) {
            if (clazz.isAnnotationPresent(CucumberOptions.class)) {
                Log.d(TAG, "Found CucumberOptions in class " + clazz.getName());
                Log.d(TAG, clazz.getAnnotations()[0].toString());
                optionsAnnotatedClass = clazz;
                break; // We assume there is only one CucumberOptions annotated class.
            }
        }
        if (optionsAnnotatedClass == null) {
            throw new CucumberException("No CucumberOptions annotation");
        }

        String cucumberOptions = getCucumberOptionsString(arguments);
        if (!cucumberOptions.isEmpty()) {
            Log.d(TAG, "Setting cucumber.options from arguments: '" + cucumberOptions + "'");
            System.setProperty("cucumber.options", cucumberOptions);
        }

        @SuppressWarnings("unchecked")
        RuntimeOptionsFactory factory = new RuntimeOptionsFactory(optionsAnnotatedClass, new Class[]{CucumberOptions.class});
        runtimeOptions = factory.create();
        resourceLoader = new AndroidResourceLoader(context);

        List<Backend> backends = new ArrayList<Backend>();
        ObjectFactory delegateObjectFactory = JavaBackend.loadObjectFactory(classFinder);
        AndroidObjectFactory objectFactory = new AndroidObjectFactory(delegateObjectFactory, this);
        backends.add(new JavaBackend(objectFactory, classFinder));
        runtime = new Runtime(resourceLoader, classLoader, backends, runtimeOptions);
        cucumberFeatures = runtimeOptions.cucumberFeatures(resourceLoader);
        testCount = countScenarios(cucumberFeatures);

        start();
    }

    private DexFile newDexFile(String apkPath) {
        try {
            return new DexFile(apkPath);
        } catch (IOException e) {
            throw new CucumberException("Failed to open " + apkPath);
        }
    }

    @Override
    public void onStart() {
        Looper.prepare();

        if (justCount) {
            results.putString(Instrumentation.REPORT_KEY_IDENTIFIER, REPORT_VALUE_ID);
            results.putInt(REPORT_KEY_NUM_TOTAL, testCount);
            finish(Activity.RESULT_OK, results);
        } else {
            if (debuggerTimeout != 0) {
                waitForDebugger(debuggerTimeout);
            }

            AndroidReporter reporter = new AndroidReporter(testCount);
            runtimeOptions.getFormatters().clear();
            runtimeOptions.getFormatters().add(reporter);

            for (CucumberFeature cucumberFeature : cucumberFeatures) {
                Formatter formatter = runtimeOptions.formatter(classLoader);
                cucumberFeature.run(formatter, reporter, runtime);
            }
            Formatter formatter = runtimeOptions.formatter(classLoader);

            formatter.done();
            printSummary();
            formatter.close();

            if (coverage) {
                generateCoverageReport();
            }

            finish(Activity.RESULT_OK, results);
        }
    }

    private boolean getBooleanArgument(Bundle arguments, String tag) {
        String tagString = arguments.getString(tag);
        return tagString != null && Boolean.parseBoolean(tagString);
    }

    /**
     * Waits the specified time for a debugger to attach.
     * <p />
     * For some reason {@link Debug#waitForDebugger()} is not blocking and thinks a debugger is
     * attached when there isn't.
     *
     * @param timeout the time in milliseconds to wait
     */
    private void waitForDebugger(int timeout) {
        System.out.println("waiting " + timeout + "ms for debugger to attach.");
        long elapsed = 0;
        while (!Debug.isDebuggerConnected() && elapsed < timeout) {
            try {
                System.out.println("waiting for debugger to attach...");
                Thread.sleep(1000);
                elapsed += 1000;
            } catch (InterruptedException ie) {
            }
        }
        if (Debug.isDebuggerConnected()) {
            System.out.println("waiting for debugger to settle...");
            try {
                Thread.sleep(1300);
            } catch (InterruptedException e) {
            }
            System.out.println("debugger connected.");
        } else {
            System.out.println("no debugger connected.");
        }
    }

    private void printSummary() {
        for (Throwable t : runtime.getErrors()) {
            Log.e(TAG, t.toString());
        }
        for (String s : runtime.getSnippets()) {
            Log.w(TAG, s);
        }
    }

    private int countScenarios(List<CucumberFeature> cucumberFeatures) {
        int numScenarios = 0;

        // How many individual scenarios (test cases) exist - is there a better way to do this?
        // This is only relevant for reporting back to the Instrumentation and does not affect
        // execution.
        for (CucumberFeature feature : cucumberFeatures) {
            for (CucumberTagStatement statement : feature.getFeatureElements()) {
                if (statement instanceof CucumberScenario) {
                    numScenarios++;
                } else if (statement instanceof CucumberScenarioOutline) {
                    for (CucumberExamples examples : ((CucumberScenarioOutline) statement).getCucumberExamplesList()) {
                        for (ExamplesTableRow row : examples.getExamples().getRows()) {
                            numScenarios++;
                        }
                    }
                    numScenarios--; // subtract table header
                }
            }
        }

        return numScenarios;
    }

    private void appendOption(StringBuilder sb, String optionKey, String optionValue) {
        for (String value : optionValue.split(OPTION_VALUE_SEPARATOR)) {
            sb.append(sb.length() == 0 || optionKey.isEmpty() ? "" : " ").append(optionKey).append(optionValue.isEmpty() ? "" : " " + value);
        }
    }

    /**
     * Returns a cucumber-jvm compatible command line argument string based on
     * the argument extras found in the passed {@link Bundle}.
     * <p />
     * The bundle <em>cannot</em> contain multiple entries for the same key,
     * however cucumber supports options that can be passed multiple times (e.g.
     * {@code --tags}). The solution is to pass values separated by
     * {@link CucumberInstrumentation#OPTION_VALUE_SEPARATOR} which will result
     * in multiple {@code --key value} pairs being created.
     * <p />
     * Note: This method should be updated whenever new options are added. See
     * {@link RuntimeOptions} and {@link CucumberOptions}.
     *
     * @param arguments
     *            the arguments bundle to extract the options from
     * @return the cucumber options string
     */
    private String getCucumberOptionsString(Bundle arguments) {
        StringBuilder cucumberOptions = new StringBuilder();
        if (arguments != null) {
            String features = "";
            for (String key : arguments.keySet()) {
                if ("glue".equals(key)) {
                    appendOption(cucumberOptions, "--glue", arguments.getString(key));
                } else if ("format".equals(key)) {
                    appendOption(cucumberOptions, "--format", arguments.getString(key));
                } else if ("tags".equals(key)) {
                    appendOption(cucumberOptions, "--tags", arguments.getString(key));
                } else if ("name".equals(key)) {
                    appendOption(cucumberOptions, "--name", arguments.getString(key));
                } else if ("dryRun".equals(key) && getBooleanArgument(arguments, key)) {
                    appendOption(cucumberOptions, "--dry-run", "");
                } else if ("noDryRun".equals(key) && getBooleanArgument(arguments, key)) {
                    appendOption(cucumberOptions, "--no-dry-run", "");
                } else if ("monochrome".equals(key) && getBooleanArgument(arguments, key)) {
                    appendOption(cucumberOptions, "--monochrome", "");
                } else if ("noMonochrome".equals(key) && getBooleanArgument(arguments, key)) {
                    appendOption(cucumberOptions, "--no-monochrome", "");
                } else if ("strict".equals(key) && getBooleanArgument(arguments, key)) {
                    appendOption(cucumberOptions, "--strict", "");
                } else if ("noStrict".equals(key) && getBooleanArgument(arguments, key)) {
                    appendOption(cucumberOptions, "--no-strict", "");
                } else if ("snippets".equals(key)) {
                    appendOption(cucumberOptions, "--snippets", arguments.getString(key));
                } else if ("dotcucumber".equals(key)) {
                    appendOption(cucumberOptions, "--dotcucumber", arguments.getString(key));
                } else if ("features".equals(key)) {
                    features = arguments.getString(key);
                }
            }
            // Even though not strictly required, wait until everything else
            // has been added before adding any feature references
            appendOption(cucumberOptions, "", features);
        }
        return cucumberOptions.toString();
    }

    /**
     * This class reports the current test-state back to the framework.
     * It also wraps the AndroidFormatter to intercept important callbacks.
     */
    private class AndroidReporter implements Formatter, Reporter {
        private final AndroidFormatter formatter;
        private final Bundle resultTemplate;
        private Bundle testResult;
        private int scenarioCounter;
        private int resultCode; // aka exit status
        private Feature currentFeature;
        private Step currentStep;

        public AndroidReporter(int numTests) {
            formatter = new AndroidFormatter(TAG);
            resultTemplate = new Bundle();
            resultTemplate.putString(Instrumentation.REPORT_KEY_IDENTIFIER, REPORT_VALUE_ID);
            resultTemplate.putInt(REPORT_KEY_NUM_TOTAL, numTests);
        }

        @Override
        public void uri(String uri) {
            formatter.uri(uri);
        }

        @Override
        public void feature(Feature feature) {
            currentFeature = feature;
            formatter.feature(feature);
        }

        @Override
        public void background(Background background) {
            reportLastResult();
            formatter.background(background);
            beginScenario(background);
        }

        @Override
        public void scenario(Scenario scenario) {
            reportLastResult();
            formatter.scenario(scenario);
            beginScenario(scenario);
        }

        @Override
        public void scenarioOutline(ScenarioOutline scenarioOutline) {
            reportLastResult();
            formatter.scenarioOutline(scenarioOutline);
            beginScenario(scenarioOutline);
        }

        @Override
        public void examples(Examples examples) {
            formatter.examples(examples);
        }

        @Override
        public void step(Step step) {
            currentStep = step;
            formatter.step(step);
        }

        @Override
        public void syntaxError(String state, String event, List<String> legalEvents, String uri, Integer line) {
            formatter.syntaxError(state, event, legalEvents, uri, line);
        }

        @Override
        public void eof() {
            reportLastResult();
            formatter.eof();
        }

        @Override
        public void done() {
            formatter.done();
        }

        @Override
        public void close() {
            formatter.close();
        }

        @Override
        public void embedding(String mimeType, byte[] data) {
        }

        @Override
        public void write(String text) {
        }

        @Override
        public void before(Match match, Result result) {
        }

        @Override
        public void after(Match match, Result result) {
        }

        @Override
        public void match(Match match) {
        }

        private void beginScenario(DescribedStatement scenario) {
            if (testResult == null) {
                String testClass = String.format("%s %s", currentFeature.getKeyword(), currentFeature.getName());
                String testName = String.format("%s %s", scenario.getKeyword(), scenario.getName());
                testResult = new Bundle(resultTemplate);
                testResult.putString(REPORT_KEY_NAME_CLASS, testClass);
                testResult.putString(REPORT_KEY_NAME_TEST, testName);
                testResult.putInt(REPORT_KEY_NUM_CURRENT, ++scenarioCounter);

                testResult.putString(Instrumentation.REPORT_KEY_STREAMRESULT, String.format("\n%s:", testClass));

                sendStatus(REPORT_VALUE_RESULT_START, testResult);
                resultCode = 0;
            }
        }

        @Override
        public void result(Result result) {
            if (result.getError() != null) {
                // If the result contains an error, report a failure.
                testResult.putString(REPORT_KEY_STACK, result.getErrorMessage());
                resultCode = REPORT_VALUE_RESULT_FAILURE;
                testResult.putString(Instrumentation.REPORT_KEY_STREAMRESULT, result.getErrorMessage());
            } else if (result.getStatus().equals("undefined")) {
                // There was a missing step definition, report an error.
                List<String> snippets = runtime.getSnippets();
                String report = String.format("Missing step-definition\n\n%s\nfor step '%s'",
                        snippets.get(snippets.size() - 1),
                        currentStep.getName());
                testResult.putString(REPORT_KEY_STACK, report);
                resultCode = REPORT_VALUE_RESULT_ERROR;
                testResult.putString(Instrumentation.REPORT_KEY_STREAMRESULT,
                        String.format("Missing step-definition: %s", currentStep.getName()));
            }
        }

        private void reportLastResult() {
            if (testResult != null && !testResult.isEmpty() && scenarioCounter != 0) {
                if (resultCode == 0) {
                    testResult.putString(Instrumentation.REPORT_KEY_STREAMRESULT, ".");
                }
                sendStatus(resultCode, testResult);
                testResult = null;
            }
        }
    }

    private void generateCoverageReport() {
        // use reflection to call emma dump coverage method, to avoid
        // always statically compiling against emma jar
        String coverageFilePath = getCoverageFilePath();
        java.io.File coverageFile = new java.io.File(coverageFilePath);
        try {
            Class<?> emmaRTClass = Class.forName("com.vladium.emma.rt.RT");
            Method dumpCoverageMethod = emmaRTClass.getMethod("dumpCoverageData",
                    coverageFile.getClass(), boolean.class, boolean.class);

            dumpCoverageMethod.invoke(null, coverageFile, false, false);
            // output path to generated coverage file so it can be parsed by a test harness if
            // needed
            results.putString(REPORT_KEY_COVERAGE_PATH, coverageFilePath);
            // also output a more user friendly msg
            final String currentStream = results.getString(
                    Instrumentation.REPORT_KEY_STREAMRESULT);
            results.putString(Instrumentation.REPORT_KEY_STREAMRESULT,
                String.format("%s\nGenerated code coverage data to %s", currentStream,
                coverageFilePath));
        } catch (ClassNotFoundException e) {
            reportEmmaError("Is emma jar on classpath?", e);
        } catch (SecurityException e) {
            reportEmmaError(e);
        } catch (NoSuchMethodException e) {
            reportEmmaError(e);
        } catch (IllegalArgumentException e) {
            reportEmmaError(e);
        } catch (IllegalAccessException e) {
            reportEmmaError(e);
        } catch (InvocationTargetException e) {
            reportEmmaError(e);
        }
    }

    private String getCoverageFilePath() {
        if (coverageFilePath == null) {
            return getTargetContext().getFilesDir().getAbsolutePath() + File.separator +
                   DEFAULT_COVERAGE_FILE_NAME;
        } else {
            return coverageFilePath;
        }
    }

    private void reportEmmaError(Exception e) {
        reportEmmaError("", e);
    }

    private void reportEmmaError(String hint, Exception e) {
        String msg = "Failed to generate emma coverage. " + hint;
        Log.e(TAG, msg, e);
        results.putString(Instrumentation.REPORT_KEY_STREAMRESULT, "\nError: " + msg);
    }
}
