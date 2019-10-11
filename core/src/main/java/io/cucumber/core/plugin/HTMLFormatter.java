package io.cucumber.core.plugin;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;
import com.eclipsesource.json.WriterConfig;
import gherkin.ast.Background;
import gherkin.ast.DataTable;
import gherkin.ast.DocString;
import gherkin.ast.Examples;
import gherkin.ast.Feature;
import gherkin.ast.Node;
import gherkin.ast.ScenarioDefinition;
import gherkin.ast.ScenarioOutline;
import gherkin.ast.Step;
import gherkin.ast.TableCell;
import gherkin.ast.TableRow;
import gherkin.ast.Tag;
import io.cucumber.core.exception.CucumberException;
import io.cucumber.plugin.EventListener;
import io.cucumber.plugin.event.DataTableArgument;
import io.cucumber.plugin.event.DocStringArgument;
import io.cucumber.plugin.event.EmbedEvent;
import io.cucumber.plugin.event.EventPublisher;
import io.cucumber.plugin.event.HookTestStep;
import io.cucumber.plugin.event.HookType;
import io.cucumber.plugin.event.PickleStepTestStep;
import io.cucumber.plugin.event.Result;
import io.cucumber.plugin.event.StepArgument;
import io.cucumber.plugin.event.TestCase;
import io.cucumber.plugin.event.TestCaseStarted;
import io.cucumber.plugin.event.TestRunFinished;
import io.cucumber.plugin.event.TestSourceRead;
import io.cucumber.plugin.event.TestStepFinished;
import io.cucumber.plugin.event.TestStepStarted;
import io.cucumber.plugin.event.WriteEvent;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Locale.ROOT;

public final class HTMLFormatter implements EventListener {
    private static final String JS_FORMATTER_VAR = "formatter";
    private static final String JS_REPORT_FILENAME = "report.js";
    private static final String[] TEXT_ASSETS = new String[]{
        "/io/cucumber/core/plugin/html/formatter.js",
        "/io/cucumber/core/plugin/html/index.html",
        "/io/cucumber/core/plugin/html/jquery-3.4.1.min.js",
        "/io/cucumber/core/plugin/html/style.css"
    };
    private static final Map<String, String> MIME_TYPES_EXTENSIONS = new HashMap<String, String>() {
        {
            put("image/bmp", "bmp");
            put("image/gif", "gif");
            put("image/jpeg", "jpg");
            put("image/png", "png");
            put("image/svg+xml", "svg");
            put("video/ogg", "ogg");
        }
    };

    private final TestSourcesModel testSources = new TestSourcesModel();
    private final URL htmlReportDir;
    private NiceAppendable jsOut;

    private boolean firstFeature = true;
    private URI currentFeatureFile;
    private JsonObject currentTestCaseMap;
    private ScenarioOutline currentScenarioOutline;
    private Examples currentExamples;
    private int embeddedIndex;

    @SuppressWarnings("WeakerAccess") // Used by PluginFactory
    public HTMLFormatter(URL htmlReportDir) {
        this(htmlReportDir, createJsOut(htmlReportDir));
    }

    HTMLFormatter(URL htmlReportDir, NiceAppendable jsOut) {
        this.htmlReportDir = htmlReportDir;
        this.jsOut = jsOut;
    }

    @Override
    public void setEventPublisher(EventPublisher publisher) {
        publisher.registerHandlerFor(TestSourceRead.class, this::handleTestSourceRead);
        publisher.registerHandlerFor(TestCaseStarted.class, this::handleTestCaseStarted);
        publisher.registerHandlerFor(TestStepStarted.class, this::handleTestStepStarted);
        publisher.registerHandlerFor(TestStepFinished.class, this::handleTestStepFinished);
        publisher.registerHandlerFor(EmbedEvent.class, this::handleEmbed);
        publisher.registerHandlerFor(WriteEvent.class, this::handleWrite);
        publisher.registerHandlerFor(TestRunFinished.class, event -> finishReport());
    }

    private void handleTestSourceRead(TestSourceRead event) {
        testSources.addTestSourceReadEvent(event.getUri(), event);
    }

    private void handleTestCaseStarted(TestCaseStarted event) {
        if (firstFeature) {
            jsOut.append("$(document).ready(function() {").append("var ")
                .append(JS_FORMATTER_VAR).append(" = new CucumberHTML.DOMFormatter($('.cucumber-report'));");
            firstFeature = false;
        }
        handleStartOfFeature(event.getTestCase());
        handleScenarioOutline(event.getTestCase());
        currentTestCaseMap = createTestCase(event.getTestCase());
        if (testSources.hasBackground(currentFeatureFile, event.getTestCase().getLine())) {
            jsFunctionCall("background", createBackground(event.getTestCase()));
        } else {
            jsFunctionCall("scenario", currentTestCaseMap);
            currentTestCaseMap = null;
        }
    }

    private void handleTestStepStarted(TestStepStarted event) {
        if (event.getTestStep() instanceof PickleStepTestStep) {
            PickleStepTestStep testStep = (PickleStepTestStep) event.getTestStep();
            if (isFirstStepAfterBackground(testStep)) {
                jsFunctionCall("scenario", currentTestCaseMap);
                currentTestCaseMap = null;
            }
            jsFunctionCall("step", createTestStep(testStep));
            jsFunctionCall("match", createMatchMap((PickleStepTestStep) event.getTestStep()));
        }
    }

    private void handleTestStepFinished(TestStepFinished event) {
        if (event.getTestStep() instanceof PickleStepTestStep) {
            jsFunctionCall("result", createResultMap(event.getResult()));
        } else if (event.getTestStep() instanceof HookTestStep) {
            HookTestStep hookTestStep = (HookTestStep) event.getTestStep();
            jsFunctionCall(getFunctionName(hookTestStep), createResultMap(event.getResult()));
        } else {
            throw new IllegalStateException();
        }
    }

    private String getFunctionName(HookTestStep hookTestStep) {
        HookType hookType = hookTestStep.getHookType();
        switch (hookType) {
            case BEFORE:
                return "before";
            case AFTER:
                return "after";
            case BEFORE_STEP:
                return "beforestep";
            case AFTER_STEP:
                return "afterstep";
            default:
                throw new IllegalArgumentException(hookType.name());
        }
    }

    private void handleEmbed(EmbedEvent event) {
        String mimeType = event.getMimeType();
        if (mimeType.startsWith("text/")) {
            // just pass straight to the plugin to output in the html
            jsFunctionCall("embedding", Json.value(mimeType), Json.value(new String(event.getData())), Json.value(event.getName()));
        } else {
            // Creating a file instead of using data urls to not clutter the js file
            String extension = MIME_TYPES_EXTENSIONS.get(mimeType);
            if (extension != null) {
                String fileName = "embedded" + embeddedIndex++ + "." + extension;
                writeBytesToURL(event.getData(), toUrl(fileName));
                jsFunctionCall("embedding", Json.value(mimeType), Json.value(fileName), Json.value(event.getName()));
            }
        }
    }

    private void handleWrite(WriteEvent event) {
        jsFunctionCall("write", Json.value(event.getText()));
    }

    private void finishReport() {
        if (!firstFeature) {
            jsOut.append("});");
            copyReportFiles();
        }
        jsOut.close();
    }

    private void handleStartOfFeature(TestCase testCase) {
        if (currentFeatureFile == null || !currentFeatureFile.equals(testCase.getUri())) {
            currentFeatureFile = testCase.getUri();
            jsFunctionCall("uri", Json.value(currentFeatureFile.toString()));
            jsFunctionCall("feature", createFeature(testCase));
        }
    }

    private JsonObject createFeature(TestCase testCase) {
        JsonObject featureMap = Json.object();
        Feature feature = testSources.getFeature(testCase.getUri());
        if (feature != null) {
            featureMap.add("keyword", feature.getKeyword());
            featureMap.add("name", feature.getName());
            featureMap.add("description", feature.getDescription() != null ? feature.getDescription() : "");
            if (!feature.getTags().isEmpty()) {
                featureMap.add("tags", createTagList(feature.getTags()));
            }
        }
        return featureMap;
    }

    private JsonArray createTagList(List<Tag> tags) {
        JsonArray tagList = Json.array();
        for (Tag tag : tags) {
            JsonObject tagMap = Json.object();
            tagMap.add("name", tag.getName());
            tagList.add(tagMap);
        }
        return tagList;
    }

    private void handleScenarioOutline(TestCase testCase) {
        TestSourcesModel.AstNode astNode = testSources.getAstNode(currentFeatureFile, testCase.getLine());
        if (TestSourcesModel.isScenarioOutlineScenario(astNode)) {
            ScenarioOutline scenarioOutline = (ScenarioOutline) TestSourcesModel.getScenarioDefinition(astNode);
            if (currentScenarioOutline == null || !currentScenarioOutline.equals(scenarioOutline)) {
                currentScenarioOutline = scenarioOutline;
                jsFunctionCall("scenarioOutline", createScenarioOutline(currentScenarioOutline));
                addOutlineStepsToReport(scenarioOutline);
            }
            Examples examples = (Examples) astNode.parent.node;
            if (currentExamples == null || !currentExamples.equals(examples)) {
                currentExamples = examples;
                jsFunctionCall("examples", createExamples(currentExamples));
            }
        } else {
            currentScenarioOutline = null;
            currentExamples = null;
        }
    }

    private JsonObject createScenarioOutline(ScenarioOutline scenarioOutline) {
        JsonObject scenarioOutlineMap = Json.object();
        scenarioOutlineMap.add("name", scenarioOutline.getName());
        scenarioOutlineMap.add("keyword", scenarioOutline.getKeyword());
        scenarioOutlineMap.add("description", scenarioOutline.getDescription() != null ? scenarioOutline.getDescription() : "");
        if (!scenarioOutline.getTags().isEmpty()) {
            scenarioOutlineMap.add("tags", createTagList(scenarioOutline.getTags()));
        }
        return scenarioOutlineMap;
    }

    private void addOutlineStepsToReport(ScenarioOutline scenarioOutline) {
        for (Step step : scenarioOutline.getSteps()) {
            JsonObject stepMap = Json.object();
            stepMap.add("name", step.getText());
            stepMap.add("keyword", step.getKeyword());
            if (step.getArgument() != null) {
                Node argument = step.getArgument();
                if (argument instanceof DocString) {
                    stepMap.add("doc_string", createDocStringMap((DocString) argument));
                } else if (argument instanceof DataTable) {
                    stepMap.add("rows", createDataTableList((DataTable) argument));
                }
            }
            jsFunctionCall("step", stepMap);
        }
    }

    private JsonObject createDocStringMap(DocString docString) {
        JsonObject docStringMap = Json.object();
        docStringMap.add("value", docString.getContent());
        return docStringMap;
    }

    private JsonArray createDataTableList(DataTable dataTable) {
        JsonArray rowList = Json.array();
        for (TableRow row : dataTable.getRows()) {
            rowList.add(createRowMap(row));
        }
        return rowList;
    }

    private JsonObject createRowMap(TableRow row) {
        JsonObject rowMap = Json.object();
        rowMap.add("cells", createCellList(row));
        return rowMap;
    }

    private JsonArray createCellList(TableRow row) {
        JsonArray cells = Json.array();
        for (TableCell cell : row.getCells()) {
            cells.add(cell.getValue());
        }
        return cells;
    }

    private JsonObject createExamples(Examples examples) {
        JsonObject examplesMap = Json.object();
        examplesMap.add("name", examples.getName());
        examplesMap.add("keyword", examples.getKeyword());
        examplesMap.add("description", examples.getDescription() != null ? examples.getDescription() : "");
        JsonArray rowList = Json.array();
        rowList.add(createRowMap(examples.getTableHeader()));
        for (TableRow row : examples.getTableBody()) {
            rowList.add(createRowMap(row));
        }
        examplesMap.add("rows", rowList);
        if (!examples.getTags().isEmpty()) {
            examplesMap.add("tags", createTagList(examples.getTags()));
        }
        return examplesMap;
    }

    private JsonObject createTestCase(TestCase testCase) {
        JsonObject testCaseMap = Json.object();
        testCaseMap.add("name", testCase.getName());
        TestSourcesModel.AstNode astNode = testSources.getAstNode(currentFeatureFile, testCase.getLine());
        if (astNode != null) {
            ScenarioDefinition scenarioDefinition = TestSourcesModel.getScenarioDefinition(astNode);
            testCaseMap.add("keyword", scenarioDefinition.getKeyword());
            testCaseMap.add("description", scenarioDefinition.getDescription() != null ? scenarioDefinition.getDescription() : "");
        }
        if (!testCase.getTags().isEmpty()) {
            JsonArray tagList = Json.array();
            for (String tag : testCase.getTags()) {
                JsonObject tagMap = Json.object();
                tagMap.add("name", tag);
                tagList.add(tagMap);
            }
            testCaseMap.add("tags", tagList);
        }
        return testCaseMap;
    }

    private JsonObject createBackground(TestCase testCase) {
        TestSourcesModel.AstNode astNode = testSources.getAstNode(currentFeatureFile, testCase.getLine());
        if (astNode != null) {
            Background background = TestSourcesModel.getBackgroundForTestCase(astNode);
            JsonObject testCaseMap = Json.object();
            testCaseMap.add("name", background.getName());
            testCaseMap.add("keyword", background.getKeyword());
            testCaseMap.add("description", background.getDescription() != null ? background.getDescription() : "");
            return testCaseMap;
        }
        return null;
    }

    private boolean isFirstStepAfterBackground(PickleStepTestStep testStep) {
        TestSourcesModel.AstNode astNode = testSources.getAstNode(currentFeatureFile, testStep.getStepLine());
        if (astNode != null) {
            return currentTestCaseMap != null && !TestSourcesModel.isBackgroundStep(astNode);
        }
        return false;
    }

    private JsonObject createTestStep(PickleStepTestStep testStep) {
        JsonObject stepMap = Json.object();
        stepMap.add("name", testStep.getStepText());
        StepArgument argument = testStep.getStepArgument();
        if (argument != null) {
            if (argument instanceof DocStringArgument) {
                DocStringArgument docStringArgument = (DocStringArgument) argument;
                stepMap.add("doc_string", createDocStringMap(docStringArgument));
            } else if (argument instanceof DataTableArgument) {
                DataTableArgument dataTableArgument = (DataTableArgument) argument;
                stepMap.add("rows", createDataTableList(dataTableArgument));
            }
        }
        TestSourcesModel.AstNode astNode = testSources.getAstNode(currentFeatureFile, testStep.getStepLine());
        if (astNode != null) {
            Step step = (Step) astNode.node;
            stepMap.add("keyword", step.getKeyword());
        }

        return stepMap;
    }

    private JsonObject createDocStringMap(DocStringArgument docString) {
        JsonObject docStringMap = Json.object();
        docStringMap.add("value", docString.getContent());
        return docStringMap;
    }

    private JsonArray createDataTableList(DataTableArgument dataTable) {
        JsonArray rowList = Json.array();
        for (List<String> row : dataTable.cells()) {
            rowList.add(createRowMap(row));
        }
        return rowList;
    }

    private JsonObject createRowMap(List<String> row) {
        JsonObject rowMap = Json.object();
        rowMap.add("cells", Json.array(row.toArray(new String[0])));
        return rowMap;
    }

    private JsonObject createMatchMap(PickleStepTestStep testStep) {
        JsonObject matchMap = Json.object();
        String location = testStep.getCodeLocation();
        if (location != null) {
            matchMap.add("location", location);
        }
        return matchMap;
    }

    private JsonObject createResultMap(Result result) {
        JsonObject resultMap = Json.object();
        resultMap.add("status", result.getStatus().name().toLowerCase(ROOT));
        if (result.getError() != null) {
            resultMap.add("error_message", printStackTrace(result.getError()));
        }
        return resultMap;
    }

    private void jsFunctionCall(String functionName, JsonValue... args) {
        NiceAppendable out = jsOut.append(JS_FORMATTER_VAR + ".").append(functionName).append("(");
        boolean comma = false;
        for (JsonValue arg : args) {
            if (comma) {
                out.append(", ");
            }
            out.append(arg.toString(WriterConfig.PRETTY_PRINT));
            comma = true;
        }
        out.append(");").println();
    }

    private void copyReportFiles() {
        if (htmlReportDir == null) {
            return;
        }
        for (String textAsset : TEXT_ASSETS) {
            InputStream textAssetStream = getClass().getResourceAsStream(textAsset);
            if (textAssetStream == null) {
                throw new CucumberException("Couldn't find " + textAsset + ". Is cucumber-html on your classpath? Make sure you have the right version.");
            }
            String fileName = new File(textAsset).getName();
            writeStreamToURL(textAssetStream, toUrl(fileName));
        }
    }

    private static String printStackTrace(Throwable error) {
        StringWriter stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);
        error.printStackTrace(printWriter);
        return stringWriter.toString();
    }

    private URL toUrl(String fileName) {
        try {
            return new URL(htmlReportDir, fileName);
        } catch (IOException e) {
            throw new CucumberException(e);
        }
    }

    private static void writeStreamToURL(InputStream in, URL url) {
        OutputStream out = createReportFileOutputStream(url);

        byte[] buffer = new byte[16 * 1024];
        try {
            int len = in.read(buffer);
            while (len != -1) {
                out.write(buffer, 0, len);
                len = in.read(buffer);
            }
        } catch (IOException e) {
            throw new CucumberException("Unable to write to report file item: ", e);
        } finally {
            closeQuietly(out);
        }
    }

    private static void writeBytesToURL(byte[] buf, URL url) throws CucumberException {
        OutputStream out = createReportFileOutputStream(url);
        try {
            out.write(buf);
        } catch (IOException e) {
            throw new CucumberException("Unable to write to report file item: ", e);
        } finally {
            closeQuietly(out);
        }
    }

    private static NiceAppendable createJsOut(URL htmlReportDir) {
        try {
            return new NiceAppendable(new OutputStreamWriter(createReportFileOutputStream(new URL(htmlReportDir, JS_REPORT_FILENAME)), UTF_8));
        } catch (IOException e) {
            throw new CucumberException(e);
        }
    }

    private static OutputStream createReportFileOutputStream(URL url) {
        try {
            return new URLOutputStream(url);
        } catch (IOException e) {
            throw new CucumberException(e);
        }
    }

    private static void closeQuietly(Closeable out) {
        try {
            out.close();
        } catch (IOException ignored) {
            // go gentle into that good night
        }
    }

}
