package jouleflow.attribution;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

/**
 * Wraps a task's execution in a <strong>real</strong> JDK Flight Recorder recording.
 *
 * <p>This is genuine {@link jdk.jfr} API usage, not a simulation: it opens a {@link Recording},
 * enables real JFR event types ({@code jdk.CPULoad}, {@code jdk.ExecutionSample},
 * {@code jdk.GarbageCollection}, {@code jdk.ThreadCPULoad}), runs the workload inside it, writes a
 * {@code .jfr} file to disk, then re-opens that file with {@link RecordingFile} and walks every
 * recorded event to report how many were captured and how much time went to GC pauses.
 *
 * <p>Requires a full JDK (the {@code jdk.jfr} module), not a JRE.
 */
public final class JfrTaskProfiler {

    /**
     * The result of profiling one task.
     *
     * @param taskName          the task's label
     * @param jfrFile           the on-disk recording that was produced and parsed
     * @param eventCount         total JFR events read back from the recording
     * @param totalGcPauseMillis sum of GC pause time observed during the task
     * @param wallClockMillis    wall-clock duration of the workload
     */
    public record Result(String taskName, Path jfrFile, long eventCount,
                         double totalGcPauseMillis, long wallClockMillis) {}

    /**
     * Runs {@code workload} under a live JFR recording and parses the resulting file.
     *
     * @param taskName label for the task (used in the recording name and file name)
     * @param workload the work to profile; run once, synchronously, on the calling thread
     * @return parsed statistics from the real recording
     * @throws IOException if the recording cannot be written or read back
     */
    public Result profile(String taskName, Runnable workload) throws IOException {
        Path jfrFile = Files.createTempFile("jouleflow-" + sanitize(taskName) + "-", ".jfr");
        jfrFile.toFile().deleteOnExit();

        long startNanos;
        try (Recording recording = new Recording()) {
            recording.setName("jouleflow-" + taskName);
            recording.enable("jdk.CPULoad").withPeriod(Duration.ofMillis(100));
            recording.enable("jdk.ThreadCPULoad").withPeriod(Duration.ofMillis(100));
            recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(10));
            recording.enable("jdk.GarbageCollection");

            startNanos = System.nanoTime();
            recording.start();
            workload.run();
            recording.stop();
            long wallClockMillis = (System.nanoTime() - startNanos) / 1_000_000;

            recording.dump(jfrFile);

            return parse(taskName, jfrFile, wallClockMillis);
        }
    }

    /** Re-reads the recording from disk and aggregates it. */
    private Result parse(String taskName, Path jfrFile, long wallClockMillis) throws IOException {
        long eventCount = 0;
        long gcPauseNanos = 0;
        try (RecordingFile rf = new RecordingFile(jfrFile)) {
            while (rf.hasMoreEvents()) {
                RecordedEvent event = rf.readEvent();
                eventCount++;
                if (event.getEventType().getName().equals("jdk.GarbageCollection")) {
                    if (event.hasField("sumOfPauses")) {
                        gcPauseNanos += event.getDuration("sumOfPauses").toNanos();
                    } else {
                        gcPauseNanos += event.getDuration().toNanos();
                    }
                }
            }
        }
        return new Result(taskName, jfrFile, eventCount, gcPauseNanos / 1_000_000.0, wallClockMillis);
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9-]", "_");
    }
}
