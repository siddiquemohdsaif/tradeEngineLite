package app.ai.lab.tradeEngineLite.Utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class CompanyInfoTest {

    @Test
    void reliance_endToEndLookups() {
        // Ground truth from your JSON
        String nse = "RELIANCE";
        String zerodha = "738561";
        String bseCode = "500325";
        String sockPath = "reliance-industries-ltd/reliance/500325";
        String marketScreener = "RELIANCE-INDUSTRIES-LTD-9058833";
        String nameSlug = "reliance-industries-ltd";

        // 1) zerodha -> nse
        assertEquals(nse, CompanyInfo.getNseSymbolFromZerodhaInstrument(zerodha));

        // 2) nse -> zerodha
        assertEquals(zerodha, CompanyInfo.getZerodhaInstrumentFromNse(nse));

        // 3) BSE code -> nse
        assertEquals(nse, CompanyInfo.getNseSymbolFromBseCompanyCode(bseCode));

        // 4) nse -> BSE code
        assertEquals(bseCode, CompanyInfo.getBseCompanyCodeFromNse(nse));

        // 5) nse -> BSE sockPath
        assertEquals(sockPath, CompanyInfo.getBseSockPathFromNse(nse));

        // 6) nse -> market-screener
        assertEquals(marketScreener, CompanyInfo.getMarketScreenerFromNse(nse));

        // 7) market-screener -> nse
        assertEquals(nse, CompanyInfo.getNseSymbolFromMarketScreener(marketScreener));

        // name from sockPath (first segment)
        assertEquals(nameSlug, CompanyInfo.getCompanyNameBySymbol(nse));
    }

    @Test
    void tcs_endToEndLookups() {
        // Values from your inline JSON
        String nse = "TCS";
        String zerodha = "2953217";
        String bseCode = "532540";
        String sockPath = "tata-consultancy-services-ltd/tcs/532540";
        String marketScreener = "TATA-CONSULTANCY-SERVICES-9743454";

        // Round trips
        assertEquals(nse, CompanyInfo.getNseSymbolFromZerodhaInstrument(zerodha));
        assertEquals(zerodha, CompanyInfo.getZerodhaInstrumentFromNse(nse));

        assertEquals(nse, CompanyInfo.getNseSymbolFromBseCompanyCode(bseCode));
        assertEquals(bseCode, CompanyInfo.getBseCompanyCodeFromNse(nse));

        assertEquals(sockPath, CompanyInfo.getBseSockPathFromNse(nse));

        assertEquals(marketScreener, CompanyInfo.getMarketScreenerFromNse(nse));
        assertEquals(nse, CompanyInfo.getNseSymbolFromMarketScreener(marketScreener));

        // Ensure no cross-wiring with RELIANCE
        assertNotEquals("RELIANCE", CompanyInfo.getNseSymbolFromZerodhaInstrument(zerodha));
        assertNotEquals("738561", CompanyInfo.getZerodhaInstrumentFromNse(nse));
    }

}