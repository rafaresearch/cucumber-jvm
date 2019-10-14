package io.cucumber.core.plugin;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.WriterConfig;
import gherkin.ast.Background;
import gherkin.ast.Feature;
import gherkin.ast.ScenarioDefinition;
import gherkin.ast.Step;
import gherkin.ast.Tag;
import io.cucumber.plugin.EventListener;
import io.cucumber.plugin.event.Argument;
import io.cucumber.plugin.event.DataTableArgument;
import io.cucumber.plugin.event.DocStringArgument;
import io.cucumber.plugin.event.EmbedEvent;
import io.cucumber.plugin.event.EventPublisher;
import io.cucumber.plugin.event.HookTestStep;
import io.cucumber.plugin.event.HookType;
import io.cucumber.plugin.event.PickleStepTestStep;
import io.cucumber.plugin.event.Result;
import io.cucumber.plugin.event.Status;
import io.cucumber.plugin.event.StepArgument;
import io.cucumber.plugin.event.TestCase;
import io.cucumber.plugin.event.TestCaseStarted;
import io.cucumber.plugin.event.TestRunFinished;
import io.cucumber.plugin.event.TestSourceRead;
import io.cucumber.plugin.event.TestStep;
import io.cucumber.plugin.event.TestStepFinished;
import io.cucumber.plugin.event.TestStepStarted;
import io.cucumber.plugin.event.WriteEvent;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;

import static java.util.Locale.ROOT;

public final class JSONFormatter implements EventListener {
    private static final String before = "before";
    private static final String after = "after";
    private final JsonArray featureMaps = Json.array();
    private final NiceAppendable out;
    private final TestSourcesModel testSources = new TestSourcesModel();
    private URI currentFeatureFile;
    private JsonArray currentElementsList;
    private JsonObject currentElementMap;
    private JsonObject currentTestCaseMap;
    private JsonArray currentStepsList;
    private JsonObject currentStepOrHookMap;
    private JsonObject currentBeforeStepHookList = Json.object();

    @SuppressWarnings("WeakerAccess") // Used by PluginFactory
    public JSONFormatter(Appendable out) {
        this.out = new NiceAppendable(out);
    }

    private static String printStackTrace(Throwable error) {
        StringWriter stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);
        error.printStackTrace(printWriter);
        return stringWriter.toString();
    }

    @Override
    public void setEventPublisher(EventPublisher publisher) {
        publisher.registerHandlerFor(TestSourceRead.class, this::handleTestSourceRead);
        publisher.registerHandlerFor(TestCaseStarted.class, this::handleTestCaseStarted);
        publisher.registerHandlerFor(TestStepStarted.class, this::handleTestStepStarted);
        publisher.registerHandlerFor(TestStepFinished.class, this::handleTestStepFinished);
        publisher.registerHandlerFor(WriteEvent.class, this::handleWrite);
        publisher.registerHandlerFor(EmbedEvent.class, this::handleEmbed);
        publisher.registerHandlerFor(TestRunFinished.class, event -> finishReport());
    }

    private void handleTestSourceRead(TestSourceRead event) {
        testSources.addTestSourceReadEvent(event.getUri(), event);
    }

    private void handleTestCaseStarted(TestCaseStarted event) {
        if (currentFeatureFile == null || !currentFeatureFile.equals(event.getTestCase().getUri())) {
            currentFeatureFile = event.getTestCase().getUri();
            JsonObject currentFeatureMap = createFeatureMap(event.getTestCase());
            featureMaps.add(currentFeatureMap);
            currentElementsList = currentFeatureMap.get("elements").asArray();
        }
        currentTestCaseMap = createTestCase(event);
        if (testSources.hasBackground(currentFeatureFile, event.getTestCase().getLine())) {
            currentElementMap = createBackground(event.getTestCase());
            currentElementsList.add(currentElementMap);
        } else {
            currentElementMap = currentTestCaseMap;
        }
        currentElementsList.add(currentTestCaseMap);
        currentStepsList = currentElementMap.get("steps").asArray();
    }

    private void handleTestStepStarted(TestStepStarted event) {
        if (event.getTestStep() instanceof PickleStepTestStep) {
            PickleStepTestStep testStep = (PickleStepTestStep) event.getTestStep();
            if (isFirstStepAfterBackground(testStep)) {
                currentElementMap = currentTestCaseMap;
                currentStepsList = currentElementMap.get("steps").asArray();
            }
            currentStepOrHookMap = createTestStep(testStep);
            //add beforeSteps list to current step
            if (currentBeforeStepHookList.get(before) != null) {
                currentStepOrHookMap.add(before, currentBeforeStepHookList.get(before));
                currentBeforeStepHookList = Json.object();
            }
            currentStepsList.add(currentStepOrHookMap);
        } else if (event.getTestStep() instanceof HookTestStep) {
            HookTestStep hookTestStep = (HookTestStep) event.getTestStep();
            currentStepOrHookMap = createHookStep(hookTestStep);
            addHookStepToTestCaseMap(currentStepOrHookMap, hookTestStep.getHookType());
        } else {
            throw new IllegalStateException();
        }
    }

    private void handleWrite(WriteEvent event) {
        addOutputToHookMap(event.getText());
    }

    private void handleEmbed(EmbedEvent event) {
        addEmbeddingToHookMap(event.getData(), event.getMimeType(), event.getName());
    }

    private void handleTestStepFinished(TestStepFinished event) {
        currentStepOrHookMap.add("match", createMatchMap(event.getTestStep(), event.getResult()));
        currentStepOrHookMap.add("result", createResultMap(event.getResult()));
    }

    private void finishReport() {
        out.append(featureMaps.toString(WriterConfig.PRETTY_PRINT));
        out.close();
    }

    private JsonObject createFeatureMap(TestCase testCase) {
        JsonObject featureMap = Json.object();
        featureMap.add("uri", testCase.getUri().toString());
        featureMap.add("elements", Json.array());
        Feature feature = testSources.getFeature(testCase.getUri());
        if (feature != null) {
            featureMap.add("keyword", feature.getKeyword());
            featureMap.add("name", feature.getName());
            featureMap.add("description", feature.getDescription() != null ? feature.getDescription() : "");
            featureMap.add("line", feature.getLocation().getLine());
            featureMap.add("id", TestSourcesModel.convertToId(feature.getName()));
            featureMap.add("tags", createTags(feature));
        }
        return featureMap;
    }

    private JsonArray createTags(Feature feature) {
        JsonArray tags = Json.array();
        for (Tag tag : feature.getTags()) {
            JsonObject t = Json.object();
            t.add("name", tag.getName());
            t.add("type", "Tag");
            JsonObject location = Json.object();
            location.add("line", tag.getLocation().getLine());
            location.add("column", tag.getLocation().getColumn());
            t.add("location", location);
            tags.add(t);
        }
        return tags;
    }

    private JsonObject createTestCase(TestCaseStarted event) {
        JsonObject testCaseMap = Json.object();

        testCaseMap.add("start_timestamp", getDateTimeFromTimeStamp(event.getInstant()));

        TestCase testCase = event.getTestCase();

        testCaseMap.add("name", testCase.getName());
        testCaseMap.add("line", testCase.getLine());
        testCaseMap.add("type", "scenario");
        TestSourcesModel.AstNode astNode = testSources.getAstNode(currentFeatureFile, testCase.getLine());
        if (astNode != null) {
            testCaseMap.add("id", TestSourcesModel.calculateId(astNode));
            ScenarioDefinition scenarioDefinition = TestSourcesModel.getScenarioDefinition(astNode);
            testCaseMap.add("keyword", scenarioDefinition.getKeyword());
            testCaseMap.add("description", scenarioDefinition.getDescription() != null ? scenarioDefinition.getDescription() : "");
        }
        testCaseMap.add("steps", Json.array());
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
            testCaseMap.add("line", background.getLocation().getLine());
            testCaseMap.add("type", "background");
            testCaseMap.add("keyword", background.getKeyword());
            testCaseMap.add("description", background.getDescription() != null ? background.getDescription() : "");
            testCaseMap.add("steps", Json.array());
            return testCaseMap;
        }
        return null;
    }

    private boolean isFirstStepAfterBackground(PickleStepTestStep testStep) {
        TestSourcesModel.AstNode astNode = testSources.getAstNode(currentFeatureFile, testStep.getStepLine());
        if (astNode == null) {
            return false;
        }
        return currentElementMap != currentTestCaseMap && !TestSourcesModel.isBackgroundStep(astNode);
    }

    private JsonObject createTestStep(PickleStepTestStep testStep) {
        JsonObject stepMap = Json.object();
        stepMap.add("name", testStep.getStepText());
        stepMap.add("line", testStep.getStepLine());
        TestSourcesModel.AstNode astNode = testSources.getAstNode(currentFeatureFile, testStep.getStepLine());
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
        if (astNode != null) {
            Step step = (Step) astNode.node;
            stepMap.add("keyword", step.getKeyword());
        }

        return stepMap;
    }

    private JsonObject createDocStringMap(DocStringArgument docString) {
        JsonObject docStringMap = Json.object();
        docStringMap.add("value", docString.getContent());
        docStringMap.add("line", docString.getLine());
        if (docString.getContentType() != null) {
            docStringMap.add("content_type", docString.getContentType());
        }
        return docStringMap;
    }

    private JsonArray createDataTableList(DataTableArgument argument) {
        JsonArray rowList = Json.array();
        for (List<String> row : argument.cells()) {
            JsonObject rowMap = Json.object();
            JsonArray jsonRow = Json.array();
            for (String r : row) {
                jsonRow.add(r);
            }
            rowMap.add("cells", jsonRow);
            rowList.add(rowMap);
        }
        return rowList;
    }

    private JsonObject createHookStep(HookTestStep hookTestStep) {
        return Json.object();
    }

    private void addHookStepToTestCaseMap(JsonObject currentStepOrHookMap, HookType hookType) {
        String hookName;
        if (hookType == HookType.AFTER || hookType == HookType.AFTER_STEP)
            hookName = after;
        else
            hookName = before;

        JsonObject mapToAddTo;
        switch (hookType) {
            case BEFORE:
                mapToAddTo = currentTestCaseMap;
                break;
            case AFTER:
                mapToAddTo = currentTestCaseMap;
                break;
            case BEFORE_STEP:
                mapToAddTo = currentBeforeStepHookList;
                break;
            case AFTER_STEP:
                mapToAddTo = currentStepsList.get(currentStepsList.size() - 1).asObject();
                break;
            default:
                mapToAddTo = currentTestCaseMap;
        }

        if (mapToAddTo.get(hookName) == null) {
            mapToAddTo.add(hookName, Json.array());
        }
        mapToAddTo.get(hookName).asArray().add(currentStepOrHookMap);
    }

    private void addOutputToHookMap(String text) {
        if (currentStepOrHookMap.get("output") == null) {
            currentStepOrHookMap.add("output", Json.array());
        }
        currentStepOrHookMap.get("output").asArray().add(text);
    }

    private void addEmbeddingToHookMap(byte[] data, String mimeType, String name) {
        if (currentStepOrHookMap.get("embeddings") == null) {
            currentStepOrHookMap.add("embeddings", Json.array());
        }
        JsonObject embedMap = createEmbeddingMap(data, mimeType, name);
        currentStepOrHookMap.get("embeddings").asArray().add(embedMap);
    }

    private JsonObject createEmbeddingMap(byte[] data, String mimeType, String name) {
        JsonObject embedMap = Json.object();
        embedMap.add("mime_type", mimeType);
        embedMap.add("data", Base64.getEncoder().encodeToString(data));
        if (name != null) {
            embedMap.add("name", name);
        }
        return embedMap;
    }

    private JsonObject createMatchMap(TestStep step, Result result) {
        JsonObject matchMap = Json.object();
        if (step instanceof PickleStepTestStep) {
            PickleStepTestStep testStep = (PickleStepTestStep) step;
            if (!testStep.getDefinitionArgument().isEmpty()) {
                JsonArray argumentList = Json.array();
                for (Argument argument : testStep.getDefinitionArgument()) {
                    JsonObject argumentMap = Json.object();
                    if (argument.getValue() != null) {
                        argumentMap.add("val", argument.getValue());
                        argumentMap.add("offset", argument.getStart());
                    }
                    argumentList.add(argumentMap);
                }
                matchMap.add("arguments", argumentList);
            }
        }
        if (!result.getStatus().is(Status.UNDEFINED)) {
            matchMap.add("location", step.getCodeLocation());
        }
        return matchMap;
    }

    private JsonObject createResultMap(Result result) {
        JsonObject resultMap = Json.object();
        resultMap.add("status", result.getStatus().name().toLowerCase(ROOT));
        if (result.getError() != null) {
            resultMap.add("error_message", printStackTrace(result.getError()));
        }
        if (!result.getDuration().isZero()) {
            resultMap.add("duration", result.getDuration().toNanos());
        }
        return resultMap;
    }

    private String getDateTimeFromTimeStamp(Instant instant) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
            .withZone(ZoneOffset.UTC);
        return formatter.format(instant);
    }
}
