package jouleflow;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import jouleflow.grid.CarbonIntensityProvider;
import jouleflow.grid.CarbonIntensitySample;
import jouleflow.scheduler.Decision;
import jouleflow.scheduler.Scheduler;

/**
 * Exercises the four decision branches with a hand-built mock provider, so the test asserts the
 * scheduler's logic and never depends on the real diurnal cosine math in SampleGridProvider.
 */
class SchedulerTest {

    /** A fully controllable CarbonIntensityProvider: you set exactly what current/forecast return. */
    private static final class MockProvider implements CarbonIntensityProvider {
        final Map<String, Double> current = new HashMap<>();
        final Map<String, List<Double>> forecast = new HashMap<>();

        @Override public String dataSourceLabel() { return "mock"; }
        @Override public boolean isLiveData() { return false; }

        @Override public CarbonIntensitySample current(String region) {
            return new CarbonIntensitySample(region, Instant.EPOCH,
                    current.getOrDefault(region, 500.0));
        }

        @Override public List<CarbonIntensitySample> forecast(String region, int hours) {
            List<Double> vals = forecast.getOrDefault(region, List.of());
            List<CarbonIntensitySample> out = new ArrayList<>();
            for (int i = 0; i < hours && i < vals.size(); i++) {
                out.add(new CarbonIntensitySample(region, Instant.EPOCH.plusSeconds((i + 1) * 3600L), vals.get(i)));
            }
            return out;
        }
    }

    @Test
    void runsNowWhenDeadlineIsZero() {
        MockProvider grid = new MockProvider();
        grid.current.put("HOME", 500.0);
        grid.current.put("GREEN", 50.0); // very clean, but no time to move
        grid.forecast.put("HOME", List.of(10.0, 10.0, 10.0)); // very clean later, but no time to wait

        Decision d = new Scheduler(grid, 0.15).decide("HOME", List.of("HOME", "GREEN"), 0);

        assertEquals(Decision.Action.RUN_NOW, d.action());
        assertEquals("HOME", d.chosenRegion());
        assertEquals(Duration.ZERO, d.deferBy());
    }

    @Test
    void defersWhenALaterHourClearsTheThreshold() {
        MockProvider grid = new MockProvider();
        grid.current.put("HOME", 500.0);
        grid.forecast.put("HOME", List.of(490.0, 300.0, 480.0)); // hour 2 = 300 -> 40% cleaner
        grid.current.put("OTHER", 495.0);                        // relocating saves only 1%

        Decision d = new Scheduler(grid, 0.15).decide("HOME", List.of("HOME", "OTHER"), 3);

        assertEquals(Decision.Action.DEFER, d.action());
        assertEquals("HOME", d.chosenRegion());
        assertEquals(Duration.ofHours(2), d.deferBy());
        assertEquals(300.0, d.chosenIntensity(), 1e-9);
    }

    @Test
    void relocatesWhenARegionBeatsTheBestDeferredHour() {
        MockProvider grid = new MockProvider();
        grid.current.put("HOME", 500.0);
        grid.forecast.put("HOME", List.of(480.0, 470.0, 490.0)); // best defer only ~6%
        grid.current.put("GREEN", 200.0);                        // relocating saves 60%

        Decision d = new Scheduler(grid, 0.15).decide("HOME", List.of("HOME", "GREEN"), 3);

        assertEquals(Decision.Action.RELOCATE, d.action());
        assertEquals("GREEN", d.chosenRegion());
        assertEquals(200.0, d.chosenIntensity(), 1e-9);
        assertEquals(Duration.ZERO, d.deferBy());
    }

    @Test
    void runsNowWhenNothingClearsTheThreshold() {
        MockProvider grid = new MockProvider();
        grid.current.put("HOME", 500.0);
        grid.forecast.put("HOME", List.of(490.0, 495.0, 492.0)); // best defer ~2%
        grid.current.put("OTHER", 480.0);                        // relocate ~4%

        Decision d = new Scheduler(grid, 0.15).decide("HOME", List.of("HOME", "OTHER"), 3);

        assertEquals(Decision.Action.RUN_NOW, d.action());
        assertEquals("HOME", d.chosenRegion());
    }
}
