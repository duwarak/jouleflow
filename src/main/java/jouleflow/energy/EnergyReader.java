package jouleflow.energy;

/**
 * Reads cumulative energy consumed by this process/host.
 *
 * <p>Implementations are polled twice around a task (before and after); the caller diffs the two
 * {@code microjoules} values to attribute energy to that task. The {@link EnergyReading} returned
 * always carries a {@link EnergyReading.Source} tag so the caller can propagate honest provenance.
 */
public interface EnergyReader {

    /**
     * @return {@code true} only if this reader is backed by real hardware energy counters.
     *         When {@code false}, callers should expect {@link EnergyReading.Source#ESTIMATED}
     *         figures and fall back accordingly.
     */
    boolean isHardwareBacked();

    /**
     * @return the current cumulative energy counter. Diff two of these to measure a task.
     *         Never {@code null}; may be {@link EnergyReading#unavailable(String)}.
     */
    EnergyReading readCumulativeMicrojoules();
}
