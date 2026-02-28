package com.oddsalchemist.backend.service;

import com.oddsalchemist.backend.dto.OddsData;
import com.oddsalchemist.backend.parser.RaceOddsParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
public class OddsSyncService {

    private static final Logger logger = LoggerFactory.getLogger(OddsSyncService.class);
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");

    private final OddsScrapingService scrapingService;
    private final RaceOddsParser parser;
    private final GoogleSheetsService sheetsService;

    public OddsSyncService(OddsScrapingService scrapingService, RaceOddsParser parser, GoogleSheetsService sheetsService) {
        this.scrapingService = scrapingService;
        this.parser = parser;
        this.sheetsService = sheetsService;
    }

    /**
     * 対象URLからオッズを取得し、スプレッドシートへ追記します。
     * @return スプレッドシートに書き込んだデータ件数
     */
    public int fetchAndSaveOdds(String targetUrl, String range) throws IOException {
        logger.info("Start fetching odds from URL: {}", targetUrl);

        // 1. HTMLの取得
        String html = scrapingService.fetchHtml(targetUrl);

        // 2. データのパース
        List<OddsData> oddsList = parser.parse(html);

        if (oddsList.isEmpty()) {
            logger.warn("No odds data found. URL: {}", targetUrl);
            return 0; // 変更点: 0件であることをコントローラーに伝える
        }

        // 3. スプレッドシート用の2次元配列に変換
        List<List<Object>> values = convertToSheetData(oddsList);

        // 4. スプレッドシートへ書き込み
        sheetsService.appendData(range, values);
        logger.info("Successfully saved {} rows to spreadsheet.", values.size());

        return values.size(); // 変更点: 保存した件数を返す
    }

    private List<List<Object>> convertToSheetData(List<OddsData> oddsList) {
        List<List<Object>> values = new ArrayList<>();
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMATTER);

        for (OddsData odds : oddsList) {
            List<Object> row = new ArrayList<>();
            row.add(timestamp);                                       // A列: タイムスタンプ
            row.add(odds.raceName());                                  // B列: レース名
            row.add(odds.horseNumber());                               // C列: 馬番
            row.add(odds.horseName());                                 // D列: 馬名
            row.add(Objects.toString(odds.winOdds(), ""));            // E列: 単勝オッズ
            row.add(Objects.toString(odds.placeOddsMin(), ""));       // F列: 複勝オッズ（下限）
            row.add(Objects.toString(odds.placeOddsMax(), ""));       // G列: 複勝オッズ（上限）
            values.add(row);
        }
        return values;
    }
}