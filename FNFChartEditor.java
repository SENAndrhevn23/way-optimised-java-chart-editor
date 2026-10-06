
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardCopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.sound.sampled.*;

public class FNFChartEditor extends JFrame {

    public static class SongData {
        public String song = "Test";
        public double bpm = 150.0;
        public boolean needsVoices = true;
        public String player1 = "bf";
        public String player2 = "dad";
        public String gfVersion = "gf";
        public double speed = 1.6;
        public String audioFilePath = ""; 
        public List<Section> notes = new ArrayList<>();
    }

    public static class Section {
        public int lengthInSteps = 16;
        public boolean mustHitSection = false;
        /** BPM stored on this section when changeBPM is true. */
        public double bpm = 0.0;
        public boolean changeBPM = false;
        public final NoteStore sectionNotes = new NoteStore();
    }

    /**
     * Ultra-compact large-chart note storage. Each section is split into the
     * 16 grid-row buckets, and each bucket is backed by a memory-mapped file.
     * One note is exactly 4 bytes:
     *   bits  0..15 = time in 1/4096-grid units (0..15.9998 grids)
     *   bits 16..28 = sustain in 1/32-grid units (0..255.96875 grids)
     *   bits 29..31 = lane (0..7)
     */
    /**
     * Disk-backed note storage for very large charts.
     *
     * The old implementation memory-mapped the entire note backing file as it
     * was filled. A 3.3B-note chart is about 13.2GB at 4 bytes/note, so that
     * strategy can exhaust native memory/page cache even though the logical
     * chart data itself is compact.
     *
     * This version keeps the same compact 4-byte note record, but writes notes
     * through buffered streams and only maps a small 4MB read window when the
     * editor actually needs to read notes. At most 32 read windows are kept
     * mapped at once (~128MB virtual/native mapping), rather than mapping the
     * complete chart.
     */
    public static final class NoteStore {
        private static final int RECORD_SIZE = 4;
        private static final int MAX_TIME_UNITS = 0xFFFF;
        private static final int MAX_SUSTAIN_UNITS = 0x1FFF;
        private static final int READ_WINDOW_BYTES = 4 * 1024 * 1024;
        private static final int MAX_READ_WINDOWS = 32;
        private static final int OUTPUT_BUFFER_BYTES = 32 * 1024;

        public interface RowVisitor {
            void visit(int row, long index, double timeMs, int lane, double sustainMs);
        }

        private static final class Bucket {
            private final Path file;
            private DataOutputStream output;
            private long size;

            Bucket() {
                try {
                    file = Files.createTempFile("fnf-chart-notes-", ".bin");
                    file.toFile().deleteOnExit();
                } catch (IOException e) {
                    throw new UncheckedIOException("Unable to create large-note backing store", e);
                }
            }

            long size() { return size; }

            private void ensureOutput() throws IOException {
                if (output != null) return;
                ReadWindowCache.removeBucket(this);
                output = new DataOutputStream(new BufferedOutputStream(
                        Files.newOutputStream(file,
                                StandardOpenOption.CREATE,
                                StandardOpenOption.WRITE,
                                StandardOpenOption.APPEND),
                        OUTPUT_BUFFER_BYTES));
            }

            void add(int packedRecord) {
                try {
                    ensureOutput();
                    output.writeInt(packedRecord);
                    size++;
                } catch (IOException e) {
                    throw new UncheckedIOException("Unable to append chart note data", e);
                }
            }

            void flushOutput() throws IOException {
                if (output == null) return;
                output.flush();
                output.close();
                output = null;
            }

            void setLogicalSize(long newSize) {
                size = Math.max(0L, newSize);
            }

            void truncateToLogicalSize() throws IOException {
                flushOutput();
                ReadWindowCache.removeBucket(this);
                EditChannelCache.removeBucket(this);
                try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
                    channel.truncate(size * (long) RECORD_SIZE);
                }
            }

            void clear() {
                try {
                    flushOutput();
                } catch (IOException ignored) {
                }
                ReadWindowCache.removeBucket(this);
                EditChannelCache.removeBucket(this);
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {
                }
                size = 0;
            }
        }

        private static final class ReadWindow {
            final Bucket bucket;
            final long startByte;
            final int length;
            final FileChannel channel;
            final MappedByteBuffer map;

            ReadWindow(Bucket bucket, long startByte, int length,
                       FileChannel channel, MappedByteBuffer map) {
                this.bucket = bucket;
                this.startByte = startByte;
                this.length = length;
                this.channel = channel;
                this.map = map;
            }

            void closeChannel() {
                try {
                    channel.close();
                } catch (IOException ignored) {
                }
            }
        }

        /**
         * Shared LRU for read-only mmap windows. Keeping this cache static is
         * important: charts can contain thousands of sections, but the process
         * never needs thousands of simultaneously-open mapped windows.
         */
        private static final class ReadWindowCache {
            private static final LinkedHashMap<String, ReadWindow> CACHE =
                    new LinkedHashMap<>(MAX_READ_WINDOWS, 0.75f, true);

            private static String key(Bucket bucket, long startByte) {
                return bucket.file.toAbsolutePath().toString() + "@" + startByte;
            }

            static int readInt(Bucket bucket, long recordIndex) throws IOException {
                bucket.flushOutput();
                long byteOffset = recordIndex * (long) RECORD_SIZE;
                long startByte = (byteOffset / READ_WINDOW_BYTES) * READ_WINDOW_BYTES;
                int offset = (int) (byteOffset - startByte);
                String cacheKey = key(bucket, startByte);

                ReadWindow window;
                synchronized (CACHE) {
                    window = CACHE.get(cacheKey);
                    if (window == null) {
                        removeBucketLocked(bucket);
                        FileChannel channel = FileChannel.open(bucket.file, StandardOpenOption.READ);
                        long fileSize = channel.size();
                        if (startByte >= fileSize) {
                            channel.close();
                            throw new EOFException("Chart note backing store ended unexpectedly");
                        }
                        int length = (int) Math.min((long) READ_WINDOW_BYTES, fileSize - startByte);
                        MappedByteBuffer mapped = channel.map(FileChannel.MapMode.READ_ONLY, startByte, length);
                        window = new ReadWindow(bucket, startByte, length, channel, mapped);
                        CACHE.put(cacheKey, window);
                        trimLocked();
                    }
                }

                if (offset < 0 || offset + RECORD_SIZE > window.length) {
                    throw new EOFException("Invalid chart note record offset");
                }
                return window.map.getInt(offset);
            }

            static synchronized void removeBucket(Bucket bucket) {
                removeBucketLocked(bucket);
            }

            private static void removeBucketLocked(Bucket bucket) {
                java.util.Iterator<Map.Entry<String, ReadWindow>> it = CACHE.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<String, ReadWindow> entry = it.next();
                    if (entry.getValue().bucket == bucket) {
                        entry.getValue().closeChannel();
                        it.remove();
                    }
                }
            }

            private static void trimLocked() {
                while (CACHE.size() > MAX_READ_WINDOWS) {
                    java.util.Iterator<Map.Entry<String, ReadWindow>> it = CACHE.entrySet().iterator();
                    if (!it.hasNext()) return;
                    Map.Entry<String, ReadWindow> eldest = it.next();
                    eldest.getValue().closeChannel();
                    it.remove();
                }
            }
        }

        /** Small LRU of read/write channels used only for rare edit operations. */
        private static final class EditChannelCache {
            private static final int MAX_CHANNELS = 16;
            private static final LinkedHashMap<Bucket, FileChannel> CACHE =
                    new LinkedHashMap<>(MAX_CHANNELS, 0.75f, true);

            static synchronized FileChannel channel(Bucket bucket) throws IOException {
                bucket.flushOutput();
                FileChannel channel = CACHE.get(bucket);
                if (channel != null && channel.isOpen()) return channel;

                if (channel != null) {
                    try { channel.close(); } catch (IOException ignored) {}
                }
                channel = FileChannel.open(bucket.file,
                        StandardOpenOption.READ, StandardOpenOption.WRITE);
                CACHE.put(bucket, channel);
                trimLocked();
                return channel;
            }

            static synchronized void removeBucket(Bucket bucket) {
                FileChannel channel = CACHE.remove(bucket);
                if (channel != null) {
                    try { channel.close(); } catch (IOException ignored) {}
                }
            }

            private static void trimLocked() {
                while (CACHE.size() > MAX_CHANNELS) {
                    java.util.Iterator<Map.Entry<Bucket, FileChannel>> it = CACHE.entrySet().iterator();
                    if (!it.hasNext()) return;
                    Map.Entry<Bucket, FileChannel> eldest = it.next();
                    try { eldest.getValue().close(); } catch (IOException ignored) {}
                    it.remove();
                }
            }
        }

        private final Map<Integer, Bucket> buckets = new java.util.TreeMap<>();
        private long size;
        private final long[] laneCounts = new long[8];

        public long size() { return size; }

        public long countLane(int lane) {
            return lane >= 0 && lane < laneCounts.length ? laneCounts[lane] : 0L;
        }

        private Bucket bucket(int row, boolean create) {
            int safeRow = Math.max(0, Math.min(1_000_000, row));
            Bucket b = buckets.get(safeRow);
            if (b == null && create) {
                b = new Bucket();
                buckets.put(safeRow, b);
            }
            return b;
        }

        /** Flush pending append buffers after a section finishes loading. */
        public void finishWrites() {
            for (Bucket b : buckets.values()) {
                try {
                    b.flushOutput();
                } catch (IOException e) {
                    throw new UncheckedIOException("Unable to flush chart note backing store", e);
                }
            }
        }

        public void clear() {
            for (Bucket b : buckets.values()) {
                b.clear();
            }
            buckets.clear();
            size = 0;
            java.util.Arrays.fill(laneCounts, 0L);
        }

        private static int pack(double localTimeMs, double sustainMs, int laneValue, double stepTimeMs) {
            double safeStep = Math.max(1.0e-9, stepTimeMs);
            int timeUnits = (int) Math.round((Math.max(0.0, localTimeMs) / safeStep) * 4096.0);
            int sustainUnits = (int) Math.round((Math.max(0.0, sustainMs) / safeStep) * 32.0);
            timeUnits = Math.max(0, Math.min(MAX_TIME_UNITS, timeUnits));
            sustainUnits = Math.max(0, Math.min(MAX_SUSTAIN_UNITS, sustainUnits));
            int lane = Math.max(0, Math.min(7, laneValue));
            return timeUnits | (sustainUnits << 16) | (lane << 29);
        }

        private static int timeUnits(int record) { return record & 0xFFFF; }
        private static int sustainUnits(int record) { return (record >>> 16) & 0x1FFF; }
        private static int lane(int record) { return (record >>> 29) & 0x7; }

        private int readRecord(Bucket bucket, long index) {
            if (index < 0 || index >= bucket.size()) return 0;
            try {
                return ReadWindowCache.readInt(bucket, index);
            } catch (IOException e) {
                throw new UncheckedIOException("Unable to read large-note backing store", e);
            }
        }

        private void writeRecord(Bucket bucket, long index, int value) {
            try {
                bucket.flushOutput();
                ReadWindowCache.removeBucket(bucket);
                FileChannel channel = EditChannelCache.channel(bucket);
                ByteBuffer buf = ByteBuffer.allocate(RECORD_SIZE);
                buf.putInt(value).flip();
                long offset = index * (long) RECORD_SIZE;
                while (buf.hasRemaining()) channel.write(buf, offset + buf.position());
            } catch (IOException e) {
                throw new UncheckedIOException("Unable to edit large-note backing store", e);
            }
        }

        public long add(double timeMs, int laneValue, double sustainMs, double rowHint, double stepTimeMs) {
            int row = (int) Math.floor(rowHint);
            row = Math.max(0, row);
            Bucket target = bucket(row, true);
            long globalIndex = globalOffsetForRow(row) + target.size();
            int packedLane = Math.max(0, Math.min(7, laneValue));
            target.add(pack(Math.max(0.0, timeMs - row * stepTimeMs), sustainMs, packedLane, stepTimeMs));
            laneCounts[packedLane]++;
            size++;
            return globalIndex;
        }

        public void addFast(double timeMs, int laneValue, double sustainMs, double rowHint, double stepTimeMs) {
            int row = Math.max(0, (int) Math.floor(rowHint));
            int packedLane = Math.max(0, Math.min(7, laneValue));
            bucket(row, true).add(pack(Math.max(0.0, timeMs - row * stepTimeMs),
                    sustainMs, packedLane, stepTimeMs));
            laneCounts[packedLane]++;
            size++;
        }

        private long globalOffsetForRow(int row) {
            long offset = 0;
            for (Map.Entry<Integer, Bucket> entry : buckets.entrySet()) {
                if (entry.getKey() >= row) break;
                offset += entry.getValue().size();
            }
            return offset;
        }

        private long[] locate(long index) {
            if (index < 0 || index >= size) return null;
            long remaining = index;
            for (Map.Entry<Integer, Bucket> entry : buckets.entrySet()) {
                Bucket b = entry.getValue();
                if (remaining < b.size()) {
                    return new long[]{entry.getKey(), remaining};
                }
                remaining -= b.size();
            }
            return null;
        }

        public double getTime(long index, double stepTimeMs) {
            long[] loc = locate(index);
            if (loc == null) return 0.0;
            return loc[0] * stepTimeMs + (timeUnits(readRecord(buckets.get((int) loc[0]), loc[1])) / 4096.0) * stepTimeMs;
        }

        public int getLane(long index) {
            long[] loc = locate(index);
            if (loc == null) return 0;
            return lane(readRecord(buckets.get((int) loc[0]), loc[1]));
        }

        public double getSustain(long index, double stepTimeMs) {
            long[] loc = locate(index);
            if (loc == null) return 0.0;
            return (sustainUnits(readRecord(buckets.get((int) loc[0]), loc[1])) / 32.0) * stepTimeMs;
        }

        public void setSustain(long index, double sustainMs, double stepTimeMs) {
            long[] loc = locate(index);
            if (loc == null) return;
            Bucket b = buckets.get((int) loc[0]);
            int record = readRecord(b, loc[1]);
            int sustainUnits = (int) Math.round((Math.max(0.0, sustainMs) / Math.max(1.0e-9, stepTimeMs)) * 32.0);
            sustainUnits = Math.max(0, Math.min(MAX_SUSTAIN_UNITS, sustainUnits));
            record = (record & 0xE000FFFF) | (sustainUnits << 16);
            writeRecord(b, loc[1], record);
        }

        public void setLane(long index, int laneValue) {
            long[] loc = locate(index);
            if (loc == null) return;
            Bucket b = buckets.get((int) loc[0]);
            int record = readRecord(b, loc[1]);
            int laneValueClamped = Math.max(0, Math.min(7, laneValue));
            int oldLane = lane(record);
            if (oldLane != laneValueClamped) {
                laneCounts[oldLane]--;
                laneCounts[laneValueClamped]++;
            }
            record = (record & 0x1FFFFFFF) | (laneValueClamped << 29);
            writeRecord(b, loc[1], record);
        }

        public void removeAt(long index) {
            long[] loc = locate(index);
            if (loc == null) return;
            Bucket b = buckets.get((int) loc[0]);
            int removedLane = lane(readRecord(b, loc[1]));
            for (long i = loc[1]; i < b.size() - 1; i++) {
                writeRecord(b, i, readRecord(b, i + 1));
            }
            b.setLogicalSize(b.size() - 1);
            try {
                b.truncateToLogicalSize();
            } catch (IOException e) {
                throw new UncheckedIOException("Unable to shrink chart note backing store", e);
            }
            laneCounts[removedLane]--;
            size--;
            if (b.size() == 0) {
                b.clear();
                buckets.remove((int) loc[0]);
            }
        }

        public long findNoteAtTimeAndLane(double timeMs, int laneValue, double stepTimeMs, double toleranceMs) {
            double safeStep = Math.max(1.0e-9, stepTimeMs);
            int targetLane = Math.max(0, Math.min(7, laneValue));
            for (Map.Entry<Integer, Bucket> entry : buckets.entrySet()) {
                int row = entry.getKey();
                Bucket b = entry.getValue();
                long base = globalOffsetForRow(row);
                for (long i = 0; i < b.size(); i++) {
                    int record = readRecord(b, i);
                    if (lane(record) != targetLane) continue;
                    double noteTime = row * safeStep + (timeUnits(record) / 4096.0) * safeStep;
                    if (Math.abs(noteTime - timeMs) <= toleranceMs) {
                        return base + i;
                    }
                }
            }
            return -1;
        }

        public long removeNoteAtTimeAndLane(double timeMs, int laneValue, double stepTimeMs, double toleranceMs) {
            long index = findNoteAtTimeAndLane(timeMs, laneValue, stepTimeMs, toleranceMs);
            if (index < 0) return 0;
            removeAt(index);
            return 1;
        }

        public long removeLast(long count) {
            if (count <= 0 || size <= 0) return 0;
            long remaining = Math.min(count, size);
            long removed = 0;
            java.util.List<Integer> rows = new ArrayList<>(buckets.keySet());
            for (int r = rows.size() - 1; r >= 0 && remaining > 0; r--) {
                int row = rows.get(r);
                Bucket b = buckets.get(row);
                if (b == null || b.size() == 0) continue;
                long taken = Math.min(remaining, b.size());
                for (long i = b.size() - taken; i < b.size(); i++) {
                    int removedLane = lane(readRecord(b, i));
                    laneCounts[removedLane]--;
                }
                b.setLogicalSize(b.size() - taken);
                try {
                    b.truncateToLogicalSize();
                } catch (IOException e) {
                    throw new UncheckedIOException("Unable to shrink chart note backing store", e);
                }
                removed += taken;
                remaining -= taken;
                if (b.size() == 0) {
                    b.clear();
                    buckets.remove(row);
                }
            }
            size -= removed;
            return removed;
        }

        public long removeMatching(double minRelativeTimeMs, double maxRelativeTimeMs,
                                   double stepTimeMs, int minLane, int maxLane) {
            if (buckets.isEmpty()) return 0;
            long removed = 0;
            int firstRow = Math.max(0, (int) Math.floor(minRelativeTimeMs / Math.max(1.0e-9, stepTimeMs)));
            int lastRow = Math.max(0, (int) Math.floor(Math.max(0.0, maxRelativeTimeMs - 1e-9) / Math.max(1.0e-9, stepTimeMs)));
            java.util.List<Integer> candidateRows = new ArrayList<>();
            for (int row : buckets.keySet()) {
                if (row >= firstRow && row <= lastRow) candidateRows.add(row);
            }
            for (int row : candidateRows) {
                Bucket b = buckets.get(row);
                if (b == null) continue;
                long i = 0;
                while (i < b.size()) {
                    int record = readRecord(b, i);
                    double time = row * stepTimeMs + (timeUnits(record) / 4096.0) * stepTimeMs;
                    int noteLane = lane(record);
                    if (noteLane >= minLane && noteLane <= maxLane
                            && time >= minRelativeTimeMs && time < maxRelativeTimeMs) {
                        laneCounts[noteLane]--;
                        for (long j = i; j < b.size() - 1; j++) {
                            writeRecord(b, j, readRecord(b, j + 1));
                        }
                        b.setLogicalSize(b.size() - 1);
                        removed++;
                        size--;
                    } else {
                        i++;
                    }
                }
                if (b.size() > 0) {
                    try {
                        b.truncateToLogicalSize();
                    } catch (IOException e) {
                        throw new UncheckedIOException("Unable to shrink chart note backing store", e);
                    }
                }
                if (b.size() == 0) {
                    b.clear();
                    buckets.remove(row);
                }
            }
            return removed;
        }

        public long rowSize(int row) {
            Bucket b = bucket(row, false);
            return b == null ? 0 : b.size();
        }

        public double rowGetTime(int row, long index, double stepTimeMs) {
            Bucket b = bucket(row, false);
            return b == null ? 0.0 : row * stepTimeMs + (timeUnits(readRecord(b, index)) / 4096.0) * stepTimeMs;
        }

        public int rowGetLane(int row, long index) {
            Bucket b = bucket(row, false);
            return b == null ? 0 : lane(readRecord(b, index));
        }

        public double rowGetSustain(int row, long index, double stepTimeMs) {
            Bucket b = bucket(row, false);
            return b == null ? 0.0 : (sustainUnits(readRecord(b, index)) / 32.0) * stepTimeMs;
        }

        /** Reads the compact note record once, useful for large sequential saves. */
        public int rowGetPackedRecord(int row, long index) {
            Bucket b = bucket(row, false);
            return b == null ? 0 : readRecord(b, index);
        }

        public static double packedTime(int record, int row, double stepTimeMs) {
            return row * stepTimeMs + (timeUnits(record) / 4096.0) * stepTimeMs;
        }

        public static int packedLane(int record) {
            return lane(record);
        }

        public static double packedSustain(int record, double stepTimeMs) {
            return (sustainUnits(record) / 32.0) * stepTimeMs;
        }

        public long globalIndexForRow(int row, long rowIndex) {
            Bucket b = bucket(row, false);
            if (b == null || rowIndex < 0 || rowIndex >= b.size()) return -1;
            return globalOffsetForRow(row) + rowIndex;
        }

        public void swapLanes() {
            for (Bucket b : buckets.values()) {
                for (long i = 0; i < b.size(); i++) {
                    int record = readRecord(b, i);
                    int oldLane = lane(record);
                    int newLane = oldLane < 4 ? oldLane + 4 : oldLane - 4;
                    writeRecord(b, i, (record & 0x1FFFFFFF) | (newLane << 29));
                }
            }
            long[] oldCounts = laneCounts.clone();
            for (int i = 0; i < 4; i++) {
                laneCounts[i] = oldCounts[i + 4];
                laneCounts[i + 4] = oldCounts[i];
            }
        }

        public void appendFrom(NoteStore source) {
            if (source == null) return;
            for (Map.Entry<Integer, Bucket> entry : source.buckets.entrySet()) {
                int row = entry.getKey();
                Bucket src = entry.getValue();
                if (src == null || src.size() == 0) continue;
                Bucket dst = bucket(row, true);
                for (long i = 0; i < src.size(); i++) {
                    int record = source.readRecord(src, i);
                    dst.add(record);
                    laneCounts[lane(record)]++;
                }
                size += src.size();
            }
        }
    }


    private SongData activeSong = new SongData();
    private int currentSectionIndex = 0;

    private Timer playbackTimer;
    private boolean isPlaying = false;
    private Clip audioClip;

    private long positionSteps = 0;
    private double positionStepsDouble = 0;
    private long lastTickMs = 0;

    private static final int GRID_STEPS_PER_SECTION = 16;
    private static final double[] GRID_ZOOM_VALUES = {
        0.25, 0.50, 0.75, 1.0, 2.0, 3.0, 4.0, 6.0, 8.0, 12.0, 16.0, 24.0, 32.0, 48.0, 64.0, 96.0, 192.0
    };
    private int gridZoomIndex = 0; // 1/0.25 default

    private int gridRowsForZoom() {
        return Math.max(1, (int) Math.round(64.0 * GRID_ZOOM_VALUES[gridZoomIndex]));
    }

    /**
     * Returns the BPM actually active during a section. A changeBPM section
     * starts a new tempo; every following section inherits that tempo until
     * another section explicitly changes it. This matches FNF/Psych-style
     * section BPM behavior.
     */
    private double getEffectiveSectionBpm(Section section) {
        if (activeSong == null || activeSong.notes == null || activeSong.notes.isEmpty()) {
            return Math.max(1.0, activeSong == null ? 120.0 : activeSong.bpm);
        }

        int target = activeSong.notes.indexOf(section);
        if (target < 0) {
            return Math.max(1.0, activeSong.bpm);
        }

        double bpm = Math.max(1.0, activeSong.bpm);
        for (int i = 0; i <= target; i++) {
            Section s = activeSong.notes.get(i);
            if (s != null && s.changeBPM && s.bpm > 0.0) {
                bpm = s.bpm;
            }
        }
        return bpm;
    }

    private double getSectionStartTimeMs(int sectionIndex) {
        double total = 0.0;
        if (activeSong == null) return 0.0;
        int limit = Math.max(0, Math.min(sectionIndex, activeSong.notes.size()));
        for (int i = 0; i < limit; i++) {
            Section s = activeSong.notes.get(i);
            double bpm = getEffectiveSectionBpm(s);
            total += (Math.max(1, s.lengthInSteps) / 4.0) * (60000.0 / bpm);
        }
        return total;
    }

    private double getCurrentSectionStepTimeMs() {
        if (activeSong == null || activeSong.notes.isEmpty()) {
            return (60000.0 / Math.max(1.0, activeSong == null ? 120.0 : activeSong.bpm)) / 4.0;
        }
        Section sec = activeSong.notes.get(Math.max(0, Math.min(currentSectionIndex, activeSong.notes.size() - 1)));
        return (60000.0 / getEffectiveSectionBpm(sec)) / 4.0;
    }

    private double displayStepTimeMs() {
        // A section is stored in the canonical 1/16-note grid (16 storage steps
        // for the normal 4/4 section). The visual grid may contain more or fewer
        // rows because of Z/X zoom, so derive each visual row from the ACTUAL
        // section duration instead of treating the default 16 rows as quarters.
        // This keeps 1 visual row = 1 visual row everywhere, which is required
        // for EZ Spam strength (1 = 1 row, 16 = 16 rows).
        Section sec = (activeSong != null && !activeSong.notes.isEmpty())
                ? activeSong.notes.get(Math.max(0, Math.min(currentSectionIndex, activeSong.notes.size() - 1)))
                : null;
        double effectiveBpm = getEffectiveSectionBpm(sec);
        double storageStepMs = (60000.0 / effectiveBpm) / 4.0;
        double sectionDurationMs = storageStepMs * (sec == null ? GRID_STEPS_PER_SECTION : Math.max(1, sec.lengthInSteps));
        return sectionDurationMs / Math.max(1, gridRowsForZoom());
    }

    private String gridZoomLabel() {
        double v = GRID_ZOOM_VALUES[gridZoomIndex];
        return "1/" + (v == 1.0 ? "1" : (v == 0.25 ? "0.25" : (v == 0.5 ? "0.50" : (v == 0.75 ? "0.75" : (v == Math.rint(v) ? Integer.toString((int)v) : Double.toString(v))))));
    }

    private ChartGridPanel gridPanel;
    private JTextArea shortcutsInfo;
    private boolean longNoteMode = false;
    private long longNoteStartIndex = -1;
    private int longNoteStartSection = -1;
    
    private JTextField songNameField;
    private JSpinner bpmSpinner;
    private JSpinner speedSpinner;
    private JComboBox<String> player1Combo;
    private JComboBox<String> player2Combo;
    private JComboBox<String> girlfriendCombo;
    private JCheckBox voiceTrackCheckbox;
    private JLabel audioTrackLabel;

    private JSpinner sustainSpinner;
    private JTextField strumTimeField;
    private JComboBox<String> noteTypeCombo;

    private JSpinner densitySpinner;
    private JSpinner strengthSpinner;
    private JCheckBox ezSpamCheckbox;

    private JSlider opacitySlider;

    private static final class CopiedNote {
        final double timeMs;
        final int lane;
        final double sustainMs;
        CopiedNote(double timeMs, int lane, double sustainMs) {
            this.timeMs = timeMs;
            this.lane = lane;
            this.sustainMs = sustainMs;
        }
    }

    private CopiedNote copiedNote = null;
    private final NoteStore copiedSectionNotes = new NoteStore();
    private boolean hasCopiedSection = false;
    private boolean copiedSectionMustHit = false;
    private int copiedSectionLengthInSteps = GRID_STEPS_PER_SECTION;

    /**
     * Internal clipboard for the Section-tab "Copy All Notes" / "Paste All Notes"
     * actions. Only non-empty source sections are stored, so empty sections do
     * not consume any destination sections when pasted. Notes keep their exact
     * relative position inside each source section.
     */
    private static final class CopiedAllSection {
        final NoteStore sectionNotes = new NoteStore();
        boolean mustHitSection;
        int lengthInSteps;
    }

    private final List<CopiedAllSection> copiedAllSections = new ArrayList<>();
    private boolean hasCopiedAllNotes = false;
    private long copiedAllNotesCount = 0;

    public FNFChartEditor() {
        setTitle("Java FNF Chart Editor");
        setSize(1280, 720);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        if (activeSong.notes.isEmpty()) {
            activeSong.notes.add(new Section());
        }

        playbackTimer = new Timer(16, e -> updatePlayback());

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        splitPane.setDividerLocation(800);

        gridPanel = new ChartGridPanel();
        splitPane.setLeftComponent(gridPanel);

        JPanel controlPanel = createControlPanel();
        splitPane.setRightComponent(controlPanel);

        add(splitPane, BorderLayout.CENTER);
        shortcutsInfo = buildBottomStatusArea();
        JScrollPane statusScroll = new JScrollPane(shortcutsInfo);
        statusScroll.setPreferredSize(new Dimension(1280, 150));
        add(statusScroll, BorderLayout.SOUTH);

        InputMap inputMap = getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap actionMap = getRootPane().getActionMap();
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "chartSpace");
        actionMap.put("chartSpace", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                handleSpaceKey();
            }
        });

        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_E, 0), "makeLongNote");
        actionMap.put("makeLongNote", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (gridPanel != null) {
                    gridPanel.extendSelectedNote();
                    gridPanel.requestFocusInWindow();
                }
            }
        });

        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_P, 0), "extendLongNoteStep");
        actionMap.put("extendLongNoteStep", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                if (gridPanel != null) {
                    gridPanel.extendLongNoteByGridStep();
                    gridPanel.requestFocusInWindow();
                }
            }
        });

        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, 0), "zoomOutGrid");
        actionMap.put("zoomOutGrid", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                if (gridPanel != null) { gridPanel.changeGridZoom(-1); gridPanel.requestFocusInWindow(); }
            }
        });

        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_X, 0), "zoomInGrid");
        actionMap.put("zoomInGrid", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                if (gridPanel != null) { gridPanel.changeGridZoom(1); gridPanel.requestFocusInWindow(); }
            }
        });

        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_R, 0), "swapSection");
        actionMap.put("swapSection", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                swapCurrentSectionSides();
                gridPanel.requestFocusInWindow();
            }
        });

        gridPanel.repaint();
    }

    private JPanel createControlPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        JTabbedPane tabbedPane = new JTabbedPane();

        tabbedPane.addTab("Charting", createChartingTab());
        tabbedPane.addTab("Data", createDataTab());
        tabbedPane.addTab("Events", createEventsTab());
        tabbedPane.addTab("Note", createNoteTab());
        tabbedPane.addTab("Spamming", createSpammingTab());
        tabbedPane.addTab("Optimiser", createOptimiserTab());
        tabbedPane.addTab("Section", createSectionTab());
        tabbedPane.addTab("Song", createSongTab());
        tabbedPane.addTab("Controls", createControlsTab());

        panel.add(tabbedPane, BorderLayout.CENTER);

        return panel;
    }

    private JTextArea buildBottomStatusArea() {
        JTextArea area = new JTextArea(9, 100);
        area.setEditable(false);
        area.setFocusable(false);
        area.setBackground(Color.DARK_GRAY);
        area.setForeground(Color.WHITE);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setLineWrap(false);
        area.setText(
                "SPACE BAR - Start / Pause Playback (BPM Camera Follow)\n" +
                "P - Extend Selected Long Note by 1/16 Step\n" +
                "E - Make Selected Note Long; press again to extend by 1 grid\n" +
                "Z/X - Zoom Grid Out / In\n" +
                "W/S - Move 1 Step     A/D - Prev/Next Section\n" +
                "Mouse Wheel - Scroll Through Grid\n" +
                "Left Click - Place Note     Right Click - Delete Note\n\n" +
                "Opponent: 0\nPlayer: 0\nTotal Notes: 0\nRendered Notes: 0");
        return area;
    }

    private JPanel createControlsTab() {
        JPanel p = new JPanel(new BorderLayout(8, 8));
        p.setBorder(new EmptyBorder(10, 10, 10, 10));
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setText(
                "KEYBOARD CONTROLS\n\n" +
                "SPACE - Start / Pause Playback\n" +
                "E - Make selected note long; press E again to extend by 1 grid\n" +
                "P - Extend the selected long note by exactly 1/16 step\n" +
                "Z - Zoom Grid Out\n" +
                "X - Zoom Grid In\n" +
                "W / S - Move 1 Step\n" +
                "A / D - Previous / Next Section\n" +
                "R - Swap Current Section Sides\n" +
                "Mouse Wheel - Scroll Through the Section\n" +
                "Left Click - Place Note / Continue Long Note\n" +
                "Right Click - Delete Note\n\n" +
                "LONG NOTES\n" +
                "Place a note, press E to make/extend it, then press P for one 1/16\n" +
                "step at a time. Clicking another note selects that note and resets the\n" +
                "previous long-note selection.");
        p.add(new JScrollPane(area), BorderLayout.CENTER);
        return p;
    }

    private JPanel createChartingTab() {
        JPanel p = new JPanel(new GridLayout(0, 1, 5, 5));
        p.setBorder(new EmptyBorder(10, 10, 10, 10));
        p.add(new JCheckBox("Metronome Enabled"));
        p.add(new JCheckBox("Disable Autoscroll", false));
        p.add(new JCheckBox("Show Grid", true));
        p.add(new JCheckBox("Save Undos", true));
        p.add(new JLabel("Playback Rate:"));
        p.add(new JSlider(25, 400, 100));
        return p;
    }

    private JPanel createDataTab() {
        JPanel p = new JPanel(new GridLayout(0, 1, 5, 5));
        p.setBorder(new EmptyBorder(10, 10, 10, 10));
        p.add(new JLabel("Song Credit:"));
        p.add(new JTextField("Test"));
        p.add(new JLabel("Credit Icon:"));
        p.add(new JTextField("bf"));
        p.add(new JCheckBox("Disable Note RGB"));
        return p;
    }

    private JPanel createEventsTab() {
        JPanel p = new JPanel(new GridLayout(0, 1, 5, 5));
        p.setBorder(new EmptyBorder(10, 10, 10, 10));
        p.add(new JLabel("Event:"));
        p.add(new JComboBox<>(new String[]{"---", "Hey!", "Camera Flash", "Play Animation"}));
        p.add(new JLabel("Value 1:"));
        p.add(new JTextField());
        p.add(new JLabel("Value 2:"));
        p.add(new JTextField());
        p.add(new JButton("Add Event"));
        return p;
    }

    private JPanel createNoteTab() {
        JPanel p = new JPanel(new GridLayout(0, 1, 5, 5));
        p.setBorder(new EmptyBorder(10, 10, 10, 10));
        
        p.add(new JLabel("Sustain Length (ms):"));
        sustainSpinner = new JSpinner(new SpinnerNumberModel(0.0, 0.0, 5000.0, 50.0));
        p.add(sustainSpinner);

        p.add(new JLabel("Strum Time (ms):"));
        strumTimeField = new JTextField("0.0");
        strumTimeField.setEditable(false);
        p.add(strumTimeField);

        p.add(new JLabel("Note Type:"));
        noteTypeCombo = new JComboBox<>(new String[]{"Default", "Alt Animation", "Mine", "Hurt Note"});
        p.add(noteTypeCombo);

        JButton copyNotes = new JButton("Copy Notes");
        copyNotes.addActionListener(e -> copySelectedNote());
        p.add(copyNotes);

        JButton pasteNotes = new JButton("Paste Notes");
        pasteNotes.addActionListener(e -> pasteCopiedNote());
        p.add(pasteNotes);

        return p;
    }

    private JPanel createSpammingTab() {
        JPanel p = new JPanel(new GridLayout(0, 1, 5, 5));
        p.setBorder(new EmptyBorder(10, 10, 10, 10));

        p.add(new JLabel("Spam Density (notes per grid row):"));
        densitySpinner = new JSpinner(new SpinnerNumberModel(1, 1, Integer.MAX_VALUE, 1));
        p.add(densitySpinner);

        p.add(new JLabel("Spam Strength (grid rows to fill, 16 = full section):"));
        strengthSpinner = new JSpinner(new SpinnerNumberModel(1.0, 0.03125, Double.MAX_VALUE, 0.03125));
        p.add(strengthSpinner);

        p.add(new JLabel("Lane range for spam (0-7):"));
        SpinnerNumberModel fromModel = new SpinnerNumberModel(0, 0, 7, 1);
        SpinnerNumberModel toModel = new SpinnerNumberModel(7, 0, 7, 1);
        JSpinner laneFromSpinner = new JSpinner(fromModel);
        JSpinner laneToSpinner = new JSpinner(toModel);
        JPanel laneRangePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        laneRangePanel.add(new JLabel("From:"));
        laneRangePanel.add(laneFromSpinner);
        laneRangePanel.add(new JLabel("To:"));
        laneRangePanel.add(laneToSpinner);
        p.add(laneRangePanel);

JButton spamNotesBtn = new JButton("Spam Notes Across Grids");
        spamNotesBtn.addActionListener(e -> {
            commitSpinner(densitySpinner);
            commitSpinner(strengthSpinner);
            int fromLane = (int) laneFromSpinner.getValue();
            int toLane = (int) laneToSpinner.getValue();
            int density = (int) densitySpinner.getValue();
            double strength = ((Number) strengthSpinner.getValue()).doubleValue();
            if (fromLane > toLane) {
                int tmp = fromLane;
                fromLane = toLane;
                toLane = tmp;
            }
            gridPanel.spamNotesForCurrentSection(fromLane, toLane, density, strength, 0);
        });
        p.add(spamNotesBtn);

        ezSpamCheckbox = new JCheckBox("Enable EZ Spam Mode");
        p.add(ezSpamCheckbox);
        
        p.add(new JButton("Stretch Notes"));
        p.add(new JButton("Shift Notes"));
        p.add(new JButton("Duplicate Notes"));

        return p;
    }

    private JPanel createOptimiserTab() {
        JPanel p = new JPanel(new GridLayout(0, 1, 5, 5));
        p.setBorder(new EmptyBorder(10, 10, 10, 10));

        p.add(new JLabel("Note Rendering Transparency (Optimisation):"));
        
        opacitySlider = new JSlider(JSlider.HORIZONTAL, 0, 100, 100);
        opacitySlider.setMajorTickSpacing(20);
        opacitySlider.setPaintTicks(true);
        opacitySlider.setPaintLabels(true);
        
        opacitySlider.addChangeListener(e -> {
            if (gridPanel != null) {
                gridPanel.repaint();
            }
        });
        
        p.add(opacitySlider);
        
        JCheckBox fastGridCheck = new JCheckBox("Low-Latency Grid Rendering Mode");
        p.add(fastGridCheck);

        return p;
    }

    private JPanel createSectionTab() {
        JPanel p = new JPanel(new GridLayout(0, 1, 5, 5));
        p.setBorder(new EmptyBorder(10, 10, 10, 10));
        
        JCheckBox mustHit = new JCheckBox("Must Hit Section (BF Camera Focus)");
        mustHit.addActionListener(e -> {
            activeSong.notes.get(currentSectionIndex).mustHitSection = mustHit.isSelected();
            gridPanel.repaint();
        });
        p.add(mustHit);

        JButton clearSec = new JButton("Clear Section");
        clearSec.addActionListener(e -> {
            activeSong.notes.get(currentSectionIndex).sectionNotes.clear();
            gridPanel.selectedNoteIndex = -1;
            gridPanel.repaint();
        });
        p.add(clearSec);

        JButton clearOpponentSec = new JButton("Clear Opponent Side (Lanes 0-3)");
        clearOpponentSec.addActionListener(e -> {
            Section sec = activeSong.notes.get(currentSectionIndex);
            double stepTimeMs = getEffectiveSectionBpm(sec) > 0 ? (60000.0 / getEffectiveSectionBpm(sec)) / 4.0 : 0.0;
            sec.sectionNotes.removeMatching(0.0, sec.lengthInSteps * stepTimeMs, stepTimeMs, 0, 3);
            gridPanel.selectedNoteIndex = -1;
            gridPanel.repaint();
        });
        p.add(clearOpponentSec);

        JButton clearPlayerSec = new JButton("Clear Player Side (Lanes 4-7)");
        clearPlayerSec.addActionListener(e -> {
            Section sec = activeSong.notes.get(currentSectionIndex);
            double stepTimeMs = getEffectiveSectionBpm(sec) > 0 ? (60000.0 / getEffectiveSectionBpm(sec)) / 4.0 : 0.0;
            sec.sectionNotes.removeMatching(0.0, sec.lengthInSteps * stepTimeMs, stepTimeMs, 4, 7);
            gridPanel.selectedNoteIndex = -1;
            gridPanel.repaint();
        });
        p.add(clearPlayerSec);

        JButton removeNotes = new JButton("Remove Notes From Chart...");
        removeNotes.addActionListener(e -> removeNotesFromChart());
        p.add(removeNotes);

        JButton swapSec = new JButton("Swap Section Sides (R)");
        swapSec.addActionListener(e -> swapCurrentSectionSides());
        p.add(swapSec);

        JButton copySection = new JButton("Copy Notes Section");
        copySection.addActionListener(e -> copyCurrentSection());
        p.add(copySection);

        JButton copyAllNotes = new JButton("Copy All Notes");
        copyAllNotes.setToolTipText("Copy every note in the current chart, skipping empty sections.");
        copyAllNotes.addActionListener(e -> copyAllNotes());
        p.add(copyAllNotes);

        JButton pasteAllNotes = new JButton("Paste All Notes");
        pasteAllNotes.setToolTipText("Paste copied non-empty sections starting at the current section.");
        pasteAllNotes.addActionListener(e -> pasteAllNotes());
        p.add(pasteAllNotes);

        JButton pasteHereSection = new JButton("Paste Notes Section Here");
        pasteHereSection.addActionListener(e -> pasteCopiedSectionHere());
        p.add(pasteHereSection);

        JButton pasteNextSection = new JButton("Paste Next Notes Section");
        pasteNextSection.addActionListener(e -> pasteNextCopiedSection());
        p.add(pasteNextSection);

        return p;
    }

    private void commitSpinner(JSpinner spinner) {
        if (spinner == null) return;
        if (spinner.getEditor() instanceof JSpinner.DefaultEditor) {
            try {
                ((JSpinner.DefaultEditor) spinner.getEditor()).commitEdit();
            } catch (java.text.ParseException ignored) {
            }
        }
    }

    private void swapCurrentSectionSides() {
        if (activeSong.notes == null || activeSong.notes.isEmpty()) return;
        currentSectionIndex = Math.max(0, Math.min(currentSectionIndex, activeSong.notes.size() - 1));
        Section sec = activeSong.notes.get(currentSectionIndex);
        sec.sectionNotes.swapLanes();
        if (gridPanel != null) {
            gridPanel.selectedNoteIndex = -1;
            gridPanel.repaint();
        }
    }

    private void removeNotesFromChart() {
        long totalNotes = 0;
        for (Section section : activeSong.notes) {
            totalNotes += section.sectionNotes.size();
        }

        if (totalNotes <= 0) {
            JOptionPane.showMessageDialog(
                    this,
                    "There are no notes to remove.",
                    "Remove Notes",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        String input = JOptionPane.showInputDialog(
                this,
                "How many notes would you like to remove?\n"
                        + "Current notes: " + formatEveryThirdDigit(totalNotes) + "\n\n"
                        + "Notes are removed from the end of the chart.",
                "Remove Notes",
                JOptionPane.QUESTION_MESSAGE);

        if (input == null) return;

        final long requested;
        try {
            requested = Long.parseLong(input.trim());
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(
                    this,
                    "Please enter a whole number, for example 500000.",
                    "Invalid Note Count",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }

        if (requested <= 0) {
            JOptionPane.showMessageDialog(
                    this,
                    "The number of notes to remove must be greater than 0.",
                    "Invalid Note Count",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }

        if (requested > totalNotes) {
            JOptionPane.showMessageDialog(
                    this,
                    "You cannot remove " + formatEveryThirdDigit(requested)
                            + " notes because the chart only has " + formatEveryThirdDigit(totalNotes) + ".",
                    "Too Many Notes",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }

        int answer = JOptionPane.showConfirmDialog(
                this,
                "Remove " + formatEveryThirdDigit(requested) + " notes from the end of the chart?\n\n"
                        + "The chart will go from " + formatEveryThirdDigit(totalNotes)
                        + " notes to " + formatEveryThirdDigit(totalNotes - requested) + " notes.",
                "Confirm Note Removal",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);

        if (answer != JOptionPane.YES_OPTION) return;

        long remaining = requested;
        for (int sectionIndex = activeSong.notes.size() - 1; sectionIndex >= 0 && remaining > 0; sectionIndex--) {
            Section section = activeSong.notes.get(sectionIndex);
            long removedHere = section.sectionNotes.removeLast(remaining);
            remaining -= removedHere;
        }

        gridPanel.selectedNoteIndex = -1;
        gridPanel.repaint();

        JOptionPane.showMessageDialog(
                this,
                "Removed " + formatEveryThirdDigit(requested) + " notes.\n"
                        + "Notes remaining: " + formatEveryThirdDigit(totalNotes - requested),
                "Notes Removed",
                JOptionPane.INFORMATION_MESSAGE);
    }

    private void copySelectedNote() {
        if (activeSong.notes.isEmpty()) return;
        Section sec = activeSong.notes.get(currentSectionIndex);
        if (gridPanel.selectedNoteIndex < 0 || gridPanel.selectedNoteIndex >= sec.sectionNotes.size()) {
            JOptionPane.showMessageDialog(
                    this,
                    "Select a note first, then press Copy Notes.",
                    "Copy Notes",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        double stepTimeMs = getCurrentSectionStepTimeMs();
        copiedNote = new CopiedNote(
                sec.sectionNotes.getTime(gridPanel.selectedNoteIndex, stepTimeMs),
                sec.sectionNotes.getLane(gridPanel.selectedNoteIndex),
                sec.sectionNotes.getSustain(gridPanel.selectedNoteIndex, stepTimeMs));

        JOptionPane.showMessageDialog(
                this,
                "Copied 1 note.\nUse Paste Notes to place it at the current grid cursor.",
                "Copy Notes",
                JOptionPane.INFORMATION_MESSAGE);
    }

    private void pasteCopiedNote() {
        if (copiedNote == null) {
            JOptionPane.showMessageDialog(
                    this,
                    "There is no copied note yet. Use Copy Notes first.",
                    "Paste Notes",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        if (activeSong.notes.isEmpty()) activeSong.notes.add(new Section());
        Section sec = activeSong.notes.get(currentSectionIndex);
        double stepTimeMs = getCurrentSectionStepTimeMs();
        int targetRow = gridPanel.lastClickedRow >= 0 ? gridPanel.lastClickedRow : (int) Math.floor(positionStepsDouble % GRID_STEPS_PER_SECTION);
        targetRow = Math.max(0, Math.min(GRID_STEPS_PER_SECTION - 1, targetRow));
        double targetTime = targetRow * stepTimeMs;

        gridPanel.selectedNoteIndex = sec.sectionNotes.add(
                targetTime,
                copiedNote.lane,
                copiedNote.sustainMs,
                targetRow,
                stepTimeMs);
        gridPanel.lastClickedLane = copiedNote.lane;
        gridPanel.lastClickedRow = targetRow;
        sustainSpinner.setValue(Math.max(0.0, Math.min(5000.0, copiedNote.sustainMs)));
        strumTimeField.setText(String.format("%.2f", rowTimeToGlobalMs(targetTime)));
        gridPanel.repaint();
    }

    private void copyCurrentSection() {
        if (activeSong.notes.isEmpty()) return;
        Section source = activeSong.notes.get(currentSectionIndex);
        copiedSectionNotes.clear();
        copiedSectionNotes.appendFrom(source.sectionNotes);
        copiedSectionMustHit = source.mustHitSection;
        copiedSectionLengthInSteps = source.lengthInSteps;
        hasCopiedSection = true;

        JOptionPane.showMessageDialog(
                this,
                "Copied section " + currentSectionIndex + " ("
                        + formatEveryThirdDigit(source.sectionNotes.size()) + " notes).\n"
                        + "Use Paste Notes Section Here to replace the current section, or "
                        + "Paste Next Notes Section to paste into the next section.",
                "Copy Notes Section",
                JOptionPane.INFORMATION_MESSAGE);
    }

    private void clearCopiedAllNotesClipboard() {
        for (CopiedAllSection copied : copiedAllSections) {
            copied.sectionNotes.clear();
        }
        copiedAllSections.clear();
        hasCopiedAllNotes = false;
        copiedAllNotesCount = 0;
    }

    private void copyAllNotes() {
        if (activeSong == null || activeSong.notes == null || activeSong.notes.isEmpty()) {
            JOptionPane.showMessageDialog(
                    this,
                    "There are no chart sections to copy.",
                    "Copy All Notes",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        clearCopiedAllNotesClipboard();

        int nonEmptySections = 0;
        long totalNotes = 0;

        // Copy every non-empty section in order. Empty sections are deliberately
        // skipped so Paste All Notes does not recreate empty spacing.
        for (Section source : activeSong.notes) {
            if (source == null || source.sectionNotes == null || source.sectionNotes.size() == 0) {
                continue;
            }

            CopiedAllSection copied = new CopiedAllSection();
            copied.sectionNotes.appendFrom(source.sectionNotes);
            copied.mustHitSection = source.mustHitSection;
            copied.lengthInSteps = Math.max(1, source.lengthInSteps);
            copiedAllSections.add(copied);

            nonEmptySections++;
            totalNotes += source.sectionNotes.size();
        }

        if (copiedAllSections.isEmpty()) {
            JOptionPane.showMessageDialog(
                    this,
                    "There are no notes in this chart to copy.",
                    "Copy All Notes",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        copiedAllNotesCount = totalNotes;
        hasCopiedAllNotes = true;

        JOptionPane.showMessageDialog(
                this,
                "Copied " + formatEveryThirdDigit(totalNotes) + " notes from "
                        + formatEveryThirdDigit(nonEmptySections) + " non-empty sections.\n\n"
                        + "Empty sections were ignored. Paste All Notes will place "
                        + "the copied non-empty sections consecutively starting at the current section.",
                "Copy All Notes",
                JOptionPane.INFORMATION_MESSAGE);
    }

    private void pasteAllNotes() {
        if (!hasCopiedAllNotes || copiedAllSections.isEmpty()) {
            JOptionPane.showMessageDialog(
                    this,
                    "There are no copied notes yet. Use Copy All Notes first.",
                    "Paste All Notes",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        if (activeSong == null) return;
        if (activeSong.notes == null) activeSong.notes = new ArrayList<>();
        if (activeSong.notes.isEmpty()) activeSong.notes.add(new Section());

        int startIndex = Math.max(0, Math.min(currentSectionIndex, activeSong.notes.size() - 1));
        int endIndex = startIndex + copiedAllSections.size() - 1;

        int answer = JOptionPane.showConfirmDialog(
                this,
                "Paste " + formatEveryThirdDigit(copiedAllNotesCount) + " notes from "
                        + formatEveryThirdDigit(copiedAllSections.size()) + " non-empty sections starting at Section "
                        + startIndex + "?\n\n"
                        + "Existing notes in those destination sections will be replaced.\n"
                        + "Empty source sections will NOT be pasted or counted.",
                "Paste All Notes",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.YES_OPTION) return;

        while (activeSong.notes.size() <= endIndex) {
            activeSong.notes.add(new Section());
        }

        int targetIndex = startIndex;
        for (CopiedAllSection copied : copiedAllSections) {
            Section target = activeSong.notes.get(targetIndex++);
            target.sectionNotes.clear();
            target.sectionNotes.appendFrom(copied.sectionNotes);
            target.mustHitSection = copied.mustHitSection;
            target.lengthInSteps = Math.max(1, copied.lengthInSteps);
        }

        currentSectionIndex = startIndex;
        positionSteps = (long) startIndex * GRID_STEPS_PER_SECTION;
        positionStepsDouble = positionSteps;
        gridPanel.selectedNoteIndex = -1;
        gridPanel.lastClickedRow = -1;
        gridPanel.setScrollRowOffset(0.0);
        gridPanel.repaint();

        JOptionPane.showMessageDialog(
                this,
                "Pasted " + formatEveryThirdDigit(copiedAllNotesCount) + " notes into "
                        + formatEveryThirdDigit(copiedAllSections.size()) + " consecutive sections.\n"
                        + "Empty source sections were ignored.",
                "Paste All Notes",
                JOptionPane.INFORMATION_MESSAGE);
    }

    private void pasteCopiedSectionHere() {
        if (!hasCopiedSection) {
            JOptionPane.showMessageDialog(
                    this,
                    "There is no copied section yet. Use Copy Notes Section first.",
                    "Paste Notes Section Here",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        if (activeSong.notes.isEmpty()) activeSong.notes.add(new Section());
        Section target = activeSong.notes.get(currentSectionIndex);
        int answer = JOptionPane.showConfirmDialog(
                this,
                "Paste the copied section into Section " + currentSectionIndex + "?\n\n"
                        + "This will replace the notes currently in this section.",
                "Paste Notes Section Here",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.YES_OPTION) return;

        target.sectionNotes.clear();
        target.sectionNotes.appendFrom(copiedSectionNotes);
        target.mustHitSection = copiedSectionMustHit;
        target.lengthInSteps = copiedSectionLengthInSteps;
        gridPanel.selectedNoteIndex = -1;
        gridPanel.lastClickedRow = -1;
        gridPanel.setScrollRowOffset(0.0);
        gridPanel.repaint();
    }

    private void pasteNextCopiedSection() {
        if (!hasCopiedSection) {
            JOptionPane.showMessageDialog(
                    this,
                    "There is no copied section yet. Use Copy Notes Section first.",
                    "Paste Next Notes Section",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        int targetIndex = currentSectionIndex + 1;
        while (targetIndex >= activeSong.notes.size()) {
            activeSong.notes.add(new Section());
        }

        Section target = activeSong.notes.get(targetIndex);
        int answer = JOptionPane.showConfirmDialog(
                this,
                "Paste the copied section into Section " + targetIndex + "?\n\n"
                        + "This will replace the notes currently in that section.",
                "Paste Next Notes Section",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.YES_OPTION) return;

        target.sectionNotes.clear();
        target.sectionNotes.appendFrom(copiedSectionNotes);
        target.mustHitSection = copiedSectionMustHit;
        target.lengthInSteps = copiedSectionLengthInSteps;
        currentSectionIndex = targetIndex;
        positionSteps = (long) targetIndex * GRID_STEPS_PER_SECTION;
        positionStepsDouble = positionSteps;
        gridPanel.selectedNoteIndex = -1;
        gridPanel.lastClickedRow = -1;
        gridPanel.setScrollRowOffset(0.0);
        gridPanel.repaint();
    }

    private double rowTimeToGlobalMs(double relativeTimeMs) {
        return getSectionStartTimeMs(currentSectionIndex) + relativeTimeMs;
    }

    private JPanel createSongTab() {
        JPanel p = new JPanel(new GridLayout(0, 2, 5, 5));
        p.setBorder(new EmptyBorder(10, 10, 10, 10));

        p.add(new JLabel("Song:"));
        songNameField = new JTextField("Test");
        p.add(songNameField);

        p.add(new JLabel("BPM:"));
        bpmSpinner = new JSpinner(new SpinnerNumberModel(150.0, 1.0, 999999.0, 1.0));
        p.add(bpmSpinner);

        p.add(new JLabel("Speed:"));
        speedSpinner = new JSpinner(new SpinnerNumberModel(1.6, 0.0, 2147483647.0, 0.1));
        p.add(speedSpinner);

        p.add(new JLabel("Boyfriend:"));
        player1Combo = new JComboBox<>(new String[]{"bf", "bf-pixel", "bf-car"});
        player1Combo.setEditable(true);
        p.add(player1Combo);

        p.add(new JLabel("Daddy Dearest (Opponent):"));
        player2Combo = new JComboBox<>(new String[]{"dad", "daddy-dearest", "pico", "mom", "bf-pixel-opponent"});
        player2Combo.setEditable(true);
        p.add(player2Combo);

        p.add(new JLabel("Girlfriend:"));
        girlfriendCombo = new JComboBox<>(new String[]{"gf", "gf-pixel", "gf-car"});
        girlfriendCombo.setEditable(true);
        p.add(girlfriendCombo);

        voiceTrackCheckbox = new JCheckBox("Has Voices", true);
        p.add(voiceTrackCheckbox);
        p.add(new JLabel("")); 

        JButton loadAudioBtn = new JButton("Import Audio Track (.wav)");
        loadAudioBtn.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser(".");
            chooser.setDialogTitle("Select Song Audio (.wav)");
            int choice = chooser.showOpenDialog(this);
            if (choice == JFileChooser.APPROVE_OPTION) {
                File file = chooser.getSelectedFile();
                activeSong.audioFilePath = file.getAbsolutePath();
                audioTrackLabel.setText("Loaded: " + file.getName());
                loadAudioEngine(file);
            }
        });
        p.add(loadAudioBtn);
        
        audioTrackLabel = new JLabel("No Audio loaded");
        p.add(audioTrackLabel);

        JButton saveBtn = new JButton("Save Chart");
        saveBtn.addActionListener(e -> chooseSaveFormatAndSave());
        p.add(saveBtn);

        JButton loadBtn = new JButton("Load Chart (JSON / BIN)");
        loadBtn.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser(initialImportDirectory());
            chooser.setDialogTitle("Load FNF Chart (JSON or BIN)");
            int choice = chooser.showOpenDialog(this);
            if (choice == JFileChooser.APPROVE_OPTION) {
                loadChart(chooser.getSelectedFile());
            }
        });
        p.add(loadBtn);

        JButton minifyBtn = new JButton("Minify JSON");
        minifyBtn.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser(".");
            chooser.setDialogTitle("Select JSON File to Minify");
            int choice = chooser.showOpenDialog(this);
            if (choice == JFileChooser.APPROVE_OPTION) {
                minifyJSONFile(chooser.getSelectedFile(), minifyBtn);
            }
        });
        p.add(minifyBtn);

        return p;
    }

    private String minifyJSON(String json) {
        StringBuilder sb = new StringBuilder(json.length());
        boolean inString = false;
        boolean escape = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                sb.append(c);
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
            } else {
                if (c == '"') {
                    inString = true;
                    sb.append(c);
                } else if (!Character.isWhitespace(c)) {
                    sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    private void minifyJSONFile(File file, JButton buttonToDisable) {
        if (buttonToDisable != null) {
            buttonToDisable.setText("Minifying...");
            buttonToDisable.setEnabled(false);
        }

        new Thread(() -> {
            try {
                StringBuilder content = new StringBuilder();
                try (BufferedReader br = new BufferedReader(new FileReader(file))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        content.append(line).append('\n');
                    }
                }
                String original = content.toString();
                String minified = minifyJSON(original);
                if (minified.equals(original.trim())) {
                    SwingUtilities.invokeLater(() -> {
                        if (buttonToDisable != null) {
                            buttonToDisable.setText("Minify JSON");
                            buttonToDisable.setEnabled(true);
                        }
                        JOptionPane.showMessageDialog(this, "File is already minified: " + file.getName());
                    });
                    return;
                }
                try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
                    writer.write(minified);
                }
                SwingUtilities.invokeLater(() -> {
                    if (buttonToDisable != null) {
                        buttonToDisable.setText("Minify JSON");
                        buttonToDisable.setEnabled(true);
                    }
                    JOptionPane.showMessageDialog(this, "Minified successfully: " + file.getName());
                });
            } catch (IOException e) {
                SwingUtilities.invokeLater(() -> {
                    if (buttonToDisable != null) {
                        buttonToDisable.setText("Minify JSON");
                        buttonToDisable.setEnabled(true);
                    }
                    JOptionPane.showMessageDialog(this, "Error minifying file: " + e.getMessage());
                });
            }
        }).start();
    }

    private void loadAudioEngine(File file) {
        try {
            if (audioClip != null && audioClip.isOpen()) {
                audioClip.close();
            }
            AudioInputStream audioStream = AudioSystem.getAudioInputStream(file);
            audioClip = AudioSystem.getClip();
            audioClip.open(audioStream);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Audio conversion error: " + e.getMessage());
        }
    }

    private void togglePlayback() {
        if (isPlaying) {
            isPlaying = false;
            playbackTimer.stop();
            if (audioClip != null) {
                audioClip.stop();
            }
        } else {
            isPlaying = true;
            lastTickMs = System.currentTimeMillis();
            playbackTimer.start();
            if (audioClip != null) {
                long ms = stepsToMs(positionSteps);
                if (ms < 0) ms = 0;
                if (audioClip.getMicrosecondLength() > 0) {
                    ms = Math.min(ms, audioClip.getMicrosecondLength() / 1000);
                }
                audioClip.setMicrosecondPosition(ms * 1000);
                audioClip.start();
            }
        }
        if (gridPanel != null) {
            gridPanel.requestFocusInWindow();
            gridPanel.repaint();
        }
    }

    private void handleSpaceKey() {
        togglePlayback();
    }

    private long stepsToMs(long steps) {
        if (activeSong == null || activeSong.notes.isEmpty() || steps <= 0) return 0L;
        int sectionIndex = (int) Math.max(0L, Math.min((long) activeSong.notes.size() - 1, steps / GRID_STEPS_PER_SECTION));
        double sectionStart = getSectionStartTimeMs(sectionIndex);
        long sectionStep = steps - (long) sectionIndex * GRID_STEPS_PER_SECTION;
        double stepMs = getCurrentSectionStepTimeMsFor(sectionIndex);
        return (long) Math.round(sectionStart + sectionStep * stepMs);
    }

    private double getCurrentSectionStepTimeMsFor(int sectionIndex) {
        if (activeSong == null || activeSong.notes.isEmpty()) {
            return (60000.0 / Math.max(1.0, activeSong == null ? 120.0 : activeSong.bpm)) / 4.0;
        }
        int idx = Math.max(0, Math.min(sectionIndex, activeSong.notes.size() - 1));
        return (60000.0 / getEffectiveSectionBpm(activeSong.notes.get(idx))) / 4.0;
    }

    private void applyPositionSteps(long newSteps) {
        if (activeSong.notes.isEmpty()) {
            activeSong.notes.add(new Section());
        }

        if (newSteps < 0) newSteps = 0;

        long section = newSteps / GRID_STEPS_PER_SECTION;

        while (section >= activeSong.notes.size()) {
            activeSong.notes.add(new Section());
        }

        positionSteps = newSteps;
        positionStepsDouble = newSteps;
        currentSectionIndex = (int) section;
        updateBpmDisplayForCurrentSection();
        gridPanel.setScrollRowOffset(0.0);
    }

    private void updatePlaybackSection(long nextSteps) {
        if (activeSong.notes.isEmpty()) {
            activeSong.notes.add(new Section());
        }

        long section = Math.max(0L, nextSteps) / GRID_STEPS_PER_SECTION;
        while (section >= activeSong.notes.size()) {
            activeSong.notes.add(new Section());
        }
        currentSectionIndex = (int) section;
        positionSteps = Math.max(0L, nextSteps);
        updateBpmDisplayForCurrentSection();
    }

    private void updatePlayback() {
        if (!isPlaying) return;

        long now = System.currentTimeMillis();
        long deltaMs = now - lastTickMs;
        if (deltaMs < 0) deltaMs = 0;
        lastTickMs = now;

        // Playback must follow the BPM of the section currently being played.
        // The old code used the song-level spinner BPM for the entire chart,
        // which made imported changeBPM sections play at the initial tempo.
        double stepMs = getCurrentSectionStepTimeMsFor(currentSectionIndex);
        double deltaSteps = deltaMs / stepMs;

        positionStepsDouble += deltaSteps;
        long nextSteps = (long) Math.floor(positionStepsDouble);

        if (nextSteps != positionSteps) {
            updatePlaybackSection(nextSteps);
        }

        gridPanel.updatePlaybackCamera(positionStepsDouble);
        gridPanel.repaint();
    }

    private void updateBpmDisplayForCurrentSection() {
        if (bpmSpinner == null || activeSong == null || activeSong.notes.isEmpty()) return;
        double bpm = getEffectiveSectionBpm(activeSong.notes.get(
                Math.max(0, Math.min(currentSectionIndex, activeSong.notes.size() - 1))));
        bpmSpinner.setValue(bpm);
    }

    private void syncSongDataFromUI() {
        activeSong.song = songNameField.getText();
        // BPM spinner can display the currently active section's BPM. Only
        // write it to the song-level BPM when editing the base section.
        if (activeSong.notes.isEmpty() || currentSectionIndex <= 0 ||
                !activeSong.notes.get(Math.min(currentSectionIndex, activeSong.notes.size() - 1)).changeBPM) {
            activeSong.bpm = (double) bpmSpinner.getValue();
        }
        activeSong.speed = (double) speedSpinner.getValue();
        activeSong.player1 = (String) player1Combo.getSelectedItem();
        activeSong.player2 = (String) player2Combo.getSelectedItem();
        activeSong.gfVersion = (String) girlfriendCombo.getSelectedItem();
        activeSong.needsVoices = voiceTrackCheckbox.isSelected();
    }

    private void syncSongDataToUI() {
        songNameField.setText(activeSong.song);
        bpmSpinner.setValue(activeSong.bpm);
        speedSpinner.setValue(activeSong.speed);
        player1Combo.setSelectedItem(activeSong.player1);
        player2Combo.setSelectedItem(activeSong.player2);
        girlfriendCombo.setSelectedItem(activeSong.gfVersion);
        voiceTrackCheckbox.setSelected(activeSong.needsVoices);
        if(!activeSong.audioFilePath.isEmpty()) {
            audioTrackLabel.setText("Loaded: " + new File(activeSong.audioFilePath).getName());
            loadAudioEngine(new File(activeSong.audioFilePath));
        }
    }

    private static final long JSON_ONE_GB_WARNING_BYTES = Long.MAX_VALUE;
    private static final long MAX_FILE_BYTES = Long.MAX_VALUE;
    private static final long JSON_CLOSE_RESERVE_BYTES = 256L;

    private static final class SaveResult {
        final File file;
        final long notes;
        final long bytes;

        SaveResult(File file, long notes, long bytes) {
            this.file = file;
            this.notes = notes;
            this.bytes = bytes;
        }
    }

    private static final class CountingWriter implements Closeable {
        private final BufferedWriter writer;
        private long bytesWritten;

        CountingWriter(File file) throws IOException {
            writer = new BufferedWriter(
                    new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8),
                    64 * 1024);
        }

        void write(String text) throws IOException {
            writer.write(text);
            bytesWritten += text.getBytes(StandardCharsets.UTF_8).length;
        }

        long bytesWritten() {
            return bytesWritten;
        }

        @Override
        public void close() throws IOException {
            writer.close();
        }
    }

    private static final class BinaryPartWriter implements Closeable {
        private final File finalFile;
        private final File tempFile;
        private final DataOutputStream out;
        private long notesWritten;
        private long bytesWritten;

        BinaryPartWriter(File finalFile, SongData song) throws IOException {
            this.finalFile = finalFile;
            this.tempFile = new File(finalFile.getAbsolutePath() + ".saving");
            if (tempFile.exists() && !tempFile.delete()) {
                throw new IOException("Unable to replace temporary save file: " + tempFile.getAbsolutePath());
            }
            out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tempFile), 64 * 1024));
            writeString("FNFEBIN3");
            writeString(song.song);
            out.writeDouble(song.bpm);
            out.writeBoolean(song.needsVoices);
            writeString(song.player1);
            writeString(song.player2);
            writeString(song.gfVersion);
            out.writeDouble(song.speed);
            bytesWritten += 8 + 1 + 8;
        }

        private void writeString(String value) throws IOException {
            byte[] b = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
            out.writeInt(b.length);
            out.write(b);
            bytesWritten += 4L + b.length;
        }

        long sizeBytes() { return bytesWritten; }
        long notesWritten() { return notesWritten; }

        void writeSection(Section section) throws IOException {
            out.writeInt(section.lengthInSteps);
            out.writeBoolean(section.mustHitSection);
            out.writeDouble(section.bpm);
            out.writeBoolean(section.changeBPM);
            out.writeLong(section.sectionNotes.size());
            bytesWritten += 22;
        }

        void writeNote(double globalTime, int lane, double sustain) throws IOException {
            out.writeDouble(globalTime);
            out.writeInt(lane);
            out.writeDouble(sustain);
            bytesWritten += 20;
            notesWritten++;
        }

        void finish() throws IOException {
            out.close();
        }

        File commit() throws IOException {
            if (!tempFile.exists()) throw new IOException("Temporary BIN part does not exist: " + tempFile);
            if (finalFile.exists() && !finalFile.delete()) throw new IOException("Unable to replace existing BIN: " + finalFile);
            Files.move(tempFile.toPath(), finalFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return finalFile;
        }

        void discard() {
            try { out.close(); } catch (IOException ignored) {}
            if (tempFile.exists()) tempFile.delete();
        }

        @Override public void close() throws IOException { out.close(); }
    }

    private static final class JsonPartWriter implements Closeable {
        private final File finalFile;
        private final File tempFile;
        private final CountingWriter writer;
        private boolean hasSection;
        private boolean sectionOpen;
        private boolean sectionHasNotes;
        private long notesWritten;

        JsonPartWriter(File finalFile, SongData song) throws IOException {
            this.finalFile = finalFile;
            this.tempFile = new File(finalFile.getAbsolutePath() + ".saving");
            if (tempFile.exists() && !tempFile.delete()) {
                throw new IOException("Unable to replace temporary save file: " + tempFile.getAbsolutePath());
            }
            this.writer = new CountingWriter(tempFile);
            writeHeader(song);
        }

        private void writeHeader(SongData song) throws IOException {
            writer.write("{\"song\":{\"song\":\"" + escapeJson(song.song)
                    + "\",\"bpm\":" + song.bpm
                    + ",\"needsVoices\":" + song.needsVoices
                    + ",\"player1\":\"" + escapeJson(song.player1)
                    + "\",\"player2\":\"" + escapeJson(song.player2)
                    + "\",\"gfVersion\":\"" + escapeJson(song.gfVersion)
                    + "\",\"speed\":" + song.speed
                    + ",\"notes\":[");
        }

        long sizeBytes() {
            return writer.bytesWritten();
        }

        boolean hasNotes() {
            return notesWritten > 0;
        }

        long notesWritten() {
            return notesWritten;
        }

        void startSection(Section section) throws IOException {
            if (hasSection) writer.write(",");
            writer.write("{\"lengthInSteps\":" + section.lengthInSteps
                    + ",\"bpm\":" + section.bpm
                    + ",\"changeBPM\":" + section.changeBPM
                    + ",\"mustHitSection\":" + section.mustHitSection
                    + ",\"sectionNotes\":[");
            hasSection = true;
            sectionOpen = true;
            sectionHasNotes = false;
        }

        long noteLineBytes(String noteJson) {
            return ((sectionHasNotes ? "," : "") + noteJson)
                    .getBytes(StandardCharsets.UTF_8).length;
        }

        void writeNote(String noteJson) throws IOException {
            if (sectionHasNotes) writer.write(",");
            writer.write(noteJson);
            sectionHasNotes = true;
            notesWritten++;
        }

        void endSection() throws IOException {
            if (!sectionOpen) return;
            writer.write("]}");
            sectionOpen = false;
            sectionHasNotes = false;
        }

        void finish() throws IOException {
            endSection();
            writer.write("],\"generatedBy\":\"Java FNF Chart Editor\"}}");
            writer.close();
        }

        File commit() throws IOException {
            if (tempFile.exists() == false) {
                throw new IOException("Temporary JSON part does not exist: " + tempFile.getAbsolutePath());
            }
            if (finalFile.exists() && !finalFile.delete()) {
                throw new IOException("Unable to replace existing file: " + finalFile.getAbsolutePath());
            }
            Files.move(tempFile.toPath(), finalFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return finalFile;
        }

        void discard() {
            try {
                writer.close();
            } catch (IOException ignored) {
            }
            if (tempFile.exists()) tempFile.delete();
        }

        @Override
        public void close() throws IOException {
            writer.close();
        }

        private static String escapeJson(String value) {
            if (value == null) return "";
            return value
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
        }
    }

    private static String jsonSizeText(long bytes) {
        return String.format(java.util.Locale.US, "%.2fGB", bytes / 1_000_000_000.0);
    }

    private static String jsonKbText(long bytes) {
        return String.format(java.util.Locale.US, "%.2fKB", bytes / 1024.0);
    }

    private static String formatNotes(long notes) {
        return String.format(java.util.Locale.US, "%,d", notes);
    }

    private void chooseSaveFormatAndSave() {
        syncSongDataFromUI();
        Object[] options = {"BIN", "JSON", "Cancel"};
        int choice = JOptionPane.showOptionDialog(
                this,
                "Wait before you start saving, which format",
                "Save Chart Format",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null, options, options[0]);
        if (choice == 2 || choice == JOptionPane.CLOSED_OPTION) return;

        boolean bin = choice == 0;
        JFileChooser chooser = new JFileChooser(".");
        chooser.setDialogTitle("Save Chart " + (bin ? "BIN" : "JSON"));
        int saveChoice = chooser.showSaveDialog(this);
        if (saveChoice != JFileChooser.APPROVE_OPTION) return;
        File selected = chooser.getSelectedFile();
        String ext = bin ? ".bin" : ".json";
        if (!selected.getName().toLowerCase().endsWith(ext)) selected = new File(selected.getAbsolutePath() + ext);
        saveChart(selected, null);
    }

    private void showSaveResults(List<SaveResult> results, long totalBytes, long totalNotes, long notesPerSection) {
        StringBuilder report = new StringBuilder();
        for (SaveResult result : results) {
            report.append(result.file.getName())
                    .append(" = ")
                    .append(formatNotes(result.notes))
                    .append(" notes = ")
                    .append(jsonKbText(result.bytes))
                    .append("\n");
        }
        report.append("\nFinished With ")
                .append(jsonKbText(totalBytes))
                .append("!");

        JDialog dialog = new JDialog(this, "Chart Save Results", true);
        dialog.setLayout(new BorderLayout(10, 10));

        JTextArea textArea = new JTextArea(report.toString());
        textArea.setEditable(false);
        textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        textArea.setBorder(new EmptyBorder(12, 12, 12, 12));
        textArea.setBackground(UIManager.getColor("Panel.background"));

        JLabel info = new JLabel(
                "Song: " + activeSong.song
                        + "    |    BPM: " + formatNumber(activeSong.bpm)
                        + "    |    Notes Per Section: " + formatNotes(notesPerSection)
                        + "    |    Total Notes In Song: " + formatNotes(totalNotes));
        info.setBorder(new EmptyBorder(10, 12, 0, 12));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton saveTxt = new JButton("Save As TXT File");
        JButton cancel = new JButton("Cancel");

        saveTxt.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Save Chart Results as TXT");
            chooser.setSelectedFile(new File(activeSong.song + "-results.txt"));
            int choice = chooser.showSaveDialog(dialog);
            if (choice != JFileChooser.APPROVE_OPTION) return;

            File txtFile = chooser.getSelectedFile();
            if (!txtFile.getName().toLowerCase().endsWith(".txt")) {
                txtFile = new File(txtFile.getParentFile(), txtFile.getName() + ".txt");
            }

            StringBuilder txt = new StringBuilder();
            txt.append("Song Name: ").append(activeSong.song).append("\n");
            txt.append("BPM: ").append(formatNumber(activeSong.bpm)).append("\n");
            txt.append("Notes Per Section: ").append(formatNotes(notesPerSection)).append("\n");
            txt.append("Total Notes In Song: ").append(formatNotes(totalNotes)).append("\n\n");
            txt.append(report);
            txt.append("\n");

            try {
                Files.writeString(txtFile.toPath(), txt.toString(), StandardCharsets.UTF_8);
                JOptionPane.showMessageDialog(dialog,
                        "TXT results saved to " + txtFile.getAbsolutePath() + "!",
                        "TXT Saved",
                        JOptionPane.INFORMATION_MESSAGE);
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(dialog,
                        "Error saving TXT file: " + ex.getMessage(),
                        "TXT Save Error",
                        JOptionPane.ERROR_MESSAGE);
            }
        });

        cancel.addActionListener(e -> dialog.dispose());
        buttons.add(saveTxt);
        buttons.add(cancel);

        dialog.add(info, BorderLayout.NORTH);
        dialog.add(new JScrollPane(textArea), BorderLayout.CENTER);
        dialog.add(buttons, BorderLayout.SOUTH);
        dialog.setSize(760, 520);
        dialog.setLocationRelativeTo(this);
        dialog.setMinimumSize(new Dimension(620, 380));
        dialog.setVisible(true);
    }

    private static String formatNumber(double value) {
        if (value == Math.rint(value)) {
            return String.format(java.util.Locale.US, "%.0f", value);
        }
        return String.format(java.util.Locale.US, "%.3f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private int showJsonWarning(String title, String message, String yesLabel, String noLabel) throws IOException {
        final int[] result = new int[]{JOptionPane.NO_OPTION};
        try {
            SwingUtilities.invokeAndWait(() -> result[0] = JOptionPane.showOptionDialog(
                    this,
                    message,
                    title,
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE,
                    null,
                    new Object[]{yesLabel, noLabel},
                    yesLabel));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return JOptionPane.NO_OPTION;
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new IOException("Could not show JSON size prompt", e.getCause());
        }
        return result[0];
    }

    private int askToSplitAtOneGb(int partNumber, long currentBytes) throws IOException {
        return showJsonWarning(
                "1GB Limit",
                "1GB Limit\n"
                        + "you reach the limit!\n"
                        + "if you want to split now\n"
                        + "before it takes hours to load\n\n"
                        + "Current file size: " + jsonSizeText(currentBytes),
                "YES",
                "NO");
    }

    private int askToSplitAtHardLimit(int partNumber, long currentBytes) throws IOException {
        return showJsonWarning(
                "2GB Limit",
                "You reached another limit\n"
                        + "if you press no\n"
                        + "the chart will unable to load\n\n"
                        + "Current file size: " + jsonSizeText(currentBytes),
                "YES",
                "NO");
    }

    private File splitFileName(File original, int partNumber) {
        String name = original.getName();
        if (name.toLowerCase().endsWith(".json")) {
            name = name.substring(0, name.length() - 5);
        }
        String finalName = partNumber == 1 ? name + ".json" : name + ".part" + partNumber + ".json";
        return new File(original.getParentFile(), finalName);
    }

    private String makeNoteJson(double globalTime, int lane, double sustain) {
        return "[" + format3(globalTime) + "," + lane + "," + format3(sustain) + "]";
    }

    private void saveChart(File file, JButton buttonToDisable) {
        syncSongDataFromUI();
        boolean binary = file.getName().toLowerCase().endsWith(".bin");
        new Thread(() -> {
            List<Closeable> writers = new ArrayList<>();
            List<File> committedFiles = new ArrayList<>();
            List<SaveResult> results = new ArrayList<>();
            try {
                int partNumber = 1;
                long totalBytes = 0;
                long totalNotes = 0;
                boolean splitAtOneGb = false;
                boolean oneGbChoiceMade = false;
                JsonPartWriter jsonWriter = null;
                BinaryPartWriter binWriter = null;

                if (binary) { binWriter = new BinaryPartWriter(splitFileName(file, partNumber), activeSong); writers.add(binWriter); }
                else { jsonWriter = new JsonPartWriter(splitFileName(file, partNumber), activeSong); writers.add(jsonWriter); }

                for (int sectionIndex = 0; sectionIndex < activeSong.notes.size(); sectionIndex++) {
                    Section section = activeSong.notes.get(sectionIndex);
                    if (binary) binWriter.writeSection(section);
                    else jsonWriter.startSection(section);
                    double sectionStartTime = getSectionStartTimeMs(sectionIndex);
                    double stepTimeMs = (60000.0 / getEffectiveSectionBpm(section)) / 4.0;

                    int storageRows = Math.max(GRID_STEPS_PER_SECTION, Math.max(1, section.lengthInSteps));
                    for (int row = 0; row < storageRows; row++) {
                        long rowCount = section.sectionNotes.rowSize(row);
                        for (long j = 0; j < rowCount; j++) {
                            int record = section.sectionNotes.rowGetPackedRecord(row, j);
                            double globalTime = sectionStartTime + NoteStore.packedTime(record, row, stepTimeMs);
                            int lane = NoteStore.packedLane(record);
                            double sustain = NoteStore.packedSustain(record, stepTimeMs);
                            long noteBytes = binary ? 20L : jsonWriter.noteLineBytes(makeNoteJson(globalTime, lane, sustain));
                            long currentSize = binary ? binWriter.sizeBytes() : jsonWriter.sizeBytes();
                            long projected = currentSize + noteBytes + JSON_CLOSE_RESERVE_BYTES;

                            if (!oneGbChoiceMade && projected >= JSON_ONE_GB_WARNING_BYTES) {
                                oneGbChoiceMade = true;
                                splitAtOneGb = askToSplitAtOneGb(partNumber, currentSize) == JOptionPane.YES_OPTION;
                            }

                            if ((splitAtOneGb && projected >= JSON_ONE_GB_WARNING_BYTES) || (!splitAtOneGb && projected >= MAX_FILE_BYTES)) {
                                if (!splitAtOneGb && projected >= MAX_FILE_BYTES) {
                                    // At the 2GB hard ceiling, NO means save must be restarted/cancelled.
                                    if (askToSplitAtHardLimit(partNumber, currentSize) != JOptionPane.YES_OPTION) {
                                        throw new SaveCancelledException();
                                    }
                                }

                                if (binary) {
                                    binWriter.finish();
                                    File committed = binWriter.commit();
                                    results.add(new SaveResult(committed, binWriter.notesWritten(), committed.length()));
                                    committedFiles.add(committed);
                                    totalNotes += binWriter.notesWritten(); totalBytes += committed.length();
                                    partNumber++;
                                    binWriter = new BinaryPartWriter(splitFileName(file, partNumber), activeSong);
                                    writers.add(binWriter);
                                    binWriter.writeSection(section);
                                } else {
                                    jsonWriter.endSection(); jsonWriter.finish();
                                    File committed = jsonWriter.commit();
                                    results.add(new SaveResult(committed, jsonWriter.notesWritten(), committed.length()));
                                    committedFiles.add(committed);
                                    totalNotes += jsonWriter.notesWritten(); totalBytes += committed.length();
                                    partNumber++;
                                    jsonWriter = new JsonPartWriter(splitFileName(file, partNumber), activeSong);
                                    writers.add(jsonWriter);
                                    jsonWriter.startSection(section);
                                }
                            }

                            if (binary) binWriter.writeNote(globalTime, lane, sustain);
                            else jsonWriter.writeNote(makeNoteJson(globalTime, lane, sustain));
                        }
                    }
                    if (!binary) jsonWriter.endSection();
                }

                if (binary) {
                    binWriter.finish();
                    File committed = binWriter.commit();
                    results.add(new SaveResult(committed, binWriter.notesWritten(), committed.length()));
                    totalNotes += binWriter.notesWritten(); totalBytes += committed.length();
                } else {
                    jsonWriter.finish();
                    File committed = jsonWriter.commit();
                    results.add(new SaveResult(committed, jsonWriter.notesWritten(), committed.length()));
                    totalNotes += jsonWriter.notesWritten(); totalBytes += committed.length();
                }

                final long finalTotalBytes = totalBytes;
                final long finalTotalNotes = totalNotes;
                SwingUtilities.invokeLater(() -> showSaveResults(results, finalTotalBytes, finalTotalNotes,
                        activeSong.notes.isEmpty() ? 0 : Math.round((double) finalTotalNotes / activeSong.notes.size())));
            } catch (SaveCancelledException e) {
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
                        "Save cancelled. Save again and choose BIN or JSON."));
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
                        "Error saving chart: " + e.getMessage(), "Save Error", JOptionPane.ERROR_MESSAGE));
            } finally {
                if (buttonToDisable != null) SwingUtilities.invokeLater(() -> { buttonToDisable.setEnabled(true); buttonToDisable.setText("Save Chart"); });
            }
        }, binary ? "BIN-Save-Thread" : "JSON-Save-Thread").start();
    }

    private static final class SaveCancelledException extends Exception {
        private static final long serialVersionUID = 1L;
    }

    private String initialImportDirectory() {
        File importDir = new File("charts-to-import");
        if (importDir.isDirectory()) return importDir.getAbsolutePath();
        File parent = new File("..", "charts-to-import");
        if (parent.isDirectory()) return parent.getAbsolutePath();
        return ".";
    }

    private static void disposeSongData(SongData song) {
        if (song == null || song.notes == null) return;
        for (Section section : song.notes) {
            if (section != null && section.sectionNotes != null) {
                section.sectionNotes.clear();
            }
        }
    }

    private void loadChart(File file) {
        if (file == null || !file.exists()) return;
        final boolean binary = file.getName().toLowerCase().endsWith(".bin");
        final Cursor oldCursor = getCursor();
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        new Thread(() -> {
            SongData loadedSong = null;
            try {
                loadedSong = binary ? readBinaryChart(file) : parseChartJSONStreaming(file.toPath());
                final SongData chartToInstall = loadedSong;
                SwingUtilities.invokeLater(() -> {
                    try {
                        SongData oldSong = this.activeSong;
                        this.activeSong = chartToInstall;
                        disposeSongData(oldSong);
                        this.currentSectionIndex = 0;
                        this.positionSteps = 0;
                        this.positionStepsDouble = 0;
                        this.gridPanel.setScrollRowOffset(0);
                        syncSongDataToUI();
                        updateBpmDisplayForCurrentSection();
                        gridPanel.repaint();
                    } finally {
                        setCursor(oldCursor);
                    }
                });
            } catch (Exception e) {
                SongData failedSong = loadedSong;
                disposeSongData(failedSong);
                SwingUtilities.invokeLater(() -> {
                    setCursor(oldCursor);
                    JOptionPane.showMessageDialog(
                            this,
                            "Error loading " + (binary ? "BIN" : "JSON") + " chart: " + e.getMessage(),
                            "Chart Load Error",
                            JOptionPane.ERROR_MESSAGE);
                });
            }
        }, (binary ? "BIN" : "JSON") + "-Load-Thread").start();
    }

    /**
     * Parses a chart JSON file into SongData. Supports the common FNF chart
     * layouts without a JSON library:
     * 1) JS Engine / Psych-style and this editor's format: { "song": { ... } }
     * 2) Psych Engine 1.0 / flat charts: { "speed": ..., "notes": [...] }
     * Fields are read from the nested "song" object first, then the root.
     */
    public static SongData parseChartJSON(String json) throws IOException {
        JsonValue root = new JsonReader(json).read();
        JsonObject global = root instanceof JsonObject ? (JsonObject) root : new JsonObject();
        JsonObject container = global;
        JsonValue songObj = global.map.get("song");
        if (songObj instanceof JsonObject) container = (JsonObject) songObj;

        SongData song = new SongData();

        String s = firstString(container, global, "song");
        if (s != null && !s.isEmpty()) song.song = s;
        Double d = firstDouble(container, global, "bpm");
        if (d != null && d > 0) song.bpm = d;
        Boolean b = firstBool(container, global, "needsVoices");
        if (b != null) song.needsVoices = b;
        s = firstString(container, global, "player1");
        if (s != null && !s.isEmpty()) song.player1 = s;
        s = firstString(container, global, "player2");
        if (s != null && !s.isEmpty()) song.player2 = s;
        s = firstString(container, global, "gfVersion");
        if (s != null && !s.isEmpty()) song.gfVersion = s;
        d = firstDouble(container, global, "speed");
        if (d != null && d >= 0) song.speed = d;

        JsonValue notesVal = container.map.get("notes");
        if (notesVal == null) notesVal = global.map.get("notes");
        if (notesVal == null) notesVal = findDeep(global, "notes");

        List<Section> sections = new ArrayList<>();
        double accumulatedSectionTimeMs = 0.0;
        double currentBpm = song.bpm;
        if (notesVal instanceof JsonArray) {
            for (JsonValue nv : ((JsonArray) notesVal).values) {
                if (!(nv instanceof JsonObject)) continue;
                JsonObject sectionObj = (JsonObject) nv;
                Section section = new Section();

                Double sectionBpm = getDouble(sectionObj, "bpm");
                Boolean sectionChangeBpm = getBool(sectionObj, "changeBPM");
                if (sectionBpm != null && sectionBpm > 0.0) section.bpm = sectionBpm;
                section.changeBPM = sectionChangeBpm != null && sectionChangeBpm;

                Double steps = getDouble(sectionObj, "lengthInSteps");
                if (steps == null || steps <= 0) {
                    Double beats = getDouble(sectionObj, "sectionBeats");
                    steps = (beats != null && beats > 0) ? beats * 4.0 : 16.0;
                }
                section.lengthInSteps = Math.max(1, (int) Math.round(steps));

                Boolean mustHit = getBool(sectionObj, "mustHitSection");
                if (mustHit != null) section.mustHitSection = mustHit;

                double effectiveSectionBpm = currentBpm;
                if (section.changeBPM && section.bpm > 0.0) {
                    effectiveSectionBpm = section.bpm;
                }
                double stepTimeMs = (60000.0 / Math.max(1.0, effectiveSectionBpm)) / 4.0;

                JsonValue noteList = sectionObj.map.get("sectionNotes");
                if (noteList instanceof JsonArray) {
                    double sectionStartTime = accumulatedSectionTimeMs;
                    for (JsonValue noteVal : ((JsonArray) noteList).values) {
                        if (!(noteVal instanceof JsonArray)) continue;
                        List<JsonValue> note = ((JsonArray) noteVal).values;
                        if (note.size() < 3) continue;
                        double strumTime = numberValue(note.get(0));
                        int noteData = (int) Math.round(numberValue(note.get(1)));
                        double sustain = numberValue(note.get(2));
                        double relative = Math.max(0.0, strumTime - sectionStartTime);
                        section.sectionNotes.addFast(relative, noteData,
                                Math.max(0.0, sustain), relative / stepTimeMs, stepTimeMs);
                    }
                }

                section.sectionNotes.finishWrites();
                sections.add(section);
                double quarterMs = 60000.0 / Math.max(1.0, effectiveSectionBpm);
                accumulatedSectionTimeMs += (section.lengthInSteps / 4.0) * quarterMs;
                currentBpm = effectiveSectionBpm;
            }
        }

        if (!sections.isEmpty()) {
            song.notes = sections;
        } else {
            song.notes.add(new Section());
        }
        return song;
    }


    /**
     * Streaming JSON importer for very large charts. It deliberately avoids
     * constructing a JsonObject/JsonArray tree for sectionNotes. Notes are
     * decoded one at a time and written directly into NoteStore's mmap-backed
     * storage. This is the path intended for 100M+ note charts.
     */
    public static SongData parseChartJSONStreaming(Path path) throws IOException {
        SongData song = new SongData();
        try (Reader reader = new BufferedReader(new InputStreamReader(
                Files.newInputStream(path), StandardCharsets.UTF_8), 1024 * 1024)) {
            LargeJsonStreamParser p = new LargeJsonStreamParser(reader, song);
            p.parseRoot();
            for (Section section : song.notes) {
                if (section != null) section.sectionNotes.finishWrites();
            }
        } catch (IOException | RuntimeException e) {
            disposeSongData(song);
            throw e;
        }
        return song;
    }

    private static final class LargeJsonStreamParser {
        private final Reader r;
        private final SongData song;
        private int ch = -2;
        private double currentBpm;
        private double accumulatedSectionTimeMs = 0.0;
        private boolean sawNotesArray = false;

        LargeJsonStreamParser(Reader r, SongData song) {
            this.r = r;
            this.song = song;
            this.currentBpm = Math.max(1.0, song.bpm);
        }

        private int peek() throws IOException {
            if (ch == -2) ch = r.read();
            return ch;
        }

        private int take() throws IOException {
            int c = peek();
            ch = -2;
            return c;
        }

        private void ws() throws IOException {
            while (Character.isWhitespace(peek())) take();
        }

        private int peekNonWs() throws IOException {
            ws();
            return peek();
        }

        private void expect(char wanted) throws IOException {
            ws();
            int got = take();
            if (got != wanted) {
                throw new IOException("Expected '" + wanted + "' in chart JSON, got '" + (char) got + "'");
            }
        }

        private String string() throws IOException {
            ws();
            if (take() != '"') throw new IOException("Expected JSON string");
            StringBuilder b = new StringBuilder(32);
            while (true) {
                int c = take();
                if (c < 0) throw new EOFException("Unterminated JSON string");
                if (c == '"') return b.toString();
                if (c == '\\') {
                    int e = take();
                    switch (e) {
                        case '"': case '\\': case '/': b.append((char) e); break;
                        case 'b': b.append('\b'); break;
                        case 'f': b.append('\f'); break;
                        case 'n': b.append('\n'); break;
                        case 'r': b.append('\r'); break;
                        case 't': b.append('\t'); break;
                        case 'u': {
                            int v = 0;
                            for (int i = 0; i < 4; i++) {
                                int h = take();
                                int d = Character.digit(h, 16);
                                if (d < 0) throw new IOException("Bad unicode escape");
                                v = (v << 4) | d;
                            }
                            b.append((char) v);
                            break;
                        }
                        default: throw new IOException("Bad JSON escape");
                    }
                } else {
                    b.append((char) c);
                }
            }
        }

        /** Allocation-free enough numeric reader for multi-billion-note files. */
        private double number() throws IOException {
            ws();
            boolean negative = false;
            if (peek() == '-') {
                negative = true;
                take();
            }

            double value = 0.0;
            boolean haveDigits = false;
            while (true) {
                int c = peek();
                if (c < '0' || c > '9') break;
                haveDigits = true;
                value = value * 10.0 + (take() - '0');
            }

            if (peek() == '.') {
                take();
                double place = 0.1;
                while (true) {
                    int c = peek();
                    if (c < '0' || c > '9') break;
                    haveDigits = true;
                    value += (take() - '0') * place;
                    place *= 0.1;
                }
            }

            if (!haveDigits) throw new IOException("Bad JSON number");

            int c = peek();
            if (c == 'e' || c == 'E') {
                take();
                boolean expNegative = false;
                c = peek();
                if (c == '+' || c == '-') {
                    expNegative = take() == '-';
                }
                int exponent = 0;
                boolean expDigits = false;
                while (true) {
                    c = peek();
                    if (c < '0' || c > '9') break;
                    expDigits = true;
                    exponent = Math.min(100000, exponent * 10 + (take() - '0'));
                }
                if (!expDigits) throw new IOException("Bad JSON exponent");
                value = value * Math.pow(10.0, expNegative ? -exponent : exponent);
            }

            return negative ? -value : value;
        }

        private boolean bool() throws IOException {
            ws();
            int c = peek();
            if (c == 't') {
                for (char x : "true".toCharArray()) if (take() != x) throw new IOException("Bad boolean");
                return true;
            }
            if (c == 'f') {
                for (char x : "false".toCharArray()) if (take() != x) throw new IOException("Bad boolean");
                return false;
            }
            throw new IOException("Bad boolean");
        }

        private void nullValue() throws IOException {
            ws();
            for (char x : "null".toCharArray()) if (take() != x) throw new IOException("Bad null");
        }

        private void skipValue() throws IOException {
            ws();
            int c = peek();
            if (c == '"') { string(); return; }
            if (c == '{') {
                take();
                ws();
                if (peek() == '}') { take(); return; }
                while (true) {
                    string();
                    expect(':');
                    skipValue();
                    ws();
                    c = take();
                    if (c == '}') return;
                    if (c != ',') throw new IOException("Bad JSON object");
                }
            }
            if (c == '[') {
                take();
                ws();
                if (peek() == ']') { take(); return; }
                while (true) {
                    skipValue();
                    ws();
                    c = take();
                    if (c == ']') return;
                    if (c != ',') throw new IOException("Bad JSON array");
                }
            }
            if (c == 't' || c == 'f') { bool(); return; }
            if (c == 'n') { nullValue(); return; }
            number();
        }

        void parseRoot() throws IOException {
            expect('{');
            ws();
            if (peek() == '}') { take(); return; }

            while (true) {
                String key = string();
                expect(':');
                switch (key) {
                    case "song":
                        if (peekNonWs() == '{') parseSongObject();
                        else if (peekNonWs() == '"') song.song = string();
                        else skipValue();
                        break;
                    case "bpm":
                        song.bpm = Math.max(1.0, number());
                        currentBpm = song.bpm;
                        break;
                    case "speed": song.speed = Math.max(0.0, number()); break;
                    case "needsVoices": song.needsVoices = bool(); break;
                    case "player1": song.player1 = string(); break;
                    case "player2": song.player2 = string(); break;
                    case "gfVersion": song.gfVersion = string(); break;
                    case "notes":
                    case "sections":
                        parseNotesArray();
                        sawNotesArray = true;
                        break;
                    default:
                        skipValue();
                }

                ws();
                int c = take();
                if (c == '}') break;
                if (c != ',') throw new IOException("Bad root JSON object");
            }

            if (song.notes.isEmpty()) song.notes.add(new Section());
        }

        private void parseSongObject() throws IOException {
            expect('{');
            ws();
            if (peek() == '}') { take(); return; }

            while (true) {
                String key = string();
                expect(':');
                switch (key) {
                    case "song": if (peekNonWs() == '"') song.song = string(); else skipValue(); break;
                    case "bpm": song.bpm = Math.max(1.0, number()); currentBpm = song.bpm; break;
                    case "speed": song.speed = Math.max(0.0, number()); break;
                    case "needsVoices": song.needsVoices = bool(); break;
                    case "player1": song.player1 = string(); break;
                    case "player2": song.player2 = string(); break;
                    case "gfVersion": song.gfVersion = string(); break;
                    case "notes":
                    case "sections": parseNotesArray(); sawNotesArray = true; break;
                    default: skipValue();
                }
                ws();
                int c = take();
                if (c == '}') break;
                if (c != ',') throw new IOException("Bad song object");
            }
        }

        private void parseNotesArray() throws IOException {
            expect('[');
            ws();
            if (peek() == ']') { take(); return; }

            while (true) {
                ws();
                int c = peek();
                if (c == '{') {
                    parseFlexibleObjectEntry();
                } else if (c == '[') {
                    // Flat legacy array: [time, lane/data, sustain]
                    parseFlatNoteTuple();
                } else {
                    throw new IOException("Unsupported note/section entry in notes array");
                }

                ws();
                c = take();
                if (c == ']') break;
                if (c != ',') throw new IOException("Bad notes array");
            }
        }

        private void parseFlexibleObjectEntry() throws IOException {
            Section sec = new Section();
            boolean hasSectionNotes = false;
            boolean hasSectionMetadata = false;
            boolean hasFlatNote = false;
            double noteTime = 0.0;
            int noteLane = 0;
            double noteSustain = 0.0;

            expect('{');
            ws();
            if (peek() == '}') { take(); return; }

            while (true) {
                String key = string();
                expect(':');
                switch (key) {
                    case "bpm":
                        sec.bpm = number();
                        hasSectionMetadata = true;
                        break;
                    case "changeBPM":
                        sec.changeBPM = bool();
                        hasSectionMetadata = true;
                        break;
                    case "lengthInSteps":
                        sec.lengthInSteps = Math.max(1, (int) Math.round(number()));
                        hasSectionMetadata = true;
                        break;
                    case "sectionBeats":
                        sec.lengthInSteps = Math.max(1, (int) Math.round(number() * 4.0));
                        hasSectionMetadata = true;
                        break;
                    case "mustHitSection":
                        sec.mustHitSection = bool();
                        hasSectionMetadata = true;
                        break;
                    case "sectionNotes":
                        parseSectionNotes(sec);
                        hasSectionNotes = true;
                        hasSectionMetadata = true;
                        break;
                    case "time":
                    case "strumTime":
                    case "timeMs":
                        noteTime = number();
                        hasFlatNote = true;
                        break;
                    case "data":
                    case "lane":
                    case "noteData":
                    case "direction":
                        noteLane = (int) Math.round(number());
                        hasFlatNote = true;
                        break;
                    case "sustain":
                    case "sustainLength":
                    case "length":
                    case "duration":
                        noteSustain = Math.max(0.0, number());
                        hasFlatNote = true;
                        break;
                    default:
                        skipValue();
                }

                ws();
                int c = take();
                if (c == '}') break;
                if (c != ',') throw new IOException("Bad note/section object");
            }

            if (hasSectionNotes || hasSectionMetadata) {
                double effective = currentBpm;
                if (sec.changeBPM && sec.bpm > 0.0) effective = sec.bpm;
                double quarter = 60000.0 / Math.max(1.0, effective);
                accumulatedSectionTimeMs += (sec.lengthInSteps / 4.0) * quarter;
                currentBpm = effective;
                sec.sectionNotes.finishWrites();
                song.notes.add(sec);
            } else if (hasFlatNote) {
                addFlatNote(noteTime, noteLane, noteSustain);
            }
        }

        private void parseSectionNotes(Section sec) throws IOException {
            double effective = currentBpm;
            if (sec.changeBPM && sec.bpm > 0.0) effective = sec.bpm;
            double step = (60000.0 / Math.max(1.0, effective)) / 4.0;

            expect('[');
            ws();
            if (peek() == ']') { take(); return; }

            while (true) {
                ws();
                if (peek() == '[') {
                    expect('[');
                    double t = number();
                    expect(',');
                    int lane = (int) Math.round(number());
                    expect(',');
                    double sustain = number();

                    // Accept optional tuple fields used by some engines.
                    ws();
                    while (peek() != ']') {
                        expect(',');
                        skipValue();
                        ws();
                    }
                    expect(']');

                    double relative = Math.max(0.0, t - accumulatedSectionTimeMs);
                    sec.sectionNotes.addFast(relative, lane, Math.max(0.0, sustain), relative / step, step);
                } else if (peek() == '{') {
                    parseSectionNoteObject(sec, step);
                } else {
                    throw new IOException("Unsupported section note entry");
                }

                ws();
                int c = take();
                if (c == ']') break;
                if (c != ',') throw new IOException("Bad sectionNotes array");
            }
        }

        private void parseSectionNoteObject(Section sec, double step) throws IOException {
            double t = 0.0;
            int lane = 0;
            double sustain = 0.0;
            boolean gotTime = false;
            boolean gotLane = false;

            expect('{');
            ws();
            if (peek() == '}') { take(); return; }
            while (true) {
                String key = string();
                expect(':');
                switch (key) {
                    case "time": case "strumTime": case "timeMs": t = number(); gotTime = true; break;
                    case "data": case "lane": case "noteData": case "direction": lane = (int) Math.round(number()); gotLane = true; break;
                    case "sustain": case "sustainLength": case "length": case "duration": sustain = Math.max(0.0, number()); break;
                    default: skipValue();
                }
                ws();
                int c = take();
                if (c == '}') break;
                if (c != ',') throw new IOException("Bad section note object");
            }
            if (gotTime && gotLane) {
                double relative = Math.max(0.0, t - accumulatedSectionTimeMs);
                sec.sectionNotes.addFast(relative, lane, sustain, relative / step, step);
            }
        }

        private void parseFlatNoteTuple() throws IOException {
            expect('[');
            double t = number();
            expect(',');
            int lane = (int) Math.round(number());
            expect(',');
            double sustain = number();
            ws();
            while (peek() != ']') {
                expect(',');
                skipValue();
                ws();
            }
            expect(']');
            addFlatNote(t, lane, sustain);
        }

        private void addFlatNote(double globalTime, int lane, double sustain) {
            double step = (60000.0 / Math.max(1.0, currentBpm)) / 4.0;
            double sectionDuration = step * GRID_STEPS_PER_SECTION;
            int sectionIndex = (int) Math.floor(Math.max(0.0, globalTime) / Math.max(1.0e-9, sectionDuration));
            if (sectionIndex > 10_000_000) {
                throw new IllegalStateException("Flat-note chart expands to an unreasonable number of sections");
            }
            while (song.notes.size() <= sectionIndex) song.notes.add(new Section());
            Section sec = song.notes.get(sectionIndex);
            double relative = Math.max(0.0, globalTime - sectionIndex * sectionDuration);
            sec.sectionNotes.addFast(relative, lane, Math.max(0.0, sustain), relative / step, step);
        }
    }

    private static String firstString(JsonObject primary, JsonObject fallback, String key) {
        String s = stringValue(primary.map.get(key));
        if (s == null) s = stringValue(fallback.map.get(key));
        return s;
    }

    private static Double firstDouble(JsonObject primary, JsonObject fallback, String key) {
        Double d = getDouble(primary, key);
        if (d == null) d = getDouble(fallback, key);
        return d;
    }

    private static Boolean firstBool(JsonObject primary, JsonObject fallback, String key) {
        Boolean b = getBool(primary, key);
        if (b == null) b = getBool(fallback, key);
        return b;
    }

    private static Double getDouble(JsonObject o, String key) {
        JsonValue v = o == null ? null : o.map.get(key);
        if (v instanceof JsonNumber) return ((JsonNumber) v).value;
        if (v instanceof JsonString) {
            try {
                return Double.parseDouble(((JsonString) v).value.trim());
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private static Boolean getBool(JsonObject o, String key) {
        JsonValue v = o == null ? null : o.map.get(key);
        if (v instanceof JsonBoolean) return ((JsonBoolean) v).value;
        if (v instanceof JsonString) return Boolean.parseBoolean(((JsonString) v).value.trim());
        return null;
    }

    private static String stringValue(JsonValue v) {
        return v instanceof JsonString ? ((JsonString) v).value : null;
    }

    private static double numberValue(JsonValue v) {
        if (v instanceof JsonNumber) return ((JsonNumber) v).value;
        if (v instanceof JsonString) {
            try {
                return Double.parseDouble(((JsonString) v).value.trim());
            } catch (Exception ignored) {
                return 0.0;
            }
        }
        return 0.0;
    }

    private static JsonValue findDeep(JsonValue node, String key) {
        if (node instanceof JsonObject) {
            JsonObject o = (JsonObject) node;
            JsonValue direct = o.map.get(key);
            if (direct != null) return direct;
            for (JsonValue child : o.map.values()) {
                JsonValue found = findDeep(child, key);
                if (found != null) return found;
            }
        } else if (node instanceof JsonArray) {
            for (JsonValue child : ((JsonArray) node).values) {
                JsonValue found = findDeep(child, key);
                if (found != null) return found;
            }
        }
        return null;
    }

    private abstract static class JsonValue {
    }

    private static final class JsonObject extends JsonValue {
        final Map<String, JsonValue> map = new LinkedHashMap<>();
    }

    private static final class JsonArray extends JsonValue {
        final List<JsonValue> values = new ArrayList<>();
    }

    private static final class JsonString extends JsonValue {
        final String value;
        JsonString(String value) { this.value = value; }
    }

    private static final class JsonNumber extends JsonValue {
        final double value;
        JsonNumber(double value) { this.value = value; }
    }

    private static final class JsonBoolean extends JsonValue {
        final boolean value;
        JsonBoolean(boolean value) { this.value = value; }
    }

    private static final class JsonNull extends JsonValue {
    }

    private static final class JsonReader {
        private final String text;
        private int pos;

        JsonReader(String text) { this.text = text; }

        JsonValue read() throws IOException {
            JsonValue value = readValue();
            skipWhitespace();
            if (pos < text.length()) throw error("Unexpected trailing characters");
            return value;
        }

        private IOException error(String message) {
            return new IOException(message + " at position " + pos);
        }

        private void skipWhitespace() {
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
                else break;
            }
        }

        private JsonValue readValue() throws IOException {
            skipWhitespace();
            if (pos >= text.length()) throw error("Unexpected end of JSON");
            char c = text.charAt(pos);
            switch (c) {
                case '{': return readObject();
                case '[': return readArray();
                case '"': return new JsonString(readString());
                case 't': expectKeyword("true"); return new JsonBoolean(true);
                case 'f': expectKeyword("false"); return new JsonBoolean(false);
                case 'n': expectKeyword("null"); return new JsonNull();
                default: {
                    if (c == '-' || (c >= '0' && c <= '9')) return new JsonNumber(readNumber());
                    throw error("Unexpected character '" + c + "'");
                }
            }
        }

        private JsonValue readObject() throws IOException {
            pos++; // consume '{'
            JsonObject object = new JsonObject();
            skipWhitespace();
            if (pos < text.length() && text.charAt(pos) == '}') { pos++; return object; }
            while (true) {
                skipWhitespace();
                if (pos >= text.length() || text.charAt(pos) != '"') throw error("Expected object key string");
                String key = readString();
                skipWhitespace();
                if (pos >= text.length() || text.charAt(pos) != ':') throw error("Expected ':' after object key");
                pos++;
                object.map.put(key, readValue());
                skipWhitespace();
                if (pos >= text.length()) throw error("Unterminated object");
                char c = text.charAt(pos);
                if (c == ',') { pos++; continue; }
                if (c == '}') { pos++; return object; }
                throw error("Expected ',' or '}' in object");
            }
        }

        private JsonValue readArray() throws IOException {
            pos++; // consume '['
            JsonArray array = new JsonArray();
            skipWhitespace();
            if (pos < text.length() && text.charAt(pos) == ']') { pos++; return array; }
            while (true) {
                array.values.add(readValue());
                skipWhitespace();
                if (pos >= text.length()) throw error("Unterminated array");
                char c = text.charAt(pos);
                if (c == ',') { pos++; continue; }
                if (c == ']') { pos++; return array; }
                throw error("Expected ',' or ']' in array");
            }
        }

        private String readString() throws IOException {
            pos++; // consume opening quote
            StringBuilder sb = new StringBuilder();
            while (pos < text.length()) {
                char c = text.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (pos >= text.length()) throw error("Unterminated string escape");
                    char e = text.charAt(pos++);
                    switch (e) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u': {
                            if (pos + 4 > text.length()) throw error("Invalid unicode escape");
                            sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                            pos += 4;
                            break;
                        }
                        default: throw error("Invalid string escape '\\" + e + "'");
                    }
                } else if (c < 0x20) {
                    throw error("Unescaped control character in string");
                } else {
                    sb.append(c);
                }
            }
            throw error("Unterminated string");
        }

        private double readNumber() throws IOException {
            int start = pos;
            if (pos < text.length() && text.charAt(pos) == '-') pos++;
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') pos++;
                else break;
            }
            String number = text.substring(start, pos);
            try {
                return Double.parseDouble(number);
            } catch (NumberFormatException e) {
                throw error("Invalid number '" + number + "'");
            }
        }

        private void expectKeyword(String word) throws IOException {
            if (!text.startsWith(word, pos)) throw error("Invalid JSON literal");
            pos += word.length();
        }
    }

    private SongData readBinaryChart(File file) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file), 1024 * 1024))) {
            String magic = readBinaryString(in);
            if (!magic.equals("FNFEBIN1") && !magic.equals("FNFEBIN2") && !magic.equals("FNFEBIN3")) {
                throw new IOException("Unknown BIN chart format: " + magic);
            }

            SongData loaded = new SongData();
            loaded.song = readBinaryString(in);
            loaded.bpm = in.readDouble();
            loaded.needsVoices = in.readBoolean();
            loaded.player1 = readBinaryString(in);
            loaded.player2 = readBinaryString(in);
            if ("FNFEBIN2".equals(magic) || "FNFEBIN3".equals(magic)) {
                loaded.gfVersion = readBinaryString(in);
            }
            loaded.speed = in.readDouble();

            double sectionStartTime = 0.0;
            double currentBpm = Math.max(1.0, loaded.bpm);

            while (true) {
                try {
                    int length = in.readInt();
                    boolean mustHit = in.readBoolean();
                    double sectionBpm = 0.0;
                    boolean changeBPM = false;
                    long noteCount;

                    if ("FNFEBIN3".equals(magic)) {
                        sectionBpm = in.readDouble();
                        changeBPM = in.readBoolean();
                        noteCount = in.readLong();
                    } else {
                        noteCount = in.readLong();
                    }

                    if (length == 0 && noteCount == 0) break;
                    if (length <= 0) throw new IOException("Invalid BIN section length: " + length);
                    if (noteCount < 0 || noteCount > 10_000_000_000L) {
                        throw new IOException("Invalid BIN note count: " + noteCount);
                    }

                    Section section = new Section();
                    section.lengthInSteps = length;
                    section.mustHitSection = mustHit;
                    if ("FNFEBIN3".equals(magic)) {
                        section.bpm = sectionBpm;
                        section.changeBPM = changeBPM;
                    }

                    double effectiveBpm = currentBpm;
                    if (section.changeBPM && section.bpm > 0.0) effectiveBpm = section.bpm;
                    double stepTimeMs = (60000.0 / Math.max(1.0, effectiveBpm)) / 4.0;

                    for (long i = 0; i < noteCount; i++) {
                        double globalTime = in.readDouble();
                        int lane = in.readInt();
                        double sustain = in.readDouble();
                        double relative = globalTime - sectionStartTime;
                        // BIN files written by this editor store global times. Keep the
                        // real fractional position instead of forcing it into row 0 when
                        // a section starts after a long chart.
                        if (relative < -0.5) relative = 0.0;
                        section.sectionNotes.addFast(relative, lane, sustain,
                                relative / stepTimeMs, stepTimeMs);
                    }

                    section.sectionNotes.finishWrites();
                    loaded.notes.add(section);
                    double quarterMs = 60000.0 / Math.max(1.0, effectiveBpm);
                    sectionStartTime += (section.lengthInSteps / 4.0) * quarterMs;
                    currentBpm = effectiveBpm;
                } catch (EOFException eof) {
                    break;
                }
            }

            if (loaded.notes.isEmpty()) loaded.notes.add(new Section());
            return loaded;
        }
    }

    private static String readBinaryString(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 0 || len > 16 * 1024 * 1024) throw new IOException("Invalid BIN string length");
        byte[] bytes = new byte[len];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            new FNFChartEditor().setVisible(true);
        });
    }

    /**
     * Compact number formatter for chart JSON. Values are rounded to the
     * existing 3-decimal precision, but unnecessary trailing zeros are
     * removed. Examples: 100.000 -> 100, 1.500 -> 1.5, 0.250 -> 0.25.
     */
    private static String format3(double v) {
        if (!Double.isFinite(v)) return "0";

        boolean neg = v < 0.0;
        double abs = Math.abs(v);
        long scaled = Math.round(abs * 1000.0d);
        long intPart = scaled / 1000L;
        long fracPart = scaled % 1000L;

        if (fracPart == 0L) {
            return neg ? "-" + intPart : Long.toString(intPart);
        }

        // Keep at most 3 decimal places, removing trailing zeros.
        int decimals = 3;
        if (fracPart % 10L == 0L) {
            fracPart /= 10L;
            decimals--;
            if (fracPart % 10L == 0L) {
                fracPart /= 10L;
                decimals--;
            }
        }

        String frac;
        if (decimals == 1) {
            frac = Long.toString(fracPart);
        } else if (decimals == 2) {
            frac = fracPart < 10 ? "0" + fracPart : Long.toString(fracPart);
        } else {
            if (fracPart < 10) frac = "00" + fracPart;
            else if (fracPart < 100) frac = "0" + fracPart;
            else frac = Long.toString(fracPart);
        }

        return (neg ? "-" : "") + intPart + "." + frac;
    }

    private String formatEveryThirdDigit(long value) {
        boolean neg = value < 0;
        long v = Math.abs(value);
        String s = String.valueOf(v);
        StringBuilder out = new StringBuilder();
        int count = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            out.append(s.charAt(i));
            count++;
            if (count == 3 && i != 0) {
                out.append(',');
                count = 0;
            }
        }
        if (neg) out.append('-');
        return out.reverse().toString();
    }

    class ChartGridPanel extends JPanel {

        private BufferedImage gridGrey;
        private BufferedImage gridWhite;
        private BufferedImage bfIcon;
        private BufferedImage dadIcon;
        private BufferedImage[] noteArrows = new BufferedImage[8];

        private int rowHeight = 60;
        private int stepsPerSection = 64;
        private int laneWidth = 60; 
        private int gridStartX = 50;
        private int gridStartY = 60; 

        private double scrollRowOffset = 0.0;
        private long selectedNoteIndex = -1;
        private int lastClickedRow = -1;
        private int lastClickedLane = -1;
        
        private long lastWheelTime = 0;
        private long lastATime = 0;
        private long lastDTime = 0;

        public void changeGridZoom(int delta) {
            int next = Math.max(0, Math.min(GRID_ZOOM_VALUES.length - 1, gridZoomIndex + delta));
            if (next == gridZoomIndex) return;
            gridZoomIndex = next;
            stepsPerSection = gridRowsForZoom();
            setToolTipText("Grid: " + gridZoomLabel() + " (Z/X to zoom)");
            scrollRowOffset = Math.max(0.0, Math.min(scrollRowOffset, Math.max(0.0, stepsPerSection - 1)));
            repaint();
        }

        private double gridRowToMs(int row) {
            return row * displayStepTimeMs();
        }

        private int storageRowForDisplayRow(int displayRow) {
            return Math.max(0, Math.min(GRID_STEPS_PER_SECTION - 1,
                    (int) Math.floor(displayRow * (GRID_STEPS_PER_SECTION / (double) stepsPerSection))));
        }

        public void setScrollRowOffset(double offset) {
            this.scrollRowOffset = Math.max(0.0, offset);
            repaint();
        }

        public void updatePlaybackCamera(double globalStepPosition) {
            double playheadRowBase = globalStepPosition % GRID_STEPS_PER_SECTION;
            if (playheadRowBase < 0) playheadRowBase += GRID_STEPS_PER_SECTION;
            double playheadRow = playheadRowBase * (stepsPerSection / (double) GRID_STEPS_PER_SECTION);

            int visibleRows = Math.max(1, (getHeight() - gridStartY) / rowHeight);
            double maxScroll = Math.max(0.0, stepsPerSection - visibleRows);
            double followPoint = Math.min(4.0, Math.max(1.0, visibleRows / 2.0));
            double target = playheadRow - followPoint;

            target = Math.max(0.0, Math.min(maxScroll, target));
            scrollRowOffset = target;
        }

        private int mapUserLaneToVisualLane(int noteData) {
            return Math.max(0, Math.min(7, noteData));
        }

        private int visualLaneToUserLane(int visualLane0to7) {
            return Math.max(0, Math.min(7, visualLane0to7));
        }

        public ChartGridPanel() {
            stepsPerSection = gridRowsForZoom();
            setToolTipText("Grid: " + gridZoomLabel() + " (Z/X to zoom)");
            loadAssets();
            setFocusable(true);
            
            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    requestFocusInWindow();

                    int clickedLaneVisual = (e.getX() - gridStartX) / laneWidth;
                    double rawDisplayRow = ((e.getY() - gridStartY) / (double) rowHeight) + scrollRowOffset;
                    int clickedRow = (int) Math.floor(rawDisplayRow + 1.0e-9);

                    // The zoomed grid is a visual subdivision of the same 4-beat
                    // section. A click must resolve against the full zoomed row
                    // range, not the original 16 storage buckets. The old check
                    // also rejected some valid rows after scrolling.
                    if (e.getY() < gridStartY || e.getX() < gridStartX) return;
                    if (clickedRow < 0 || clickedRow >= stepsPerSection) return;

                    if (clickedLaneVisual >= 0 && clickedLaneVisual < 8) {
                        int clickedUserLane = visualLaneToUserLane(clickedLaneVisual);
                        lastClickedRow = clickedRow;
                        lastClickedLane = clickedUserLane;

                        if (ezSpamCheckbox != null && ezSpamCheckbox.isSelected() && !SwingUtilities.isRightMouseButton(e)) {
                            // EZ Spam is an independent note-generation action. Never carry
                            // the previously selected long-note/sustain state into the new spam.
                            longNoteMode = false;
                            longNoteStartIndex = -1;
                            longNoteStartSection = -1;
                            if (sustainSpinner != null) sustainSpinner.setValue(0.0);

                            commitSpinner(densitySpinner);
                            commitSpinner(strengthSpinner);
                            int density = (int) densitySpinner.getValue();
                            int strength = Math.max(1, ((Number) strengthSpinner.getValue()).intValue());
                            // EZ Spam uses the SAME strength control as the normal Spam tool.
                            // Strength is the number of visual grid rows to fill starting at
                            // the clicked row. Density only controls how many notes are packed
                            // inside each of those rows.
                            ezSpamAtGrid(clickedUserLane, clickedRow, density, strength);
                        } else {
                            Section currentSec = activeSong.notes.get(currentSectionIndex);
                            double stepTime = rowToMs(clickedRow);
                            double stepTimeMs = displayStepTimeMs();

                            double sustain = 0.0;
                            double timeTol = Math.max(0.05, getCurrentSectionStepTimeMs() * 0.02);
                            // A new click always selects a fresh note. Long-note editing is
                            // explicit through E/P and never carries into the next note.
                            longNoteMode = false;
                            longNoteStartIndex = -1;
                            longNoteStartSection = -1;
                            
                            if (SwingUtilities.isRightMouseButton(e)) {
                                double relativeTime = Math.max(0.0, stepTime
                                        - getSectionStartTimeMs(currentSectionIndex));
                                double storageStepMs = getCurrentSectionStepTimeMs();
                                double deleteTolerance = Math.max(0.5, (displayStepTimeMs() * 0.45));

                                // Delete against the note's real chart time. Do not assume
                                // the visible row number is a storage-bucket number: at
                                // 1/1 and higher zooms several visual rows live inside the
                                // same 1/0.25 storage row.
                                long removed = currentSec.sectionNotes.removeNoteAtTimeAndLane(
                                        relativeTime, clickedUserLane, storageStepMs, deleteTolerance);
                                if (removed > 0) {
                                    selectedNoteIndex = -1;
                                    repaint();
                                }
                            } else {
                                boolean replaced = false;
                                double sectionStartTime = currentSectionStartTimeMs();
                                double relativeTime = Math.max(0.0, stepTime - sectionStartTime);
                                double storageStepMs = getCurrentSectionStepTimeMs();

                                // Treat the clicked grid cell as the identity of the note.
                                // This prevents a second click on the same visual cell from
                                // creating a duplicate that can appear on the opposite side.
                                // It also preserves an existing sustain instead of resetting it.
                                double sameCellTolerance = Math.max(0.05, displayStepTimeMs() * 0.20);
                                long existingIndex = currentSec.sectionNotes.findNoteAtTimeAndLane(
                                        relativeTime, clickedUserLane, storageStepMs, sameCellTolerance);
                                if (existingIndex >= 0) {
                                    selectedNoteIndex = existingIndex;
                                    replaced = true;
                                }
                                if (!replaced) {
                                    // Every new click starts as a normal tap note.
                                    // Long-note behavior is opt-in through E/P only.
                                    longNoteMode = false;
                                    longNoteStartIndex = -1;
                                    longNoteStartSection = -1;
                                    selectedNoteIndex = currentSec.sectionNotes.add(
                                            relativeTime, clickedUserLane, 0.0, relativeTime / storageStepMs, storageStepMs);
                                    sustainSpinner.setValue(0.0);
                                }
                                strumTimeField.setText(String.format("%.2f", stepTime));
                                repaint();
                            }
                        }
                    }
                }
            });

            addMouseWheelListener(e -> {
                int visibleRows = Math.max(1, (getHeight() - gridStartY) / rowHeight);
                double maxScroll = Math.max(0.0, stepsPerSection - visibleRows);
                double nextOffset = scrollRowOffset + (e.getWheelRotation() * 1.0);
                nextOffset = Math.max(0.0, Math.min(maxScroll, nextOffset));

                if (nextOffset != scrollRowOffset) {
                    scrollRowOffset = nextOffset;
                    repaint();
                }
            });

            addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    int keyCode = e.getKeyCode();
                    long now = System.currentTimeMillis();

                    if (keyCode == KeyEvent.VK_R) {
                        swapCurrentSectionSides();
                        return;
                    }

                    if (keyCode == KeyEvent.VK_S) {
                        positionSteps++;
                        applyPositionSteps(positionSteps);
                        repaint();
                    } else if (keyCode == KeyEvent.VK_W) {
                        positionSteps--;
                        applyPositionSteps(positionSteps);
                        repaint();
                    } else if (keyCode == KeyEvent.VK_D) {
                        if (now - lastDTime > 100) {
                            positionSteps += GRID_STEPS_PER_SECTION;
                            applyPositionSteps(positionSteps);
                            lastDTime = now;
                            repaint();
                        }
                    } else if (keyCode == KeyEvent.VK_A) {
                        if (now - lastATime > 100) {
                            positionSteps -= GRID_STEPS_PER_SECTION;
                            applyPositionSteps(positionSteps);
                            lastATime = now;
                            repaint();
                        }
                    }
                }
            });
        }

        private void extendLongNoteByGridStep() {
            Section sec = activeSong.notes.get(currentSectionIndex);
            if (selectedNoteIndex < 0 || selectedNoteIndex >= sec.sectionNotes.size()) {
                return;
            }

            // Sustain is stored in the canonical 1/16-note storage grid.
            // The visual grid may be zoomed, but one E press must add exactly
            // one displayed grid interval without scaling the existing sustain.
            double storageStepMs = getCurrentSectionStepTimeMs();
            double gridStepMs = displayStepTimeMs();
            double sustain = sec.sectionNotes.getSustain(selectedNoteIndex, storageStepMs);
            sustain = Math.max(0.0, sustain) + gridStepMs;
            sec.sectionNotes.setSustain(selectedNoteIndex, sustain, storageStepMs);
            sustainSpinner.setValue(sustain);
            repaint();
        }

        private double sectionStartTimeMs(int sectionIndex) {
            // Use the shared BPM-aware calculation so every previous section
            // keeps its own tempo instead of incorrectly using the current
            // section's BPM for the entire chart.
            return getSectionStartTimeMs(sectionIndex);
        }

        private double currentSectionStartTimeMs() {
            return sectionStartTimeMs(currentSectionIndex);
        }

        private double rowTimeToGlobalMs(double relativeTimeMs) {
            return currentSectionStartTimeMs() + relativeTimeMs;
        }

        private void extendSelectedNote() {
            Section sec = activeSong.notes.get(currentSectionIndex);
            if (selectedNoteIndex < 0 || selectedNoteIndex >= sec.sectionNotes.size()) {
                return;
            }

            // Each E press adds exactly one current visual grid interval.
            // Read/write sustain using the canonical storage step so zoom level
            // never causes the existing sustain to be multiplied or halved.
            double storageStepMs = getCurrentSectionStepTimeMs();
            double gridStepMs = displayStepTimeMs();
            double sustain = sec.sectionNotes.getSustain(selectedNoteIndex, storageStepMs);
            sustain = Math.max(0.0, sustain) + gridStepMs;
            sec.sectionNotes.setSustain(selectedNoteIndex, sustain, storageStepMs);

            sustainSpinner.setValue(sustain);
            double sectionStartTime = currentSectionStartTimeMs();
            strumTimeField.setText(String.format("%.2f", sectionStartTime + sec.sectionNotes.getTime(selectedNoteIndex, storageStepMs)));
            repaint();
        }

        private double rowToMs(int row) {
            double sectionStartTime = currentSectionStartTimeMs();
            // Every visual row is a real musical subdivision of the current section.
            // Do not collapse visual rows back to the 16-row storage buckets when
            // mapping the mouse to time.
            double stepTimeMs = displayStepTimeMs();
            return sectionStartTime + (row * stepTimeMs);
        }

        private void loadAssets() {
            try {
                InputStream gridGreyStream = getClass().getResourceAsStream("/assets/charteditor/GridGrey.png");
                InputStream gridWhiteStream = getClass().getResourceAsStream("/assets/charteditor/GridWhite.png");
                InputStream bfIconStream = getClass().getResourceAsStream("/icons/bf.png");
                InputStream dadIconStream = getClass().getResourceAsStream("/icons/dad.png");

                if (gridGreyStream != null) gridGrey = ImageIO.read(gridGreyStream);
                if (gridWhiteStream != null) gridWhite = ImageIO.read(gridWhiteStream);
                if (bfIconStream != null) bfIcon = ImageIO.read(bfIconStream);
                if (dadIconStream != null) dadIcon = ImageIO.read(dadIconStream);

                String[] noteNames = {"LeftComing.png", "DownComing.png", "UpComing.png", "RightComing.png"};
                for (int i = 0; i < 4; i++) {
                    InputStream noteStream = getClass().getResourceAsStream("/notes/" + noteNames[i]);
                    if (noteStream != null) {
                        noteArrows[i] = ImageIO.read(noteStream);
                    }
                }

                System.arraycopy(noteArrows, 0, noteArrows, 4, 4);
            } catch (Exception e) {
                System.out.println("Internal resource loading error: " + e.getMessage());
            }
        }

        private void ezSpamAtGrid(int lane, int gridRow, int densityValue, int strengthRows) {
            Section currentSec = activeSong.notes.get(currentSectionIndex);

            int safeDensity = Math.max(1, densityValue);
            int safeStrength = Math.max(1, strengthRows);
            double visualStepTimeMs = Math.max(1.0e-9, displayStepTimeMs());
            double storageStepMs = getCurrentSectionStepTimeMs();
            double startRelativeTime = Math.max(0.0, gridRow * visualStepTimeMs);
            double endRelativeTime = startRelativeTime + (safeStrength * visualStepTimeMs);

            // Replace the full EZ Spam range, not just the clicked row. This is the
            // important part of the fix: strength N now owns N visual grid rows.
            currentSec.sectionNotes.removeMatching(
                    startRelativeTime, endRelativeTime, storageStepMs, lane, lane);

            // Fresh tap notes only. For each visual row, density packs notes INSIDE
            // that row. The outer loop is an integer loop so strength 16 can never
            // collapse back to 1 because of floating-point rounding.
            double rowStep = 1.0 / safeDensity;
            for (int row = 0; row < safeStrength; row++) {
                double rowStartTime = startRelativeTime + (row * visualStepTimeMs);
                for (double offset = 0.0; offset < 1.0 - 1.0e-9; offset += rowStep) {
                    double relativeTime = rowStartTime + (offset * visualStepTimeMs);
                    double storageRowHint = relativeTime / storageStepMs;
                    currentSec.sectionNotes.addFast(
                            relativeTime, lane, 0.0, storageRowHint, storageStepMs);
                }
            }

            repaint();
        }

        public void spamNotesForCurrentSection(int laneFrom, int laneTo, int densityValue, double strengthRowsToFill, int startRow) {
            Section currentSec = activeSong.notes.get(currentSectionIndex);

            int safeDensity = Math.max(1, densityValue);
            double safeStrength = Math.max(0.03125, strengthRowsToFill);

            int minLane = Math.min(laneFrom, laneTo);
            int maxLane = Math.max(laneFrom, laneTo);

            double sectionStartTime = currentSectionStartTimeMs();
            // Spam moves in canonical 1/16-section grid rows so "strength" always
            // counts the 16 section grids you see at the default zoom, no matter how
            // far in/out you zoom with Z/X. Density packs that many notes into each
            // of those grid rows.
            double storageStepMs = getCurrentSectionStepTimeMs();

            double startRelativeTime = Math.max(0.0, startRow * storageStepMs);
            double endRelativeTime = startRelativeTime + (safeStrength * storageStepMs);

            currentSec.sectionNotes.removeMatching(
                    startRelativeTime, endRelativeTime, storageStepMs, minLane, maxLane);

            double rowStep = 1.0 / safeDensity;
            double targetMaxRow = safeStrength;
            // EZ/Spam always creates fresh tap notes. A previous long-note edit
            // must never leak its sustain into the first generated spam note.
            double sustain = 0.0;

            for (double offsetRow = 0.0; offsetRow < targetMaxRow; offsetRow += rowStep) {
                double relativeTime = startRelativeTime + (offsetRow * storageStepMs);
                double storageRowHint = relativeTime / storageStepMs;

                for (int targetLane = minLane; targetLane <= maxLane; targetLane++) {
                    currentSec.sectionNotes.addFast(
                            relativeTime, targetLane, sustain, storageRowHint, storageStepMs);
                }
            }

            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2d = (Graphics2D) g;

            long totalNotesInSong = 0;
            for (Section s : activeSong.notes) {
                totalNotesInSong += s.sectionNotes.size();
            }

            Section secForCount = activeSong.notes.get(currentSectionIndex);
            long notesInThisSection = secForCount.sectionNotes.size();

            long renderedNotes = 0;
            double stepTimeMs = getCurrentSectionStepTimeMs();

            g2d.setColor(Color.WHITE);
            g2d.drawString(
                "Section: " + currentSectionIndex + " | Camera: " + String.format("%.2f", scrollRowOffset) +
                " | Status: " + (isPlaying ? "PLAYING" : "PAUSED") +
                " | Notes: " + formatEveryThirdDigit(totalNotesInSong),
                20, 20
            );

            boolean mustHit = activeSong.notes.get(currentSectionIndex).mustHitSection;
            BufferedImage leftSideIcon = mustHit ? bfIcon : dadIcon;
            BufferedImage rightSideIcon = mustHit ? dadIcon : bfIcon;

            if (leftSideIcon != null) {
                g2d.drawImage(leftSideIcon, gridStartX + (2 * laneWidth) - 25, gridStartY - 50, 50, 50, null);
            } else {
                g2d.setColor(Color.RED);
                g2d.drawString(mustHit ? "BF Side" : "DAD Side", gridStartX + laneWidth, gridStartY - 15);
            }

            if (rightSideIcon != null) {
                g2d.drawImage(rightSideIcon, gridStartX + (6 * laneWidth) - 25, gridStartY - 50, 50, 50, null);
            } else {
                g2d.setColor(Color.GREEN);
                g2d.drawString(mustHit ? "DAD Side" : "BF Side", gridStartX + (5 * laneWidth), gridStartY - 15);
            }

            for (int rowActual = 0; rowActual < stepsPerSection; rowActual++) {
                int rowY = gridStartY + (int) Math.round((rowActual - scrollRowOffset) * rowHeight);
                if (rowY + rowHeight < gridStartY || rowY > getHeight()) continue;

                for (int laneVisual = 0; laneVisual < 8; laneVisual++) {
                    int x = gridStartX + (laneVisual * laneWidth);

                    BufferedImage gridImg = ((rowActual / 4) % 2 == 0) ? gridGrey : gridWhite;
                    if (gridImg != null) {
                        g2d.drawImage(gridImg, x, rowY, laneWidth, rowHeight, null);
                    } else {
                        g2d.setColor((rowActual % 2 == 0) ? Color.DARK_GRAY : Color.GRAY);
                        g2d.fillRect(x, rowY, laneWidth, rowHeight);
                    }
                    g2d.setColor(Color.BLACK);
                    g2d.drawRect(x, rowY, laneWidth, rowHeight);
                }
            }

            g2d.setColor(Color.CYAN);
            g2d.setStroke(new BasicStroke(3));
            g2d.drawLine(gridStartX + (4 * laneWidth), gridStartY, gridStartX + (4 * laneWidth), getHeight());

            if (isPlaying) {
                double playheadRowBase = positionStepsDouble % GRID_STEPS_PER_SECTION;
                if (playheadRowBase < 0) playheadRowBase += GRID_STEPS_PER_SECTION;
                double playheadRow = playheadRowBase * (stepsPerSection / (double) GRID_STEPS_PER_SECTION);
                double playheadY = gridStartY + ((playheadRow - scrollRowOffset) * rowHeight);
                if (playheadY >= gridStartY - 2 && playheadY <= getHeight() + 2) {
                    g2d.setColor(Color.YELLOW);
                    g2d.setStroke(new BasicStroke(3));
                    g2d.drawLine(gridStartX, (int) Math.round(playheadY),
                                 gridStartX + (8 * laneWidth), (int) Math.round(playheadY));
                    g2d.fillOval(gridStartX + (8 * laneWidth) - 7, (int) Math.round(playheadY) - 7, 14, 14);
                }
            }

            float opacity = 1.0f;
            if (opacitySlider != null) {
                opacity = opacitySlider.getValue() / 100.0f;
            }

            Composite originalComposite = g2d.getComposite();
            Section sec = activeSong.notes.get(currentSectionIndex);

            int visibleFirstRow = Math.max(0, (int) Math.floor(scrollRowOffset));
            int visibleLastRow = Math.min(stepsPerSection - 1, (int) Math.ceil(scrollRowOffset + (getHeight() - gridStartY) / (double) rowHeight));
            final long maxRenderedPerRow = 2048;

            // A fully transparent note layer should do no note rendering at all.
            // This both fixes the visual issue and avoids wasting time iterating/rendering
            // potentially millions of notes when the transparency slider is at 0%.
            if (opacity > 0.0f) {
                g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, opacity));

                int firstStorageRow = storageRowForDisplayRow(visibleFirstRow);
                int lastStorageRow = storageRowForDisplayRow(Math.max(visibleFirstRow, visibleLastRow));
                for (int storageRow = firstStorageRow; storageRow <= lastStorageRow; storageRow++) {
                    long rowCount = sec.sectionNotes.rowSize(storageRow);
                    if (rowCount == 0) continue;
                    long stride = Math.max(1L, (rowCount + maxRenderedPerRow - 1) / maxRenderedPerRow);

                    for (long i = 0; i < rowCount; i += stride) {
                        double relativeTime = sec.sectionNotes.rowGetTime(storageRow, i, stepTimeMs);
                        int userLane = sec.sectionNotes.rowGetLane(storageRow, i);
                        double sustain = sec.sectionNotes.rowGetSustain(storageRow, i, stepTimeMs);

                        double rowActualDouble = relativeTime / displayStepTimeMs();
                        double rowVisualDouble = rowActualDouble - scrollRowOffset;

                        if (rowActualDouble >= -0.1 && rowActualDouble < stepsPerSection
                                && rowVisualDouble >= -0.1 && rowVisualDouble < stepsPerSection
                                && userLane >= 0 && userLane <= 7) {
                            renderedNotes++;
                            int laneVisual = mapUserLaneToVisualLane(userLane);
                            int x = gridStartX + (laneVisual * laneWidth);
                            int y = gridStartY + (int) Math.round(rowVisualDouble * rowHeight);

                            if (sustain > 0) {
                                // Sustain is measured from the note head, not from the top of the
                                // sprite.  The old renderer added half a grid visually
                                // because the tail started at the head center while its
                                // full sustain length was still drawn.  Keep the total
                                // visible long-note length exactly equal to the stored
                                // sustain grid length.
                                double sustainGrids = sustain / displayStepTimeMs();
                                int tailHeight = Math.max(0, (int) Math.round((sustainGrids - 0.5) * rowHeight));
                                g2d.setColor(new Color(0, 255, 0, 150));
                                g2d.fillRect(x + (laneWidth / 3), y + (rowHeight / 2), laneWidth / 3, tailHeight);
                            }

                            if (noteArrows[laneVisual] != null) {
                                g2d.drawImage(noteArrows[laneVisual], x, y, laneWidth, rowHeight, null);
                            } else {
                                g2d.setColor(Color.YELLOW);
                                g2d.fillOval(x + 2, y + 2, laneWidth - 4, rowHeight - 4);
                            }
                        }
                    }
                }

                g2d.setComposite(originalComposite);
            }

            if (shortcutsInfo != null) {
                // Never scan every note during paint. A chart can contain hundreds
                // of millions or more notes, and the old UI did a full note-by-note
                // count on every repaint even when notes were fully invisible.
                long opponentNotes = 0;
                long playerNotes = 0;
                for (Section countSection : activeSong.notes) {
                    opponentNotes += countSection.sectionNotes.countLane(0);
                    opponentNotes += countSection.sectionNotes.countLane(1);
                    opponentNotes += countSection.sectionNotes.countLane(2);
                    opponentNotes += countSection.sectionNotes.countLane(3);
                    playerNotes += countSection.sectionNotes.countLane(4);
                    playerNotes += countSection.sectionNotes.countLane(5);
                    playerNotes += countSection.sectionNotes.countLane(6);
                    playerNotes += countSection.sectionNotes.countLane(7);
                }
                shortcutsInfo.setText(
                    "SPACE BAR - Start / Pause Playback (BPM Camera Follow)\n" +
                    "P - Extend Selected Long Note by 1/16 Step\n" +
                    "E - Make Selected Note Long; press again to extend by 1 grid\n" +
                    "Z/X - Zoom Grid Out / In\n" +
                    "W/S - Move 1 Step     A/D - Prev/Next Section\n" +
                    "Mouse Wheel - Scroll Through Grid\n" +
                    "Left Click - Place Note     Right Click - Delete Note\n\n" +
                    "Opponent: " + String.format(java.util.Locale.US, "%,d", opponentNotes) + "\n" +
                    "Player: " + String.format(java.util.Locale.US, "%,d", playerNotes) + "\n" +
                    "Total Notes: " + String.format(java.util.Locale.US, "%,d", opponentNotes + playerNotes) + "\n" +
                    "Rendered Notes: " + String.format(java.util.Locale.US, "%,d", renderedNotes));
            }
        }
    }
}
