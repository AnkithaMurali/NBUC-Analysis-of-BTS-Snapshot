package org.example.myplugin.services;

import org.graylog2.plugin.periodical.Periodical;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SnapshotLogLoaderService extends Periodical {
    private static final Logger LOG = LoggerFactory.getLogger(SnapshotLogLoaderService.class);

    // === Directories (inside container) ===
    private static final Path SNAPSHOTS_ROOT = Paths.get(
            System.getenv().getOrDefault("SNAPSHOTS_ROOT", "/var/lib/graylog/snapshots"));
    private static final Path EXTRACT_ROOT = Paths.get(
            System.getenv().getOrDefault("EXTRACT_ROOT", "/var/lib/graylog/_extracted"));

    // Lock & processed markers (next to ZIP)
    private static final String LOCK_NAME = ".processing.lock";
    private static final String PROCESSED_NAME = ".processed";
    private static final long STALE_LOCK_MS = 10L * 60L * 1000L; // 10 minutes

    // Runtime knobs (env)
    private static final int SNAPSHOT_MAX_PER_RUN =
            Integer.parseInt(System.getenv().getOrDefault("SNAPSHOT_MAX_PER_RUN", "3"));
    private static final boolean REPROCESS_ON_START =
            Boolean.parseBoolean(System.getenv().getOrDefault("SNAPSHOT_REPROCESS_ON_START", "false"));
    private static final int STARTUP_DELAY_SECONDS =
            Integer.parseInt(System.getenv().getOrDefault("SNAPSHOT_STARTUP_DELAY_SECONDS", "60"));
    private static final AtomicBoolean BOOT_CLEAR_DONE = new AtomicBoolean(false);

    // GELF UDP target
    private DatagramSocket udp;
    private InetAddress gelfHost;
    private final int gelfPort = Integer.parseInt(System.getenv().getOrDefault("GELF_PORT", "12201"));
    private final ObjectMapper mapper = new ObjectMapper();

    // File selection (runtime*.log and *_log.txt)
    private static final Pattern RUNTIME_MATCH =
            Pattern.compile(".*runtime.*\\.log|.*_log\\.txt", Pattern.CASE_INSENSITIVE);

    // Level + family extraction
    private static final Pattern LEVEL_TOKEN_RE = Pattern.compile(
            "\\b(?<level>ERR|ERROR|WRN|WARN|INF|INFO|DBG|DEBUG)/(?<comp>[A-Za-z0-9_]+)/(?:.*/)?(?<base>[A-Za-z0-9_]+)\\.cpp\\b",
            Pattern.CASE_INSENSITIVE);

    // Normalization helpers
    private static final Pattern LEAD_TOKEN_BEFORE_ASC = Pattern.compile("^[A-Za-z0-9]+\\s+(?=ASC-)");
    private static final Pattern ANGLE_TS_RE           = Pattern.compile("<[^>]+>");
    private static final Pattern CPP_LINE_RE           = Pattern.compile("(\\b[\\w/.-]+\\.cpp):\\d+");
    private static final Pattern LONG_PATHS            = Pattern.compile("(/[^\\s:()]+)+");
    private static final Pattern UUID_RE               = Pattern.compile("\\b[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern MAC_RE                = Pattern.compile("\\b(?:[0-9a-f]{2}:){5}[0-9a-f]{2}\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern IPV4_RE               = Pattern.compile("\\b\\d{1,3}(?:\\.\\d{1,3}){3}\\b");
    private static final Pattern IPV6_RE_STRICT        = Pattern.compile("\\b(?:[0-9A-Fa-f]{1,4}:){2,7}[0-9A-Fa-f]{1,4}\\b");
    private static final Pattern HEX_RE                = Pattern.compile("\\b0x[0-9A-Fa-f]+\\b");
    private static final Pattern NUM_RE                = Pattern.compile("\\b\\d+\\b");
    private static final Pattern SEND_ERR_IND_RE       = Pattern.compile("send\\s+Error\\s+Indication\\s*:\\s*\\d+,\\s*\\d+\\.", Pattern.CASE_INSENSITIVE);
    private static final Pattern PEER_ADDR_RE          = Pattern.compile("peer\\s+addr\\s*:\\s*\\S+", Pattern.CASE_INSENSITIVE);

    private static final String L_ERR = "ERR";
    private static final String L_WRN = "WRN";

    private static class FamKey {
        final String snapshotId;
        final String level;
        final String family; // e.g. comp:base
        FamKey(String snapshotId, String level, String family) {
            this.snapshotId = snapshotId; this.level = level; this.family = family;
        }
        @Override public boolean equals(Object o){
            if (!(o instanceof FamKey)) return false;
            FamKey k = (FamKey)o;
            return snapshotId.equals(k.snapshotId) && level.equals(k.level) && family.equals(k.family);
        }
        @Override public int hashCode(){ return Objects.hash(snapshotId, level, family); }
    }

    @Inject
    public SnapshotLogLoaderService() {
        try {
            this.udp = new DatagramSocket();
            String host = System.getenv().getOrDefault("GELF_HOST", "127.0.0.1");
            this.gelfHost = InetAddress.getByName(host);
        } catch (Exception e) {
            throw new RuntimeException("Failed to init GELF UDP sender", e);
        }
    }

    // ========== Periodical lifecycle ==========
    @Override public boolean runsForever() { return false; }
    @Override public boolean masterOnly() { return false; }
    @Override public int getPeriodSeconds() { return 60; }
    @Override public int getInitialDelaySeconds() { return STARTUP_DELAY_SECONDS; }
    @Override public boolean isDaemon() { return false; }
    @Override public boolean startOnThisNode() { return true; }
    @Override public boolean stopOnGracefulShutdown() { return true; }
    @Override public Logger getLogger() { return LOG; }

    // ========== Main loop ==========
    @Override
    public void doRun() {
        LOG.info(">>> SnapshotLogLoaderService started <<<");

        try {
            Files.createDirectories(SNAPSHOTS_ROOT);
            Files.createDirectories(EXTRACT_ROOT);
        } catch (IOException e) {
            LOG.error("Failed to ensure directories", e);
            return;
        }

        if (REPROCESS_ON_START && BOOT_CLEAR_DONE.compareAndSet(false, true)) {
            clearProcessedMarkers(SNAPSHOTS_ROOT);
        }

        List<Path> toProcess = listZipFiles(SNAPSHOTS_ROOT);
        toProcess.removeIf(p -> Files.exists(p.resolveSibling(p.getFileName() + "." + PROCESSED_NAME)));
        toProcess.sort(Comparator.comparingLong(this::mtime));

        if (toProcess.isEmpty()) {
            LOG.info("No snapshot ZIPs to process.");
            return;
        }

        int processed = 0;
        for (Path zip : toProcess) {
            if (processed >= SNAPSHOT_MAX_PER_RUN) break;

            String zipName = zip.getFileName().toString();
            String snapshotId = zipName.replaceFirst("(?i)\\.zip$", "");
            LOG.info("Candidate ZIP (unprocessed): {}", zipName);

            Path lockFile = zip.resolveSibling(zip.getFileName() + "." + LOCK_NAME);
            Path doneFile = zip.resolveSibling(zip.getFileName() + "." + PROCESSED_NAME);

            if (!acquireLock(lockFile)) {
                LOG.info("Could not acquire lock for {}. Skipping.", zipName);
                continue;
            }

            try {
                processSnapshotZip(zip, snapshotId);

                try {
                    Files.createFile(doneFile);
                } catch (IOException e) {
                    if (!Files.exists(doneFile)) {
                        LOG.warn("Failed to create {} for {}.", PROCESSED_NAME, zipName, e);
                    }
                }

                try { Files.deleteIfExists(zip); } catch (IOException ignored) {}
                processed++;
            } catch (Exception e) {
                LOG.error("ZIP processing failed for {}", zipName, e);
            } finally {
                try { Files.deleteIfExists(lockFile); } catch (IOException ignored) {}
            }
        }

        LOG.info("Processed {} ZIP snapshot(s) this run.", processed);
        LOG.info(">>> SnapshotLogLoaderService completed <<<");
    }

    // ========== ZIP processing ==========
    private void processSnapshotZip(Path zip, String snapshotId) throws IOException {
        String batchId = snapshotId + "-" + Instant.now().toEpochMilli();
        Path extractDir = EXTRACT_ROOT.resolve(batchId);
        LOG.info("Extracting {} -> {}", zip.getFileName(), extractDir);

        try {
            org.example.myplugin.util.Extractor.extractRecursively(zip, extractDir);
            processSnapshot(extractDir, snapshotId);
        } finally {
            try { org.example.myplugin.util.Extractor.deleteTreeQuietly(extractDir); } catch (Exception ignored) {}
        }
    }

    // ========== Core analysis on extracted dir ==========
    private void processSnapshot(Path snap, String snapshotId) {
        List<File> logFiles = findRuntimeLogs(snap);
        LOG.info("Processing {} runtime logs for {}", logFiles.size(), snapshotId);

        Map<String, Integer> levelTotals = new HashMap<>();
        levelTotals.put(L_ERR, 0);
        levelTotals.put(L_WRN, 0);

        Map<FamKey,Integer> familyCounts = new HashMap<>();
        Map<FamKey,Map<String,Integer>> familyFiles = new HashMap<>();
        Map<FamKey,Map<String,Integer>> familyPatterns = new HashMap<>();
        Map<FamKey,Map<String,List<String>>> familyExamples = new HashMap<>();

        for (File logFile : logFiles) {
            int fileErr = 0, fileWrn = 0;

            try (BufferedReader br = new BufferedReader(new FileReader(logFile, StandardCharsets.UTF_8))) {
                String raw;
                while ((raw = br.readLine()) != null) {
                    String[] parsed = parseLevelFamily(raw);
                    if (parsed == null) continue;

                    String level = parsed[0];
                    if (!L_ERR.equals(level) && !L_WRN.equals(level)) continue;

                    String family = parsed[1];
                    String pattern = normalizeLine(raw);
                    FamKey key = new FamKey(snapshotId, level, family);

                    // Totals
                    levelTotals.put(level, levelTotals.get(level) + 1);

                    // Family & file counts
                    familyCounts.merge(key, 1, Integer::sum);
                    familyFiles.computeIfAbsent(key, k -> new HashMap<>())
                               .merge(logFile.getName(), 1, Integer::sum);

                    // Pattern counts & examples
                    familyPatterns.computeIfAbsent(key, k -> new HashMap<>())
                                  .merge(pattern, 1, Integer::sum);
                    Map<String,List<String>> exMap = familyExamples.computeIfAbsent(key, k -> new HashMap<>());
                    List<String> exs = exMap.computeIfAbsent(pattern, p -> new ArrayList<>());
                    if (exs.size() < 3) exs.add(raw);

                    // per-file counters
                    if (L_ERR.equals(level)) fileErr++;
                    if (L_WRN.equals(level)) fileWrn++;

                    // Ship raw line (optional)
                    Map<String,Object> fields = new HashMap<>();
                    fields.put("type", level);
                    fields.put("snapshot_id", snapshotId);
                    fields.put("logger_family", family);
                    fields.put("file", logFile.getName());
                    fields.put("level_text", level);
                    sendGelf(raw, level, fields);
                }
            } catch (IOException e) {
                LOG.error("Read error: {}", logFile.getAbsolutePath(), e);
            }

            // Per-file summary
            Map<String,Object> fileFields = new HashMap<>();
            fileFields.put("type", "snapshot_file_summary");
            fileFields.put("snapshot_id", snapshotId);
            fileFields.put("file", logFile.getName());
            fileFields.put("err_count", fileErr);
            fileFields.put("wrn_count", fileWrn);
            fileFields.put("total_runtime_lines", countLines(logFile));
            sendGelf("Snapshot File Summary: " + logFile.getName(), "INFO", fileFields);
        }

        // Snapshot summary
        Map<String,Object> sumFields = new HashMap<>();
        sumFields.put("type", "snapshot_summary");
        sumFields.put("snapshot_id", snapshotId);
        sumFields.put("err_count", levelTotals.get(L_ERR));
        sumFields.put("wrn_count", levelTotals.get(L_WRN));
        sendGelf("Snapshot Summary", "INFO", sumFields);

        // Top families per level
        emitTopFamilies(snapshotId, L_ERR, 5, familyCounts, familyFiles, familyPatterns, familyExamples);
        emitTopFamilies(snapshotId, L_WRN, 5, familyCounts, familyFiles, familyPatterns, familyExamples);
    }

    // -------- processing helpers --------
    private List<File> findRuntimeLogs(Path snapshotDir) {
        List<File> result = new ArrayList<>();
        try {
            Files.walk(snapshotDir)
                 .filter(Files::isRegularFile)
                 .filter(p -> RUNTIME_MATCH.matcher(p.getFileName().toString()).matches())
                 .forEach(p -> result.add(p.toFile()));
        } catch (IOException e) {
            LOG.error("Scan failed {}", snapshotDir, e);
        }
        return result;
    }

    private static String[] parseLevelFamily(String line) {
        Matcher m = LEVEL_TOKEN_RE.matcher(line);
        if (!m.find()) return null;
        String level = m.group("level").toUpperCase(Locale.ROOT);
        if ("ERROR".equals(level)) level = "ERR";
        if ("WARN".equals(level))  level = "WRN";
        String comp = m.group("comp");
        String base = m.group("base");
        String family = comp + ":" + base;
        return new String[]{ level, family };
    }

    private static String normalizeLine(String line) {
        String s = line.trim();
        s = LEAD_TOKEN_BEFORE_ASC.matcher(s).replaceFirst("");
        s = ANGLE_TS_RE.matcher(s).replaceAll("<TS>");
        s = CPP_LINE_RE.matcher(s).replaceAll("$1:<N>");
        s = LONG_PATHS.matcher(s).replaceAll("<PATH>");
        s = SEND_ERR_IND_RE.matcher(s).replaceAll("send Error Indication : <N>, <N>.");
        s = PEER_ADDR_RE.matcher(s).replaceAll("peer addr : <ADDR>");
        s = UUID_RE.matcher(s).replaceAll("<UUID>");
        s = MAC_RE.matcher(s).replaceAll("<MAC>");
        s = IPV6_RE_STRICT.matcher(s).replaceAll("<IP6>");
        s = IPV4_RE.matcher(s).replaceAll("<IP4>");
        s = HEX_RE.matcher(s).replaceAll("<HEX>");
        s = NUM_RE.matcher(s).replaceAll("<N>");
        s = s.replaceAll("\\s+", " ");
        return s;
    }

    private int countLines(File file) {
        try (BufferedReader r = new BufferedReader(new FileReader(file, StandardCharsets.UTF_8))) {
            int n = 0; while (r.readLine() != null) n++; return n;
        } catch (IOException e) { return -1; }
    }

    private void emitTopFamilies(
            String snapshotId, String level, int topK,
            Map<FamKey,Integer> familyCounts,
            Map<FamKey,Map<String,Integer>> familyFiles,
            Map<FamKey,Map<String,Integer>> familyPatterns,
            Map<FamKey,Map<String,List<String>>> familyExamples) {

        List<Map.Entry<FamKey,Integer>> famList = new ArrayList<>();
        for (Map.Entry<FamKey,Integer> e : familyCounts.entrySet()) {
            if (e.getKey().snapshotId.equals(snapshotId) && e.getKey().level.equals(level)) {
                famList.add(e);
            }
        }
        famList.sort((a,b) -> Integer.compare(b.getValue(), a.getValue()));

        for (int i = 0; i < Math.min(topK, famList.size()); i++) {
            FamKey key = famList.get(i).getKey();
            int famCount = famList.get(i).getValue();

            Map<String,Integer> pattMap = familyPatterns.getOrDefault(key, Collections.emptyMap());
            String topPattern = null; int topPatternCount = 0;
            for (Map.Entry<String,Integer> pe : pattMap.entrySet()) {
                if (pe.getValue() > topPatternCount) { topPattern = pe.getKey(); topPatternCount = pe.getValue(); }
            }

            Map<String,Integer> fileMap = familyFiles.getOrDefault(key, Collections.emptyMap());
            List<Map.Entry<String,Integer>> fileList = new ArrayList<>(fileMap.entrySet());
            fileList.sort((a,b) -> Integer.compare(b.getValue(), a.getValue()));
            List<String> topFiles = new ArrayList<>();
            for (int j = 0; j < Math.min(3, fileList.size()); j++) {
                topFiles.add(fileList.get(j).getKey() + ":" + fileList.get(j).getValue());
            }

            List<String> examples = familyExamples
                    .getOrDefault(key, Collections.emptyMap())
                    .getOrDefault(topPattern, Collections.emptyList());

            Map<String,Object> fields = new HashMap<>();
            fields.put("type", "top_family");
            fields.put("snapshot_id", snapshotId);
            fields.put("rank", i + 1);
            fields.put("level", levelToSyslog(level));
            fields.put("level_text", level);
            fields.put("family", key.family);
            fields.put("family_count", famCount);
            fields.put("top_files", topFiles.toString());
            if (topPattern != null) {
                fields.put("top_pattern", topPattern);
                fields.put("top_pattern_count", topPatternCount);
            }
            for (int k = 0; k < Math.min(3, examples.size()); k++) {
                fields.put("example_" + (k + 1), examples.get(k));
            }
            sendGelf("Top " + level + " Family #" + (i + 1) + ": " + key.family, level, fields);
        }
    }

    // --- filesystem helpers ---
    private static List<Path> listZipFiles(Path root) {
        List<Path> out = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(root, "*.zip")) {
            for (Path p : ds) if (Files.isRegularFile(p)) out.add(p);
        } catch (IOException ignored) {}
        return out;
    }

    private boolean acquireLock(Path lockFile) {
        try {
            if (Files.exists(lockFile)) {
                long age = System.currentTimeMillis() - Files.getLastModifiedTime(lockFile).toMillis();
                if (age > STALE_LOCK_MS) {
                    LOG.warn("Found STALE lock (age {} ms) at {}. Removing it.", age, lockFile);
                    Files.deleteIfExists(lockFile);
                } else {
                    LOG.info("Lock {} exists and is fresh (age {} ms).", lockFile, age);
                    return false;
                }
            }
            Files.createFile(lockFile);
            return true;
        } catch (IOException e) {
            LOG.warn("Failed to create/recover lock {}", lockFile, e);
            return false;
        }
    }

    private long mtime(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return Long.MAX_VALUE;
        }
    }

    private void clearProcessedMarkers(Path root) {
        try {
            if (!Files.exists(root)) return;
            Files.walk(root)
                 .filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith("." + PROCESSED_NAME))
                 .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
            LOG.info("Cleared .processed markers under {}", root);
        } catch (IOException e) {
            LOG.warn("Failed clearing .processed markers under {}", root, e);
        }
    }

    // --- GELF UDP ---
    private int levelToSyslog(String level) {
        if (L_ERR.equals(level)) return 3;
        if (L_WRN.equals(level)) return 4;
        return 6;
    }

    private void sendGelf(String shortMessage, String level, Map<String,Object> fields) {
        try {
            Map<String,Object> g = new HashMap<>();
            g.put("version", "1.1");
            g.put("host", "snapshot-loader");
            g.put("short_message", shortMessage);
            g.put("timestamp", (double) (System.currentTimeMillis()/1000.0));
            g.put("level", levelToSyslog(level));
            if (fields != null) {
                for (Map.Entry<String,Object> e : fields.entrySet()) {
                    g.put("_" + e.getKey(), e.getValue());
                }
            }
            byte[] payload = mapper.writeValueAsBytes(g);
            DatagramPacket packet = new DatagramPacket(payload, payload.length, gelfHost, gelfPort);
            udp.send(packet);
        } catch (Exception e) {
            LOG.warn("Failed to send GELF UDP", e);
        }
    }
}

