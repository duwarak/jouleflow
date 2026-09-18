package jouleflow.grid;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An <strong>illustrative</strong> carbon-intensity provider — <em>not live data.</em>
 *
 * <p>It generates a smooth diurnal curve for four made-up regions, each a cosine with its trough
 * (cleanest hour) at that region's solar-peak hour and its ridge (dirtiest hour) twelve hours
 * later. The four baselines and swings are chosen to span realistic grid archetypes:
 *
 * <ul>
 *   <li>{@code NORDIC}  — hydro/nuclear, ~70 gCO2/kWh baseline, almost flat.</li>
 *   <li>{@code CAISO}   — solar+wind+gas, ~300 baseline with a big midday dip.</li>
 *   <li>{@code INDIA_S} — solar+gas, ~220 baseline, moderate dip.</li>
 *   <li>{@code POLAND}  — coal-heavy, ~740 baseline.</li>
 * </ul>
 *
 * <p><strong>These numbers are illustrative and must not be used for compliance reporting or any
 * real carbon accounting.</strong> For real figures wire in {@link LiveGridApiProvider} with an
 * Electricity Maps API key. The shape is real enough to exercise the scheduler; the values are not.
 */
public final class SampleGridProvider implements CarbonIntensityProvider {

    /** @param baseline mean intensity; @param amplitude peak-to-mean swing; @param solarPeakHour cleanest hour. */
    private record Region(String name, double baseline, double amplitude, int solarPeakHour) {
        double intensityAtHour(double hourOfDay) {
            double phase = 2 * Math.PI * (hourOfDay - solarPeakHour) / 24.0;
            // cos is +1 at the solar-peak hour, so this is the trough (cleanest) there.
            return baseline - amplitude * Math.cos(phase);
        }
    }

    private final Map<String, Region> regions = new LinkedHashMap<>();

    public SampleGridProvider() {
        add(new Region("NORDIC", 70, 20, 13));
        add(new Region("CAISO", 300, 180, 13));
        add(new Region("INDIA_S", 220, 90, 12));
        add(new Region("POLAND", 740, 120, 12));
    }

    private void add(Region r) {
        regions.put(r.name(), r);
    }

    /** The region identifiers this provider knows about, in a stable order. */
    public List<String> regionNames() {
        return List.copyOf(regions.keySet());
    }

    @Override
    public String dataSourceLabel() {
        return "SampleGridProvider (illustrative diurnal curve — NOT live data)";
    }

    @Override
    public boolean isLiveData() {
        return false;
    }

    @Override
    public CarbonIntensitySample current(String region) {
        Region r = require(region);
        Instant now = Instant.now();
        double hour = hourOfDay(now);
        return new CarbonIntensitySample(region, now, r.intensityAtHour(hour));
    }

    @Override
    public List<CarbonIntensitySample> forecast(String region, int hours) {
        Region r = require(region);
        Instant now = Instant.now();
        double baseHour = hourOfDay(now);
        List<CarbonIntensitySample> out = new ArrayList<>();
        for (int i = 1; i <= hours; i++) {
            Instant t = now.plusSeconds(i * 3600L);
            double hour = (baseHour + i) % 24.0;
            out.add(new CarbonIntensitySample(region, t, r.intensityAtHour(hour)));
        }
        return out;
    }

    private Region require(String region) {
        Region r = regions.get(region);
        if (r == null) {
            throw new IllegalArgumentException("Unknown sample region: " + region
                    + " (known: " + regionNames() + ")");
        }
        return r;
    }

    private static double hourOfDay(Instant instant) {
        LocalDateTime ldt = LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
        return ldt.getHour() + ldt.getMinute() / 60.0;
    }
}
