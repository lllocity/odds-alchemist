package com.oddsalchemist.backend.service;

import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
public class OddsScrapingService {

    private static final Logger logger = LoggerFactory.getLogger(OddsScrapingService.class);

    public String fetchHtml(String url) throws IOException {
        logger.info("HTMLを取得中: URL={}", url);
        return Jsoup.connect(url)
                .userAgent("Mozilla/5.0")
                .timeout(5000)
                .get()
                .html();
    }
}