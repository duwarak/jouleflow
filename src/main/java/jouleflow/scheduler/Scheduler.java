package jouleflow.scheduler;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

import jouleflow.grid.CarbonIntensityProvider;
import jouleflow.grid.CarbonIntensitySample;

/**
 * Decides, for a deadline-bounded task, whether running it later or elsewhere would cut its carbon
 * enough to be worth it.
 *
 * <p>The rule is deliberately conservative: doing nothing (running now, here) is the default, and
 * the scheduler only recommends a change when the cleaner option beats "now, here" by more than a
 * configurable {@code improvementThreshold} fraction. A tiny predicted improvement is not worth
 * delaying a job or moving it across regions.
 */
public final class Scheduler {

    private final CarbonIntensityProvider provider;
    private final double improvementThreshold;

    /**
     * @param provider             source of current/forecast carbon intensity
     * @param improvementThreshold minimum fractional improvement (e.g. 0.15 = 15%) required before
     *                             the scheduler will defer or relocate rather than run now
     */
    public Scheduler(CarbonIntensityProvider provider, double improvementThreshold) {
        this.provider = provider;
        this.improvementThreshold = improvementThreshold;
    }

    /**
     * @param homeRegion       where the task would run by default
     * @param candidateRegions regions it could be relocated to (home is ignored if included)
     * @param deadlineHours    how many hours the task may be deferred; {@code <= 0} forces RUN_NOW
     */
    public Decision decide(String homeRegion, List<String> candidateRegions, int deadlineHours) {
        double currentHome = provider.current(homeRegion).gramsCO2PerKwh();

        if (deadlineHours <= 0) {
            return new Decision(Decision.Action.RUN_NOW, homeRegion, currentHome, currentHome,
                    Duration.ZERO,
                    "Deadline leaves no room to wait — running now in " + homeRegion + " at "
                    + fmt(currentHome) + " gCO2/kWh.");
        }

        // Option A: defer within the home region to its cleanest hour inside the deadline window.
        double bestFuture = currentHome;
        int bestFutureHour = 0;
        List<CarbonIntensitySample> forecast = provider.forecast(homeRegion, deadlineHours);
        for (int i = 0; i < forecast.size(); i++) {
            double g = forecast.get(i).gramsCO2PerKwh();
            if (g < bestFuture) {
                bestFuture = g;
                bestFutureHour = i + 1; // forecast index 0 == 1 hour out
            }
        }

        // Option B: relocate now to the cleanest candidate region.
        String bestRegion = homeRegion;
        double bestRegionIntensity = currentHome;
        for (String r : candidateRegions) {
            if (r.equals(homeRegion)) {
                continue;
            }
            double g = provider.current(r).gramsCO2PerKwh();
            if (g < bestRegionIntensity) {
                bestRegionIntensity = g;
                bestRegion = r;
            }
        }

        double deferImprovement = improvement(currentHome, bestFuture);
        double relocateImprovement = improvement(currentHome, bestRegionIntensity);
        double best = Math.max(deferImprovement, relocateImprovement);

        if (best < improvementThreshold) {
            return new Decision(Decision.Action.RUN_NOW, homeRegion, currentHome, currentHome,
                    Duration.ZERO,
                    "No option beats running now by the " + pct(improvementThreshold)
                    + " threshold (best available saving " + pct(best) + ") — running now in "
                    + homeRegion + ".");
        }

        if (relocateImprovement >= deferImprovement) {
            return new Decision(Decision.Action.RELOCATE, bestRegion, bestRegionIntensity, currentHome,
                    Duration.ZERO,
                    "Relocating to " + bestRegion + " (" + fmt(bestRegionIntensity)
                    + " gCO2/kWh) cuts intensity " + pct(relocateImprovement) + " vs " + homeRegion
                    + " now (" + fmt(currentHome) + ").");
        }

        return new Decision(Decision.Action.DEFER, homeRegion, bestFuture, currentHome,
                Duration.ofHours(bestFutureHour),
                "Deferring " + bestFutureHour + "h in " + homeRegion + " reaches " + fmt(bestFuture)
                + " gCO2/kWh, " + pct(deferImprovement) + " cleaner than running now ("
                + fmt(currentHome) + ").");
    }

    /** Fractional improvement of {@code candidate} below {@code base}; 0 if base is non-positive. */
    private static double improvement(double base, double candidate) {
        if (base <= 0) {
            return 0;
        }
        return (base - candidate) / base;
    }

    private static String fmt(double v) {
        return String.format(Locale.US, "%.0f", v);
    }

    private static String pct(double fraction) {
        return String.format(Locale.US, "%.0f%%", fraction * 100);
    }
}
