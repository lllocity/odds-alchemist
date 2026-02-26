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

@Component
public class RaceOddsParser {

    private static final Logger logger = LoggerFactory.getLogger(RaceOddsParser.class);

    public List<OddsData> parse(String html) {
        List<OddsData> oddsList = new ArrayList<>();
        Document doc = Jsoup.parse(html);

        // スポナビのすべての行を取得
        Elements rows = doc.select("tr");

        for (Element row : rows) {
            try {
                Elements cells = row.select("td");
                if (cells.size() < 4) continue;

                // 1. 馬番を探す（1〜18の数字が単独で含まれるセル）
                String horseNumber = "";
                int nameIndex = -1;
                for (int i = 0; i < Math.min(cells.size(), 3); i++) {
                    String text = cells.get(i).text().trim();
                    if (text.matches("\\d+")) {
                        horseNumber = text;
                        nameIndex = i + 1; // 馬番の隣が馬名である可能性が高い
                        break;
                    }
                }
                if (horseNumber.isEmpty()) continue;

                // 2. 馬名を取得
                String horseName = cells.get(nameIndex).text().trim();
                // 馬名にリンクがある場合は、リンクのテキストを優先
                Element nameLink = cells.get(nameIndex).selectFirst("a");
                if (nameLink != null) horseName = nameLink.text().trim();

                // 3. オッズを「数値.数値」のパターンで探す
                Double winOdds = null;
                Double placeMin = null;
                Double placeMax = null;

                for (Element cell : cells) {
                    String text = cell.text().trim();
                    // 単勝オッズ (例: 5.4)
                    if (text.matches("^\\d+\\.\\d+$")) {
                        if (winOdds == null) winOdds = parseDouble(text);
                    } 
                    // 複勝オッズ (例: 1.2-1.5 や 1.2 - 1.5)
                    else if (text.contains("-") && text.matches(".*\\d+\\.\\d+.*")) {
                        Double[] p = parsePlace(text);
                        if (p[0] != null) {
                            placeMin = p[0];
                            placeMax = p[1];
                        }
                    }
                }

                // 最低限、馬番と単勝オッズがあればリストに追加
                if (!horseNumber.isEmpty() && winOdds != null) {
                    oddsList.add(new OddsData(horseNumber, horseName, winOdds, placeMin, placeMax));
                }
            } catch (Exception e) {
                // スキップ
            }
        }

        logger.info("Parse complete. Found {} valid horse rows. Total scanned: {}", oddsList.size(), rows.size());
        return oddsList;
    }

    private Double parseDouble(String s) {
        try {
            return Double.parseDouble(s.replaceAll("[^0-9.]", ""));
        } catch (Exception e) {
            return null;
        }
    }

    private Double[] parsePlace(String s) {
        Double[] res = new Double[]{null, null};
        // 1.2-1.5 などを分割
        String[] parts = s.split("-");
        if (parts.length >= 1) res[0] = parseDouble(parts[0]);
        if (parts.length >= 2) res[1] = parseDouble(parts[1]);
        return res;
    }
}