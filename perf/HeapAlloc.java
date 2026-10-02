import java.nio.file.Path;
import jdk.jfr.consumer.*;
/**
 * Bytes a recording's process allocated, counted exactly from the heap summaries around each collection - used
 * before collection n+1 minus used after collection n - with the number of collections and their pauses. The
 * allocation samples JFR also records are throttled and weighted: estimates, which here were wrong by tens of percent.
 */
public class HeapAlloc {
    public static void main(String[] a) throws Exception {
        long total = 0, gcs = 0; double pauseMs = 0;
        for (String f : a) {
            long lastAfter = -1;
            try (RecordingFile r = new RecordingFile(Path.of(f))) {
                while (r.hasMoreEvents()) {
                    RecordedEvent e = r.readEvent();
                    String t = e.getEventType().getName();
                    if (t.equals("jdk.GCHeapSummary")) {
                        long used = e.getLong("heapUsed");
                        String when = e.getString("when");
                        if (when.equals("Before GC")) { if (lastAfter >= 0) total += used - lastAfter; gcs++; }
                        else lastAfter = used;
                    } else if (t.equals("jdk.GarbageCollection")) {
                        pauseMs += e.getDuration("sumOfPauses").toNanos() / 1e6;
                    }
                }
            }
        }
        System.out.printf(java.util.Locale.ROOT, "allocatedBytes=%d gcs=%d pauseMs=%.0f%n", total, gcs, pauseMs);
    }
}
