package jouleflow.grid;

import java.util.List;

/**
 * Source of grid carbon-intensity data, live or illustrative.
 *
 * <p>{@link #dataSourceLabel()} and {@link #isLiveData()} let every downstream consumer state
 * plainly where a number came from — the same honesty discipline the energy layer applies.
 */
public interface CarbonIntensityProvider {

    /** Human-readable label for the data source (shown in the ledger and dashboard). */
    String dataSourceLabel();

    /** @return {@code true} only if these numbers come from a real, live grid feed. */
    boolean isLiveData();

    /** Current carbon intensity for a region. */
    CarbonIntensitySample current(String region);

    /**
     * Forecast intensity for the next {@code hours} hours, one sample per hour, offsets 1..hours
     * (strictly future; use {@link #current(String)} for "now").
     */
    List<CarbonIntensitySample> forecast(String region, int hours);
}
