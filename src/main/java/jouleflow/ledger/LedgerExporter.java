package jouleflow.ledger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import jouleflow.grid.CarbonIntensityProvider;
import jouleflow.grid.CarbonIntensitySample;

/**
 * Writes the ledger and the grid curves out as JSON for the static dashboard to render.
 *
 * <p>Two files are produced:
 * <ul>
 *   <li>the whole {@code task_runs} table as a JSON array, and</li>
 *   <li>each region's next-24-hour intensity curve, keyed by region.</li>
 * </ul>
 * JSON is written by hand (with proper string escaping) rather than adding a dependency — the
 * shapes are small and fixed.
 */
public final class LedgerExporter {

    private LedgerExporter() {}

    /** Export the full ledger table to {@code out} as a JSON array of run objects. */
    public static void exportLedger(Ledger ledger, Path out) throws IOException {
        List<Ledger.Row> rows = ledger.allRuns();
        StringBuilder sb = new StringBuilder();
        sb.append("[\n");
        for (int i = 0; i < rows.size(); i++) {
            Ledger.Row r = rows.get(i);
            sb.append("  {")
              .append(str("task_name", r.taskName())).append(",")
              .append(str("started_at", r.startedAt())).append(",")
              .append(num("wall_clock_ms", r.wallClockMs())).append(",")
              .append(num("gc_pause_ms", r.gcPauseMs())).append(",")
              .append(num("jfr_event_count", r.jfrEventCount())).append(",")
              .append(num("energy_joules", r.energyJoules())).append(",")
              .append(str("energy_source", r.energySource())).append(",")
              .append(str("energy_note", r.energyNote())).append(",")
              .append(str("region", r.region())).append(",")
              .append(num("carbon_intensity_gco2_per_kwh", r.intensity())).append(",")
              .append(str("grid_data_source", r.gridDataSource())).append(",")
              .append(bool("is_live_grid_data", r.isLiveGridData())).append(",")
              .append(num("emissions_grams_co2", r.emissionsGramsCo2())).append(",")
              .append(num("baseline_intensity_gco2_per_kwh", r.baselineIntensity())).append(",")
              .append(num("baseline_emissions_grams_co2", r.baselineEmissionsGramsCo2())).append(",")
              .append(str("decision_action", r.decisionAction())).append(",")
              .append(str("decision_rationale", r.decisionRationale()))
              .append("}");
            if (i < rows.size() - 1) {
                sb.append(",");
            }
            sb.append("\n");
        }
        sb.append("]\n");
        write(out, sb.toString());
    }

    /**
     * Export each region's {@code hours}-long intensity curve (hour 0 = now) to {@code out}, as
     * {@code { dataSourceLabel, isLiveData, regions: { REGION: [ {hour, gramsCO2PerKwh}, ... ] } } }.
     */
    public static void exportRegionCurves(CarbonIntensityProvider provider, List<String> regions,
                                          int hours, Path out) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("{")
          .append(str("dataSourceLabel", provider.dataSourceLabel())).append(",")
          .append(bool("isLiveData", provider.isLiveData())).append(",")
          .append("\"regions\":{");
        for (int ri = 0; ri < regions.size(); ri++) {
            String region = regions.get(ri);
            sb.append(jsonString(region)).append(":[");

            double nowIntensity = provider.current(region).gramsCO2PerKwh();
            List<CarbonIntensitySample> future = provider.forecast(region, hours - 1);
            for (int h = 0; h < hours; h++) {
                double g = (h == 0) ? nowIntensity : future.get(h - 1).gramsCO2PerKwh();
                sb.append("{").append(num("hour", h)).append(",")
                  .append(num("gramsCO2PerKwh", g)).append("}");
                if (h < hours - 1) {
                    sb.append(",");
                }
            }
            sb.append("]");
            if (ri < regions.size() - 1) {
                sb.append(",");
            }
        }
        sb.append("}}\n");
        write(out, sb.toString());
    }

    // --- tiny JSON helpers ---

    private static String str(String key, String value) {
        return jsonString(key) + ":" + jsonString(value);
    }

    private static String num(String key, double value) {
        String v = (Double.isNaN(value) || Double.isInfinite(value)) ? "0" : trimNumber(value);
        return jsonString(key) + ":" + v;
    }

    private static String num(String key, long value) {
        return jsonString(key) + ":" + value;
    }

    private static String bool(String key, boolean value) {
        return jsonString(key) + ":" + value;
    }

    private static String trimNumber(double value) {
        if (value == Math.rint(value) && !Double.isInfinite(value)) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }

    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append("\"").toString();
    }

    private static void write(Path out, String content) throws IOException {
        if (out.getParent() != null) {
            Files.createDirectories(out.getParent());
        }
        Files.writeString(out, content, StandardCharsets.UTF_8);
    }
}
