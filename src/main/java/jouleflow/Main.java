package jouleflow;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import jouleflow.attribution.JfrTaskProfiler;
import jouleflow.energy.EnergyReader;
import jouleflow.energy.EnergyReading;
import jouleflow.energy.FallbackEnergyReader;
import jouleflow.energy.RaplEnergyReader;
import jouleflow.grid.SampleGridProvider;
import jouleflow.ledger.Ledger;
import jouleflow.ledger.LedgerExporter;
import jouleflow.scheduler.Decision;
import jouleflow.scheduler.Scheduler;

/**
 * End-to-end demo: profile five workloads under real JFR, attribute energy (honestly tagged),
 * ask the carbon-aware scheduler what to do with each, record every run to the SQLite ledger,
 * print a summary, and export the two JSON files the dashboard renders.
 */
public final class Main {

    private static final double JOULES_PER_KWH = 3_600_000.0;

    /** Keeps the JIT from optimising the demo workloads away. */
    private static long blackhole = 0;

    /** A demo task: a name, a deadline, the regions it may move to, and the work itself. */
    private record Workload(String name, int deadlineHours, List<String> candidateRegions, Runnable work) {}

    public static void main(String[] args) throws Exception {
        System.out.println("=== JouleFlow — carbon-aware JVM scheduler (demo run) ===\n");

        // 1. Pick an energy reader: real hardware if we can, an honest estimate otherwise.
        RaplEnergyReader rapl = new RaplEnergyReader();
        EnergyReader reader = rapl.isHardwareBacked() ? rapl : new FallbackEnergyReader();
        if (rapl.isHardwareBacked()) {
            System.out.println("Energy source : Intel RAPL hardware counters (MEASURED).");
        } else {
            System.out.println("Energy source : RAPL unavailable -> CPU-time proxy (ESTIMATED).");
            System.out.println("                powercap/RAPL is absent or masked here (typical in containers and");
            System.out.println("                cloud VMs since the PLATYPUS CVEs, CVE-2020-8694/8695). The energy");
            System.out.println("                numbers below are estimates, and every row is tagged as such.");
        }

        // 2. Grid data + scheduler.
        SampleGridProvider grid = new SampleGridProvider();
        Scheduler scheduler = new Scheduler(grid, 0.15);
        String homeRegion = "INDIA_S";
        List<String> allRegions = grid.regionNames();
        System.out.println("Grid data     : " + grid.dataSourceLabel());
        System.out.println("Home region   : " + homeRegion + "   |   defer/relocate threshold: 15%\n");

        // 3. Fresh ledger for each demo run.
        Path dbPath = Path.of("target", "jouleflow.db");
        Files.createDirectories(dbPath.getParent());
        Files.deleteIfExists(dbPath);
        JfrTaskProfiler profiler = new JfrTaskProfiler();

        List<Workload> workloads = List.of(
                new Workload("cpu-crunch", 0, allRegions,
                        () -> cpuBurn(60_000_000L)),
                new Workload("batch-etl", 6, allRegions,
                        () -> { allocateChurn(400, 256); cpuBurn(10_000_000L); }),
                new Workload("ml-train", 12, List.of("INDIA_S"),
                        () -> { cpuBurn(90_000_000L); allocateChurn(200, 256); }),
                new Workload("log-rollup", 3, List.of("INDIA_S", "POLAND"),
                        () -> cpuBurn(15_000_000L)),
                new Workload("nightly-report", 8, allRegions,
                        () -> { cpuBurn(30_000_000L); allocateChurn(150, 128); }));

        try (Ledger ledger = new Ledger(dbPath.toString())) {
            System.out.printf("%-15s %10s %-10s %-9s %-8s %8s %13s %14s%n",
                    "TASK", "ENERGY(J)", "SOURCE", "ACTION", "REGION", "JFR-EVT", "EMIT(mgCO2)", "AVOIDED(mgCO2)");
            System.out.println("-".repeat(96));

            for (Workload w : workloads) {
                Instant startedAt = Instant.now();

                long before = reader.readCumulativeMicrojoules().microjoules();
                JfrTaskProfiler.Result prof = profiler.profile(w.name(), w.work());
                EnergyReading afterReading = reader.readCumulativeMicrojoules();
                long deltaUj = Math.max(0, afterReading.microjoules() - before);

                // Attribute the diff to this task, preserving the reader's honest source tag.
                EnergyReading energy = new EnergyReading(
                        afterReading.source(), deltaUj, afterReading.domainsRead(), afterReading.note());

                Decision d = scheduler.decide(homeRegion, w.candidateRegions(), w.deadlineHours());
                double kwh = energy.joules() / JOULES_PER_KWH;
                double emitMg = kwh * d.chosenIntensity() * 1000.0;
                double baseMg = kwh * d.currentRegionIntensity() * 1000.0;

                ledger.recordRun(w.name(), startedAt, prof, energy,
                        d.chosenRegion(), d.chosenIntensity(),
                        grid.dataSourceLabel(), grid.isLiveData(),
                        d.currentRegionIntensity(), d);

                System.out.printf("%-15s %10.3f %-10s %-9s %-8s %8d %13.5f %14.5f%n",
                        w.name(), energy.joules(), energy.source(), d.action(),
                        d.chosenRegion(), prof.eventCount(), emitMg, baseMg - emitMg);
            }

            System.out.println("-".repeat(96));
            System.out.printf("%nDecisions: RUN_NOW=%d  DEFER=%d  RELOCATE=%d%n",
                    ledger.countByDecision("RUN_NOW"),
                    ledger.countByDecision("DEFER"),
                    ledger.countByDecision("RELOCATE"));

            System.out.println("\nWhy each task was scheduled that way:");
            for (Ledger.Row r : ledger.allRuns()) {
                System.out.println("  - " + r.taskName() + " [" + r.energySource() + "]: " + r.decisionRationale());
            }

            // 4. Export the two JSON files the dashboard reads.
            Path dash = Path.of("dashboard");
            LedgerExporter.exportLedger(ledger, dash.resolve("ledger_export.json"));
            LedgerExporter.exportRegionCurves(grid, allRegions, 24, dash.resolve("region_curves.json"));

            System.out.println("\nExported -> dashboard/ledger_export.json, dashboard/region_curves.json");
            System.out.println("View the dashboard:  cd dashboard && python3 -m http.server 8000");
            System.out.println("                     then open http://localhost:8000/");
            System.out.println("\nHonesty note: energy figures are " + (rapl.isHardwareBacked() ? "RAPL measurements" : "CPU-time ESTIMATES")
                    + " and grid intensities are illustrative sample data, not live measurements.");

            // Consume the blackhole so the workloads can't be dead-code-eliminated.
            if (blackhole == Long.MIN_VALUE) {
                System.out.println(blackhole);
            }
        }
    }

    // --- demo workloads: real CPU and allocation so JFR and CPU-time have something to measure ---

    private static void cpuBurn(long iterations) {
        long acc = 0;
        for (long i = 0; i < iterations; i++) {
            acc += (acc * 31 + i) ^ (i << 7);
            acc = Long.rotateLeft(acc, 3);
        }
        blackhole += acc;
    }

    private static void allocateChurn(int chunks, int chunkKb) {
        long acc = 0;
        for (int i = 0; i < chunks; i++) {
            byte[] b = new byte[chunkKb * 1024];
            b[0] = (byte) i;
            b[b.length - 1] = (byte) i;
            acc += b.length;
        }
        blackhole += acc;
    }
}
