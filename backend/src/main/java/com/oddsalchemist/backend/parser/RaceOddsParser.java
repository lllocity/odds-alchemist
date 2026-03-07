package com.oddsalchemist.backend.parser;

import com.oddsalchemist.backend.dto.OddsData;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class RaceOddsParser {

    private static final Logger logger = LoggerFactory.getLogger(RaceOddsParser.class);

    // 複勝オッズ: "1.7 - 2.4" 形式（キャプチャグループで値を取得）
    private static final Pattern PLACE_ODDS_PATTERN = Pattern.compile("(\\d+\\.\\d+)\\s*-\\s*(\\d+\\.\\d+)");
    // titleタグからのレース名抽出: "競馬 - {レース名} オッズ - スポーツナビ"
    private static final Pattern RACE_NAME_FROM_TITLE_PATTERN = Pattern.compile("競馬 - (.+?) オッズ");
    // 発走時刻の抽出: "HH:MM" 形式（例: "15:25"）
    private static final Pattern TIME_PATTERN = Pattern.compile("(\\d{1,2}):(\\d{2})");

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
     * HTMLからレース発走時刻を抽出します。
     * 以下の優先順位で試みます:
     * <ol>
     *   <li>「発走」テキストを含む div.hr-predictRaceInfo__text（例: "15:30発走"）</li>
     *   <li>スポナビのレース情報エリア（dl.hr-predictRaceInfo__raceData 内のdd要素）</li>
     *   <li>HTMLのtime要素</li>
     *   <li>li.hr-predictRaceInfo__raceDataList</li>
     * </ol>
     * いずれでも取得できない場合は {@link Optional#empty()} を返します。
     *
     * @param html パース対象のHTML文字列
     * @return 発走時刻（取得できない場合は empty）
     */
    public Optional<LocalTime> parseStartTime(String html) {
        try {
            Document doc = Jsoup.parse(html);

            // 最優先: "発走" を含む div.hr-predictRaceInfo__text（例: "15:30発走"）
            for (Element el : doc.select("div.hr-predictRaceInfo__text")) {
                if (el.text().contains("発走")) {
                    Optional<LocalTime> time = extractTimeFromText(el.text());
                    if (time.isPresent()) {
                        logger.debug("発走時刻を取得しました (div.hr-predictRaceInfo__text): {}", time.get());
                        return time;
                    }
                }
            }

            // フォールバック
            String[] selectors = {
                "dl.hr-predictRaceInfo__raceData dd",
                "time",
                "li.hr-predictRaceInfo__raceDataList"
            };
            for (String selector : selectors) {
                for (Element el : doc.select(selector)) {
                    Optional<LocalTime> time = extractTimeFromText(el.text());
                    if (time.isPresent()) {
                        logger.debug("発走時刻を取得しました ({}): {}", selector, time.get());
                        return time;
                    }
                }
            }

            logger.info("発走時刻を取得できませんでした。空で代替します。");
            return Optional.empty();

        } catch (Exception e) {
            logger.warn("発走時刻のパース中にエラーが発生しました: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * テキストから "HH:MM" 形式の時刻を抽出します。
     * 競馬の発走時刻として妥当な範囲（06:00〜20:59）のみを有効とします。
     */
    private Optional<LocalTime> extractTimeFromText(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = TIME_PATTERN.matcher(text);
        while (matcher.find()) {
            try {
                int hour = Integer.parseInt(matcher.group(1));
                int minute = Integer.parseInt(matcher.group(2));
                // 競馬の発走時刻として妥当な範囲（06〜20時）のみ有効
                if (hour >= 6 && hour <= 20) {
                    return Optional.of(LocalTime.of(hour, minute));
                }
            } catch (DateTimeException | NumberFormatException e) {
                // 不正な時刻はスキップ
            }
        }
        return Optional.empty();
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

        logger.info("レース名を取得できませんでした。空文字で代替します。");
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

        return new OddsData(raceName, horseNumber, horseName, winOdds, placeMin, placeMax, null);
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
