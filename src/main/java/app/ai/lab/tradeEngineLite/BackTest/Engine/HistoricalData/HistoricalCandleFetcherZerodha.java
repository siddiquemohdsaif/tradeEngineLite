package app.ai.lab.tradeEngineLite.BackTest.Engine.HistoricalData;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

public class HistoricalCandleFetcherZerodha {

    private final String enctoken;
    private final String userId;
    private final RestTemplate restTemplate;

    public HistoricalCandleFetcherZerodha(String enctoken) {
        // Hardcoded for now; ideally should come from properties or constructor injection
        this.enctoken = enctoken;
        this.userId = "OOJ378";
        this.restTemplate = new RestTemplate();
    }

    public List<Candle> fetchCandles(int instrumentId, int timeFrameMinutes, String from, String to) throws Exception {
        String url = String.format(
            "https://kite.zerodha.com/oms/instruments/historical/%d/%sminute?user_id=%s&oi=1&from=%s&to=%s",
            instrumentId,
            (timeFrameMinutes == 1 ? "" : timeFrameMinutes),
            userId,
            from,
            to
        );

        HttpHeaders headers = new HttpHeaders();
        headers.set("authorization", "enctoken " + enctoken);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));

        HttpEntity<String> entity = new HttpEntity<>(headers);

        ResponseEntity<ZerodhaResponse> response = restTemplate.exchange(
            url,
            HttpMethod.GET,
            entity,
            ZerodhaResponse.class
        );

        ZerodhaResponse body = response.getBody();
        if (body == null || !"success".equalsIgnoreCase(body.status)) {
            throw new Exception("API returned error or null body.");
        }

        return body.data.candles;
    }

    // ------------------ Inner Model Classes ------------------

    @JsonDeserialize(using = CandleDeserializer.class)
    public static class Candle {
        public String timestamp;
        public double open;
        public double high;
        public double low;
        public double close;
        public long volume;
        public long oi;
    }

    public static class CandleDeserializer extends JsonDeserializer<Candle> {
        @Override
        public Candle deserialize(JsonParser p, DeserializationContext ctxt)
                throws IOException, JsonProcessingException {

            JsonNode node = p.getCodec().readTree(p);

            if (!node.isArray() || node.size() != 7) {
                throw new JsonMappingException(p, "Expected 7 elements in candle array");
            }

            Candle candle = new Candle();
            candle.timestamp = node.get(0).asText();
            candle.open = node.get(1).asDouble();
            candle.high = node.get(2).asDouble();
            candle.low = node.get(3).asDouble();
            candle.close = node.get(4).asDouble();
            candle.volume = node.get(5).asLong();
            candle.oi = node.get(6).asLong();
            return candle;
        }
    }

    public static class ZerodhaResponse {
        public String status;
        public ZerodhaData data;
    }

    public static class ZerodhaData {
        public List<Candle> candles;
    }
}
