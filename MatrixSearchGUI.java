import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.Arrays;
import java.util.HashMap;
import java.util.concurrent.ThreadLocalRandom;

public class MatrixSearchGUI extends JFrame {

    // ----- Data state -----
    private int N = 0;
    private int[][] matrix = null;

    // Cache for binary + hash requirements:
    private int[] sorted = null;                      // cached sorted 1D copy
    private boolean sortedReady = false;

    private HashMap<Integer, Integer> posMap = null;  // cached value -> linear index
    private boolean hashReady = false;

    private static final int RUNS = 100;

    // ----- UI -----
    private final JTextField tfMatrixSize = new JTextField(6);     // BLANK
    private final JButton btnGenerate = new JButton("Generate Matrix");

    private final JTextField tfSearchNumber = new JTextField(10);  // BLANK
    private final JButton btnSequential = new JButton("Sequential Search");
    private final JButton btnBinary = new JButton("Binary Search");
    private final JButton btnHash = new JButton("Hash Search");

    private final JTextArea taLog = new JTextArea(12, 90);
    private final JTable table = new JTable();
    private final MatrixTableModel tableModel = new MatrixTableModel();

    public MatrixSearchGUI() {
        super("Matrix Display");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));

        // Top panel (reference-like layout)
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        top.add(new JLabel("Matrix size:"));
        top.add(tfMatrixSize);
        top.add(btnGenerate);

        top.add(Box.createHorizontalStrut(12));
        top.add(new JLabel("Search Number:"));
        top.add(tfSearchNumber);

        top.add(btnSequential);
        top.add(btnBinary);
        top.add(btnHash);

        add(top, BorderLayout.NORTH);

        // Center: table
        table.setModel(tableModel);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        add(new JScrollPane(table), BorderLayout.CENTER);

        // Bottom: log
        taLog.setEditable(false);
        taLog.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        add(new JScrollPane(taLog), BorderLayout.SOUTH);

        // Actions
        btnGenerate.addActionListener(e -> onGenerate());
        btnSequential.addActionListener(e -> runBenchmark(SearchMode.SEQ));
        btnBinary.addActionListener(e -> runBenchmark(SearchMode.BIN));
        btnHash.addActionListener(e -> runBenchmark(SearchMode.HASH));

        setSize(1150, 720);
        setLocationRelativeTo(null);
    }

    enum SearchMode { SEQ, BIN, HASH }

    private void onGenerate() {
        int newN;
        try {
            newN = Integer.parseInt(tfMatrixSize.getText().trim());
            if (newN <= 0) throw new NumberFormatException();
        } catch (NumberFormatException ex) {
            log("Invalid matrix size.");
            return;
        }

        // Safeguard (GUI tables can freeze with extremely large sizes)
        long total = (long) newN * (long) newN;
        if (total > 50_000_000L) {
            log("Matrix too large for GUI demo. Try smaller N (depends on RAM).");
            return;
        }

        long t0 = System.nanoTime();
        generateUniqueMatrix(newN);
        long t1 = System.nanoTime();

        // Reset caches (per requirement)
        sorted = null;
        sortedReady = false;
        posMap = null;
        hashReady = false;

        tableModel.setMatrix(matrix);
        resizeColumnsForSmallN();

        log(String.format("Generated %dx%d matrix with unique numbers. Time: %.3f ms",
                N, N, (t1 - t0) / 1e6));
    }

    private void runBenchmark(SearchMode mode) {
        if (matrix == null) {
            log("Generate the matrix first.");
            return;
        }

        int target;
        try {
            target = Integer.parseInt(tfSearchNumber.getText().trim());
        } catch (NumberFormatException ex) {
            log("Invalid search number.");
            return;
        }

        String name = switch (mode) {
            case SEQ -> "Sequential Search";
            case BIN -> "Binary Search";
            case HASH -> "Hash Search";
        };

        log("");
        log("==============================================================");
        log(String.format("%s (100 runs) | target=%d", name, target));
        log("--------------------------------------------------------------");

        // Build caches once if needed, and measure build time separately
        long buildTimeNs = 0L;

        if (mode == SearchMode.BIN) {
            if (!sortedReady) {
                long b0 = System.nanoTime();
                ensureSorted();
                long b1 = System.nanoTime();
                buildTimeNs += (b1 - b0);
            }
            if (!hashReady) {
                long b0 = System.nanoTime();
                ensureHash();
                long b1 = System.nanoTime();
                buildTimeNs += (b1 - b0);
            }
        } else if (mode == SearchMode.HASH) {
            if (!hashReady) {
                long b0 = System.nanoTime();
                ensureHash();
                long b1 = System.nanoTime();
                buildTimeNs += (b1 - b0);
            }
        }

        if (buildTimeNs > 0) {
            log(String.format("Cache build time (one-time): %.3f ms", buildTimeNs / 1e6));
            log("--------------------------------------------------------------");
        }

        long totalSearchNs = 0L;

        // Run 100 searches, showing each run
        int firstFoundR = -1, firstFoundC = -1;
        boolean firstFound = false;

        for (int i = 1; i <= RUNS; i++) {
            int[] rc = {-1, -1};
            boolean found;

            long t0 = System.nanoTime();
            found = switch (mode) {
                case SEQ -> sequentialSearch(target, rc);
                case BIN -> binarySearchCached(target, rc);   // only uses cached data now
                case HASH -> hashSearchCached(target, rc);    // only uses cached data now
            };
            long t1 = System.nanoTime();

            long dt = (t1 - t0);
            totalSearchNs += dt;

            if (found && !firstFound) {
                firstFound = true;
                firstFoundR = rc[0];
                firstFoundC = rc[1];
            }

            if (found) {
                log(String.format("#%03d: Found at (%d, %d) | %.3f ms",
                        i, rc[0], rc[1], dt / 1e6));
            } else {
                log(String.format("#%03d: Not found           | %.3f ms",
                        i, dt / 1e6));
            }
        }

        // Report
        double totalSearchMs = totalSearchNs / 1e6;
        double avgSearchMs = totalSearchMs / RUNS;

        double buildMs = buildTimeNs / 1e6;
        double grandTotalMs = buildMs + totalSearchMs;
        double avgIncludingBuildMs = grandTotalMs / RUNS;

        log("--------------------------------------------------------------");
        log("REPORT (after 100 searches)");
        if (buildTimeNs > 0) {
            log(String.format("One-time cache build time:      %.3f ms", buildMs));
        } else {
            log("One-time cache build time:      0.000 ms (already built)");
        }
        log(String.format("Total search time (100 runs):   %.3f ms", totalSearchMs));
        log(String.format("Average time per search:        %.6f ms", avgSearchMs));
        log(String.format("Grand total (build + searches): %.3f ms", grandTotalMs));
        log(String.format("Avg per search incl. build:     %.6f ms", avgIncludingBuildMs));
        log("==============================================================");

        // Optional: scroll table to the first found location
        if (firstFound) {
            scrollToCell(firstFoundR, firstFoundC);
        }
    }

    // ----- Core requirements implementation -----

    private void generateUniqueMatrix(int n) {
        this.N = n;
        int total = n * n;

        int[] values = new int[total];
        for (int i = 0; i < total; i++) values[i] = i + 1;

        // Fisher-Yates shuffle
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = total - 1; i > 0; i--) {
            int j = rnd.nextInt(i + 1);
            int tmp = values[i];
            values[i] = values[j];
            values[j] = tmp;
        }

        matrix = new int[n][n];
        int k = 0;
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                matrix[r][c] = values[k++];
            }
        }
    }

    private boolean sequentialSearch(int target, int[] rcOut) {
        for (int r = 0; r < N; r++) {
            for (int c = 0; c < N; c++) {
                if (matrix[r][c] == target) {
                    rcOut[0] = r;
                    rcOut[1] = c;
                    return true;
                }
            }
        }
        return false;
    }

    // Uses cached sorted + hash map (location lookup)
    private boolean binarySearchCached(int target, int[] rcOut) {
        // assume caches are already ensured in benchmark build step,
        // but safe to keep these:
        ensureSorted();
        ensureHash();

        int idx = Arrays.binarySearch(sorted, target);
        if (idx < 0) return false;

        Integer lin = posMap.get(target);
        if (lin == null) return false;

        rcOut[0] = lin / N;
        rcOut[1] = lin % N;
        return true;
    }

    private boolean hashSearchCached(int target, int[] rcOut) {
        ensureHash();
        Integer lin = posMap.get(target);
        if (lin == null) return false;
        rcOut[0] = lin / N;
        rcOut[1] = lin % N;
        return true;
    }

    private void ensureSorted() {
        if (sortedReady) return;
        int total = N * N;
        sorted = new int[total];
        int k = 0;
        for (int r = 0; r < N; r++) {
            for (int c = 0; c < N; c++) {
                sorted[k++] = matrix[r][c];
            }
        }
        Arrays.sort(sorted);
        sortedReady = true;
    }

    private void ensureHash() {
        if (hashReady) return;
        posMap = new HashMap<>(N * N * 2);
        for (int r = 0; r < N; r++) {
            for (int c = 0; c < N; c++) {
                posMap.put(matrix[r][c], r * N + c);
            }
        }
        hashReady = true;
    }

    // ----- UI helpers -----

    private void log(String msg) {
        taLog.append(msg + "\n");
        taLog.setCaretPosition(taLog.getDocument().getLength());
    }

    private void scrollToCell(int r, int c) {
        if (r < 0 || c < 0) return;
        Rectangle rect = table.getCellRect(r, c, true);
        table.scrollRectToVisible(rect);
        table.setRowSelectionInterval(r, r);
        table.setColumnSelectionInterval(c, c);
    }

    private void resizeColumnsForSmallN() {
        if (matrix == null) return;
        if (N <= 50) {
            for (int c = 0; c < N; c++) {
                table.getColumnModel().getColumn(c).setPreferredWidth(70);
            }
        } else {
            // Dense look like the reference screenshot
            int limit = Math.min(N, 300);
            for (int c = 0; c < limit; c++) {
                table.getColumnModel().getColumn(c).setPreferredWidth(55);
            }
        }
    }

    // ----- Table Model -----
    static class MatrixTableModel extends AbstractTableModel {
        private int[][] data = null;

        public void setMatrix(int[][] m) {
            this.data = m;
            fireTableStructureChanged();
        }

        @Override public int getRowCount() {
            return data == null ? 0 : data.length;
        }

        @Override public int getColumnCount() {
            return (data == null || data.length == 0) ? 0 : data[0].length;
        }

        @Override public Object getValueAt(int rowIndex, int columnIndex) {
            return data[rowIndex][columnIndex];
        }

        @Override public String getColumnName(int column) {
            return String.valueOf(column);
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new MatrixSearchGUI().setVisible(true));
    }
}