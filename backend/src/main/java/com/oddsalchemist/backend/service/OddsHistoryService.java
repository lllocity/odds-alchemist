package com.oddsalchemist.backend.service;

import com.oddsalchemist.backend.dto.AlertHistoryItemDto;
import com.oddsalchemist.backend.dto.HorseDto;
import com.oddsalchemist.backend.dto.OddsHistoryItemDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * OddsDataシートからオッズ履歴を読み込み、フロントエンドのグラフ表示用データを提供するサービス。
 * Sheets API 読み込み失敗時は例外を握りつぶし、空リストを返してシステムを止めない。
 */
@Service
public class OddsHistoryService {

    private static final Logger logger = LoggerFactory.getLogger(OddsHistoryService.class);
    private static final String ODDS_DATA_RANGE = "OddsData!A:H";
    private static final String ALERTS_RANGE = "Alerts!A:G";
    private final GoogleSheetsService googleSheetsService;

    public OddsHistoryService(GoogleSheetsService googleSheetsService) {
        this.googleSheetsService = googleSheetsService;
    }

    /**
     * OddsDataシートに存在するレースURLの一覧を昇順で返します。
     * 重複は除外します。
     */
    public List<String> getUrls() {
        try {
            List<List<Object>> rows = googleSheetsService.readData(ODDS_DATA_RANGE);
            return rows.stream()
                    .filter(row -> row.size() > 1)
                    .map(row -> row.get(1).toString())
                    .filter(url -> !url.isBlank() && url.startsWith("http"))
                    .distinct()
                    .sorted()
                    .collect(Collectors.toList());
        } catch (Exception e) {
            logger.warn("OddsDataからURL一覧の取得に失敗しました: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 指定URLのレースに存在する馬の一覧を馬番昇順で返します。
     * 重複は除外します。
     */
    public List<HorseDto> getHorses(String url) {
        try {
            List<List<Object>> rows = googleSheetsService.readData(ODDS_DATA_RANGE);
            return rows.stream()
                    .filter(row -> row.size() > 4 && url.equals(row.get(1).toString()))
                    .map(row -> new HorseDto(
                            parseIntSafe(row.get(3).toString()),
                            row.get(4).toString()
                    ))
                    .filter(dto -> !dto.horseName().isBlank())
                    .distinct()
                    .sorted(Comparator.comparingInt(HorseDto::horseNumber))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            logger.warn("OddsDataから馬一覧の取得に失敗しました: url={}, error={}", url, e.getMessage());
            return List.of();
        }
    }

    /**
     * 指定URLと馬名に一致するオッズ時系列データを取得日時昇順で返します。
     */
    public List<OddsHistoryItemDto> getHistory(String url, String horseName) {
        try {
            List<List<Object>> rows = googleSheetsService.readData(ODDS_DATA_RANGE);
            return rows.stream()
                    .filter(row -> row.size() > 7
                            && url.equals(row.get(1).toString())
                            && horseName.equals(row.get(4).toString()))
                    .sorted(Comparator.comparing(row -> row.get(0).toString()))
                    .map(row -> new OddsHistoryItemDto(
                            row.get(0).toString(),
                            parseDoubleSafe(row.get(5).toString()),
                            parseDoubleSafe(row.get(6).toString()),
                            parseDoubleSafe(row.get(7).toString())
                    ))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            logger.warn("OddsDataから時系列データの取得に失敗しました: url={}, horse={}, error={}",
                    url, horseName, e.getMessage());
            return List.of();
        }
    }

    /**
     * 指定URLと馬名に一致するアラート履歴を検知日時昇順で返します。
     */
    public List<AlertHistoryItemDto> getAlerts(String url, String horseName) {
        try {
            List<List<Object>> rows = googleSheetsService.readData(ALERTS_RANGE);
            return rows.stream()
                    .filter(row -> row.size() > 6
                            && url.equals(row.get(1).toString())
                            && horseName.equals(row.get(4).toString()))
                    .sorted(Comparator.comparing(row -> row.get(0).toString()))
                    .map(row -> {
                        Double val = parseDoubleSafe(row.get(6).toString());
                        return new AlertHistoryItemDto(
                                row.get(0).toString(),
                                row.get(5).toString(),
                                val != null ? val : 0.0
                        );
                    })
                    .collect(Collectors.toList());
        } catch (Exception e) {
            logger.warn("Alertsから履歴の取得に失敗しました: url={}, horse={}, error={}", url, horseName, e.getMessage());
            return List.of();
        }
    }

    private int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private Double parseDoubleSafe(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
