# JouleFlow

**Carbon-aware JVM task scheduler with honest energy accounting.**

JouleFlow profiles a JVM task with real JDK Flight Recorder, attributes energy to it from hardware counters when they are available (and an *honestly labelled* estimate when they are not), asks whether running the task later or in a cleaner grid region would meaningfully cut its carbon, records every run to an auditable SQLite ledger, and reports the result — including how much CO₂ was avoided versus naively running everything right now. Its defining rule: **an estimate is never presented as a measurement**, anywhere downstream.

---

## Architecture

```
                          ┌───────────────────────────┐
  workload (Runnable) ──▶ │  JfrTaskProfiler          │  real jdk.jfr.Recording
                          │  (attribution/)           │  → event count, GC pause
                          └────────────┬──────────────┘
                                       │
     energy before/after              ▼
  ┌──────────────────┐        ┌────────────────┐        ┌──────────────────────┐
  │ RaplEnergyReader │──has?─▶ │  EnergyReading │        │ CarbonIntensityProvider
  │ FallbackReader   │ tag it  │  MEASURED /    │        │  SampleGridProvider   │ illustrative
  │ (energy/)        │        │  ESTIMATED     │        │  LiveGridApiProvider  │ Electricity Maps
  └──────────────────┘        └───────┬────────┘        └───────────┬──────────┘
                                      │                             │ current + forecast
                                      ▼                             ▼
                               ┌─────────────────────────────────────────┐
                               │  Scheduler.decide(...)  (scheduler/)      │
                               │  RUN_NOW · DEFER · RELOCATE + rationale    │
                               └────────────────────┬──────────────────────┘
                                                    ▼
                               ┌─────────────────────────────────────────┐
                               │  Ledger (SQLite, ledger/)  +  Exporter    │
                               │  task_runs table → ledger_export.json     │
                               │  region curves   → region_curves.json     │
                               └────────────────────┬──────────────────────┘
                                                    ▼
                                        dashboard/index.html (Chart.js)
```

Each energy number carries a `Source` tag (`MEASURED` / `ESTIMATED` / `UNAVAILABLE`) from the moment it is created; each grid number carries a `dataSourceLabel()` and `isLiveData()` flag. The ledger persists both, so the provenance survives all the way to the dashboard badges.

---

## Requirements

- **JDK 21+** — a full JDK, not a JRE (the profiler uses `jdk.jfr.*`).
- **Maven 3.9+**.

## Build & test

```bash
mvn clean test          # runs the JUnit 5 tests
mvn clean package       # builds the fat jar: target/jouleflow.jar
```

## Run the demo

```bash
mvn exec:java -Dexec.mainClass=jouleflow.Main
# or, after packaging:
java -jar target/jouleflow.jar
```

It profiles five sample workloads, prints a summary table plus each scheduling decision's rationale, writes `target/jouleflow.db`, and exports the two dashboard JSON files.

## View the dashboard

The dashboard fetches `ledger_export.json` and `region_curves.json`, and browsers block `fetch()` over `file://` — so serve the folder rather than double-clicking:

```bash
cd dashboard
python3 -m http.server 8000
# open http://localhost:8000/
```

---

## Honesty notes

These are the caveats that make the numbers trustworthy. They are deliberately loud.

- **RAPL is usually unavailable, and that's expected.** `RaplEnergyReader` reads Intel RAPL counters from `/sys/class/powercap/intel-rapl:*/energy_uj`. Those are readable only by root on bare-metal Linux, and are masked or absent in most containers and cloud VMs following the PLATYPUS side-channel attacks (CVE-2020-8694 / CVE-2020-8695). When RAPL is unavailable, JouleFlow falls back to `FallbackEnergyReader`, which estimates energy from JVM CPU time × an assumed watts-per-core constant and tags **every** reading `ESTIMATED`. It is a proxy, not a measurement — it ignores DRAM, GPU, uncore and idle draw.
- **The sample grid is illustrative, not real.** `SampleGridProvider` generates a smooth diurnal cosine curve for four made-up regions. The *shape* is realistic enough to exercise the scheduler; the *values* are invented and must not be used for compliance or real carbon accounting. For real data, use `LiveGridApiProvider` with a valid [Electricity Maps](https://www.electricitymaps.com/) API key.
- **Absolute emissions are tiny** because the demo workloads are short. The point is the mechanism and the provenance, not the headline milligrams.

## Prior art & credit

JouleFlow does **not** claim there is no Java energy tooling — there is good work here already:

- **[JoularJX](https://github.com/joular/joularjx)** and **jRAPL** — academic Java + RAPL energy profilers that attribute power to code.
- **[Kepler](https://sustainable-computing.io/)**, **[CodeCarbon](https://codecarbon.io/)**, and the **[Carbon Aware SDK](https://github.com/Green-Software-Foundation/carbon-aware-sdk)** — the closest existing tools for measuring or scheduling around carbon.

JouleFlow's angle is the **closed loop specifically for enterprise JVM workloads**: JFR-based profiling → honestly-tagged energy → a carbon-aware scheduling decision (defer / relocate) → an auditable ledger with SCI-style avoided-emissions reporting, in one Java/Maven project.

---

## Project layout

```
src/main/java/jouleflow/
  energy/       EnergyReading, EnergyReader, RaplEnergyReader, FallbackEnergyReader
  attribution/  JfrTaskProfiler                (real JFR record + parse)
  grid/         CarbonIntensityProvider, CarbonIntensitySample,
                SampleGridProvider, LiveGridApiProvider
  scheduler/    Scheduler, Decision            (RUN_NOW / DEFER / RELOCATE)
  ledger/       Ledger (SQLite), LedgerExporter (JSON)
  Main.java     wires it all together, runs 5 workloads
src/test/java/jouleflow/
  SchedulerTest, EnergyReadingTest
dashboard/index.html                            (Chart.js, self-contained)
```

## License

MIT (see below if a `LICENSE` file is added).
