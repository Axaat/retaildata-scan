package com.sgglabs.retail;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sgglabs.retail.model.dto.ProductSearchResultDTO;
import com.sgglabs.retail.model.dto.SellerProductDataDTO;
import com.sgglabs.retail.model.entity.SearchText;
import com.sgglabs.retail.model.entity.Site;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Attributes;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;

@Service
public class RetailDataScanner {
    private static final Logger LOG = LoggerFactory.getLogger(RetailDataScanner.class);

    private class SearchInfo {
        private String hostName;
        private String siteName;
        private String searchText;
        private String searchURI;
        private String fullURL;

        String getSiteName() {
            return siteName;
        }

        void setSiteName(String siteName) {
            this.siteName = siteName;
        }

        String getHostName() {
            return hostName;
        }

        void setHostName(String hostName) {
            this.hostName = hostName;
        }

        String getSearchText() {
            return searchText;
        }

        void setSearchText(String searchText) {
            this.searchText = searchText;
        }

        String getSearchURI() {
            return searchURI;
        }

        void setSearchURI(String searchURI) {
            this.searchURI = searchURI;
        }

        String getFullURL() {
            return fullURL;
        }

        void setFullURL(String fullURL) {
            this.fullURL = fullURL;
        }

        @Override
        public String toString() {
            return "SearchInfo{" +
                    "hostName='" + hostName + '\'' +
                    ", searchText='" + searchText + '\'' +
                    ", searchURI='" + searchURI + '\'' +
                    ", fullURL='" + fullURL + '\'' +
                    '}';
        }
    }

    @Autowired
    private SearchTextRepository searchTextRepo;

    @Autowired
    private SiteRepository siteRepo;

    @Autowired
    MessagePostService messageService;

    public RetailDataScanner() {
    }

    void startScanning() {
        List<SearchInfo> searchList = getSearchList();
        fetchRetailData(searchList);
    }

    private void fetchRetailData(List<SearchInfo> searchList) {
        try {
            SearchInfo searchInfo = searchList.get(0);
            Document resultPageDoc = Jsoup.connect(searchInfo.getFullURL()).get();

            ProductSearchResultDTO productResult = new ProductSearchResultDTO();
            productResult.setSiteName(searchInfo.getSiteName());
            productResult.setSearchText(searchInfo.getSearchText());

            /*
             * div.sh-sr__shop-result-group
             *      div.sh-pr__product-results
             *          div.sh-dlr__list-result
             *              div.sh-dlr__content
             *                  div.ZGFjDb
             */
            // ("div.sh-sr__shop-result-group").("div.sh-pr__product-results")
            Elements prodSearchResultsTag = resultPageDoc.select("div.sh-sr__shop-result-group")
                    .select("div.sh-pr__product-results");

            // ("div.sh-dlr__list-result")
            Elements prodSearchResultTag = prodSearchResultsTag.select("div.sh-dlr__list-result");

            // ("div.sh-dlr__content")
            Elements prodSearchResultContentTag = prodSearchResultTag.select("div.sh-dlr__content");

            // ("div.ZGFjDb")
            Elements divProductResultTags = prodSearchResultContentTag.select("div.ZGFjDb");

            // For Product short description
            Elements productNameTags = divProductResultTags.select("div.eIuuYe");
            String aHrefValue = productNameTags.select("a").attr("href");

            Element productNameTag = productNameTags.first();
            productResult.setShortDescription(productNameTag.text());

            //For Product Price
            Elements na4IcdDivTags = prodSearchResultsTag.select("div.na4ICd");
            Element productPriceTag = na4IcdDivTags.first();
            productResult.setPrice(productPriceTag.text());

            //For Product Reviews and Ratings
            Elements divTags = na4IcdDivTags.next();
            Element productReviewTag = divTags.first();
            productResult.setNumberOfReviews(productReviewTag.text());

            Elements spanRatingTags = productReviewTag.select("span.o0Xcvc");
            Elements divRatingTags = spanRatingTags.select("div.vq3ore");
            Element divRatingTag = divRatingTags.first();
            Attributes attributes = divRatingTag.attributes();
            productResult.setTotalRatings(attributes.get("aria-label"));

            //For Product long description
            divTags = na4IcdDivTags.next().next();
            Element prodLongDescTag = divTags.first();
            productResult.setLongDescription(prodLongDescTag.text());

            // For Product Categories
            divTags = na4IcdDivTags.next().next().next();
            Element prodCategoriesTag = divTags.first();
            productResult.setCategories(prodCategoriesTag.text());

            // For Product Other Options
            divTags = na4IcdDivTags.next().next().next().next();
            Element prodOtherOptionsTag = divTags.first();
            productResult.setOtherOptions(prodOtherOptionsTag.text());

            /*
             * div.id: main-content-with-search
             *      div.id: pp-main
             *          div.id: online
             *              div.id: os-content
             *                  div.id: os-sellers-content
             *                      table.os-main-table
             *                          tbody
             *                              tr.os-row
             *                                  td
             *                                  td
             *                                  td
             *                                  td
             *                                  td
             *                                  td
             *
             */

            //productResult.getSellerList().addAll(sellerDataList);
            productResult.setStatus(StatusEnum.Active.getString());

            // After clicking on the result URI
            List<SellerProductDataDTO> sellerDataList = getProductSellerData(
                    searchInfo.getHostName() + aHrefValue);

            productResult.setSellerDataList(sellerDataList);

            messageService.sendSearchResultMessage(productResult);
        } catch (JsonProcessingException me) {
            LOG.error("unable to post the data as message to store", me);
        } catch (IOException ioe) {
            LOG.error("unable to fetch data", ioe);
        }
    }

    /**
     * Returns the list of search URLs fetching the from the tables.
     * It concatenates the site URL and search string to form the fully qualified search URL
     * <p>
     * Site: https://www.google.co.uk/search?q={0}&hl=en-GB&source=lnms&tbm=shop&sa=X
     * Search Text: men+shampoo
     * URL: https://www.google.co.uk/search?q=men+shampoo&hl=en-GB&source=lnms&tbm=shop&sa=X
     */
    private List<SearchInfo> getSearchList() {
        List<SearchInfo> searchList = new ArrayList<>();
        for (Site site : siteRepo.findAll()) {
            MessageFormat urlFormat = new MessageFormat(site.getSearchURI());
            for (SearchText searchText : searchTextRepo.findAll()) {
                Object[] searchTextObjectArray = new Object[]{searchText.getSearchText()};
                SearchInfo searchInfo = new SearchInfo();
                searchInfo.setSiteName(site.getSiteName());
                searchInfo.setHostName(site.getSiteHostName());
                searchInfo.setSearchText(searchText.getSearchText());
                String formattedURI = urlFormat.format(searchTextObjectArray);
                searchInfo.setSearchURI(formattedURI);
                searchInfo.setFullURL(site.getSiteHostName() + formattedURI);
                searchList.add(searchInfo);
            }
        }
        return searchList;
    }

    private void writeToFile(final Document doc, final String fileName) throws IOException {
        final File htmlFile = new File(fileName);
        FileWriter writer = new FileWriter(htmlFile);
        writer.write(doc.outerHtml());
    }

    private Document getDocumentFromFile(final String fileName) throws IOException {
        File input = new File(fileName);
        Document doc = Jsoup.parse(input, "UTF-8");
        return doc;
    }

    private Document getDocumentFromURL(final String url) throws IOException {
        Document doc = Jsoup.connect(url).get();
        return doc;
    }

    private List<SellerProductDataDTO> getProductSellerData(final String productDetailsPageURL) throws IOException {
        // After clicking on the result URI
        Document prodDetailPageDoc = Jsoup.connect(productDetailsPageURL).get();

        Elements productRetailStoreTableRowTags = prodDetailPageDoc.getElementById("main-content-with-search")
                .getElementById("pp-main")
                .getElementById("online").getElementById("os-content")
                .getElementById("os-sellers-content")
                .select("table.os-main-table")
                .select("tbody")
                .select("tr");

        List<SellerProductDataDTO> sellerProductDataList = new ArrayList<>();
        for (int i = 1; i < productRetailStoreTableRowTags.size(); i++) { //first row is the col names so skip it.
            Element row = productRetailStoreTableRowTags.get(i);
            Elements cols = row.children();
            if (cols.size() >= 6) {
                SellerProductDataDTO sellerData = new SellerProductDataDTO();
                sellerData.setSellerName(cols.get(0).text());
                sellerData.setRatingIndex(cols.get(1).text());
                sellerData.setDetails(cols.get(2).text());
                sellerData.setBasePrice(cols.get(3).text());
                sellerData.setTotalPrice(cols.get(4).text());
                sellerData.setStatus(StatusEnum.Active.getString());
                sellerProductDataList.add(sellerData);
            } else {
                LOG.warn("skipped row: " + row.text());
            }
        }
        return sellerProductDataList;
    }
}