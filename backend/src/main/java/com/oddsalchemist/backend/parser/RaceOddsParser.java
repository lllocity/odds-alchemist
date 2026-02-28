package com.oddsalchemist.backend.parser;

import com.oddsalchemist.backend.dto.OddsData;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class RaceOddsParser {

    private static final Logger logger = LoggerFactory.getLogger(RaceOddsParser.class);

    // 複勝オッズ: "1.7 - 2.4" 形式（キャプチャグループで値を取得）
    private static final Pattern PLACE_ODDS_PATTERN = Pattern.compile("(\\d+\\.\\d+)\\s*-\\s*(\\d+\\.\\d+)");
    // titleタグからのレース名抽出: "競馬 - {レース名} オッズ - スポーツナビ"
    private static final Pattern RACE_NAME_FROM_TITLE_PATTERN = Pattern.compile("競馬 - (.+?) オッズ");

    public List<OddsData> parse(String html) {
        List<OddsData> oddsList = new ArrayList<>();
        Document doc = Jsoup.parse(html);

        // レース名を抽出
        String raceName = extractRaceName(doc);

        // スポナビのオッズテーブル行を選択
        Elements rows = doc.select("tr.hr-tableValue__row");

        for (Element row : rows) {
            try {
                OddsData data = parseRow(row, raceName);
                if (data != null) {
                    oddsList.add(data);
                }
            } catch (Exception e) {
                logger.warn("行のパースに失敗しました。スキップします: {}", e.getMessage());
            }
        }

        logger.info("パース完了: レース名='{}' 有効な馬データ {}件", raceName, oddsList.size());
        return oddsList;
    }

    /**
     * ドキュメントからレース名を抽出します。
     * スポナビのh2.hr-predictRaceInfo__titleからownTextで取得し、
     * 見つからない場合はtitleタグのパターンマッチにフォールバックします。
     */
    private String extractRaceName(Document doc) {
        // h2.hr-predictRaceInfo__title のownText（GII等のspanを除く直接テキスト）
        Element titleEl = doc.selectFirst("h2.hr-predictRaceInfo__title");
        if (titleEl != null) {
            String name = titleEl.ownText().trim();
            if (!name.isEmpty()) {
                return name;
            }
        }

        // フォールバック: <title>タグから "競馬 - {レース名} オッズ" パターンで抽出
        Element titleTag = doc.selectFirst("title");
        if (titleTag != null) {
            Matcher m = RACE_NAME_FROM_TITLE_PATTERN.matcher(titleTag.text());
            if (m.find()) {
                return m.group(1).trim();
            }
        }

        logger.warn("レース名の取得に失敗しました。空文字で代替します。");
        return "";
    }

    private OddsData parseRow(Element row, String raceName) {
        // 馬番: --number クラスのtdのうち、枠番span（hr-icon__bracketNum）を含まないもの
        Elements numberCells = row.select("td.hr-tableValue__data--number");
        String horseNumber = null;
        for (Element cell : numberCells) {
            if (cell.selectFirst("span.hr-icon__bracketNum") == null) {
                horseNumber = cell.text().trim();
                break;
            }
        }
        if (horseNumber == null || horseNumber.isEmpty()) return null;

        // 馬名: --horse クラスのtd（リンクがあればそのテキストを優先）
        Element horseCell = row.selectFirst("td.hr-tableValue__data--horse");
        if (horseCell == null) return null;
        Element horseLink = horseCell.selectFirst("a");
        String horseName = (horseLink != null ? horseLink.text() : horseCell.text()).trim();
        if (horseName.isEmpty()) return null;

        // オッズ: --odds クラスのtd（1番目=単勝、2番目=複勝）
        Elements oddsCells = row.select("td.hr-tableValue__data--odds");
        Double winOdds = null;
        Double placeMin = null;
        Double placeMax = null;

        if (oddsCells.size() >= 1) {
            winOdds = parseDouble(oddsCells.get(0).text().trim());
        }
        if (oddsCells.size() >= 2) {
            Matcher m = PLACE_ODDS_PATTERN.matcher(oddsCells.get(1).text().trim());
            if (m.find()) {
                placeMin = parseDouble(m.group(1));
                placeMax = parseDouble(m.group(2));
            }
        }

        return new OddsData(raceName, horseNumber, horseName, winOdds, placeMin, placeMax);
    }

    private Double parseDouble(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            logger.warn("数値変換に失敗しました: '{}'", s);
            return null;
        }
    }
}
