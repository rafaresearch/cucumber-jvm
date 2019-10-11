package io.cucumber.core.plugin;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import io.cucumber.plugin.event.EventPublisher;
import io.cucumber.plugin.event.PickleStepTestStep;
import io.cucumber.plugin.event.Result;
import io.cucumber.plugin.event.Status;
import io.cucumber.plugin.event.TestRunFinished;
import io.cucumber.plugin.event.TestStepFinished;
import io.cucumber.plugin.EventListener;
import io.cucumber.plugin.Plugin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Formatter to measure performance of steps. Includes average and median step duration.
 */
public final class UsageFormatter implements Plugin, EventListener {

    private static final long NANOS_PER_SECOND = TimeUnit.SECONDS.toNanos(1);
    final Map<String, List<StepContainer>> usageMap = new LinkedHashMap<>();
    private final NiceAppendable out;

    /**
     * Constructor
     *
     * @param out {@link Appendable} to print the result
     */
    @SuppressWarnings("WeakerAccess") // Used by PluginFactory
    public UsageFormatter(Appendable out) {
        this.out = new NiceAppendable(out);
    }

    @Override
    public void setEventPublisher(EventPublisher publisher) {
        publisher.registerHandlerFor(TestStepFinished.class, this::handleTestStepFinished);
        publisher.registerHandlerFor(TestRunFinished.class, event -> finishReport());
    }

    void handleTestStepFinished(TestStepFinished event) {
        if (event.getTestStep() instanceof PickleStepTestStep && event.getResult().getStatus().is(Status.PASSED)) {
            PickleStepTestStep testStep = (PickleStepTestStep) event.getTestStep();
            addUsageEntry(event.getResult(), testStep);
        }
    }

    void finishReport() {
        JsonArray stepDefContainers = Json.array();
        for (Map.Entry<String, List<StepContainer>> usageEntry : usageMap.entrySet()) {
            JsonObject stepDefContainer = Json.object();
            stepDefContainer.add("source", usageEntry.getKey());
            stepDefContainer.add("steps", createStepContainers(usageEntry.getValue()));

            stepDefContainers.add(stepDefContainer);
        }
        out.append(stepDefContainers.toString());
        out.close();
    }

    private JsonArray createStepContainers(List<StepContainer> stepContainers) {
        for (StepContainer stepContainer : stepContainers) {
            stepContainer.putAllAggregatedDurations(createAggregatedDurations(stepContainer));
        }

        JsonArray stepContainerArray = Json.array();
        for (StepContainer stepContainer : stepContainers) {
            stepContainerArray.add(stepContainer.toJson());
        }

        return stepContainerArray;
    }

    private Map<String, Duration> createAggregatedDurations(StepContainer stepContainer) {
        Map<String, Duration> aggregatedResults = new LinkedHashMap<>();
        List<Duration> rawDurations = getRawDurations(stepContainer.getDurations());

        Duration average = calculateAverage(rawDurations);
        aggregatedResults.put("average", average);

        Duration median = calculateMedian(rawDurations);
        aggregatedResults.put("median", median);

        return aggregatedResults;
    }

    private List<Duration> getRawDurations(List<StepDuration> stepDurations) {
        List<Duration> rawDurations = new ArrayList<>();

        for (StepDuration stepDuration : stepDurations) {
            rawDurations.add(stepDuration.duration);
        }
        return rawDurations;
    }

    private static double durationInSections(Duration duration) {
        return (double) duration.getNano() / NANOS_PER_SECOND;
    }


    private void addUsageEntry(Result result, PickleStepTestStep testStep) {
        List<StepContainer> stepContainers = usageMap.computeIfAbsent(testStep.getPattern(), k -> new ArrayList<>());
        StepContainer stepContainer = findOrCreateStepContainer(testStep.getStepText(), stepContainers);
        StepDuration stepDuration = new StepDuration(result.getDuration(), testStep.getUri() + ":" + testStep.getStepLine());
        stepContainer.getDurations().add(stepDuration);
    }

    private StepContainer findOrCreateStepContainer(String stepNameWithArgs, List<StepContainer> stepContainers) {
        for (StepContainer container : stepContainers) {
            if (stepNameWithArgs.equals(container.getName())) {
                return container;
            }
        }
        StepContainer stepContainer = new StepContainer(stepNameWithArgs);
        stepContainers.add(stepContainer);
        return stepContainer;
    }

    /**
     * Calculate the average of a list of duration entries
     */
    Duration calculateAverage(List<Duration> durationEntries) {

        Duration sum = Duration.ZERO;
        for (Duration duration : durationEntries) {
            sum = sum.plus(duration);
        }
        if (sum.isZero()) {
            return Duration.ZERO;
        }

        return sum.dividedBy(durationEntries.size());
    }

    /**
     * Calculate the median of a list of duration entries
     */
    Duration calculateMedian(List<Duration> durationEntries) {
        if (durationEntries.isEmpty()) {
            return Duration.ZERO;
        }
        Collections.sort(durationEntries);
        int middle = durationEntries.size() / 2;
        if (durationEntries.size() % 2 == 1) {
            return durationEntries.get(middle);
        } else {
            Duration total = durationEntries.get(middle - 1).plus(durationEntries.get(middle));
            return total.dividedBy(2);
        }
    }

    /**
     * Container for usage-entries of steps
     */
    static class StepContainer {
        private final String name;
        private final Map<String, Duration> aggregatedDurations = new HashMap<>();
        private final List<StepDuration> durations = new ArrayList<>();

        StepContainer(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        List<StepDuration> getDurations() {
            return durations;
        }

        void putAllAggregatedDurations(Map<String, Duration> aggregatedDurations) {
            this.aggregatedDurations.putAll(aggregatedDurations);
        }

        JsonObject toJson() {
            JsonObject stepContainer = Json.object();
            stepContainer.add("name", name);
            JsonObject aggregatedDurations = Json.object();
            for (Map.Entry<String, Duration> entry : this.aggregatedDurations.entrySet()) {
                aggregatedDurations.add(entry.getKey(), durationInSections(entry.getValue()));
            }
            stepContainer.add("aggregatedDurations", aggregatedDurations);
            JsonArray durations = Json.array();
            for (StepDuration duration : this.durations) {
                durations.add(duration.toJson());
            }
            stepContainer.add("durations", durations);
            return stepContainer;
        }
    }

    static class StepDuration {
        private final Duration duration;
        private final String location;

        StepDuration(Duration duration, String location) {
            this.duration = duration;
            this.location = location;
        }

        public Duration getDuration() {
            return duration;
        }

        public String getLocation() {
            return location;
        }

        JsonObject toJson() {
            JsonObject duration = Json.object();
            duration.add("duration", durationInSections(this.duration));
            duration.add("location", this.location);
            return duration;
        }
    }
}
