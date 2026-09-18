package jouleflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import jouleflow.energy.EnergyReading;

/** Verifies joules() conversion and that the factory methods set the right Source tag. */
class EnergyReadingTest {

    @Test
    void measuredSetsMeasuredTagAndConvertsJoules() {
        EnergyReading r = EnergyReading.measured(2_500_000L, List.of("package-0", "core"), "RAPL");
        assertEquals(EnergyReading.Source.MEASURED, r.source());
        assertEquals(2.5, r.joules(), 1e-9);
        assertEquals(List.of("package-0", "core"), r.domainsRead());
    }

    @Test
    void estimatedSetsEstimatedTagAndCarriesNoDomains() {
        EnergyReading r = EnergyReading.estimated(1_000_000L, "CPU-time proxy");
        assertEquals(EnergyReading.Source.ESTIMATED, r.source());
        assertEquals(1.0, r.joules(), 1e-9);
        assertTrue(r.domainsRead().isEmpty());
    }

    @Test
    void unavailableSetsUnavailableTagAndZeroEnergy() {
        EnergyReading r = EnergyReading.unavailable("no counter");
        assertEquals(EnergyReading.Source.UNAVAILABLE, r.source());
        assertEquals(0.0, r.joules(), 1e-9);
        assertTrue(r.domainsRead().isEmpty());
    }
}
