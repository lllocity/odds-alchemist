package com.oddsalchemist.backend.service;

import com.oddsalchemist.backend.dto.AnomalyAlertDto;
import com.oddsalchemist.backend.dto.OddsData;
import com.oddsalchemist.backend.parser.RaceOddsParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class OddsSyncService {

    private static final Logger logger = LoggerFactory.getLogger(OddsSyncService.class);
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");

    private final OddsScrapingService scrapingService;
    private final RaceOddsParser parser;
    private final GoogleSheetsService sheetsService;
    private final OddsAnomalyDetector anomalyDetector;

    /** URL別の発走時刻キャッシュ（スクレイピングのたびに更新） */
    private final ConcurrentHashMap<String, Optional<LocalTime>> cachedStartTimes = new ConcurrentHashMap<>();

    public OddsSyncService(OddsScrapingService scrapingService, RaceOddsParser parser,
                           GoogleSheetsService sheetsService, OddsAnomalyDetector anomalyDetector) {
        this.scrapingService = scrapingService;
        this.parser = parser;
        this.sheetsService = sheetsService;
        this.anomalyDetector = anomalyDetector;
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

        // 3. 発走時刻をパースしてキャッシュに保存（次回スケジューリングの間隔算出に使用）
        Optional<LocalTime> startTime = parser.parseStartTime(html);
        cachedStartTimes.put(targetUrl, startTime);
        startTime.ifPresentOrElse(
                t -> logger.info("発走時刻を取得: URL={}, 発走時刻={}", targetUrl, t),
                () -> logger.warn("発走時刻を取得できませんでした: URL={}", targetUrl));

        // 4. 異常検知を実行
        List<AnomalyAlertDto> alerts = anomalyDetector.detect(oddsList);
        logger.info("異常検知完了: アラート件数={}", alerts.size());

        // 4.1. 検知されたアラートをスプレッドシートの "Alerts" シートへ永続化
        saveAlertsToSheet(targetUrl, alerts);

        // 5. スプレッドシート用の2次元配列に変換
        List<List<Object>> values = convertToSheetData(oddsList);

        // 6. スプレッドシートへ書き込み
        sheetsService.appendData(range, values);
        logger.info("Successfully saved {} rows to spreadsheet.", values.size());

        return values.size(); // 変更点: 保存した件数を返す
    }

    /**
     * 指定URLの最新発走時刻キャッシュを返します。
     * スクレイピング前（初回実行前）は {@link Optional#empty()} を返します。
     *
     * @param url 対象URL
     * @return キャッシュされた発走時刻（未取得の場合は empty）
     */
    public Optional<LocalTime> getCachedStartTime(String url) {
        return cachedStartTimes.getOrDefault(url, Optional.empty());
    }

    /**
     * 検知されたアラートを "Alerts" シートへ追記します。
     * アラートがない場合は何もしません。
     * 書き込み失敗時はシステムを止めず、ERRORログを出力します。
     *
     * 列順: A=検知日時, B=対象URL, C=レース名, D=馬番, E=馬名, F=検知タイプ, G=該当数値
     */
    private void saveAlertsToSheet(String targetUrl, List<AnomalyAlertDto> alerts) {
        if (alerts.isEmpty()) {
            return;
        }

        List<List<Object>> rows = new ArrayList<>();
        for (AnomalyAlertDto alert : alerts) {
            rows.add(List.of(
                    alert.detectedAt(),   // A列: 検知日時
                    targetUrl,            // B列: 対象URL
                    alert.raceName(),     // C列: レース名
                    alert.horseNumber(),  // D列: 馬番
                    alert.horseName(),    // E列: 馬名
                    alert.alertType(),    // F列: 検知タイプ
                    alert.value()         // G列: 該当数値
            ));
        }

        try {
            sheetsService.appendData("Alerts!A:G", rows);
            logger.info("アラートをSheetsに保存しました: {}件, URL={}", rows.size(), targetUrl);
        } catch (IOException e) {
            logger.error("アラートのSheets書き込みに失敗しました: URL={}", targetUrl, e);
        }
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