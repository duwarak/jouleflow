package jouleflow.energy;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Reads <strong>real</strong> Intel RAPL (Running Average Power Limit) hardware energy counters
 * from the Linux powercap sysfs interface at
 * {@code /sys/class/powercap/intel-rapl:*}{@code /energy_uj}.
 *
 * <p><strong>Availability caveat — read this before trusting a MEASURED reading.</strong>
 * RAPL is masked or entirely absent in most containers and cloud VMs. Following the PLATYPUS
 * side-channel attacks (CVE-2020-8694 / CVE-2020-8695), which used RAPL's fine-grained energy
 * readings to leak secrets, the kernel restricted powercap so that {@code energy_uj} is readable
 * only by root and is often hidden entirely inside containers. In practice this reader only
 * produces {@link EnergyReading.Source#MEASURED} figures on <em>bare-metal Linux</em> with an
 * accessible powercap driver. Everywhere else {@link #isHardwareBacked()} returns {@code false}
 * and the caller must fall back to {@link FallbackEnergyReader}.
 *
 * <p>Only top-level package domains ({@code intel-rapl:0}, {@code intel-rapl:1}, …) are summed.
 * Sub-domains such as {@code intel-rapl:0:0} (core) are children of a package and would be
 * double-counted if added.
 *
 * <p>The {@code energy_uj} counter wraps at {@code max_energy_range_uj}. Over the short
 * before/after windows JouleFlow uses this is unlikely, but a production reader would compensate.
 */
public final class RaplEnergyReader implements EnergyReader {

    private static final Path POWERCAP = Path.of("/sys/class/powercap");
    /** Matches a top-level package domain like "intel-rapl:0" but not "intel-rapl:0:0". */
    private static final Pattern PACKAGE_DOMAIN = Pattern.compile("intel-rapl:\\d+");

    private final List<Path> energyFiles = new ArrayList<>();
    private final List<String> domainNames = new ArrayList<>();
    private final boolean hardwareBacked;

    /**
     * Probes the powercap interface. Any package domain whose {@code energy_uj} is present and
     * readable is recorded; the reader is hardware-backed only if at least one was found.
     */
    public RaplEnergyReader() {
        boolean found = false;
        File dir = POWERCAP.toFile();
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                if (!PACKAGE_DOMAIN.matcher(child.getName()).matches()) {
                    continue;
                }
                Path energyUj = child.toPath().resolve("energy_uj");
                if (!Files.isReadable(energyUj)) {
                    continue;
                }
                try {
                    Long.parseLong(Files.readString(energyUj).trim()); // confirm it really reads
                    energyFiles.add(energyUj);
                    domainNames.add(readDomainName(child.toPath()));
                    found = true;
                } catch (IOException | NumberFormatException ignored) {
                    // domain present but unreadable (permissions / masked) — skip it
                }
            }
        }
        this.hardwareBacked = found;
    }

    private static String readDomainName(Path domainDir) {
        try {
            return Files.readString(domainDir.resolve("name")).trim();
        } catch (IOException e) {
            return domainDir.getFileName().toString();
        }
    }

    @Override
    public boolean isHardwareBacked() {
        return hardwareBacked;
    }

    @Override
    public EnergyReading readCumulativeMicrojoules() {
        if (!hardwareBacked) {
            return EnergyReading.unavailable(
                    "Intel RAPL not accessible (absent or masked in this container/VM since the "
                    + "PLATYPUS CVEs); no hardware energy counter to read.");
        }
        long totalUj = 0;
        try {
            for (Path energyUj : energyFiles) {
                totalUj += Long.parseLong(Files.readString(energyUj).trim());
            }
        } catch (IOException | NumberFormatException e) {
            return EnergyReading.unavailable("RAPL became unreadable mid-run: " + e.getMessage());
        }
        return EnergyReading.measured(
                totalUj,
                List.copyOf(domainNames),
                "Intel RAPL powercap counters, domains: " + String.join(", ", domainNames));
    }
}
