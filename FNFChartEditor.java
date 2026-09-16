
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
import java.util.List;
import javax.imageio.ImageIO;
import javax.sound.sampled.*;

public class FNFChartEditor extends JFrame {

    public static class SongData {
        public String song = "Test";
        public double bpm = 150.0;
        public boolean needsVoices = true;
        public String player1 = "bf";
        public String player2 = "dad";
        public double speed = 1.6;
        public String audioFilePath = ""; 
        public List<Section> notes = new ArrayList<>();
    }

    public static class Section {
        public int lengthInSteps = 16;
        public boolean mustHitSection = false;
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
    public static final class NoteStore {
        private static final int ROW_BUCKETS = 16;
        private static final long RECORD_SIZE = 4L;
        private static final int MAP_SEGMENT_BYTES = 64 * 1024 * 1024;
        private static final int MAX_TIME_UNITS = 0xFFFF;
        private static final int MAX_SUSTAIN_UNITS = 0x1FFF;

        public interface RowVisitor {
            void visit(int row, long index, double timeMs, int lane, double sustainMs);
        }

        private static final class Bucket {
            private final Path file;
            private final FileChannel channel;
            private final List<MappedByteBuffer> maps = new ArrayList<>();
            private long size;

            Bucket() {
                try {
                    file = Files.createTempFile("fnf-chart-notes-", ".bin");
                    file.toFile().deleteOnExit();
                    channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE);
                } catch (IOException e) {
                    throw new UncheckedIOException("Unable to create large-note backing store", e);
                }
            }

            long size() { return size; }

            private void ensureMapped(long recordIndex) {
                long byteOffset = recordIndex * RECORD_SIZE;
                int segment = (int) (byteOffset / MAP_SEGMENT_BYTES);
                try {
                    while (maps.size() <= segment) {
                        long segmentStart = (long) maps.size() * MAP_SEGMENT_BYTES;
                        long requiredBytes = segmentStart + MAP_SEGMENT_BYTES;
                        long currentLength = channel.size();
                        if (currentLength < requiredBytes) {
                            channel.position(requiredBytes - 1);
                            channel.write(ByteBuffer.wrap(new byte[]{0}));
                        }
                        maps.add(channel.map(FileChannel.MapMode.READ_WRITE, segmentStart, MAP_SEGMENT_BYTES));
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException("Unable to map large-note backing store", e);
                }
            }

            private void putInt(long recordIndex, int value) {
                ensureMapped(recordIndex);
                long byteOffset = recordIndex * RECORD_SIZE;
                int segmentOffset = (int) (byteOffset % MAP_SEGMENT_BYTES);
                maps.get((int) (byteOffset / MAP_SEGMENT_BYTES)).putInt(segmentOffset, value);
            }

            private int getInt(long recordIndex) {
                if (recordIndex < 0 || recordIndex >= size) return 0;
                long byteOffset = recordIndex * RECORD_SIZE;
                int segment = (int) (byteOffset / MAP_SEGMENT_BYTES);
                if (segment >= maps.size()) return 0;
                int segmentOffset = (int) (byteOffset % MAP_SEGMENT_BYTES);
                return maps.get(segment).getInt(segmentOffset);
            }

            void add(int packedRecord) {
                putInt(size, packedRecord);
                size++;
            }

            long removeLast(long count) {
                if (count <= 0 || size <= 0) return 0;
                long removed = Math.min(count, size);
                size -= removed;
                return removed;
            }

            void setInt(long index, int value) {
                putInt(index, value);
            }

            int getRecord(long index) { return getInt(index); }

            long removeAt(long index) {
                if (index < 0 || index >= size) return -1;
                for (long i = index; i < size - 1; i++) {
                    putInt(i, getInt(i + 1));
                }
                size--;
                return index;
            }

            void clear() {
                maps.clear();
                size = 0;
                try {
                    channel.close();
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {
                }
            }
        }

        private final Bucket[] buckets = new Bucket[ROW_BUCKETS];
        private long size;

        public long size() { return size; }

        private Bucket bucket(int row, boolean create) {
            int safeRow = Math.max(0, Math.min(ROW_BUCKETS - 1, row));
            Bucket b = buckets[safeRow];
            if (b == null && create) {
                b = new Bucket();
                buckets[safeRow] = b;
            }
            return b;
        }

        public void clear() {
            for (int i = 0; i < ROW_BUCKETS; i++) {
                if (buckets[i] != null) {
                    buckets[i].clear();
                    buckets[i] = null;
                }
            }
            size = 0;
        }

        private static int pack(double timeMs, double sustainMs, int laneValue, double stepTimeMs) {
            double safeStep = Math.max(1.0e-9, stepTimeMs);
            int timeUnits = (int) Math.round((Math.max(0.0, timeMs) / safeStep) * 4096.0);
            int sustainUnits = (int) Math.round((Math.max(0.0, sustainMs) / safeStep) * 32.0);
            timeUnits = Math.max(0, Math.min(MAX_TIME_UNITS, timeUnits));
            sustainUnits = Math.max(0, Math.min(MAX_SUSTAIN_UNITS, sustainUnits));
            int lane = Math.max(0, Math.min(7, laneValue));
            return timeUnits | (sustainUnits << 16) | (lane << 29);
        }

        private static int timeUnits(int record) { return record & 0xFFFF; }
        private static int sustainUnits(int record) { return (record >>> 16) & 0x1FFF; }
        private static int lane(int record) { return (record >>> 29) & 0x7; }

        public long add(double timeMs, int laneValue, double sustainMs, double rowHint, double stepTimeMs) {
            int row = (int) Math.floor(rowHint);
            row = Math.max(0, Math.min(ROW_BUCKETS - 1, row));
            Bucket target = bucket(row, true);
            long globalIndex = globalOffsetForRow(row) + target.size();
            target.add(pack(timeMs, sustainMs, laneValue, stepTimeMs));
            size++;
            return globalIndex;
        }

        public void addFast(double timeMs, int laneValue, double sustainMs, double rowHint, double stepTimeMs) {
            int row = (int) Math.floor(rowHint);
            row = Math.max(0, Math.min(ROW_BUCKETS - 1, row));
            bucket(row, true).add(pack(timeMs, sustainMs, laneValue, stepTimeMs));
            size++;
        }

        private long globalOffsetForRow(int row) {
            long offset = 0;
            for (int r = 0; r < row; r++) {
                Bucket b = buckets[r];
                if (b != null) offset += b.size();
            }
            return offset;
        }

        private long[] locate(long index) {
            if (index < 0 || index >= size) return null;
            long remaining = index;
            for (int row = 0; row < ROW_BUCKETS; row++) {
                Bucket b = buckets[row];
                long count = b == null ? 0 : b.size();
                if (remaining < count) return new long[]{row, remaining};
                remaining -= count;
            }
            return null;
        }

        public double getTime(long index, double stepTimeMs) {
            long[] loc = locate(index);
            if (loc == null) return 0.0;
            return (timeUnits(buckets[(int) loc[0]].getRecord(loc[1])) / 4096.0) * stepTimeMs;
        }

        public int getLane(long index) {
            long[] loc = locate(index);
            if (loc == null) return 0;
            return lane(buckets[(int) loc[0]].getRecord(loc[1]));
        }

        public double getSustain(long index, double stepTimeMs) {
            long[] loc = locate(index);
            if (loc == null) return 0.0;
            return (sustainUnits(buckets[(int) loc[0]].getRecord(loc[1])) / 32.0) * stepTimeMs;
        }

        public void setSustain(long index, double sustainMs, double stepTimeMs) {
            long[] loc = locate(index);
            if (loc == null) return;
            Bucket b = buckets[(int) loc[0]];
            int record = b.getRecord(loc[1]);
            int sustainUnits = (int) Math.round((Math.max(0.0, sustainMs) / Math.max(1.0e-9, stepTimeMs)) * 32.0);
            sustainUnits = Math.max(0, Math.min(MAX_SUSTAIN_UNITS, sustainUnits));
            record = (record & 0xE000FFFF) | (sustainUnits << 16);
            b.setInt(loc[1], record);
        }

        public void setLane(long index, int laneValue) {
            long[] loc = locate(index);
            if (loc == null) return;
            Bucket b = buckets[(int) loc[0]];
            int record = b.getRecord(loc[1]);
            int lane = Math.max(0, Math.min(7, laneValue));
            record = (record & 0x1FFFFFFF) | (lane << 29);
            b.setInt(loc[1], record);
        }

        public void removeAt(long index) {
            long[] loc = locate(index);
            if (loc == null) return;
            Bucket b = buckets[(int) loc[0]];
            b.removeAt(loc[1]);
            size--;
            if (b.size() == 0) {
                b.clear();
                buckets[(int) loc[0]] = null;
            }
        }

        public long findNoteAtTimeAndLane(double timeMs, int laneValue, double stepTimeMs, double toleranceMs) {
            double safeStep = Math.max(1.0e-9, stepTimeMs);
            int targetLane = Math.max(0, Math.min(7, laneValue));
            for (int row = 0; row < ROW_BUCKETS; row++) {
                Bucket b = buckets[row];
                if (b == null) continue;
                long base = globalOffsetForRow(row);
                for (long i = 0; i < b.size(); i++) {
                    int record = b.getRecord(i);
                    if (lane(record) != targetLane) continue;
                    double noteTime = (timeUnits(record) / 4096.0) * safeStep;
                    if (Math.abs(noteTime - timeMs) <= toleranceMs) {
                        return base + i;
                    }
                }
            }
            return -1;
        }

        /**
         * Removes the note at an exact time/lane without assuming that the
         * note lives in the bucket represented by the visible grid row.
         * A storage bucket is a coarse 1/16-step bucket; higher zoom levels
         * can contain several visible rows inside the same bucket.
         */
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
            for (int row = ROW_BUCKETS - 1; row >= 0 && remaining > 0; row--) {
                Bucket b = buckets[row];
                if (b == null || b.size() == 0) continue;
                long taken = b.removeLast(remaining);
                removed += taken;
                remaining -= taken;
                if (b.size() == 0) {
                    b.clear();
                    buckets[row] = null;
                }
            }
            size -= removed;
            return removed;
        }

        public long removeMatching(double minRelativeTimeMs, double maxRelativeTimeMs,
                                   double stepTimeMs, int minLane, int maxLane) {
            long removed = 0;
            int firstRow = Math.max(0, Math.min(ROW_BUCKETS - 1, (int) Math.floor(minRelativeTimeMs / stepTimeMs)));
            int lastRow = Math.max(0, Math.min(ROW_BUCKETS - 1, (int) Math.floor(Math.max(0.0, maxRelativeTimeMs - 1e-9) / stepTimeMs)));
            for (int row = firstRow; row <= lastRow; row++) {
                Bucket b = buckets[row];
                if (b == null) continue;
                long i = 0;
                while (i < b.size()) {
                    int record = b.getRecord(i);
                    double time = (timeUnits(record) / 4096.0) * stepTimeMs;
                    int noteLane = lane(record);
                    if (noteLane >= minLane && noteLane <= maxLane
                            && time >= minRelativeTimeMs && time < maxRelativeTimeMs) {
                        b.removeAt(i);
                        removed++;
                        size--;
                    } else {
                        i++;
                    }
                }
                if (b.size() == 0) {
                    b.clear();
                    buckets[row] = null;
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
            return b == null ? 0.0 : (timeUnits(b.getRecord(index)) / 4096.0) * stepTimeMs;
        }

        public int rowGetLane(int row, long index) {
            Bucket b = bucket(row, false);
            return b == null ? 0 : lane(b.getRecord(index));
        }

        public double rowGetSustain(int row, long index, double stepTimeMs) {
            Bucket b = bucket(row, false);
            return b == null ? 0.0 : (sustainUnits(b.getRecord(index)) / 32.0) * stepTimeMs;
        }

        public long globalIndexForRow(int row, long rowIndex) {
            if (row < 0 || row >= ROW_BUCKETS) return -1;
            Bucket b = buckets[row];
            if (b == null || rowIndex < 0 || rowIndex >= b.size()) return -1;
            return globalOffsetForRow(row) + rowIndex;
        }

        public void swapLanes() {
            for (int row = 0; row < ROW_BUCKETS; row++) {
                Bucket b = buckets[row];
                if (b == null) continue;
                for (long i = 0; i < b.size(); i++) {
                    int record = b.getRecord(i);
                    int oldLane = lane(record);
                    int newLane = oldLane < 4 ? oldLane + 4 : oldLane - 4;
                    b.setInt(i, (record & 0x1FFFFFFF) | (newLane << 29));
                }
            }
        }

        public void appendFrom(NoteStore source) {
            if (source == null) return;
            for (int row = 0; row < ROW_BUCKETS; row++) {
                Bucket src = source.buckets[row];
                if (src == null || src.size() == 0) continue;
                Bucket dst = bucket(row, true);
                for (long i = 0; i < src.size(); i++) {
                    dst.add(src.getRecord(i));
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

    private double displayStepTimeMs() {
        // The zoom level changes how many visual rows represent one 4/4 section.
        // At 1/0.25 (the default) there are 16 rows, so each row is one quarter note.
        // At 1/1 there are 64 rows, so each row is one sixteenth note.
        double quarterNoteMs = (60000.0 / activeSong.bpm) / 4.0;
        double visualRowsPerStorageRow = 4.0 * GRID_ZOOM_VALUES[gridZoomIndex];
        return quarterNoteMs / visualRowsPerStorageRow;
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

        p.add(new JLabel("Spam Density (higher = closer notes):"));
        densitySpinner = new JSpinner(new SpinnerNumberModel(1, 1, Integer.MAX_VALUE, 1));
        p.add(densitySpinner);

        p.add(new JLabel("Spam Strength (how many grid row units down to fill):"));
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
            double stepTimeMs = (60000.0 / activeSong.bpm) / 4.0;
            sec.sectionNotes.removeMatching(0.0, sec.lengthInSteps * stepTimeMs, stepTimeMs, 0, 3);
            gridPanel.selectedNoteIndex = -1;
            gridPanel.repaint();
        });
        p.add(clearOpponentSec);

        JButton clearPlayerSec = new JButton("Clear Player Side (Lanes 4-7)");
        clearPlayerSec.addActionListener(e -> {
            Section sec = activeSong.notes.get(currentSectionIndex);
            double stepTimeMs = (60000.0 / activeSong.bpm) / 4.0;
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

        JButton pasteHereSection = new JButton("Paste Notes Section Here");
        pasteHereSection.addActionListener(e -> pasteCopiedSectionHere());
        p.add(pasteHereSection);

        JButton pasteNextSection = new JButton("Paste Next Notes Section");
        pasteNextSection.addActionListener(e -> pasteNextCopiedSection());
        p.add(pasteNextSection);

        return p;
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

        double stepTimeMs = (60000.0 / activeSong.bpm) / 4.0;
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
        double stepTimeMs = (60000.0 / activeSong.bpm) / 4.0;
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
        return currentSectionIndex * (4 * (60000.0 / activeSong.bpm)) + relativeTimeMs;
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
        speedSpinner = new JSpinner(new SpinnerNumberModel(1.6, 0.5, 2147483647.0, 0.1));
        p.add(speedSpinner);

        p.add(new JLabel("Boyfriend (P1):"));
        player1Combo = new JComboBox<>(new String[]{"bf", "bf-pixel", "bf-car"});
        p.add(player1Combo);

        p.add(new JLabel("Opponent (P2):"));
        player2Combo = new JComboBox<>(new String[]{"dad", "pico", "mom", "bf-pixel-opponent"});
        p.add(player2Combo);

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
            JFileChooser chooser = new JFileChooser(".");
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
        double stepMs = (60000.0 / (double) bpmSpinner.getValue()) / 4.0;
        return (long) (steps * stepMs);
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
    }

    private void updatePlayback() {
        if (!isPlaying) return;

        long now = System.currentTimeMillis();
        long deltaMs = now - lastTickMs;
        if (deltaMs < 0) deltaMs = 0;
        lastTickMs = now;

        double stepMs = (60000.0 / (double) bpmSpinner.getValue()) / 4.0;
        double deltaSteps = deltaMs / stepMs;

        positionStepsDouble += deltaSteps;
        long nextSteps = (long) Math.floor(positionStepsDouble);

        if (nextSteps != positionSteps) {
            updatePlaybackSection(nextSteps);
        }

        gridPanel.updatePlaybackCamera(positionStepsDouble);
        gridPanel.repaint();
    }

    private void syncSongDataFromUI() {
        activeSong.song = songNameField.getText();
        activeSong.bpm = (double) bpmSpinner.getValue();
        activeSong.speed = (double) speedSpinner.getValue();
        activeSong.player1 = (String) player1Combo.getSelectedItem();
        activeSong.player2 = (String) player2Combo.getSelectedItem();
        activeSong.needsVoices = voiceTrackCheckbox.isSelected();
    }

    private void syncSongDataToUI() {
        songNameField.setText(activeSong.song);
        bpmSpinner.setValue(activeSong.bpm);
        speedSpinner.setValue(activeSong.speed);
        player1Combo.setSelectedItem(activeSong.player1);
        player2Combo.setSelectedItem(activeSong.player2);
        voiceTrackCheckbox.setSelected(activeSong.needsVoices);
        if(!activeSong.audioFilePath.isEmpty()) {
            audioTrackLabel.setText("Loaded: " + new File(activeSong.audioFilePath).getName());
            loadAudioEngine(new File(activeSong.audioFilePath));
        }
    }

    private static final long JSON_ONE_GB_WARNING_BYTES = 1_000_000_000L;
    private static final long MAX_FILE_BYTES = 2_000_000_000L;
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
            writeString("FNFEBIN1");
            writeString(song.song);
            out.writeDouble(song.bpm);
            out.writeBoolean(song.needsVoices);
            writeString(song.player1);
            writeString(song.player2);
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
            out.writeLong(section.sectionNotes.size());
            bytesWritten += 13;
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
            writer.write("],\"generatedBy\":\"SNIFF ver.6\"}}");
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
                    double sectionStartTime = sectionIndex * (4 * (60000.0 / activeSong.bpm));
                    double stepTimeMs = (60000.0 / activeSong.bpm) / 4.0;

                    for (int row = 0; row < GRID_STEPS_PER_SECTION; row++) {
                        long rowCount = section.sectionNotes.rowSize(row);
                        for (long j = 0; j < rowCount; j++) {
                            double globalTime = sectionStartTime + section.sectionNotes.rowGetTime(row, j, stepTimeMs);
                            int lane = section.sectionNotes.rowGetLane(row, j);
                            double sustain = section.sectionNotes.rowGetSustain(row, j, stepTimeMs);
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

    private void loadChart(File file) {
        if (!file.exists()) return;
        if (file.getName().toLowerCase().endsWith(".bin")) {
            loadBinaryChart(file);
            return;
        }
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            StringBuilder content = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                content.append(line).append('\n');
            }

            String json = content.toString();
            SongData loadedSong = new SongData();

            // Supports the three common JSON layouts used by the supplied charts:
            // 1) JS Engine / Psych-style { "song": { ... } }
            // 2) Psych Engine 1.0 { "speed": ..., "notes": [...] }
            // 3) This editor's JSON { "song": ..., "bpm": ..., "notes": [...] }
            int songObjectOffset = json.indexOf("\"song\"", json.indexOf("{"));
            if (songObjectOffset < 0) songObjectOffset = 0;

            loadedSong.song = extractJSONString(json, "song", songObjectOffset);
            loadedSong.bpm = extractJSONDouble(json, "bpm", songObjectOffset);
            if (loadedSong.bpm <= 0) loadedSong.bpm = 120.0;
            loadedSong.needsVoices = extractJSONBool(json, "needsVoices", songObjectOffset);
            loadedSong.player1 = extractJSONString(json, "player1", songObjectOffset);
            loadedSong.player2 = extractJSONString(json, "player2", songObjectOffset);
            loadedSong.speed = extractJSONDouble(json, "speed", songObjectOffset);
            if (loadedSong.speed <= 0) loadedSong.speed = 1.0;

            List<Section> sections = new ArrayList<>();
            int notesStartIndex = json.indexOf("\"notes\"");
            if (notesStartIndex != -1) {
                int notesArrayStart = json.indexOf('[', notesStartIndex);
                int notesArrayEnd = notesArrayStart >= 0 ? findMatchingJsonBracket(json, notesArrayStart, '[', ']') : -1;

                if (notesArrayStart >= 0 && notesArrayEnd > notesArrayStart) {
                    String notesArray = json.substring(notesArrayStart + 1, notesArrayEnd);
                    java.util.regex.Pattern objectPattern = java.util.regex.Pattern.compile("\\{([^{}]*)\\}", java.util.regex.Pattern.DOTALL);
                    java.util.regex.Matcher objectMatcher = objectPattern.matcher(notesArray);

                    double accumulatedSectionTimeMs = 0.0;
                    while (objectMatcher.find()) {
                        String secBlock = objectMatcher.group(1);
                        Section section = new Section();

                        // Java Chart Editor uses lengthInSteps. JS Engine / Psych often use sectionBeats.
                        String stepStr = extractJSONString(secBlock, "lengthInSteps", 0);
                        if (stepStr == null || stepStr.isEmpty()) {
                            String beatsStr = extractJSONString(secBlock, "sectionBeats", 0);
                            if (beatsStr != null && !beatsStr.isEmpty()) {
                                try {
                                    section.lengthInSteps = Math.max(1,
                                            (int) Math.round(Double.parseDouble(beatsStr.replaceAll("[^0-9.+-]", "")) * 4.0));
                                } catch (Exception ignored) {
                                    section.lengthInSteps = 16;
                                }
                            } else {
                                section.lengthInSteps = 16;
                            }
                        } else {
                            try {
                                section.lengthInSteps = Math.max(1,
                                        (int) Math.round(Double.parseDouble(stepStr.replaceAll("[^0-9.+-]", ""))));
                            } catch (Exception ignored) {
                                section.lengthInSteps = 16;
                            }
                        }

                        String mustHitStr = extractJSONString(secBlock, "mustHitSection", 0);
                        section.mustHitSection = "true".equalsIgnoreCase(mustHitStr.trim());

                        int notesArrIdx = secBlock.indexOf("\"sectionNotes\"");
                        if (notesArrIdx != -1) {
                            int arrStart = secBlock.indexOf('[', notesArrIdx);
                            int arrEnd = arrStart >= 0 ? findMatchingJsonBracket(secBlock, arrStart, '[', ']') : -1;
                            if (arrStart >= 0 && arrEnd > arrStart) {
                                String notesBlock = secBlock.substring(arrStart, arrEnd + 1);
                                java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                                        "\\[\\s*([0-9.+-E]+)\\s*,\\s*([0-9.+-E]+)\\s*,\\s*([0-9.+-E]+)(?:\\s*,.*?)?\\]"
                                );
                                java.util.regex.Matcher matcher = pattern.matcher(notesBlock);
                                double sectionStartTime = accumulatedSectionTimeMs;
                                double stepTimeMs = (60000.0 / loadedSong.bpm) / 4.0;
                                while (matcher.find()) {
                                    try {
                                        double strumTime = Double.parseDouble(matcher.group(1));
                                        int noteData = (int) Math.round(Double.parseDouble(matcher.group(2)));
                                        double sustain = Double.parseDouble(matcher.group(3));
                                        double relative = Math.max(0.0, strumTime - sectionStartTime);
                                        section.sectionNotes.addFast(relative, noteData,
                                                Math.max(0.0, sustain), relative / stepTimeMs, stepTimeMs);
                                    } catch (Exception ignored) {
                                        // Ignore malformed individual notes instead of failing the whole chart.
                                    }
                                }
                            }
                        }

                        sections.add(section);
                        double quarterMs = 60000.0 / loadedSong.bpm;
                        accumulatedSectionTimeMs += (section.lengthInSteps / 4.0) * quarterMs;
                    }
                }
            }

            if (!sections.isEmpty()) {
                loadedSong.notes = sections;
            } else {
                loadedSong.notes.add(new Section());
            }

            this.activeSong = loadedSong;
            this.currentSectionIndex = 0;
            this.positionSteps = 0;
            this.positionStepsDouble = 0;
            this.gridPanel.setScrollRowOffset(0);
            syncSongDataToUI();
            gridPanel.repaint();

        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Error parsing JSON chart: " + e.getMessage());
        }
    }

    private static int findMatchingJsonBracket(String text, int start, char open, char close) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private void loadBinaryChart(File file) {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file), 64 * 1024))) {
            String magic = readBinaryString(in);
            if (!"FNFEBIN1".equals(magic)) throw new IOException("Unknown BIN chart format");
            SongData loaded = new SongData();
            loaded.song = readBinaryString(in);
            loaded.bpm = in.readDouble();
            loaded.needsVoices = in.readBoolean();
            loaded.player1 = readBinaryString(in);
            loaded.player2 = readBinaryString(in);
            loaded.speed = in.readDouble();

            while (true) {
                try {
                    int length = in.readInt();
                    boolean mustHit = in.readBoolean();
                    long noteCount = in.readLong();
                    if (length == 0 && noteCount == 0) break;
                    Section section = new Section();
                    section.lengthInSteps = length;
                    section.mustHitSection = mustHit;
                    double stepTimeMs = (60000.0 / loaded.bpm) / 4.0;
                    double sectionStartTime = loaded.notes.size() * (4 * (60000.0 / loaded.bpm));
                    for (long i = 0; i < noteCount; i++) {
                        double globalTime = in.readDouble();
                        int lane = in.readInt();
                        double sustain = in.readDouble();
                        double relative = globalTime - sectionStartTime;
                        section.sectionNotes.addFast(relative, lane, sustain, relative / stepTimeMs, stepTimeMs);
                    }
                    loaded.notes.add(section);
                } catch (EOFException eof) {
                    break;
                }
            }

            if (loaded.notes.isEmpty()) loaded.notes.add(new Section());
            activeSong = loaded;
            currentSectionIndex = 0;
            positionSteps = 0;
            positionStepsDouble = 0;
            gridPanel.setScrollRowOffset(0);
            syncSongDataToUI();
            gridPanel.repaint();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Error parsing BIN chart: " + e.getMessage(), "BIN Load Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static String readBinaryString(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 0 || len > 16 * 1024 * 1024) throw new IOException("Invalid BIN string length");
        byte[] bytes = new byte[len];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private String extractJSONString(String raw, String key, int startFrom) {
        int idx = raw.indexOf("\"" + key + "\"", startFrom);
        if (idx == -1) return "";
        int colon = raw.indexOf(":", idx);
        int startQuote = raw.indexOf("\"", colon);
        if (startQuote != -1 && startQuote < raw.indexOf(",", colon)) {
            int endQuote = raw.indexOf("\"", startQuote + 1);
            return raw.substring(startQuote + 1, endQuote);
        } else {
            int nextComma = raw.indexOf(",", colon);
            if (nextComma == -1) nextComma = raw.indexOf("}", colon);
            return raw.substring(colon + 1, nextComma).trim();
        }
    }

    private double extractJSONDouble(String raw, String key, int startFrom) {
        try {
            return Double.parseDouble(extractJSONString(raw, key, startFrom).replaceAll("[^0-9.-]", ""));
        } catch (Exception e) {
            return 0.0;
        }
    }

    private boolean extractJSONBool(String raw, String key, int startFrom) {
        return extractJSONString(raw, key, startFrom).contains("true");
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

                            int density = (int) densitySpinner.getValue();
                            // EZ Spam is anchored to exactly ONE visual grid tile. The tile
                            // gets smaller musically as Z/X zooms in, but it remains one
                            // complete editable grid tile. Density only controls how many
                            // spam taps are packed INSIDE that tile; strength is intentionally
                            // ignored here because it belongs to the full-section Spam tool.
                            ezSpamAtGrid(clickedUserLane, clickedRow, density);
                        } else {
                            Section currentSec = activeSong.notes.get(currentSectionIndex);
                            double stepTime = rowToMs(clickedRow);
                            double stepTimeMs = displayStepTimeMs();

                            double sustain = 0.0;
                            double timeTol = Math.max(0.05, ((60000.0 / activeSong.bpm) / 4.0) * 0.02);
                            // A new click always selects a fresh note. Long-note editing is
                            // explicit through E/P and never carries into the next note.
                            longNoteMode = false;
                            longNoteStartIndex = -1;
                            longNoteStartSection = -1;
                            
                            if (SwingUtilities.isRightMouseButton(e)) {
                                double relativeTime = Math.max(0.0, stepTime
                                        - (currentSectionIndex * (4 * (60000.0 / activeSong.bpm))));
                                double storageStepMs = (60000.0 / activeSong.bpm) / 4.0;
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
                                double sectionStartTime = currentSectionIndex * (4 * (60000.0 / activeSong.bpm));
                                double relativeTime = Math.max(0.0, stepTime - sectionStartTime);
                                double storageStepMs = (60000.0 / activeSong.bpm) / 4.0;

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
            double storageStepMs = (60000.0 / activeSong.bpm) / 4.0;
            double gridStepMs = displayStepTimeMs();
            double sustain = sec.sectionNotes.getSustain(selectedNoteIndex, storageStepMs);
            sustain = Math.max(0.0, sustain) + gridStepMs;
            sec.sectionNotes.setSustain(selectedNoteIndex, sustain, storageStepMs);
            sustainSpinner.setValue(sustain);
            repaint();
        }

        private double rowTimeToGlobalMs(double relativeTimeMs) {
            return currentSectionIndex * (4 * (60000.0 / activeSong.bpm)) + relativeTimeMs;
        }

        private void extendSelectedNote() {
            Section sec = activeSong.notes.get(currentSectionIndex);
            if (selectedNoteIndex < 0 || selectedNoteIndex >= sec.sectionNotes.size()) {
                return;
            }

            // Each E press adds exactly one current visual grid interval.
            // Read/write sustain using the canonical storage step so zoom level
            // never causes the existing sustain to be multiplied or halved.
            double storageStepMs = (60000.0 / activeSong.bpm) / 4.0;
            double gridStepMs = displayStepTimeMs();
            double sustain = sec.sectionNotes.getSustain(selectedNoteIndex, storageStepMs);
            sustain = Math.max(0.0, sustain) + gridStepMs;
            sec.sectionNotes.setSustain(selectedNoteIndex, sustain, storageStepMs);

            sustainSpinner.setValue(sustain);
            double sectionStartTime = currentSectionIndex * (4 * (60000.0 / activeSong.bpm));
            strumTimeField.setText(String.format("%.2f", sectionStartTime + sec.sectionNotes.getTime(selectedNoteIndex, storageStepMs)));
            repaint();
        }

        private double rowToMs(int row) {
            double sectionStartTime = currentSectionIndex * (4 * (60000.0 / activeSong.bpm));
            // Every visual row is a real musical subdivision. Do not collapse
            // zoomed rows back to the original 16-row storage grid when mapping
            // the mouse to time.
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

        private void ezSpamAtGrid(int lane, int gridRow, int densityValue) {
            Section currentSec = activeSong.notes.get(currentSectionIndex);

            int safeDensity = Math.max(1, densityValue);
            double visualStepTimeMs = Math.max(1.0e-9, displayStepTimeMs());
            double storageStepMs = (60000.0 / activeSong.bpm) / 4.0;
            double startRelativeTime = Math.max(0.0, gridRow * visualStepTimeMs);
            double endRelativeTime = startRelativeTime + visualStepTimeMs;

            // Replace only notes inside the clicked visual tile/lane. This keeps EZ Spam
            // fully compatible with every zoom level, including rows between the old
            // 1/16 storage buckets.
            currentSec.sectionNotes.removeMatching(
                    startRelativeTime, endRelativeTime, storageStepMs, lane, lane);

            // Fresh tap notes only: never inherit a previous long-note sustain.
            double rowStep = 1.0 / safeDensity;
            for (double offsetRow = 0.0; offsetRow < 1.0 - 1.0e-9; offsetRow += rowStep) {
                double relativeTime = startRelativeTime + (offsetRow * visualStepTimeMs);
                double storageRowHint = relativeTime / storageStepMs;
                currentSec.sectionNotes.addFast(
                        relativeTime, lane, 0.0, storageRowHint, storageStepMs);
            }

            repaint();
        }

        public void spamNotesForCurrentSection(int laneFrom, int laneTo, int densityValue, double strengthRowsToFill, int startRow) {
            Section currentSec = activeSong.notes.get(currentSectionIndex);

            int safeDensity = Math.max(1, densityValue);
            double safeStrength = Math.max(0.03125, strengthRowsToFill);

            int minLane = Math.min(laneFrom, laneTo);
            int maxLane = Math.max(laneFrom, laneTo);

            double sectionStartTime = currentSectionIndex * (4 * (60000.0 / activeSong.bpm));
            // Visual/EZ-Spam movement follows the current zoom grid. Notes are still
            // stored in the canonical 1/16-section timing base so all zoom levels
            // (including rows beyond the original 16 buckets) remain addressable.
            double visualStepTimeMs = displayStepTimeMs();
            double storageStepMs = (60000.0 / activeSong.bpm) / 4.0;

            double startRelativeTime = Math.max(0.0, startRow * visualStepTimeMs);
            double endRelativeTime = startRelativeTime + (safeStrength * visualStepTimeMs);

            currentSec.sectionNotes.removeMatching(
                    startRelativeTime, endRelativeTime, storageStepMs, minLane, maxLane);

            double rowStep = 1.0 / safeDensity;
            double targetMaxRow = safeStrength;
            // EZ/Spam always creates fresh tap notes. A previous long-note edit
            // must never leak its sustain into the first generated spam note.
            double sustain = 0.0;

            for (double offsetRow = 0.0; offsetRow < targetMaxRow; offsetRow += rowStep) {
                double relativeTime = startRelativeTime + (offsetRow * visualStepTimeMs);
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
            double stepTimeMs = (60000.0 / activeSong.bpm) / 4.0;

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
            final long maxRenderedPerRow = 20000;

            // A fully transparent note layer should do no note rendering at all.
            // This both fixes the visual issue and avoids wasting time iterating/rendering
            // potentially millions of notes when the transparency slider is at 0%.
            if (opacity > 0.0f) {
                g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, opacity));

                for (int storageRow = 0; storageRow < GRID_STEPS_PER_SECTION; storageRow++) {
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
                long opponentNotes = 0;
                long playerNotes = 0;
                for (Section countSection : activeSong.notes) {
                    long n = countSection.sectionNotes.size();
                    for (long i = 0; i < n; i++) {
                        int lane = countSection.sectionNotes.getLane(i);
                        if (lane < 4) opponentNotes++; else playerNotes++;
                    }
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