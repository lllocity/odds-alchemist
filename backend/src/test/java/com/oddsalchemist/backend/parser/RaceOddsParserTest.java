package com.oddsalchemist.backend.parser;

import com.oddsalchemist.backend.dto.OddsData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RaceOddsParserTest {

    private RaceOddsParser parser;

    // スポナビのHTML構造を模したベーステンプレート
    private static String buildHtml(String raceName, String tableRows) {
        return """
            <html>
              <head>
                <title>競馬 - %s オッズ - スポーツナビ</title>
              </head>
              <body>
                <h1 class="hr-style--hidden">スポーツナビ</h1>
                <h2 class="hr-predictRaceInfo__title">
                  %s
                  <span class="hr-label hr-label--g2">GII</span>
                </h2>
                <table class="hr-tableValue">
                  <thead>
                    <tr>
                      <th class="hr-tableValue__head--number">枠番</th>
                      <th class="hr-tableValue__head--number">馬番</th>
                      <th class="hr-tableValue__head--horse">馬名</th>
                      <th class="hr-tableValue__head--odds">単勝</th>
                      <th class="hr-tableValue__head--odds">複勝</th>
                    </tr>
                  </thead>
                  <tbody>
                    %s
                  </tbody>
                </table>
              </body>
            </html>
            """.formatted(raceName, raceName, tableRows);
    }

    private static String horseRow(int frameNum, int horseNum, String name, String win, String place) {
        return """
            <tr class="hr-tableValue__row">
              <td class="hr-tableValue__data hr-tableValue__data--number">
                <span class="hr-icon__bracketNum hr-icon__bracketNum--%d">%d</span>
              </td>
              <td class="hr-tableValue__data hr-tableValue__data--number">%d</td>
              <td class="hr-tableValue__data hr-tableValue__data--horse">
                <a href="/keiba/directory/horse/dummy/">%s</a>
              </td>
              <td class="hr-tableValue__data hr-tableValue__data--odds"><span>%s</span></td>
              <td class="hr-tableValue__data hr-tableValue__data--odds"><span>%s</span></td>
            </tr>
            """.formatted(frameNum, frameNum, horseNum, name, win, place);
    }

    @BeforeEach
    void setUp() {
        parser = new RaceOddsParser();
    }

    @Test
    void parse_正常なHTMLからオッズ情報を抽出できること() {
        String rows = horseRow(1, 1, "キタサンブラック", "2.5", "1.2 - 1.5")
                    + horseRow(2, 2, "イクイノックス", "1.8", "1.1-1.3");
        String html = buildHtml("中山記念", rows);

        List<OddsData> result = parser.parse(html);

        assertThat(result).hasSize(2);

        OddsData horse1 = result.get(0);
        assertThat(horse1.raceName()).isEqualTo("中山記念");
        assertThat(horse1.horseNumber()).isEqualTo("1");
        assertThat(horse1.horseName()).isEqualTo("キタサンブラック");
        assertThat(horse1.winOdds()).isEqualTo(2.5);
        assertThat(horse1.placeOddsMin()).isEqualTo(1.2);
        assertThat(horse1.placeOddsMax()).isEqualTo(1.5);

        OddsData horse2 = result.get(1);
        assertThat(horse2.raceName()).isEqualTo("中山記念");
        assertThat(horse2.horseNumber()).isEqualTo("2");
        assertThat(horse2.horseName()).isEqualTo("イクイノックス");
        assertThat(horse2.winOdds()).isEqualTo(1.8);
        assertThat(horse2.placeOddsMin()).isEqualTo(1.1);
        assertThat(horse2.placeOddsMax()).isEqualTo(1.3);
    }

    @Test
    void parse_枠番が馬番と混同されないこと() {
        // 枠番=3, 馬番=5 のように異なる場合
        String rows = """
            <tr class="hr-tableValue__row">
              <td class="hr-tableValue__data hr-tableValue__data--number">
                <span class="hr-icon__bracketNum hr-icon__bracketNum--3">3</span>
              </td>
              <td class="hr-tableValue__data hr-tableValue__data--number">5</td>
              <td class="hr-tableValue__data hr-tableValue__data--horse">
                <a href="/keiba/directory/horse/dummy/">テスト馬</a>
              </td>
              <td class="hr-tableValue__data hr-tableValue__data--odds"><span>10.5</span></td>
              <td class="hr-tableValue__data hr-tableValue__data--odds"><span>3.2 - 4.5</span></td>
            </tr>
            """;
        String html = buildHtml("大阪杯", rows);

        List<OddsData> result = parser.parse(html);

        assertThat(result).hasSize(1);
        // 馬番は枠番（3）ではなく馬番（5）であること
        assertThat(result.get(0).horseNumber()).isEqualTo("5");
        assertThat(result.get(0).horseName()).isEqualTo("テスト馬");
        assertThat(result.get(0).winOdds()).isEqualTo(10.5);
        assertThat(result.get(0).placeOddsMin()).isEqualTo(3.2);
        assertThat(result.get(0).placeOddsMax()).isEqualTo(4.5);
    }

    @Test
    void parse_オッズが未定の場合でもパースを継続できること() {
        String rows = """
            <tr class="hr-tableValue__row">
              <td class="hr-tableValue__data hr-tableValue__data--number">
                <span class="hr-icon__bracketNum hr-icon__bracketNum--1">1</span>
              </td>
              <td class="hr-tableValue__data hr-tableValue__data--number">3</td>
              <td class="hr-tableValue__data hr-tableValue__data--horse">
                <a href="/keiba/directory/horse/dummy/">タイトルホルダー</a>
              </td>
              <td class="hr-tableValue__data hr-tableValue__data--odds">---</td>
              <td class="hr-tableValue__data hr-tableValue__data--odds"></td>
            </tr>
            """;
        String html = buildHtml("阪神大賞典", rows);

        List<OddsData> result = parser.parse(html);

        assertThat(result).hasSize(1);
        OddsData horse = result.get(0);
        assertThat(horse.raceName()).isEqualTo("阪神大賞典");
        assertThat(horse.horseNumber()).isEqualTo("3");
        assertThat(horse.horseName()).isEqualTo("タイトルホルダー");
        assertThat(horse.winOdds()).isNull();
        assertThat(horse.placeOddsMin()).isNull();
        assertThat(horse.placeOddsMax()).isNull();
    }

    @Test
    void parse_テーブルが空の場合は空リストが返ること() {
        String html = buildHtml("テストレース", "");

        List<OddsData> result = parser.parse(html);

        assertThat(result).isEmpty();
    }

    @Test
    void parse_h2がない場合はtitleタグからレース名を抽出すること() {
        String html = """
            <html>
              <head>
                <title>競馬 - 有馬記念 オッズ - スポーツナビ</title>
              </head>
              <body>
                <table class="hr-tableValue">
                  <tbody>
                    <tr class="hr-tableValue__row">
                      <td class="hr-tableValue__data hr-tableValue__data--number">
                        <span class="hr-icon__bracketNum hr-icon__bracketNum--1">1</span>
                      </td>
                      <td class="hr-tableValue__data hr-tableValue__data--number">1</td>
                      <td class="hr-tableValue__data hr-tableValue__data--horse">
                        <a href="/keiba/directory/horse/dummy/">テスト馬</a>
                      </td>
                      <td class="hr-tableValue__data hr-tableValue__data--odds"><span>5.0</span></td>
                      <td class="hr-tableValue__data hr-tableValue__data--odds"><span>1.5 - 2.0</span></td>
                    </tr>
                  </tbody>
                </table>
              </body>
            </html>
            """;

        List<OddsData> result = parser.parse(html);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).raceName()).isEqualTo("有馬記念");
    }
}
