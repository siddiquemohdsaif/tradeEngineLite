package app.ai.lab.tradeEngineLite.BackTest.Engine.HistoricalData;

import app.ai.lab.tradeEngineLite.BackTest.Engine.HistoricalData.Block;
import app.ai.lab.tradeEngineLite.BackTest.Engine.HistoricalData.BlockUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.*;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

public class BlockUtilTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    // Windows path with spaces handled safely:
    private static final Path BIN_PATH = Paths.get(
            "D:", "SpringBoot project", "Trade", "output files", "timestamp_1738913634720.bin"
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

        // 5) Sanity check: compare structural equality of original vs re-encoded by comparing Blocks
        //    (Direct byte-for-byte comparison can fail if original had non-zero padding bytes.)
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

    // -------- helpers --------

    private static long extractTimestamp(Path binPath) {
        String name = binPath.getFileName().toString();
        // matches: timestamp_<digits>.bin
        Pattern p = Pattern.compile("timestamp_(\\d+)\\.bin");
        Matcher m = p.matcher(name);
        if (m.find()) {
            return Long.parseLong(m.group(1));
        }
        // Fallback: use file modified time if the filename doesn’t contain ts
        try {
            return Files.getLastModifiedTime(binPath).toMillis();
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot extract timestamp from: " + name);
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
}
