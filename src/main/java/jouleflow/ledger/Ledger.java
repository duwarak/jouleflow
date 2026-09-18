package jouleflow.ledger;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import jouleflow.attribution.JfrTaskProfiler;
import jouleflow.energy.EnergyReading;
import jouleflow.scheduler.Decision;

/**
 * Append-only record of every scheduled task run, persisted in a single embedded SQLite file via
 * JDBC ({@code jdbc:sqlite:<path>}).
 *
 * <p>Each row keeps the honest provenance of its numbers — the energy source tag, whether the grid
 * data was live, and the scheduler's decision and rationale — so the ledger can be audited later
 * without trusting a headline figure. Emissions are computed at insert time from energy and grid
 * intensity, alongside a naive "run now, here" baseline so savings can be shown.
 */
public final class Ledger implements AutoCloseable {

    private static final double JOULES_PER_KWH = 3_600_000.0;

    private final Connection connection;

    public Ledger(String dbPath) {
        try {
            this.connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            createSchema();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to open ledger at " + dbPath + ": " + e.getMessage(), e);
        }
    }

    private void createSchema() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS task_runs (
                    id                              INTEGER PRIMARY KEY AUTOINCREMENT,
                    task_name                       TEXT    NOT NULL,
                    started_at                      TEXT    NOT NULL,
                    wall_clock_ms                   INTEGER NOT NULL,
                    gc_pause_ms                     REAL    NOT NULL,
                    jfr_event_count                 INTEGER NOT NULL,
                    energy_joules                   REAL    NOT NULL,
                    energy_source                   TEXT    NOT NULL,
                    energy_note                     TEXT    NOT NULL,
                    region                          TEXT    NOT NULL,
                    carbon_intensity_gco2_per_kwh   REAL    NOT NULL,
                    grid_data_source                TEXT    NOT NULL,
                    is_live_grid_data               INTEGER NOT NULL,
                    emissions_grams_co2             REAL    NOT NULL,
                    baseline_intensity_gco2_per_kwh REAL    NOT NULL,
                    baseline_emissions_grams_co2    REAL    NOT NULL,
                    decision_action                 TEXT    NOT NULL,
                    decision_rationale              TEXT    NOT NULL
                )
                """);
        }
    }

    /**
     * Insert one run. Emissions are computed here as {@code energy(kWh) * intensity(gCO2/kWh)} for
     * both the chosen scenario and the naive "run now in home region" baseline.
     *
     * @param intensity         carbon intensity actually attributed to the run (chosen time/place)
     * @param baselineIntensity intensity of running now in the home region (the do-nothing baseline)
     */
    public void recordRun(String taskName, Instant startedAt, JfrTaskProfiler.Result profile,
                          EnergyReading energy, String region, double intensity,
                          String gridDataSource, boolean isLiveGridData,
                          double baselineIntensity, Decision decision) {
        double kwh = energy.joules() / JOULES_PER_KWH;
        double emissions = kwh * intensity;
        double baselineEmissions = kwh * baselineIntensity;

        String sql = """
            INSERT INTO task_runs (
                task_name, started_at, wall_clock_ms, gc_pause_ms, jfr_event_count,
                energy_joules, energy_source, energy_note,
                region, carbon_intensity_gco2_per_kwh, grid_data_source, is_live_grid_data,
                emissions_grams_co2, baseline_intensity_gco2_per_kwh, baseline_emissions_grams_co2,
                decision_action, decision_rationale
            ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, taskName);
            ps.setString(2, startedAt.toString());
            ps.setLong(3, profile.wallClockMillis());
            ps.setDouble(4, profile.totalGcPauseMillis());
            ps.setLong(5, profile.eventCount());
            ps.setDouble(6, energy.joules());
            ps.setString(7, energy.source().name());
            ps.setString(8, energy.note());
            ps.setString(9, region);
            ps.setDouble(10, intensity);
            ps.setString(11, gridDataSource);
            ps.setInt(12, isLiveGridData ? 1 : 0);
            ps.setDouble(13, emissions);
            ps.setDouble(14, baselineIntensity);
            ps.setDouble(15, baselineEmissions);
            ps.setString(16, decision.action().name());
            ps.setString(17, decision.rationale());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to record run for " + taskName + ": " + e.getMessage(), e);
        }
    }

    /** Average emissions (gCO2) across all recorded runs of a task; 0 if none. */
    public double averageEmissionsPerRun(String taskName) {
        String sql = "SELECT AVG(emissions_grams_co2) FROM task_runs WHERE task_name = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, taskName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getDouble(1) : 0.0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("averageEmissionsPerRun failed: " + e.getMessage(), e);
        }
    }

    /** Number of recorded runs whose scheduler decision was {@code action}. */
    public int countByDecision(String action) {
        String sql = "SELECT COUNT(*) FROM task_runs WHERE decision_action = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, action);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("countByDecision failed: " + e.getMessage(), e);
        }
    }

    /** Every recorded run, oldest first — used by {@link LedgerExporter}. */
    public List<Row> allRuns() {
        List<Row> rows = new ArrayList<>();
        String sql = "SELECT * FROM task_runs ORDER BY id";
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                rows.add(new Row(
                        rs.getString("task_name"),
                        rs.getString("started_at"),
                        rs.getLong("wall_clock_ms"),
                        rs.getDouble("gc_pause_ms"),
                        rs.getLong("jfr_event_count"),
                        rs.getDouble("energy_joules"),
                        rs.getString("energy_source"),
                        rs.getString("energy_note"),
                        rs.getString("region"),
                        rs.getDouble("carbon_intensity_gco2_per_kwh"),
                        rs.getString("grid_data_source"),
                        rs.getInt("is_live_grid_data") == 1,
                        rs.getDouble("emissions_grams_co2"),
                        rs.getDouble("baseline_intensity_gco2_per_kwh"),
                        rs.getDouble("baseline_emissions_grams_co2"),
                        rs.getString("decision_action"),
                        rs.getString("decision_rationale")));
            }
        } catch (SQLException e) {
            throw new RuntimeException("allRuns failed: " + e.getMessage(), e);
        }
        return rows;
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to close ledger: " + e.getMessage(), e);
        }
    }

    /** One row of the ledger, flattened for export. */
    public record Row(String taskName, String startedAt, long wallClockMs, double gcPauseMs,
                      long jfrEventCount, double energyJoules, String energySource, String energyNote,
                      String region, double intensity, String gridDataSource, boolean isLiveGridData,
                      double emissionsGramsCo2, double baselineIntensity, double baselineEmissionsGramsCo2,
                      String decisionAction, String decisionRationale) {}
}
