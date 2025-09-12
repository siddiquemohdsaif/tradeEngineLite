package app.ai.lab.tradeEngineLite.BackTest.Engine.HistoricalData;

import app.ai.lab.tradeEngineLite.BackTest.Engine.HistoricalData.Block;
import app.ai.lab.tradeEngineLite.BackTest.Engine.HistoricalData.Block.IndexPacket;
import app.ai.lab.tradeEngineLite.BackTest.Engine.HistoricalData.Block.MarketDepthEntry;
import app.ai.lab.tradeEngineLite.BackTest.Engine.HistoricalData.Block.PacketData;
import app.ai.lab.tradeEngineLite.BackTest.Engine.HistoricalData.Block.StockPacket;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class BlockUtils {

    private BlockUtils() {}

    // ---------------- Public API ----------------

    /** Rust: parse_bin_to_block(data, time_stamp) -> Option<Block> */
    public static Optional<Block> parseBinToBlock(byte[] buffer, long timeStamp) {
        List<PacketData> packets = parseBinaryData(buffer);
        if (packets == null) return Optional.empty();
        return Optional.of(new Block(timeStamp, packets));
    }

    /** Rust: parse_block_to_bin(block) -> Vec<u8> */
    public static byte[] parseBlockToBin(Block block) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(out);

            List<PacketData> infos = block.getInfo();
            if (infos.size() > 0xFFFF) {
                throw new IllegalArgumentException("Too many packets (max 65535).");
            }

            writeU16(dos, infos.size());

            for (PacketData pd : infos) {
                if (pd instanceof StockPacket sp) {
                    byte[] payload = buildStockPayload(sp);
                    writeU16(dos, payload.length);
                    dos.write(payload);
                } else if (pd instanceof IndexPacket ip) {
                    byte[] payload = buildIndexPayload(ip);
                    writeU16(dos, payload.length);
                    dos.write(payload);
                } else {
                    // Unknown packet type — skip
                }
            }

            dos.flush();
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize Block to binary", e);
        }
    }

    // ---------------- Parsing ----------------

    private static List<PacketData> parseBinaryData(byte[] buffer) {
        if (buffer == null || buffer.length < 2) return null;

        int offset = 0;
        int numPackets = readU16(buffer, offset);
        offset += 2;

        List<PacketData> packets = new ArrayList<>();

        for (int i = 0; i < numPackets; i++) {
            if (offset + 2 > buffer.length) return null;
            int packetLength = readU16(buffer, offset);
            offset += 2;

            if (offset + packetLength > buffer.length) return null;

            if (packetLength == 184) {
                PacketData pd = parseStockPacket(buffer, offset, packetLength);
                if (pd != null) packets.add(pd);
            } else if (packetLength == 32) {
                PacketData pd = parseIndexPacket(buffer, offset, packetLength);
                if (pd != null) packets.add(pd);
            } else {
                // Unknown packet length — ignore (matches Rust behavior of skipping unknowns)
            }

            offset += packetLength;
        }

        return packets;
    }

    private static PacketData parseStockPacket(byte[] buf, int off, int len) {
        if (len != 184) return null;
        int p = off;

        StockPacket sp = new StockPacket();
        sp.setInstrumentToken(readU32(buf, p)); p += 4;
        sp.setLastTradedPrice(readU32(buf, p)); p += 4;
        sp.setLastTradedQuantity(readU32(buf, p)); p += 4;
        sp.setAvgTradedPrice(readU32(buf, p)); p += 4;
        sp.setVolumeTraded(readU32(buf, p)); p += 4;
        sp.setTotalBuyQuantity(readU32(buf, p)); p += 4;
        sp.setTotalSellQuantity(readU32(buf, p)); p += 4;
        sp.setOpenPrice(readU32(buf, p)); p += 4;
        sp.setHighPrice(readU32(buf, p)); p += 4;
        sp.setLowPrice(readU32(buf, p)); p += 4;
        sp.setClosePrice(readU32(buf, p)); p += 4;
        sp.setLastTradedTimestamp(readU32(buf, p)); p += 4;
        sp.setOpenInterest(readU32(buf, p)); p += 4;
        sp.setOpenInterestDayHigh(readU32(buf, p)); p += 4;
        sp.setOpenInterestDayLow(readU32(buf, p)); p += 4;
        sp.setExchangeTimestamp(readU32(buf, p)); p += 4;

        // Market depth: 10 entries * 12 bytes each
        List<MarketDepthEntry> md = new ArrayList<>(10);
        for (int i = 0; i < 10; i++) {
            int e = p + i * 12;
            long quantity = readU32(buf, e);
            long price = readU32(buf, e + 4);
            int orders = readU16(buf, e + 8);
            // e+10..e+11 are padding/unused (2 bytes) — ignored (to match Rust)
            md.add(new MarketDepthEntry(quantity, price, orders));
        }
        sp.setMarketDepth(md);

        return sp;
    }

    private static PacketData parseIndexPacket(byte[] buf, int off, int len) {
        if (len != 32) return null;
        int p = off;

        IndexPacket ip = new IndexPacket();
        ip.setToken(readU32(buf, p)); p += 4;
        ip.setLastTradedPrice(readU32(buf, p)); p += 4;
        ip.setHighPrice(readU32(buf, p)); p += 4;
        ip.setLowPrice(readU32(buf, p)); p += 4;
        ip.setOpenPrice(readU32(buf, p)); p += 4;
        ip.setClosePrice(readU32(buf, p)); p += 4;
        ip.setPriceChange(readU32(buf, p)); p += 4;
        ip.setExchangeTimestamp(readU32(buf, p)); // p += 4;

        return ip;
    }

    // ---------------- Building payloads ----------------

    private static byte[] buildStockPayload(StockPacket sp) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(184);
        DataOutputStream dos = new DataOutputStream(out);

        writeU32(dos, sp.getInstrumentToken());
        writeU32(dos, sp.getLastTradedPrice());
        writeU32(dos, sp.getLastTradedQuantity());
        writeU32(dos, sp.getAvgTradedPrice());
        writeU32(dos, sp.getVolumeTraded());
        writeU32(dos, sp.getTotalBuyQuantity());
        writeU32(dos, sp.getTotalSellQuantity());
        writeU32(dos, sp.getOpenPrice());
        writeU32(dos, sp.getHighPrice());
        writeU32(dos, sp.getLowPrice());
        writeU32(dos, sp.getClosePrice());
        writeU32(dos, sp.getLastTradedTimestamp());
        writeU32(dos, sp.getOpenInterest());
        writeU32(dos, sp.getOpenInterestDayHigh());
        writeU32(dos, sp.getOpenInterestDayLow());
        writeU32(dos, sp.getExchangeTimestamp());

        List<MarketDepthEntry> md = sp.getMarketDepth();
        int count = md == null ? 0 : md.size();
        if (count != 10) {
            // Wire format expects exactly 10 entries; fill/trim as needed
            List<MarketDepthEntry> fixed = new ArrayList<>(10);
            if (md != null) fixed.addAll(md);
            while (fixed.size() < 10) fixed.add(new MarketDepthEntry(0, 0, 0));
            if (fixed.size() > 10) fixed = fixed.subList(0, 10);
            md = fixed;
        }

        for (MarketDepthEntry e : md) {
            writeU32(dos, e.getQuantity());
            writeU32(dos, e.getPrice());
            writeU16(dos, e.getOrders());
            writeU16(dos, 0); // 2-byte padding to match 12-byte stride (Rust ignored these)
        }

        dos.flush();
        byte[] payload = out.toByteArray();
        if (payload.length != 184) {
            throw new IllegalStateException("Stock payload must be 184 bytes, got " + payload.length);
        }
        return payload;
    }

    private static byte[] buildIndexPayload(IndexPacket ip) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(32);
        DataOutputStream dos = new DataOutputStream(out);

        writeU32(dos, ip.getToken());
        writeU32(dos, ip.getLastTradedPrice());
        writeU32(dos, ip.getHighPrice());
        writeU32(dos, ip.getLowPrice());
        writeU32(dos, ip.getOpenPrice());
        writeU32(dos, ip.getClosePrice());
        writeU32(dos, ip.getPriceChange());
        writeU32(dos, ip.getExchangeTimestamp());

        dos.flush();
        byte[] payload = out.toByteArray();
        if (payload.length != 32) {
            throw new IllegalStateException("Index payload must be 32 bytes, got " + payload.length);
        }
        return payload;
    }

    // ---------------- Helpers (big-endian, unsigned) ----------------

    private static int readU16(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    private static long readU32(byte[] b, int off) {
        return ((long)(b[off] & 0xFF) << 24) |
               ((long)(b[off + 1] & 0xFF) << 16) |
               ((long)(b[off + 2] & 0xFF) << 8) |
               ((long)(b[off + 3] & 0xFF));
    }

    private static void writeU16(DataOutputStream dos, int v) throws Exception {
        dos.writeShort(v & 0xFFFF);
    }

    private static void writeU32(DataOutputStream dos, long v) throws Exception {
        if ((v & ~0xFFFFFFFFL) != 0) {
            throw new IllegalArgumentException("u32 overflow: " + v);
        }
        dos.writeInt((int)(v & 0xFFFFFFFFL));
    }
}
