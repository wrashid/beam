package beam.analysis.plots;

import beam.agentsim.events.ModeChoiceEvent;
import beam.agentsim.events.ReplanningEvent;
import beam.sim.metrics.MetricsSupport;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.data.category.CategoryDataset;
import org.jfree.data.general.DatasetUtilities;
import org.matsim.api.core.v01.events.Event;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import static beam.sim.metrics.Metrics.ShortLevel;

public class RealizedModeStats implements IGraphStats, MetricsSupport {


    private static Map<Integer, Map<String, Integer>> hourModeFrequency = new HashMap<>();
    private static final String graphTitle = "Realized Mode Histogram";
    private static final String xAxisTitle = "Hour";
    private static final String yAxisTitle = "# mode chosen";
    private static final String fileName = "realized_mode";
    private static List<String> personIdList = new ArrayList<>();
    private Logger log = LoggerFactory.getLogger(this.getClass());
    private static Map<ModePerson, Integer> hourPerson = new HashMap<>();

    @Override
    public void processStats(Event event) {
        processRealizedMode(event);
    }

    @Override
    public void createGraph(IterationEndsEvent event) throws IOException {

        Map<String, String> tags = new HashMap<>();
        tags.put("stats-type", "aggregated-mode-choice");
        hourModeFrequency.values().stream().flatMap(x -> x.entrySet().stream())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a + b))
                .forEach((mode, count) -> countOccurrenceJava(mode, count, ShortLevel(), tags));

        CategoryDataset modesFrequencyDataset = buildModesFrequencyDatasetForGraph();
        if (modesFrequencyDataset != null)
            createModesFrequencyGraph(modesFrequencyDataset, event.getIteration());

        writeToCSV(event);
    }

    @Override
    public void createGraph(IterationEndsEvent event, String graphType) throws IOException {

    }

    @Override
    public void resetStats() {
        hourModeFrequency.clear();
        personIdList.clear();
        hourPerson.clear();
    }


    private void processRealizedMode(Event event) {
        int hour = GraphsStatsAgentSimEventsListener.getEventHour(event.getTime());
        Map<String, Integer> hourData = hourModeFrequency.get(hour);
        if (event.getEventType() == ModeChoiceEvent.EVENT_TYPE) {

            String mode = event.getAttributes().get(ModeChoiceEvent.ATTRIBUTE_MODE);
            String personId = event.getAttributes().get(ModeChoiceEvent.ATTRIBUTE_PERSON_ID);
            Map<String, String> tags = new HashMap<>();
            tags.put("stats-type", "mode-choice");
            tags.put("hour", "" + (hour + 1));
            countOccurrenceJava(mode, 1, ShortLevel(), tags);
            if (personIdList.contains(personId)) {
                personIdList.remove(personId);
                return;
            }

            Integer frequency = 1;
            if (hourData != null) {
                frequency = hourData.get(mode);
                if (frequency != null) {
                    frequency++;
                } else {
                    frequency = 1;
                }
            } else {
                hourData = new HashMap<>();
            }
            hourData.put(mode, frequency);

            hourPerson.put(new ModePerson(mode, personId), hour);
            hourModeFrequency.put(hour, hourData);
        }
        if (event.getEventType() == ReplanningEvent.EVENT_TYPE) {
            Map<String, String> attributes = event.getAttributes();
            if (attributes != null) {
                String person = attributes.get(ReplanningEvent.ATTRIBUTE_PERSON);
                personIdList.add(person);
                int modeHour = -1;
                String mode = null;

                for (ModePerson mP : hourPerson.keySet()) {
                    if (person.equals(mP.getPerson())) {
                        modeHour = hourPerson.get(mP);
                        mode = mP.getMode();
                    }
                }

                if (mode != null && modeHour != -1) {
                    hourPerson.remove(new ModePerson(mode,person));
                    Integer replanning = 1;
                    if (hourData != null) {
                        replanning = hourData.get("others");
                        if (replanning != null) {
                            replanning++;
                        } else {
                            replanning = 1;
                        }
                    } else {
                        hourData = new HashMap<>();
                    }
                    hourData.put("others", replanning);
                    Map<String, Integer> hourMode = hourModeFrequency.get(modeHour);
                    if (hourMode != null) {
                        Integer frequency = hourMode.get(mode);
                        if (frequency != null) {
                            frequency--;
                            hourMode.put(mode, frequency);

                        }
                    }
                }
            }
        }
    }

    private double[] getHoursDataPerOccurrenceAgainstMode(String modeChosen, int maxHour) {
        double[] modeOccurrencePerHour = new double[maxHour + 1];
        int index = 0;
        for (int hour = 0; hour <= maxHour; hour++) {
            Map<String, Integer> hourData = hourModeFrequency.get(hour);
            if (hourData != null) {
                modeOccurrencePerHour[index] = hourData.get(modeChosen) == null ? 0 : hourData.get(modeChosen);
            } else {
                modeOccurrencePerHour[index] = 0;
            }
            index = index + 1;
        }
        return modeOccurrencePerHour;
    }

    private double[][] buildModesFrequencyDataset() {

        Set<String> modeChoosen = getModesChosen();

        List<Integer> hoursList = GraphsStatsAgentSimEventsListener.getSortedIntegerList(hourModeFrequency.keySet());
        List<String> modesChosenList = GraphsStatsAgentSimEventsListener.getSortedStringList(modeChoosen);
        if (0 == hoursList.size())
            return null;
        int maxHour = hoursList.get(hoursList.size() - 1);
        double[][] dataset = new double[modeChoosen.size()][maxHour + 1];
        for (int i = 0; i < modesChosenList.size(); i++) {
            String modeChosen = modesChosenList.get(i);
            dataset[i] = getHoursDataPerOccurrenceAgainstMode(modeChosen, maxHour);
        }
        return dataset;
    }

    private CategoryDataset buildModesFrequencyDatasetForGraph() {
        CategoryDataset categoryDataset = null;
        double[][] dataset = buildModesFrequencyDataset();
        if (dataset != null)
            categoryDataset = DatasetUtilities.createCategoryDataset("Mode ", "", dataset);

        return categoryDataset;
    }

    private void createModesFrequencyGraph(CategoryDataset dataset, int iterationNumber) throws IOException {
        boolean legend = true;
        final JFreeChart chart = GraphUtils.createStackedBarChartWithDefaultSettings(dataset, graphTitle, xAxisTitle, yAxisTitle, fileName, legend);
        CategoryPlot plot = chart.getCategoryPlot();
        List<String> modesChosenList = new ArrayList<>();
        modesChosenList.addAll(getModesChosen());
        Collections.sort(modesChosenList);
        GraphUtils.plotLegendItems(plot, modesChosenList, dataset.getRowCount());
        String graphImageFile = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getIterationFilename(iterationNumber, fileName + ".png");
        GraphUtils.saveJFreeChartAsPNG(chart, graphImageFile, GraphsStatsAgentSimEventsListener.GRAPH_WIDTH, GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT);
    }

    //This is used for removing columns if all entries is 0
    private Set<String> getModesChosen() {

        Set<String> modes = new TreeSet<>();
        Map<String, Integer> modeCountBucket = new HashMap<>();
        hourModeFrequency.keySet().stream().forEach(hour -> hourModeFrequency.get(hour).keySet().stream().
                forEach(mode -> {
                    Integer count = modeCountBucket.get(mode);
                    Map<String, Integer> modeFrequency = hourModeFrequency.get(hour);
                    if (modeFrequency != null) {
                        if (count != null) {
                            modeCountBucket.put(mode, count + modeFrequency.get(mode));
                        } else {
                            modeCountBucket.put(mode, modeFrequency.get(mode));
                        }
                    }
                }));

        modeCountBucket.keySet().stream().forEach(mode -> {
            if (modeCountBucket.get(mode) != null && modeCountBucket.get(mode) != 0) {
                modes.add(mode);
            }
        });
        return modes;
    }

    private void writeToCSV(IterationEndsEvent event) {

        String csvFileName = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getIterationFilename(event.getIteration(), fileName + ".csv");

        try (final BufferedWriter out = new BufferedWriter(new FileWriter(new File(csvFileName)))) {

            Set<String> modes = getModesChosen();

            String heading = modes.stream().reduce((x, y) -> x + "," + y).get();
            out.write("hours," + heading);
            out.newLine();

            int max = hourModeFrequency.keySet().stream().mapToInt(x -> x).max().getAsInt();

            for (int hour = 0; hour <= max; hour++) {
                Map<String, Integer> modeCount = hourModeFrequency.get(hour);
                StringBuilder builder = new StringBuilder(hour + 1 + "");
                if (modeCount != null) {
                    for (String mode : modes) {
                        if (modeCount.get(mode) != null) {
                            builder.append("," + modeCount.get(mode));
                        } else {
                            builder.append(",0");
                        }
                    }
                } else {
                    for (String mode : modes) {
                        builder.append(",0");
                    }
                }
                out.write(builder.toString());
                out.newLine();
            }
            out.flush();
        } catch (IOException e) {
            log.error("CSV generation failed.", e);
        }
    }

    class ModePerson {
        private String mode;
        private String person;

        public ModePerson(String mode, String person) {
            this.mode = mode;
            this.person = person;
        }

        public String getMode() {
            return mode;
        }

        public String getPerson() {
            return person;
        }

        @Override
        public String toString() {
            return "[mode: "+mode+", person: "+person+"]";
        }

        @Override
        public boolean equals(Object o) {

            if (o == this) return true;
            if (!(o instanceof ModePerson)) {
                return false;
            }

            ModePerson modePerson = (ModePerson) o;

            return modePerson.person.equals(person) &&
                    modePerson.mode.equals(mode);
        }

        @Override
        public int hashCode() {
            int result = 17;
            result = 31 * result + person.hashCode();
            result = 31 * result + mode.hashCode();
            return result;
        }
    }
}

