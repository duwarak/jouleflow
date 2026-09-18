package jouleflow.scheduler;

import java.time.Duration;

/**
 * The scheduler's verdict for one task: run it now, wait, or move it — and why.
 *
 * @param action                 what to do
 * @param chosenRegion           region the task should run in
 * @param chosenIntensity        carbon intensity (gCO2/kWh) at the chosen time/place
 * @param currentRegionIntensity intensity if the task ran now, in its home region (the baseline)
 * @param deferBy                how long to wait ({@link Duration#ZERO} unless {@link Action#DEFER})
 * @param rationale              human-readable explanation of the decision
 */
public record Decision(Action action, String chosenRegion, double chosenIntensity,
                       double currentRegionIntensity, Duration deferBy, String rationale) {

    /** What the scheduler decided to do with a task. */
    public enum Action {
        /** Run immediately, here. */
        RUN_NOW,
        /** Wait {@link Decision#deferBy()} and run here when the grid is cleaner. */
        DEFER,
        /** Run now, but in {@link Decision#chosenRegion()} instead of home. */
        RELOCATE
    }
}
