package app.ai.lab.tradeEngineLite.BackTest.Engine.HistoricalData;

import app.ai.lab.tradeEngineLite.BackTest.Engine.HistoricalData.Block;
import app.ai.lab.tradeEngineLite.BackTest.Engine.HistoricalData.BlockUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.Comparator;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

public class BlockUtilTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    // Single BIN test file (existing)
    private static final Path BIN_PATH = Paths.get(
            "D:", "SpringBoot project", "Trade", "output files", "timestamp_1738913634720.bin"
    );

    // ZIP which contains the folder with metadata_info.json + many timestamp_*.bin
    // D:\Node Project\Trading\IntraDay record\ticker_historic_data\1739145600000_260617_NIFTY_100_10-02-25.zip
    private static final Path ZIP_BIN_PATH = Paths.get(
            "D:", "Node Project", "Trading", "IntraDay record", "ticker_historic_data",
            "1760486400000_260617_NIFTY_100_15-10-25.zip"
    );

    @Test
    void binToJson_and_back() throws IOException {
        Assumptions.assumeTrue(Files.exists(BIN_PATH), "Input .bin not found: " + BIN_PATH);

        // 1) Read .bin
        byte[] bin = Files.readAllBytes(BIN_PATH);

        // Derive timestamp from filename (e.g., "timestamp_1738913634720.bin")
        long ts = extractTimestamp(BIN_PATH);

        // 2) Convert BIN -> Block
        Optional<Block> blockOpt = BlockUtils.parseBinToBlock(bin, ts);
        assertTrue(blockOpt.isPresent(), "Failed to parse .bin into Block");
        Block block = blockOpt.get();

        // 3) Save Block -> JSON (pretty)
        Path jsonOut = replaceExtension(BIN_PATH, ".json");
        MAPPER.writeValue(jsonOut.toFile(), block);
        System.out.println("Wrote JSON: " + jsonOut);

        // 4) Encode Block -> BIN
        byte[] reEncoded = BlockUtils.parseBlockToBin(block);
        Path binOut = appendSuffixBeforeExt(BIN_PATH, "_reencoded");
        Files.write(binOut, reEncoded, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        System.out.println("Wrote re-encoded BIN: " + binOut);

        // 5) Structural equality check (Block -> JSON tree)
        Block blockFromOriginal = block; // already parsed
        Optional<Block> blockFromReEncodedOpt = BlockUtils.parseBinToBlock(reEncoded, ts);
        assertTrue(blockFromReEncodedOpt.isPresent(), "Re-encoded BIN failed to parse");
        Block blockFromReEncoded = blockFromReEncodedOpt.get();

        JsonNode a = MAPPER.valueToTree(blockFromOriginal);
        JsonNode b = MAPPER.valueToTree(blockFromReEncoded);
        assertEquals(a, b, "Round-trip mismatch between original Block and re-encoded Block");
    }

    @Test
    void jsonToBlock_and_bin() throws IOException {
        // Looks for a sibling JSON with the same base name
        Path jsonPath = replaceExtension(BIN_PATH, ".json");
        if (!Files.exists(jsonPath)) {
            System.out.println("No JSON found at: " + jsonPath + " (skipping this test).");
            return; // skip gracefully if user doesn't have the JSON yet
        }

        // 1) Read JSON -> Block
        Block block = MAPPER.readValue(jsonPath.toFile(), Block.class);

        // 2) Block -> BIN
        byte[] bin = BlockUtils.parseBlockToBin(block);

        // 3) Write .bin next to the JSON
        Path outBin = appendSuffixBeforeExt(jsonPath, "_from_json").resolveSibling(
                replaceExtension(jsonPath, ".bin").getFileName()
        );
        Files.write(outBin, bin, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        System.out.println("Wrote BIN from JSON: " + outBin);

        // 4) Validate: parse back and compare structure with original JSON Block
        Optional<Block> parsedBack = BlockUtils.parseBinToBlock(bin, block.getTimeStamp());
        assertTrue(parsedBack.isPresent(), "BIN produced from JSON failed to parse");
        JsonNode a = MAPPER.valueToTree(block);
        JsonNode b = MAPPER.valueToTree(parsedBack.get());
        assertEquals(a, b, "Mismatch after JSON -> BIN -> Block");
    }

    /**
     * NEW:
     * Convert a ZIP containing timestamp_*.bin files into a new ZIP where
     * each .bin is replaced with a .json (Block serialized with ObjectMapper).
     */
    @Test
    void convertZipOfBinsToZipOfJson() throws IOException {
        Assumptions.assumeTrue(Files.exists(ZIP_BIN_PATH), "Input ZIP not found: " + ZIP_BIN_PATH);

        Path outZip = convertBinZipToJsonZip(ZIP_BIN_PATH);
        System.out.println("Wrote JSON ZIP: " + outZip);
        assertTrue(Files.exists(outZip), "Output ZIP not found: " + outZip);
    }

    // -------- helpers --------

    private static long extractTimestamp(Path binPath) {
        String name = binPath.getFileName().toString();
        // matches: timestamp_(digits).bin
        Pattern p = Pattern.compile("timestamp_(\\d+)\\.bin");
        Matcher m = p.matcher(name);
        if (m.find()) {
            return Long.parseLong(m.group(1));
        }
        // Fallback: use file modified time if the filename doesn’t contain ts
        try {
            return Files.getLastModifiedTime(binPath).toMillis();
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot extract timestamp from: " + name, e);
        }
    }

    private static Path replaceExtension(Path path, String newExtWithDot) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = (dot >= 0) ? name.substring(0, dot) : name;
        return path.getParent().resolve(base + newExtWithDot);
    }

    private static Path appendSuffixBeforeExt(Path path, String suffix) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = (dot >= 0) ? name.substring(0, dot) : name;
        String ext  = (dot >= 0) ? name.substring(dot) : "";
        return path.getParent().resolve(base + suffix + ext);
    }

    /**
     * Core logic:
     * - Unzips zipPath to a temp directory.
     * - For each entry:
     *     - if *.bin -> parse to Block and write *.json with same relative path.
     *     - else    -> copy as-is (e.g. metadata_info.json).
     * - Zips the temp directory into <originalName>_json.zip in same folder.
     * - Cleans the temp directory.
     */
    private static Path convertBinZipToJsonZip(Path zipPath) throws IOException {
        if (!Files.exists(zipPath)) {
            throw new IllegalArgumentException("ZIP does not exist: " + zipPath);
        }

        String zipFileName = zipPath.getFileName().toString();
        int dot = zipFileName.lastIndexOf('.');
        String base = (dot >= 0) ? zipFileName.substring(0, dot) : zipFileName;

        Path outZip = zipPath.getParent().resolve(base + "_json.zip");
        Path tempRoot = Files.createTempDirectory("bin_zip_to_json_");

        try {
            // ---------- 1) Unzip + convert BIN -> JSON into tempRoot ----------
            try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipPath))) {
                ZipEntry entry;
                byte[] buffer = new byte[8192];

                while ((entry = zis.getNextEntry()) != null) {
                    String entryName = entry.getName(); // e.g. "1739.../timestamp_123.bin"

                    if (entry.isDirectory()) {
                        Files.createDirectories(tempRoot.resolve(entryName));
                        zis.closeEntry();
                        continue;
                    }

                    // Read entire entry into memory
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    int len;
                    while ((len = zis.read(buffer)) > 0) {
                        baos.write(buffer, 0, len);
                    }
                    byte[] data = baos.toByteArray();

                    if (entryName.endsWith(".bin")) {
                        // Use just file name for extracting timestamp (timestamp_*.bin)
                        Path fileNameOnly = Paths.get(entryName).getFileName();
                        long ts = extractTimestamp(fileNameOnly);

                        Optional<Block> blockOpt = BlockUtils.parseBinToBlock(data, ts);
                        if (blockOpt.isEmpty()) {
                            System.out.println("WARN: Could not parse BIN entry " + entryName + " - skipping JSON creation.");
                            zis.closeEntry();
                            continue;
                        }

                        Block block = blockOpt.get();
                        // Same folder structure, just .json instead of .bin
                        String jsonEntryName = replaceExtension(Paths.get(entryName), ".json")
                                .toString()
                                .replace('\\', '/');

                        Path outFile = tempRoot.resolve(jsonEntryName);
                        Files.createDirectories(outFile.getParent());
                        MAPPER.writeValue(outFile.toFile(), block);
                    } else {
                        // Non-bin (e.g. metadata_info.json) -> copy as-is
                        Path outFile = tempRoot.resolve(entryName);
                        Files.createDirectories(outFile.getParent());
                        Files.write(outFile, data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                    }

                    zis.closeEntry();
                }
            }

            // ---------- 2) Zip tempRoot into outZip ----------
            try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(outZip))) {
                try {
                    Files.walk(tempRoot)
                            .filter(Files::isRegularFile)
                            .forEach(path -> {
                                String rel = tempRoot.relativize(path).toString().replace('\\', '/');
                                try {
                                    zos.putNextEntry(new ZipEntry(rel));
                                    Files.copy(path, zos);
                                    zos.closeEntry();
                                } catch (IOException e) {
                                    throw new UncheckedIOException(e);
                                }
                            });
                } catch (UncheckedIOException e) {
                    throw e.getCause();
                }
            }

            return outZip;
        } finally {
            // ---------- 3) Cleanup temp directory ----------
            deleteRecursively(tempRoot);
        }
    }

    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) return;
        try {
            Files.walk(root)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
    }
}
