package jouleflow.grid;

import java.time.Instant;

/**
 * One point on a grid's carbon-intensity curve.
 *
 * @param region          grid region identifier
 * @param timestamp       the instant this intensity applies to
 * @param gramsCO2PerKwh  grid carbon intensity in grams CO2-equivalent per kWh
 */
public record CarbonIntensitySample(String region, Instant timestamp, double gramsCO2PerKwh) {}
