package app.ai.lab.tradeEngineLite.Utils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;

/**
 * CompanyInfo
 *
 * JSON format (per NSE symbol):
 * "RELIANCE": [
 *   "738561",                                   // Zerodha instrument code
 *   "RELIANCE-INDUSTRIES-LTD-9058833",          // MarketScreener code
 *   "reliance-industries-ltd/reliance/500325"   // BSE sockPath: {companyNameSlug}/{companySymbolSlug}/{BSE code}
 * ]
 *
 * Supported lookups:
 * 1) Zerodha instrument  -> NSE symbol
 * 2) NSE symbol          -> Zerodha instrument
 * 3) BSE company code    -> NSE symbol
 * 4) NSE symbol          -> BSE company code
 * 5) NSE symbol          -> BSE sockPath
 * 6) NSE symbol          -> MarketScreener code
 * 7) MarketScreener code -> NSE symbol
 */
public class CompanyInfo {

    private static final String companies = "{ \"RELIANCE\": [ \"738561\", \"RELIANCE-INDUSTRIES-LTD-9058833\", \"reliance-industries-ltd/reliance/500325\" ], \"TCS\": [ \"2953217\", \"TATA-CONSULTANCY-SERVICES-9743454\", \"tata-consultancy-services-ltd/tcs/532540\" ], \"HDFCBANK\": [ \"341249\", \"HDFC-BANK-LIMITED-105516154\", \"hdfc-bank-ltd/hdfcbank/500180\" ], \"BHARTIARTL\": [ \"2714625\", \"BHARTI-AIRTEL-LIMITED-6494412\", \"bharti-airtel-ltd/bhartiartl/532454\" ], \"ICICIBANK\": [ \"1270529\", \"ICICI-BANK-LIMITED-23672738\", \"icici-bank-ltd/icicibank/532174\" ], \"INFY\": [ \"408065\", \"INFOSYS-LIMITED-9743342\", \"infosys-ltd/infy/500209\" ], \"SBIN\": [ \"779521\", \"STATE-BANK-OF-INDIA-18603402\", \"state-bank-of-india/sbin/500112\" ], \"HINDUNILVR\": [ \"356865\", \"HINDUSTAN-UNILEVER-LIMITE-9058826\", \"hindustan-unilever-ltd/hindunilvr/500696\" ], \"ITC\": [ \"424961\", \"ITC-LIMITED-9743470\", \"itc-ltd/itc/500875\" ], \"LICI\": [ \"139142660\", \"LIFE-INSURANCE-CORPORATIO-137965464\", \"life-insurance-corporation-of-india/lici/543526\" ], \"BAJFINANCE\": [ \"81153\", \"BAJAJ-FINANCE-LIMITED-33647061\", \"bajaj-finance-limited/bajfinance/500034\" ], \"LT\": [ \"2939649\", \"LARSEN-TOUBRO-LIMITED-6491923\", \"larsen--toubro-ltd/lt/500510\" ], \"HCLTECH\": [ \"1850625\", \"HCL-TECHNOLOGIES-LIMITED-9058931\", \"hcl-technologies-ltd/hcltech/532281\" ], \"SUNPHARMA\": [ \"857857\", \"SUN-PHARMACEUTICAL-INDUST-9058928\", \"sun-pharmaceutical-industries-ltd/sunpharma/524715\" ], \"MARUTI\": [ \"2815745\", \"MARUTI-SUZUKI-INDIA-LTD-9059169\", \"maruti-suzuki-india-ltd/maruti/532500\" ], \"M&M\": [ \"519937\", \"MAHINDRA-MAHINDRA-LIMITED-9058830\", \"mahindra--mahindra-ltd/mm/500520\" ], \"KOTAKBANK\": [ \"492033\", \"KOTAK-MAHINDRA-BANK-LIMIT-9059013\", \"kotak-mahindra-bank-ltd/kotakbank/500247\" ], \"WIPRO\": [ \"969473\", \"WIPRO-LIMITED-6494362\", \"wipro-ltd/wipro/507685\" ], \"ULTRACEMCO\": [ \"2952193\", \"ULTRATECH-CEMENT-LIMITED-9059270\", \"ultratech-cement-ltd/ultracemco/532538\" ], \"ONGC\": [ \"633601\", \"OIL-AND-NATURAL-GAS-CORPO-9743117\", \"oil-natural-gas-corpn-ltd/ongc/500312\" ], \"AXISBANK\": [ \"1510401\", \"AXIS-BANK-LIMITED-46730206\", \"axis-bank-ltd/axisbank/532215\" ], \"NTPC\": [ \"2977281\", \"NTPC-LTD-9743456\", \"ntpc-ltd/ntpc/532555\" ], \"TITAN\": [ \"897537\", \"TITAN-COMPANY-LIMITED-9059025\", \"titan-company-ltd/titan/500114\" ], \"BAJAJFINSV\": [ \"4268801\", \"BAJAJ-FINSERV-LTD-9059974\", \"bajaj-finserv-ltd/bajajfinsv/532978\" ], \"ADANIENT\": [ \"6401\", \"ADANI-ENTERPRISES-LIMITED-6498499\", \"adani-enterprises-ltd/adanient/512599\" ], \"POWERGRID\": [ \"3834113\", \"POWER-GRID-CORPORATION-OF-9059859\", \"power-grid-corporation-of-india-ltd/powergrid/532898\" ], \"TATAMOTORS\": [ \"884737\", \"TATA-MOTORS-LIMITED-9058835\", \"tata-motors-ltd/tatamotors/500570\" ], \"HAL\": [ \"589569\", \"HINDUSTAN-AERONAUTICS-LIM-47006646\", \"hindustan-aeronautics-ltd/hal/541154\" ], \"DMART\": [ \"5097729\", \"AVENUE-SUPERMARTS-LIMITED-34491272\", \"avenue-supermarts-ltd/dmart/540376\" ], \"BAJAJ-AUTO\": [ \"4267265\", \"BAJAJ-AUTO-LIMITED-9059975\", \"bajaj-auto-ltd/bajaj-auto/532977\" ], \"ADANIPORTS\": [ \"3861249\", \"ADANI-PORTS-SPECIAL-ECONO-9059803\", \"adani-ports-and-special-economic-zone-ltd/adaniports/532921\" ], \"COALINDIA\": [ \"5215745\", \"COAL-INDIA-LIMITED-9060008\", \"coal-india-ltd/coalindia/533278\" ], \"JSWSTEEL\": [ \"3001089\", \"JSW-STEEL-LIMITED-46728626\", \"jsw-steel-ltd/jswsteel/500228\" ], \"ASIANPAINT\": [ \"60417\", \"ASIAN-PAINTS-LIMITED-13891348\", \"asian-paints-ltd/asianpaint/500820\" ], \"NESTLEIND\": [ \"4598529\", \"NESTLE-INDIA-LIMITED-6493165\", \"nestle-india-ltd/nestleind/500790\" ], \"BEL\": [ \"98049\", \"BHARAT-ELECTRONICS-LIMITE-46728583\", \"bharat-electronics-ltd/bel/500049\" ], \"ETERNAL\": [ \"1304833\", \"ETERNAL-LIMITED-125138034\", \"eternal-ltd/eternal/543320\" ], \"TRENT\": [ \"502785\", \"TRENT-LIMITED-46728633\", \"trent-ltd/trent/500251\" ], \"SIEMENS\": [ \"806401\", \"SIEMENS-LIMITED-9058964\", \"siemens-ltd/siemens/500550\" ], \"HINDZINC\": [ \"364545\", \"HINDUSTAN-ZINC-LIMITED-9058942\", \"hindustan-zinc-ltd/hindzinc/500188\" ], \"VBL\": [ \"4843777\", \"VARUN-BEVERAGES-LIMITED-34067670\", \"varun-beverages-ltd/vbl/540180\" ], \"ADANIPOWER\": [ \"4451329\", \"ADANI-POWER-LIMITED-9059969\", \"adani-power-ltd/adanipower/533096\" ], \"DLF\": [ \"3771393\", \"DLF-LIMITED-9743639\", \"dlf-ltd/dlf/532868\" ], \"IRFC\": [ \"519425\", \"INDN-RAIL-119082036\", \"indian-railway-finance-corporation-ltd/irfc/543257\" ], \"IOC\": [ \"415745\", \"INDIAN-OIL-CORPORATION-LI-9743425\", \"indian-oil-corporation-ltd/ioc/530965\" ], \"LTIM\": [ \"4561409\", \"LTIMINDTREE-LIMITED-31496899\", \"ltimindtree-ltd/ltim/540005\" ], \"VEDL\": [ \"784129\", \"VEDANTA-LIMITED-37569657\", \"vedanta-ltd/vedl/500295\" ], \"INDIGO\": [ \"2865921\", \"INTERGLOBE-AVIATION-LIMIT-25531239\", \"interglobe-aviation-ltd/indigo/539448\" ], \"TATASTEEL\": [ \"895745\", \"TATA-STEEL-LIMITED-6491942\", \"tata-steel-ltd/tatasteel/500470\" ], \"GRASIM\": [ \"315393\", \"GRASIM-INDUSTRIES-LIMITED-33647063\", \"grasim-industries-ltd/grasim/500300\" ], \"DIVISLAB\": [ \"2800641\", \"DIVI-S-LABORATORIES-LIMIT-9059190\", \"divis-laboratories-ltd/divislab/532488\" ], \"ADANIGREEN\": [ \"912129\", \"ADANI-GREEN-ENERGY-LIMITE-46901469\", \"adani-green-energy-ltd/adanigreen/541450\" ], \"JIOFIN\": [ \"4644609\", \"JIO-FINANCIAL-SERVICES-LI-157409091\", \"jio-financial-services-ltd/jiofin/543940\" ], \"EICHERMOT\": [ \"232961\", \"EICHER-MOTORS-LIMITED-6493334\", \"eicher-motors-ltd/eichermot/505200\" ], \"SBILIFE\": [ \"5582849\", \"SBI-LIFE-INSURANCE-COMPAN-46731375\", \"sbi-life-insurance-company-ltd/sbilife/540719\" ], \"TECHM\": [ \"3465729\", \"TECH-MAHINDRA-LIMITED-33647041\", \"tech-mahindra-ltd/techm/532755\" ], \"PIDILITIND\": [ \"681985\", \"PIDILITE-INDUSTRIES-LIMIT-9058912\", \"pidilite-industries-ltd/pidilitind/500331\" ], \"PFC\": [ \"3660545\", \"POWER-FINANCE-CORPORATION-9059675\", \"power-finance-corporation-ltd/pfc/532810\" ], \"HDFCLIFE\": [ \"119553\", \"HDFC-LIFE-INSURANCE-COMPA-46731381\", \"hdfc-life-insurance-company-ltd/hdfclife/540777\" ], \"HINDALCO\": [ \"348929\", \"HINDALCO-INDUSTRIES-LIMIT-6493281\", \"hindalco-industries-ltd/hindalco/500440\" ], \"AMBUJACEM\": [ \"325121\", \"AMBUJA-CEMENTS-6491917\", \"ambuja-cements-ltd/ambujacem/500425\" ], \"BAJAJHLDNG\": [ \"78081\", \"BAJAJ-HOLDINGS-INVESTMENT-6491903\", \"bajaj-holdings--investment-ltd/bajajhldng/500490\" ], \"LODHA\": [ \"824321\", \"LODHA-DEVELOPERS-LIMITED-121553374\", \"lodha-developers-ltd/lodha/543287\" ], \"TVSMOTOR\": [ \"2170625\", \"TVS-MOTOR-COMPANY-LIMITED-6495911\", \"tvs-motor-company-ltd/tvsmotor/532343\" ], \"ABB\": [ \"3329\", \"ABB-LTD-8943400\", \"abb-india-ltd/abb/500002\" ], \"BRITANNIA\": [ \"140033\", \"BRITANNIA-INDUSTRIES-LTD-9058852\", \"britannia-industries-ltd/britannia/500825\" ], \"GAIL\": [ \"1207553\", \"GAIL-INDIA-LIMITED-9743098\", \"gail-(india)-ltd/gail/532155\" ], \"TATAPOWER\": [ \"877057\", \"TATA-POWER-COMPANY-LIMITE-9062790\", \"tata-power-company-ltd/tatapower/500400\" ], \"CHOLAFIN\": [ \"175361\", \"CHOLAMANDALAM-INVESTMENT--6498532\", \"cholamandalam-investment-and-finance-company-ltd/cholafin/511243\" ], \"INDHOTEL\": [ \"387073\", \"THE-INDIAN-HOTELS-COMPANY-6493261\", \"indian-hotels-company-ltd/indhotel/500850\" ], \"CIPLA\": [ \"177665\", \"CIPLA-LIMITED-9058821\", \"cipla-ltd/cipla/500087\" ], \"RECLTD\": [ \"3930881\", \"REC-LIMITED-9059899\", \"rec-ltd/recltd/532955\" ], \"PNB\": [ \"2730497\", \"PUNJAB-NATIONAL-BANK-19165555\", \"punjab-national-bank/pnb/532461\" ], \"GODREJCP\": [ \"2585345\", \"GODREJ-CONSUMER-PRODUCTS--9059191\", \"godrej-consumer-products-ltd/godrejcp/532424\" ], \"BANKBARODA\": [ \"1195009\", \"BANK-OF-BARODA-23320361\", \"bank-of-baroda/bankbaroda/532134\" ], \"MAXHEALTH\": [ \"5728513\", \"MAX-HEALTHCARE-INSTITUTE--111315625\", \"max-healthcare-institute-ltd/maxhealth/543220\" ], \"BPCL\": [ \"134657\", \"BHARAT-PETROLEUM-CORPORAT-9743071\", \"bharat-petroleum-corporation-ltd/bpcl/500547\" ], \"SHRIRAMFIN\": [ \"1102337\", \"SHRIRAM-FINANCE-LIMITED-6498530\", \"shriram-finance-ltd/shriramfin/511218\" ], \"TORNTPHARM\": [ \"900609\", \"TORRENT-PHARMACEUTICALS-L-6493365\", \"torrent-pharmaceuticals-ltd/torntpharm/500420\" ], \"DRREDDY\": [ \"225537\", \"DR-REDDY-S-LABORATORIES-L-6492671\", \"dr-reddys-laboratories-ltd/drreddy/500124\" ], \"NAUKRI\": [ \"3520257\", \"INFO-EDGE-INDIA-LIMITED-9059674\", \"info-edge-(india)-ltd/naukri/532777\" ], \"SHREECEM\": [ \"794369\", \"SHREE-CEMENT-LIMITED-9059180\", \"shree-cement-ltd/shreecem/500387\" ], \"UNITDSPR\": [ \"2674433\", \"UNITED-SPIRITS-LIMITED-45456076\", \"united-spirits-ltd/unitdspr/532432\" ], \"HAVELLS\": [ \"2513665\", \"HAVELLS-INDIA-LIMITED-17567316\", \"havells-india-ltd/havells/517354\" ], \"TATACONSUM\": [ \"878593\", \"TATA-CONSUMER-PRODUCTS-LI-9058838\", \"tata-consumer-products-ltd/tataconsum/500800\" ], \"APOLLOHOSP\": [ \"40193\", \"APOLLO-HOSPITALS-ENTERPRI-9058923\", \"apollo-hospitals-enterprise-ltd/apollohosp/508869\" ], \"LUPIN\": [ \"2672641\", \"LUPIN-LIMITED-9059009\", \"lupin-ltd/lupin/500257\" ], \"MANKIND\": [ \"3937281\", \"MANKIND-PHARMA-LIMITED-154280476\", \"mankind-pharma-ltd/mankind/543904\" ], \"ZYDUSLIFE\": [ \"2029825\", \"ZYDUS-LIFESCIENCES-LIMITE-34067618\", \"zydus-lifesciences-ltd/zyduslife/532321\" ], \"IOB\": [ \"2393089\", \"INDIAN-OVERSEAS-BANK-9059279\", \"indian-overseas-bank/iob/532388\" ], \"CGPOWER\": [ \"194561\", \"CG-POWER-AND-INDUSTRIAL-S-9058926\", \"cg-power-and-industrial-solutions-ltd/cgpower/500093\" ], \"MOTHERSON\": [ \"1076225\", \"SAMVARDHANA-MOTHERSON-INT-6496618\", \"samvardhana-motherson-international-ltd/motherson/517334\" ], \"PERSISTENT\": [ \"4701441\", \"PERSISTENT-SYSTEMS-LIMITE-9059922\", \"persistent-systems-ltd/persistent/533179\" ], \"SWIGGY\": [ \"6928897\", \"SWIGGY-LIMITED-178213415\", \"swiggy-ltd/swiggy/544285\" ], \"INDUSTOWER\": [ \"7458561\", \"INDUS-TOWERS-LIMITED-13122683\", \"indus-towers-ltd/industower/534816\" ], \"DABUR\": [ \"197633\", \"DABUR-INDIA-LIMITED-9058822\", \"dabur-india-ltd/dabur/500096\" ], \"ADANIENSOL\": [ \"2615553\", \"ADANI-ENERGY-SOLUTIONS-LI-23437234\", \"adani-energy-solutions-ltd/adaniensol/539254\" ], \"ICICIGI\": [ \"5573121\", \"ICICI-LOMBARD-GENERAL-INS-46731374\", \"icici-lombard-general-insurance-company-ltd/icicigi/540716\" ], \"UNIONBANK\": [ \"2752769\", \"UNION-BANK-OF-INDIA-9058857\", \"union-bank-of-india/unionbank/532477\" ], \"MUTHOOTFIN\": [ \"6054401\", \"MUTHOOT-FINANCE-LIMITED-9743895\", \"muthoot-finance-ltd/muthootfin/533398\" ], \"POLYCAB\": [ \"2455041\", \"POLYCAB-INDIA-LIMITED-58674735\", \"polycab-india-ltd/polycab/542652\" ], \"DIXON\": [ \"5552641\", \"DIXON-TECHNOLOGIES-INDIA--111615221\", \"dixon-technologies-(india)-ltd/dixon/540699\" ], \"GMRINFRA\": [ \"3463169\", \"GMR-POWER-AND-URBAN-INFRA-135486325\", \"gmr-power-and-urban-infra-ltd/gmrpui/543490\" ], \"ICICIPRULI\": [ \"4774913\", \"ICICI-PRUDENTIAL-LIFE-INS-32000303\", \"icici-prudential-life-insurance-company-ltd/icicipruli/540133\" ], \"SRF\": [ \"837889\", \"SRF-LIMITED-6498495\", \"srf-ltd/srf/503806\" ], \"SOLARINDS\": [ \"3412993\", \"SOLAR-INDUSTRIES-INDIA-LI-29752167\", \"solar-industries-india-ltd/solarinds/532725\" ], \"IDBI\": [ \"377857\", \"IDBI-BANK-LIMITED-9743068\", \"idbi-bank-ltd/idbi/500116\" ], \"CANBK\": [ \"2763265\", \"CANARA-BANK-9059113\", \"canara-bank/canbk/532483\" ], \"MARICO\": [ \"1041153\", \"MARICO-LIMITED-6493255\", \"marico-ltd/marico/531642\" ], \"JSWENERGY\": [ \"4574465\", \"JSW-ENERGY-LIMITED-6500125\", \"jsw-energy-ltd/jswenergy/533148\" ], \"HEROMOTOCO\": [ \"345089\", \"HERO-MOTOCORP-LTD-6491918\", \"hero-motocorp-ltd/heromotoco/500182\" ], \"BOSCHLTD\": [ \"558337\", \"BOSCH-LIMITED-6493403\", \"bosch-ltd/boschltd/500530\" ], \"JINDALSTEL\": [ \"1723649\", \"JINDAL-STEEL-POWER-LIMITE-9059230\", \"jindal-steel-ltd/jindalstel/532286\" ], \"INDUSINDBK\": [ \"1346049\", \"INDUSLND-BANK-LIMITED-9059225\", \"indusind-bank-ltd/indusindbk/532187\" ], \"CUMMINSIND\": [ \"486657\", \"CUMMINS-INDIA-LIMITED-6493116\", \"cummins-india-ltd/cumminsind/500480\" ], \"POLICYBZR\": [ \"1703937\", \"PB-FINTECH-LIMITED-129330252\", \"pb-fintech-ltd/policybzr/543390\" ], \"NHPC\": [ \"4454401\", \"NHPC-LIMITED-9743748\", \"nhpc-ltd/nhpc/533098\" ], \"BSE\": [ \"5013761\", \"BSE-LIMITED-33930062\", \"-\" ], \"SBICARD\": [ \"4600577\", \"SBI-CARDS-AND-PAYMENT-SER-104922920\", \"sbi-cards-and-payment-services-ltd/sbicard/543066\" ], \"SUZLON\": [ \"3076609\", \"SUZLON-ENERGY-LIMITED-9059353\", \"suzlon-energy-ltd/suzlon/532667\" ], \"COLPAL\": [ \"3876097\", \"COLGATE-PALMOLIVE-INDIA-L-6492485\", \"colgate-palmolive-(india)-ltd/colpal/500830\" ], \"HINDPETRO\": [ \"359937\", \"HINDUSTAN-PETROLEUM-CORPO-9058827\", \"hindustan-petroleum-corporation-ltd/hindpetro/500104\" ], \"INDIANB\": [ \"3663105\", \"INDIAN-BANK-9059774\", \"indian-bank/indianb/532814\" ], \"GICRE\": [ \"70913\", \"GENERAL-INSURANCE-CORPORA-46731378\", \"general-insurance-corporation-of-india/gicre/540755\" ], \"ATGL\": [ \"1552897\", \"ADANI-TOTAL-GAS-LIMITED-111614976\", \"adani-total-gas-ltd/atgl/542066\" ], \"OIL\": [ \"4464129\", \"OIL-INDIA-LIMITED-9059932\", \"oil-india-ltd/oil/533106\" ], \"GODREJPROP\": [ \"4576001\", \"GODREJ-PROPERTIES-LIMITED-14976317\", \"godrej-properties-ltd/godrejprop/533150\" ], \"AUROPHARMA\": [ \"70401\", \"AUROBINDO-PHARMA-9059028\", \"aurobindo-pharma-ltd/auropharma/524804\" ], \"IDEA\": [ \"3677697\", \"VODAFONE-IDEA-LIMITED-9743588\", \"vodafone-idea-ltd/idea/532822\" ], \"BHARTIHEXA\": [ \"6013185\", \"BHARTI-HEXACOM-LIMITED-168461592\", \"bharti-hexacom-ltd/bhartihexa/544162\" ], \"TORNTPOWER\": [ \"3529217\", \"TORRENT-POWER-LIMITED-9059747\", \"torrent-power-ltd/torntpower/532779\" ], \"OBEROIRLTY\": [ \"5181953\", \"OBEROI-REALTY-LIMITED-7939324\", \"oberoi-realty-ltd/oberoirlty/533273\" ], \"PATANJALI\": [ \"4359425\", \"PATANJALI-FOODS-LIMITED-6498538\", \"patanjali-foods-ltd/patanjali/500368\" ], \"IRCTC\": [ \"3484417\", \"INDIAN-RAILWAY-CATERING-T-68642709\", \"indian-railway-catering-and-tourism-corporation-ltd/irctc/542830\" ], \"ASHOKLEY\": [ \"54273\", \"ASHOK-LEYLAND-LIMITED-9058885\", \"ashok-leyland-ltd/ashokley/500477\" ], \"ALKEM\": [ \"2995969\", \"ALKEM-LABORATORIES-LIMITE-25531345\", \"alkem-laboratories-ltd/alkem/539523\" ], \"YESBANK\": [ \"3050241\", \"YES-BANK-LIMITED-9059346\", \"yes-bank-ltd/yesbank/532648\" ], \"PHOENIXLTD\": [ \"3725313\", \"THE-PHOENIX-MILLS-LIMITED-6498526\", \"the-phoenix-mills-ltd/phoenixltd/503100\" ], \"PRESTIGE\": [ \"5197313\", \"PRESTIGE-ESTATES-PROJECTS-8017310\", \"prestige-estates-projects-ltd/prestige/533274\" ], \"LTTS\": [ \"4752385\", \"L-T-TECHNOLOGY-SERVICES-L-31439537\", \"lt-technology-services-ltd/ltts/540115\" ], \"ABBOTINDIA\": [ \"4583169\", \"ABBOTT-INDIA-LIMITED-8495776\", \"abbott-india-ltd/abbotindia/500488\" ], \"UNOMINDA\": [ \"3623425\", \"UNO-MINDA-LIMITED-46730285\", \"uno-minda-ltd/unominda/532539\" ], \"KALYANKJIL\": [ \"756481\", \"KALYAN-JEWELLERS-INDIA-LI-120718174\", \"kalyan-jewellers-india-ltd/kalyankjil/543278\" ], \"TIINDIA\": [ \"79873\", \"TUBE-INVESTMENTS-OF-INDIA-105516309\", \"tube-investments-of-india-ltd/tiindia/540762\" ], \"NMDC\": [ \"3924993\", \"NMDC-LIMITED-9743333\", \"nmdc-ltd/nmdc/526371\" ], \"COFORGE\": [ \"2955009\", \"COFORGE-LIMITED-9059315\", \"coforge-ltd/coforge/532541\" ], \"BERGEPAINT\": [ \"103425\", \"BERGER-PAINTS-INDIA-LIMIT-46728958\", \"berger-paints-india-ltd/bergepaint/509480\" ], \"COROMANDEL\": [ \"189185\", \"COROMANDEL-INTERNATIONAL--9059066\", \"coromandel-international-ltd/coromandel/506395\" ], \"BHARATFORG\": [ \"108033\", \"BHARAT-FORGE-LIMITED-6493080\", \"bharat-forge-ltd/bharatforg/500493\" ], \"BALKRISIND\": [ \"85761\", \"BALKRISHNA-INDUSTRIES-LIM-9059338\", \"balkrishna-industries-ltd/balkrisind/502355\" ], \"PIIND\": [ \"6191105\", \"PI-INDUSTRIES-LIMITED-12996038\", \"pi-industries-ltd/piind/523642\" ], \"UBL\": [ \"4278529\", \"UNITED-BREWERIES-LIMITED-6493101\", \"united-breweries-ltd/ubl/532478\" ], \"LINDEINDIA\": [ \"416513\", \"LINDE-INDIA-LIMITED-9743167\", \"linde-india-ltd/lindeindia/523457\" ], \"MPHASIS\": [ \"1152769\", \"MPHASIS-LIMITED-6493907\", \"mphasis-ltd/mphasis/526299\" ], \"SCHAEFFLER\": [ \"258817\", \"SCHAEFFLER-INDIA-LIMITED-6493485\", \"schaeffler-india-ltd/schaeffler/505790\" ], \"JSL\": [ \"2876417\", \"JINDAL-STAINLESS-LIMITED-9059514\", \"jindal-stainless-ltd/jsl/532508\" ], \"PAYTM\": [ \"1716481\", \"ONE97-COMMUNICATIONS-LIMI-129457853\", \"one-97-communications-ltd/paytm/543396\" ], \"PAGEIND\": [ \"3689729\", \"PAGE-INDUSTRIES-LIMITED-9059783\", \"page-industries-ltd/pageind/532827\" ], \"UPL\": [ \"2889473\", \"UPL-LIMITED-9059343\", \"upl-ltd/upl/512070\" ], \"SUPREMEIND\": [ \"860929\", \"THE-SUPREME-INDUSTRIES-LI-9059584\", \"supreme-industries-ltd/supremeind/509930\" ], \"FORTIS\": [ \"3735553\", \"FORTIS-HEALTHCARE-LIMITED-9059727\", \"fortis-healthcare-ltd/fortis/532843\" ], \"POWERINDIA\": [ \"4724993\", \"HITACHI-ENERGY-INDIA-LIMI-105516418\", \"hitachi-energy-india-ltd/powerindia/543187\" ], \"NYKAA\": [ \"1675521\", \"FSN-E-COMMERCE-VENTURES-L-129226164\", \"fsn-e-commerce-ventures-ltd/nykaa/543384\" ], \"BANKINDIA\": [ \"1214721\", \"BANK-OF-INDIA-LIMITED-9743081\", \"bank-of-india/bankindia/532149\" ], \"MRF\": [ \"582913\", \"MRF-LIMITED-9743372\", \"mrf-ltd/mrf/500290\" ], \"JUBLFOOD\": [ \"4632577\", \"JUBILANT-FOODWORKS-LIMITE-9059999\", \"jubilant-foodworks-ltd/jublfood/533155\" ], \"PETRONET\": [ \"2905857\", \"PETRONET-LNG-LIMITED-9059291\", \"petronet-lng-ltd/petronet/532522\" ], \"PGHH\": [ \"648961\", \"PROCTER-GAMBLE-HYGIENE-AN-6492496\", \"procter--gamble-hygiene-and-health-care-ltd/pghh/500459\" ], \"BIOCON\": [ \"2911489\", \"BIOCON-LIMITED-9059261\", \"biocon-ltd/biocon/532523\" ], \"IDFCFIRSTB\": [ \"2863105\", \"IDFC-FIRST-BANK-LIMITED-46731334\", \"idfc-first-bank-ltd/idfcfirstb/539437\" ], \"VOLTAS\": [ \"951809\", \"VOLTAS-LIMITED-9058902\", \"voltas-ltd/voltas/500575\" ], \"TATACOMM\": [ \"952577\", \"TATA-COMMUNICATIONS-LIMIT-9058863\", \"tata-communications-ltd/tatacomm/500483\" ], \"FEDERALBNK\": [ \"261889\", \"THE-FEDERAL-BANK-LIMITED-14609339\", \"federal-bank-ltd/federalbnk/500469\" ], \"CONCOR\": [ \"1215745\", \"CONTAINER-CORPORATION-OF--105516155\", \"container-corporation-of-india-ltd/concor/531344\" ], \"SAIL\": [ \"758529\", \"STEEL-AUTHORITY-OF-INDIA--9058997\", \"steel-authority-of-india-ltd/sail/500113\" ], \"ABCAPITAL\": [ \"5533185\", \"ADITYA-BIRLA-CAPITAL-LIMI-46731373\", \"aditya-birla-capital-ltd/abcapital/540691\" ], \"CENTRALBK\": [ \"3812865\", \"CENTRAL-BANK-OF-INDIA-9059895\", \"central-bank-of-india/centralbk/532885\" ], \"BDL\": [ \"548865\", \"BHARAT-DYNAMICS-LIMITED-42470806\", \"bharat-dynamics-ltd/bdl/541143\" ], \"AUBANK\": [ \"5436929\", \"AU-SMALL-FINANCE-BANK-LIM-36939117\", \"au-small-finance-bank-ltd/aubank/540611\" ], \"GLENMARK\": [ \"1895937\", \"GLENMARK-PHARMACEUTICALS--9058934\", \"glenmark-pharmaceuticals-ltd/glenmark/532296\" ], \"MOTILALOFS\": [ \"3826433\", \"MOTILAL-OSWAL-FINANCIAL-S-9059898\", \"motilal-oswal-financial-services-ltd/motilalofs/532892\" ], \"HUDCO\": [ \"5331201\", \"HOUSING-AND-URBAN-DEVELOP-35776292\", \"housing--urban-development-corporation-ltd/hudco/540530\" ], \"ASTRAL\": [ \"3691009\", \"ASTRAL-LIMITED-18088171\", \"astral-ltd/astral/532830\" ], \"BLUESTARCO\": [ \"2127617\", \"BLUE-STAR-LIMITED-6494350\", \"blue-star-ltd/bluestarco/500067\" ], \"FLUOROCHEM\": [ \"3520001\", \"GUJARAT-FLUOROCHEMICALS-L-119082031\", \"gujarat-fluorochemicals-ltd/fluorochem/542812\" ], \"360ONE\": [ \"3343617\", \"360-ONE-WAM-LIMITED-105516388\", \"360-one-wam-ltd/360one/542772\" ], \"APLAPOLLO\": [ \"6599681\", \"APL-APOLLO-TUBES-LIMITED-46730605\", \"apl-apollo-tubes-ltd/aplapollo/533758\" ], \"TATAELXSI\": [ \"873217\", \"TATA-ELXSI-LIMITED-6493061\", \"tata-elxsi-ltd/tataelxsi/500408\" ], \"MAHABANK\": [ \"2912513\", \"BANK-OF-MAHARASHTRA-9059511\", \"bank-of-maharashtra/mahabank/532525\" ], \"KPITTECH\": [ \"2478849\", \"KPIT-TECHNOLOGIES-LIMITED-105516357\", \"kpit-technologies-ltd/kpittech/542651\" ], \"CRISIL\": [ \"193793\", \"CRISIL-LIMITED-9063424\", \"crisil-ltd/crisil/500092\" ], \"MFSL\": [ \"548353\", \"MAX-FINANCIAL-SERVICES-LI-9058905\", \"max-financial-services-ltd/mfsl/500271\" ], \"ACC\": [ \"5633\", \"ACC-LTD-9058814\", \"acc-ltd/acc/500410\" ], \"JKCEMENT\": [ \"3397121\", \"J-K-CEMENT-LIMITED-6498521\", \"jk-cement-ltd/jkcement/532644\" ], \"SJVN\": [ \"4834049\", \"SJVN-LIMITED-8435226\", \"sjvn-ltd/sjvn/533206\" ], \"IPCALAB\": [ \"418049\", \"IPCA-LABORATORIES-LIMITED-9059018\", \"ipca-laboratories-ltd/ipcalab/524494\" ], \"THERMAX\": [ \"889601\", \"THERMAX-LIMITED-9058890\", \"thermax-ltd/thermax/500411\" ], \"KEI\": [ \"3407361\", \"KEI-INDUSTRIES-LIMITED-6498805\", \"kei-industries-ltd/kei/517569\" ], \"NATIONALUM\": [ \"1629185\", \"NATIONAL-ALUMINIUM-COMPAN-9058953\", \"national-aluminium-company-ltd/nationalum/532234\" ] }";

    // ===== Data model =====
    public static final class Company {
        public final String nseSymbol;            // e.g., RELIANCE
        public final String zerodhaInstrument;    // e.g., 738561
        public final String marketScreenerCode;   // e.g., RELIANCE-INDUSTRIES-LTD-9058833
        public final String bseSockPath;          // e.g., reliance-industries-ltd/reliance/500325

        // Derived from bseSockPath:
        public final String bseCompanyNameSlug;   // e.g., reliance-industries-ltd
        public final String bseSymbolSlug;        // e.g., reliance
        public final String bseCompanyCode;       // e.g., 500325

        private Company(String nseSymbol, String zerodhaInstrument, String marketScreenerCode, String bseSockPath) {
            this.nseSymbol = nseSymbol;
            this.zerodhaInstrument = zerodhaInstrument;
            this.marketScreenerCode = marketScreenerCode;
            this.bseSockPath = bseSockPath;

            String name = null, sym = null, code = null;
            if (bseSockPath != null) {
                String[] parts = bseSockPath.split("/");
                if (parts.length >= 3) {
                    name = parts[0];
                    sym  = parts[1];
                    code = parts[2];
                }
            }
            this.bseCompanyNameSlug = name;
            this.bseSymbolSlug = sym;
            this.bseCompanyCode = code;
        }
    }

    // ===== Indexes (built once) =====
    private static final Map<String, Company> BY_NSE = new HashMap<>();
    private static final Map<String, Company> BY_ZERODHA = new HashMap<>();
    private static final Map<String, Company> BY_BSE_CODE = new HashMap<>();
    private static final Map<String, Company> BY_MARKET_SCREENER = new HashMap<>();

    static {
        try {
            ObjectMapper om = new ObjectMapper();
            Map<String, List<String>> raw = om.readValue(
                    companies,
                    new TypeReference<Map<String, List<String>>>() {}
            );

            for (Map.Entry<String, List<String>> e : raw.entrySet()) {
                String nse = safeTrim(e.getKey());
                List<String> arr = e.getValue();
                if (nse == null || arr == null || arr.size() < 3) continue;

                String zerodha = safeTrim(arr.get(0));
                String market  = safeTrim(arr.get(1));
                String sock    = safeTrim(arr.get(2));

                Company c = new Company(nse, zerodha, market, sock);
                // Core index
                putIfNotEmpty(BY_NSE, nse, c);
                // Reverse indexes
                putIfNotEmpty(BY_ZERODHA, c.zerodhaInstrument, c);
                putIfNotEmpty(BY_MARKET_SCREENER, c.marketScreenerCode, c);
                putIfNotEmpty(BY_BSE_CODE, c.bseCompanyCode, c);
            }
        } catch (Exception ex) {
            // If JSON is malformed (e.g., contains "..."), maps will remain partially/fully empty.
            // You can log this as needed.
        }
    }

    private static void putIfNotEmpty(Map<String, Company> map, String key, Company value) {
        if (key != null && !key.isEmpty()) map.put(key, value);
    }

    private static String safeTrim(String s) {
        return (s == null) ? null : s.trim();
    }

    // ===== Existing method from your stub (returns BSE "companyName" from sockPath, first segment) =====
    public static String getCompanyNameBySymbol(String nseSymbol) {
        Company c = BY_NSE.get(nseSymbol);
        return (c != null && c.bseCompanyNameSlug != null) ? c.bseCompanyNameSlug : "Unknown Company";
    }

    // ===== Direct accessors for the 7 lookups you requested =====

    // 1) Zerodha instrument -> NSE symbol
    public static String getNseSymbolFromZerodhaInstrument(String zerodhaInstrument) {
        Company c = BY_ZERODHA.get(zerodhaInstrument);
        return c != null ? c.nseSymbol : null;
    }

    // 2) NSE symbol -> Zerodha instrument
    public static String getZerodhaInstrumentFromNse(String nseSymbol) {
        Company c = BY_NSE.get(nseSymbol);
        return c != null ? c.zerodhaInstrument : null;
    }

    // 3) BSE company code -> NSE symbol
    public static String getNseSymbolFromBseCompanyCode(String bseCompanyCode) {
        Company c = BY_BSE_CODE.get(bseCompanyCode);
        return c != null ? c.nseSymbol : null;
    }

    // 4) NSE symbol -> BSE company code
    public static String getBseCompanyCodeFromNse(String nseSymbol) {
        Company c = BY_NSE.get(nseSymbol);
        return c != null ? c.bseCompanyCode : null;
    }

    // 5) NSE symbol -> BSE sockPath
    public static String getBseSockPathFromNse(String nseSymbol) {
        Company c = BY_NSE.get(nseSymbol);
        return c != null ? c.bseSockPath : null;
    }

    // 6) NSE symbol -> MarketScreener code
    public static String getMarketScreenerFromNse(String nseSymbol) {
        Company c = BY_NSE.get(nseSymbol);
        return c != null ? c.marketScreenerCode : null;
    }

    // 7) MarketScreener code -> NSE symbol
    public static String getNseSymbolFromMarketScreener(String marketScreenerCode) {
        Company c = BY_MARKET_SCREENER.get(marketScreenerCode);
        return c != null ? c.nseSymbol : null;
    }

    // ===== Optional convenience getters (full record or specific pieces) =====

    public static Company getByNse(String nseSymbol) { return BY_NSE.get(nseSymbol); }
    public static Company getByZerodha(String zerodhaInstrument) { return BY_ZERODHA.get(zerodhaInstrument); }
    public static Company getByBseCode(String bseCode) { return BY_BSE_CODE.get(bseCode); }
    public static Company getByMarketScreener(String msCode) { return BY_MARKET_SCREENER.get(msCode); }

    public static String getBseCompanySymbolSlugFromNse(String nseSymbol) {
        Company c = BY_NSE.get(nseSymbol);
        return c != null ? c.bseSymbolSlug : null;
    }

    public static String getBseCompanyNameSlugFromNse(String nseSymbol) {
        Company c = BY_NSE.get(nseSymbol);
        return c != null ? c.bseCompanyNameSlug : null;
    }

    // Read-only views in case you want to expose them
    public static Map<String, Company> viewAllByNse() { return Collections.unmodifiableMap(BY_NSE); }
    public static Map<String, Company> viewAllByZerodha() { return Collections.unmodifiableMap(BY_ZERODHA); }
    public static Map<String, Company> viewAllByBseCode() { return Collections.unmodifiableMap(BY_BSE_CODE); }
    public static Map<String, Company> viewAllByMarketScreener() { return Collections.unmodifiableMap(BY_MARKET_SCREENER); }
}
