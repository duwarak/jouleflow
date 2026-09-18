package jouleflow.energy;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

/**
 * Estimates energy when no hardware counter is available.
 *
 * <p>It reads cumulative JVM CPU time from {@link ThreadMXBean} and multiplies by an assumed
 * active power draw per core. This is a <strong>proxy, not a measurement</strong>: it ignores DRAM,
 * GPU, uncore, package idle draw and the real (voltage- and frequency-dependent) power curve. Every
 * reading it produces is tagged {@link EnergyReading.Source#ESTIMATED} with a note that says so, so
 * an estimate can never be mistaken for a RAPL measurement downstream.
 *
 * <p>The multiplier is deliberately a single, visible constant rather than a hidden model — the
 * honesty of the label matters more than the precision of the number.
 */
public final class FallbackEnergyReader implements EnergyReader {

    /** Assumed active power drawn by one fully-busy core, in watts. A rough, honest placeholder. */
    public static final double ASSUMED_WATTS_PER_CORE = 15.0;

    private final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
    private final boolean cpuTimeSupported;

    public FallbackEnergyReader() {
        this.cpuTimeSupported = threads.isThreadCpuTimeSupported();
        if (cpuTimeSupported && !threads.isThreadCpuTimeEnabled()) {
            threads.setThreadCpuTimeEnabled(true);
        }
    }

    /** Always false — this reader is explicitly a software estimate, never hardware-backed. */
    @Override
    public boolean isHardwareBacked() {
        return false;
    }

    @Override
    public EnergyReading readCumulativeMicrojoules() {
        if (!cpuTimeSupported) {
            return EnergyReading.unavailable("JVM does not support per-thread CPU time on this platform.");
        }
        long cpuNanos = 0;
        for (long id : threads.getAllThreadIds()) {
            long t = threads.getThreadCpuTime(id); // -1 if the thread died between calls
            if (t > 0) {
                cpuNanos += t;
            }
        }
        // microjoules = watts * cpu_seconds * 1e6 = watts * cpu_nanos / 1000
        long microjoules = (long) (ASSUMED_WATTS_PER_CORE * cpuNanos / 1000.0);
        return EnergyReading.estimated(
                microjoules,
                "CPU-time proxy: JVM thread CPU time x " + ASSUMED_WATTS_PER_CORE
                + " W/core. NOT a hardware measurement — RAPL was unavailable.");
    }
}
