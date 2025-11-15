package app.ai.lab.tradeEngineLite.Algos.ResultReact;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;



/**
 * Model for a single quarter row from your JSON array.
 * Use ObjectMapper to read a List<QuarterRecord>.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class QuarterRecord {

    @JsonProperty("Quarter")
    private String quarter;

    @JsonProperty("Sales")
    private Double sales;

    @JsonProperty("EPS")
    private Double eps;

    @JsonProperty("sales_qoq_change")
    private Double salesQoqChange;

    @JsonProperty("sales_qoq_pct")
    private Double salesQoqPct;

    @JsonProperty("sales_yoy_change")
    private Double salesYoyChange;

    @JsonProperty("sales_yoy_pct")
    private Double salesYoyPct;

    @JsonProperty("eps_qoq_change")
    private Double epsQoqChange;

    @JsonProperty("eps_qoq_pct")
    private Double epsQoqPct;

    @JsonProperty("eps_yoy_change")
    private Double epsYoyChange;

    @JsonProperty("eps_yoy_pct")
    private Double epsYoyPct;

    @JsonProperty("dateTimeRaw")
    private String dateTimeRaw; // keep raw; parse to LocalDate elsewhere if needed

    @JsonProperty("currentDateClosePrice")
    private Double currentDateClosePrice;

    @JsonProperty("pastYearDateClosePrice")
    private Double pastYearDateClosePrice;

    @JsonProperty("price_yoy_pct")
    private Double priceYoyPct;

    @JsonProperty("price_qoq_pct")
    private Double priceQoqPct;

    @JsonProperty("performance")
    private Performance performance;

    // Getters & setters (generate via IDE or Lombok if you prefer)
    public String getQuarter() { return quarter; }
    public void setQuarter(String quarter) { this.quarter = quarter; }

    public Double getSales() { return sales; }
    public void setSales(Double sales) { this.sales = sales; }

    public Double getEps() { return eps; }
    public void setEps(Double eps) { this.eps = eps; }

    public Double getSalesQoqChange() { return salesQoqChange; }
    public void setSalesQoqChange(Double salesQoqChange) { this.salesQoqChange = salesQoqChange; }

    public Double getSalesQoqPct() { return salesQoqPct; }
    public void setSalesQoqPct(Double salesQoqPct) { this.salesQoqPct = salesQoqPct; }

    public Double getSalesYoyChange() { return salesYoyChange; }
    public void setSalesYoyChange(Double salesYoyChange) { this.salesYoyChange = salesYoyChange; }

    public Double getSalesYoyPct() { return salesYoyPct; }
    public void setSalesYoyPct(Double salesYoyPct) { this.salesYoyPct = salesYoyPct; }

    public Double getEpsQoqChange() { return epsQoqChange; }
    public void setEpsQoqChange(Double epsQoqChange) { this.epsQoqChange = epsQoqChange; }

    public Double getEpsQoqPct() { return epsQoqPct; }
    public void setEpsQoqPct(Double epsQoqPct) { this.epsQoqPct = epsQoqPct; }

    public Double getEpsYoyChange() { return epsYoyChange; }
    public void setEpsYoyChange(Double epsYoyChange) { this.epsYoyChange = epsYoyChange; }

    public Double getEpsYoyPct() { return epsYoyPct; }
    public void setEpsYoyPct(Double epsYoyPct) { this.epsYoyPct = epsYoyPct; }

    public String getDateTimeRaw() { return dateTimeRaw; }
    public void setDateTimeRaw(String dateTimeRaw) { this.dateTimeRaw = dateTimeRaw; }

    public Double getCurrentDateClosePrice() { return currentDateClosePrice; }
    public void setCurrentDateClosePrice(Double currentDateClosePrice) { this.currentDateClosePrice = currentDateClosePrice; }

    public Double getPastYearDateClosePrice() { return pastYearDateClosePrice; }
    public void setPastYearDateClosePrice(Double pastYearDateClosePrice) { this.pastYearDateClosePrice = pastYearDateClosePrice; }

    public Double getPriceYoyPct() { return priceYoyPct; }
    public void setPriceYoyPct(Double priceYoyPct) { this.priceYoyPct = priceYoyPct; }

    public Double getPriceQoqPct() { return priceQoqPct; }
    public void setPriceQoqPct(Double priceQoqPct) { this.priceQoqPct = priceQoqPct; }

    public Performance getPerformance() { return performance; }
    public void setPerformance(Performance performance) { this.performance = performance; }

    // ---------- Nested types ----------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Performance {
        @JsonProperty("yoy")
        private PeriodPerformance yoy;

        @JsonProperty("qoq")
        private PeriodPerformance qoq;

        @JsonProperty("final_performance_score")
        private FinalScore finalPerformanceScore;

        @JsonProperty("final_price_score")
        private FinalScore finalPriceScore;

        public PeriodPerformance getYoy() { return yoy; }
        public void setYoy(PeriodPerformance yoy) { this.yoy = yoy; }

        public PeriodPerformance getQoq() { return qoq; }
        public void setQoq(PeriodPerformance qoq) { this.qoq = qoq; }

        public FinalScore getFinalPerformanceScore() { return finalPerformanceScore; }
        public void setFinalPerformanceScore(FinalScore finalPerformanceScore) { this.finalPerformanceScore = finalPerformanceScore; }

        public FinalScore getFinalPriceScore() { return finalPriceScore; }
        public void setFinalPriceScore(FinalScore finalPriceScore) { this.finalPriceScore = finalPriceScore; }
    }

    /**
     * Represents either the YoY or QoQ block.
     * The JSON uses different keys for expected growth; we normalize via @JsonAlias.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PeriodPerformance {

        @JsonProperty("expectedYoyGrowth")
        @JsonAlias({"expectedQoqGrowth"})
        private Double expectedGrowth;

        @JsonProperty("sales")
        private MetricScore sales;

        @JsonProperty("eps")
        private MetricScore eps;

        @JsonProperty("price")
        private MetricScore price;

        public Double getExpectedGrowth() { return expectedGrowth; }
        public void setExpectedGrowth(Double expectedGrowth) { this.expectedGrowth = expectedGrowth; }

        public MetricScore getSales() { return sales; }
        public void setSales(MetricScore sales) { this.sales = sales; }

        public MetricScore getEps() { return eps; }
        public void setEps(MetricScore eps) { this.eps = eps; }

        public MetricScore getPrice() { return price; }
        public void setPrice(MetricScore price) { this.price = price; }
    }

    /**
     * Common "actual / ratio / score" triplet for sales/eps/price.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MetricScore {
        @JsonProperty("actual")
        private Double actual;

        @JsonProperty("ratio")
        private Double ratio;

        @JsonProperty("score")
        private Double score;

        public Double getActual() { return actual; }
        public void setActual(Double actual) { this.actual = actual; }

        public Double getRatio() { return ratio; }
        public void setRatio(Double ratio) { this.ratio = ratio; }

        public Double getScore() { return score; }
        public void setScore(Double score) { this.score = score; }
    }

    /**
     * Final score objects with x and abs_sqrt_x.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FinalScore {
        @JsonProperty("x")
        private Double x;

        @JsonProperty("abs_sqrt_x")
        private Double absSqrtX;

        public Double getX() { return x; }
        public void setX(Double x) { this.x = x; }

        public Double getAbsSqrtX() { return absSqrtX; }
        public void setAbsSqrtX(Double absSqrtX) { this.absSqrtX = absSqrtX; }

        public double getScore(){
            double sign;
            double magnitude = getAbsSqrtX();
            if (getX() > 0.0) {
                sign = 1;
            }else{
                sign = -1;
            }
            return sign * magnitude;
        }
    }


    /** Load quarters JSON. */
    public static List<QuarterRecord> loadHistoricalsQuarters(String jsonPath) {
        List<QuarterRecord> records = new ArrayList<>();
        try {
            String raw = Files.readString(Path.of(jsonPath));
            ObjectMapper mapper = new ObjectMapper();
            records = mapper.readValue(raw, new TypeReference<List<QuarterRecord>>() {});
        } catch (Exception e) {
            e.printStackTrace();
            records = new ArrayList<>();
        }

        return records;
    }


    /** dd-MM-yy window strings based on dateTimeRaw ± 3 months; null if unparsable. */
    public String[] startEndWindow3Months() {
        try {
            var tradeDate = Dates.parseQuarterDate(dateTimeRaw);
            var start = tradeDate.minusMonths(3);
            var end   = tradeDate.plusMonths(3);
            return new String[]{ Dates.fmtDdMmYy(start), Dates.fmtDdMmYy(end) };
        } catch (Exception e) {
            return null;
        }
    }

        /** dd-MM-yy window strings based on dateTimeRaw ± 4 months; null if unparsable. */
    public String[] startEndWindow4Months() {
        try {
            var tradeDate = Dates.parseQuarterDate(dateTimeRaw);
            var start = tradeDate.minusMonths(4);
            var end   = tradeDate.plusMonths(4);
            return new String[]{ Dates.fmtDdMmYy(start), Dates.fmtDdMmYy(end) };
        } catch (Exception e) {
            return null;
        }
    }

    /** True when both final scores exist. */
    public boolean hasBothFinalScores() {
        return performance != null
                && performance.getFinalPerformanceScore() != null
                && performance.getFinalPriceScore() != null;
    }
}
