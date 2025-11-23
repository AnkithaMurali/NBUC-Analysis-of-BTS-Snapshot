package org.example.myplugin.util;

import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tukaani.xz.XZInputStream;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Recursive extractor used by SnapshotLogLoaderService.
 * Supports: .zip, .tar, .tar.xz, .xz, and copies *.log files.
 * Includes path-traversal protection for archives.
 */
public class Extractor {
    private static final Logger LOG = LoggerFactory.getLogger(Extractor.class);

    /** Extract a single archive recursively into outputDir. Returns outputDir. */
    public static Path extractRecursively(Path archive, Path outputDir) throws IOException {
        Files.createDirectories(outputDir);
        extractRecursive(archive.toFile(), outputDir.toFile());
        return outputDir;
    }

    /** Best-effort delete of a directory tree. */
    public static void deleteTreeQuietly(Path root) {
        if (root == null) return;
        try (var walk = Files.walk(root)) {
            walk.sorted((a, b) -> Integer.compare(b.getNameCount(), a.getNameCount()))
                .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}
    }

    // ---------------- core recursive expansion ----------------

    /** Recursively expands archives found under outputDir; safe for nested structures. */
    public static void extractRecursive(File file, File outputDir) throws IOException {
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            throw new IOException("Failed to create output dir: " + outputDir);
        }

        final String name = file.getName().toLowerCase();
        LOG.info("📦 Processing: {}", file.getAbsolutePath());

        try {
            if (name.endsWith(".zip")) {
                extractZip(file, outputDir);
            } else if (name.endsWith(".tar")) {
                try (FileInputStream fis = new FileInputStream(file)) {
                    extractTar(fis, outputDir, outputDir.toPath());
                }
            } else if (name.endsWith(".tar.xz")) {
                extractTarXZ(file, outputDir);
            } else if (name.endsWith(".xz")) {
                extractXZ(file, outputDir);
            } else if (name.endsWith(".log")) {
                Files.copy(file.toPath(), new File(outputDir, file.getName()).toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOG.warn("⚠️ Failed to extract: {}", file.getAbsolutePath(), e);
        }

        // Now walk anything we just created and expand nested archives
        File[] children = outputDir.listFiles();
        if (children == null) return;

        for (File child : children) {
            if (child.isDirectory()) {
                extractRecursive(child, child); // dive into new directory
            } else {
                String cname = child.getName().toLowerCase();
                boolean isArchive = cname.endsWith(".zip") || cname.endsWith(".tar")
                        || cname.endsWith(".tar.xz") || cname.endsWith(".xz");
                if (isArchive) {
                    File nestedOut = new File(child.getParentFile(),
                            cname.replaceAll("\\.(tar\\.xz|zip|tar|xz)$", "") + "_extracted");
                    extractRecursive(child, nestedOut);
                    // If you want to delete the nested archive after expansion, uncomment:
                    // if (!child.delete()) { LOG.debug("Could not delete nested archive {}", child); }
                }
            }
        }
    }

    // ---------------- archive helpers (with traversal protection) ----------------

    private static void extractZip(File zipFile, File outputDir) throws IOException {
        LOG.info("🔓 Extracting ZIP: {}", zipFile.getName());
        var destRoot = outputDir.toPath().toAbsolutePath().normalize();

        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path outPath = destRoot.resolve(entry.getName()).normalize();
                if (!outPath.startsWith(destRoot)) {
                    throw new IOException("Zip entry attempts path traversal: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(outPath);
                } else {
                    Files.createDirectories(outPath.getParent());
                    try (OutputStream fos = Files.newOutputStream(outPath)) {
                        zis.transferTo(fos);
                    }
                }
                zis.closeEntry();
            }
        }
    }

    private static void extractTar(InputStream is, File outputDir, Path destRoot) throws IOException {
        LOG.info("🔓 Extracting TAR to: {}", outputDir.getAbsolutePath());
        Path root = destRoot.toAbsolutePath().normalize();

        try (TarArchiveInputStream tarIn = new TarArchiveInputStream(is)) {
            ArchiveEntry entry;
            while ((entry = tarIn.getNextEntry()) != null) {
                Path outPath = root.resolve(entry.getName()).normalize();
                if (!outPath.startsWith(root)) {
                    throw new IOException("Tar entry attempts path traversal: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(outPath);
                } else {
                    Files.createDirectories(outPath.getParent());
                    try (OutputStream out = Files.newOutputStream(outPath)) {
                        tarIn.transferTo(out);
                    }
                }
            }
        }
    }

    /** .tar.xz = XZ stream that contains a TAR; unwrap then feed to extractTar */
    private static void extractTarXZ(File tarXzFile, File outputDir) throws IOException {
        LOG.info("🔓 Extracting TAR.XZ: {}", tarXzFile.getName());
        try (InputStream fi = new FileInputStream(tarXzFile);
             BufferedInputStream bi = new BufferedInputStream(fi);
             XZInputStream xzIn = new XZInputStream(bi)) {
            extractTar(xzIn, outputDir, outputDir.toPath());
        }
    }

    /** Decompress plain .xz and, if payload is a TAR, auto-extract it inline. */
    private static void extractXZ(File xzFile, File outputDir) throws IOException {
        LOG.info("🔓 Decompressing XZ: {}", xzFile.getName());
        File outputFile = new File(outputDir, xzFile.getName().replaceAll("\\.xz$", ""));
        Files.createDirectories(outputDir.toPath());

        byte[] buf = new byte[1024 * 1024]; // 1 MiB buffer
        long bytes = 0, lastLog = 0;

        try (InputStream fi = new FileInputStream(xzFile);
             BufferedInputStream bi = new BufferedInputStream(fi);
             XZInputStream xzIn = new XZInputStream(bi);
             OutputStream out = new BufferedOutputStream(new FileOutputStream(outputFile), buf.length)) {

            int r;
            while ((r = xzIn.read(buf)) != -1) {
                out.write(buf, 0, r);
                bytes += r;
                if (bytes - lastLog >= 50L * 1024 * 1024) {
                    LOG.info("… {}: decompressed {} MB so far", xzFile.getName(), (bytes / (1024 * 1024)));
                    lastLog = bytes;
                }
            }
        }
        LOG.info("✅ XZ done: {} ({} MB)", outputFile.getName(), (bytes / (1024 * 1024)));

        // Heuristic: if decompressed file is a TAR (ustar at offset 257), extract it inline
        try (RandomAccessFile raf = new RandomAccessFile(outputFile, "r")) {
            if (raf.length() > 265) {
                raf.seek(257);
                byte[] magic = new byte[5];
                raf.readFully(magic);
                if ("ustar".equals(new String(magic))) {
                    LOG.info("🪄 Detected TAR payload in {}, extracting as TAR", outputFile.getName());
                    try (InputStream is2 = new FileInputStream(outputFile)) {
                        extractTar(is2, outputDir, outputDir.toPath());
                    }
                }
            }
        } catch (IOException ignore) {}
    }
}

