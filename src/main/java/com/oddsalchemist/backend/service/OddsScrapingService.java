package com.oddsalchemist.backend.service;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
public class OddsScrapingService {

    private static final Logger logger = LoggerFactory.getLogger(OddsScrapingService.class);

    /**
     * 指定したURLからHTMLドキュメントを取得し、タイトルをログ出力する（テスト用）
     */
    public void testFetch(String url) {
        try {
            // Jsoupを使用してURLに接続し、HTMLドキュメント(DOMツリー)を取得
            Document document = Jsoup.connect(url).get();
            logger.info("通信成功。取得したページのタイトル: {}", document.title());
        } catch (IOException e) {
            logger.error("ページの取得に失敗しました。URL: {}", url, e);
        }
    }
}