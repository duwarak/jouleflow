package jouleflow.energy;

import java.util.List;

/**
 * An immutable energy figure that always carries the honest provenance of the number.
 *
 * <p>The whole point of JouleFlow is that an <em>estimate</em> must never be presented as a
 * <em>measurement</em> anywhere downstream — not in the ledger, not on the dashboard, not in logs.
 * Every energy number in the system is one of these three, tagged at the source:
 *
 * <ul>
 *   <li>{@link Source#MEASURED} — read from real hardware counters (Intel RAPL).</li>
 *   <li>{@link Source#ESTIMATED} — derived from a proxy (CPU time), clearly not a measurement.</li>
 *   <li>{@link Source#UNAVAILABLE} — no figure could be produced.</li>
 * </ul>
 *
 * @param source       provenance tag — never {@code null}
 * @param microjoules  energy in microjoules (0 when {@link Source#UNAVAILABLE})
 * @param domainsRead  the hardware domains that contributed (e.g. {@code package-0}, {@code core});
 *                     empty for estimates and unavailable readings
 * @param note         human-readable explanation of how this number was obtained
 */
public record EnergyReading(Source source, long microjoules, List<String> domainsRead, String note) {

    /** Provenance of an energy figure. */
    public enum Source {
        /** Read from real hardware energy counters. */
        MEASURED,
        /** Derived from a proxy such as CPU time — not a hardware measurement. */
        ESTIMATED,
        /** No figure could be produced. */
        UNAVAILABLE
    }

    /** Compact constructor: defensively copy the domain list and reject a null source. */
    public EnergyReading {
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
        domainsRead = domainsRead == null ? List.of() : List.copyOf(domainsRead);
    }

    /** @return the energy in joules (microjoules / 1_000_000). */
    public double joules() {
        return microjoules / 1_000_000.0;
    }

    /** A real hardware measurement from the named RAPL domains. */
    public static EnergyReading measured(long microjoules, List<String> domainsRead, String note) {
        return new EnergyReading(Source.MEASURED, microjoules, domainsRead, note);
    }

    /** A proxy-derived estimate. The note must explain what the proxy is. */
    public static EnergyReading estimated(long microjoules, String note) {
        return new EnergyReading(Source.ESTIMATED, microjoules, List.of(), note);
    }

    /** No energy figure available. */
    public static EnergyReading unavailable(String note) {
        return new EnergyReading(Source.UNAVAILABLE, 0L, List.of(), note);
    }
}
