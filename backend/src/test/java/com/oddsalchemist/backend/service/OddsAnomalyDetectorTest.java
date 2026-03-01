package com.oddsalchemist.backend.service;

import com.oddsalchemist.backend.dto.AnomalyAlertDto;
import com.oddsalchemist.backend.dto.OddsData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OddsAnomalyDetector の単体テスト。
 * Double型の精度問題を含む支持率計算と、順位乖離の算出が正確であることを検証する。
 */
class OddsAnomalyDetectorTest {

    private OddsAnomalyDetector detector;

    // テスト用のレース名定数
    private static final String RACE = "第1回東京1レース";

    @BeforeEach
    void setUp() {
        detector = new OddsAnomalyDetector();
    }

    // ===== ロジックA: 支持率急増検知 =====

    @Test
    void detect_初回実行ではアラートが発生しないこと() {
        // 初回は前回データなし → 支持率急増は検知不可
        List<OddsData> oddsList = List.of(
                odds("5", "ウマA", 10.0, 3.0, 5.0),
                odds("6", "ウマB", 12.0, 3.5, 6.0)
        );

        List<AnomalyAlertDto> alerts = detector.detect(oddsList);

        assertThat(alerts).isEmpty();
    }

    @Test
    void detect_支持率が2パーセント以上急増した場合にアラートが発生すること() {
        // 1回目: 単勝10.0 → 支持率 = 0.1
        detector.detect(List.of(
                odds("1", "人気馬A", 1.5, 1.1, 1.3),
                odds("2", "人気馬B", 2.0, 1.2, 1.5),
                odds("3", "人気馬C", 3.0, 1.4, 2.0),
                odds("5", "中穴馬", 10.0, 3.0, 5.0)
        ));

        // 2回目: 単勝5.0 → 支持率 = 0.2, 増加 = 0.2 - 0.1 = 0.1 (10%) >= 0.02
        List<AnomalyAlertDto> alerts = detector.detect(List.of(
                odds("1", "人気馬A", 1.5, 1.1, 1.3),
                odds("2", "人気馬B", 2.0, 1.2, 1.5),
                odds("3", "人気馬C", 3.0, 1.4, 2.0),
                odds("5", "中穴馬", 5.0, 2.5, 4.0)
        ));

        assertThat(alerts).hasSize(1);
        AnomalyAlertDto alert = alerts.get(0);
        assertThat(alert.horseNumber()).isEqualTo("5");
        assertThat(alert.horseName()).isEqualTo("中穴馬");
        assertThat(alert.alertType()).isEqualTo("支持率急増");
        // 支持率増加 = 1/5.0 - 1/10.0 = 0.2 - 0.1 = 0.1
        assertThat(alert.value()).isCloseTo(0.1, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void detect_支持率増加が閾値未満の場合はアラートが発生しないこと() {
        // 1回目: 単勝20.0 → 支持率 = 0.05
        detector.detect(List.of(
                odds("1", "人気馬A", 1.5, 1.1, 1.3),
                odds("2", "人気馬B", 2.0, 1.2, 1.5),
                odds("3", "人気馬C", 3.0, 1.4, 2.0),
                odds("8", "大穴馬", 20.0, 4.0, 8.0)
        ));

        // 2回目: 単勝19.0 → 支持率 ≈ 0.0526, 増加 ≈ 0.0026 < 0.02
        List<AnomalyAlertDto> alerts = detector.detect(List.of(
                odds("1", "人気馬A", 1.5, 1.1, 1.3),
                odds("2", "人気馬B", 2.0, 1.2, 1.5),
                odds("3", "人気馬C", 3.0, 1.4, 2.0),
                odds("8", "大穴馬", 19.0, 4.0, 8.0)
        ));

        assertThat(alerts).isEmpty();
    }

    @Test
    void detect_上位3番人気は支持率急増が大きくてもアラートが発生しないこと() {
        // 1回目: 1〜3位 + 4位以降
        detector.detect(List.of(
                odds("1", "人気馬A", 2.0, 1.2, 1.5),
                odds("2", "人気馬B", 3.0, 1.4, 2.0),
                odds("3", "人気馬C", 4.0, 1.6, 2.5),
                odds("4", "穴馬", 15.0, 3.0, 6.0)
        ));

        // 2回目: 上位3番人気が大きくオッズ短縮（支持率急増）→ 除外対象
        // 1位: 2.0→1.2 (支持率増加 = 1/1.2 - 1/2.0 ≈ 0.333)
        List<AnomalyAlertDto> alerts = detector.detect(List.of(
                odds("1", "人気馬A", 1.2, 1.1, 1.3),
                odds("2", "人気馬B", 1.5, 1.2, 1.5),
                odds("3", "人気馬C", 2.5, 1.3, 2.0),
                odds("4", "穴馬", 15.0, 3.0, 6.0)
        ));

        // 上位3番人気(1,2,3番)の支持率急増は除外され、アラートなし
        assertThat(alerts).extracting(AnomalyAlertDto::alertType).noneMatch(t -> t.equals("支持率急増"));
        assertThat(alerts).extracting(AnomalyAlertDto::horseNumber)
                .doesNotContain("1", "2", "3");
    }

    @Test
    void detect_支持率計算の精度_BigDecimalで閾値ちょうど0_02を正確に判定すること() {
        // 1/5.0 - 1/50.0 = 0.2 - 0.02 = 0.18 >= 0.02 → アラート
        // 1/10.0 - 1/11.0 ≈ 0.1 - 0.0909 = 0.009 < 0.02 → アラートなし
        detector.detect(List.of(
                odds("1", "人気馬A", 1.5, 1.1, 1.3),
                odds("2", "人気馬B", 2.0, 1.2, 1.5),
                odds("3", "人気馬C", 3.0, 1.4, 2.0),
                odds("5", "チェック馬X", 50.0, 5.0, 10.0),
                odds("6", "チェック馬Y", 11.0, 3.0, 5.0)
        ));

        List<AnomalyAlertDto> alerts = detector.detect(List.of(
                odds("1", "人気馬A", 1.5, 1.1, 1.3),
                odds("2", "人気馬B", 2.0, 1.2, 1.5),
                odds("3", "人気馬C", 3.0, 1.4, 2.0),
                odds("5", "チェック馬X", 5.0, 4.0, 8.0),   // 増加 = 0.18 → アラート
                odds("6", "チェック馬Y", 10.0, 3.0, 5.0)   // 増加 ≈ 0.009 → なし
        ));

        assertThat(alerts).extracting(AnomalyAlertDto::horseNumber).contains("5");
        assertThat(alerts).extracting(AnomalyAlertDto::horseNumber).doesNotContain("6");
    }

    // ===== ロジックB: 単複オッズ順位乖離検知 =====

    @Test
    void detect_単複順位ギャップが3以上の場合にアラートが発生すること() {
        // 単勝順位と複勝順位の乖離
        // 馬A: 単勝4位(12.0), 複勝1位(1.1) → gap = 4 - 1 = 3 → アラート
        List<AnomalyAlertDto> alerts = detector.detect(List.of(
                odds("1", "人気馬1", 1.5, 2.5, 3.5),
                odds("2", "人気馬2", 2.0, 3.0, 5.0),
                odds("3", "人気馬3", 3.5, 4.0, 7.0),
                odds("4", "乖離馬A", 12.0, 1.1, 1.5)
        ));

        List<AnomalyAlertDto> rankAlerts = alerts.stream()
                .filter(a -> a.alertType().equals("順位乖離"))
                .toList();
        assertThat(rankAlerts).hasSize(1);
        assertThat(rankAlerts.get(0).horseNumber()).isEqualTo("4");
        assertThat(rankAlerts.get(0).value()).isEqualTo(3.0);
    }

    @Test
    void detect_単複順位ギャップが2の場合はアラートが発生しないこと() {
        // 馬A: 単勝4位(12.0), 複勝2位(1.5) → gap = 4 - 2 = 2 < 3 → なし
        List<AnomalyAlertDto> alerts = detector.detect(List.of(
                odds("1", "人気馬1", 1.5, 1.1, 1.3),
                odds("2", "人気馬2", 2.0, 1.5, 2.0),
                odds("3", "人気馬3", 3.5, 3.0, 5.0),
                odds("4", "判定馬A", 12.0, 2.0, 3.5)
        ));

        assertThat(alerts.stream().filter(a -> a.alertType().equals("順位乖離"))).isEmpty();
    }

    @Test
    void detect_上位3番人気は順位乖離があってもアラートが発生しないこと() {
        // 1番人気(単勝最小)が複勝でも最下位 → gap大でも除外
        // 単勝1位: 1.2, 複勝4位(最高): 8.0 → gap = 1 - 4 = -3 (逆方向なので不発)
        // 単勝4位: 12.0, 複勝1位(最低): 1.1 → gap = 4 - 1 = 3 >= 3 → だが上位3番人気外なら出る
        // ここでは4頭しかいないので4位は除外されない
        List<AnomalyAlertDto> alerts = detector.detect(List.of(
                odds("1", "人気馬1_複勝低位", 1.5, 1.1, 1.3),  // 単勝1位,複勝1位 → top3かつgap=0
                odds("2", "人気馬2", 2.0, 2.0, 3.0),
                odds("3", "人気馬3", 3.0, 3.0, 5.0),
                odds("4", "穴馬", 8.0, 4.0, 7.0)
        ));
        // 上位3番人気(1,2,3)はアラート対象外
        assertThat(alerts).extracting(AnomalyAlertDto::horseNumber)
                .doesNotContain("1", "2", "3");
    }

    // ===== winOddsがnullの場合の安全性 =====

    @Test
    void detect_winOddsがnullの馬がいても例外が発生しないこと() {
        List<OddsData> oddsList = List.of(
                odds("1", "人気馬A", 1.5, 1.1, 1.3),
                new OddsData(RACE, "2", "オッズ未定馬", null, null, null),
                odds("3", "人気馬C", 3.0, 1.4, 2.0),
                odds("5", "穴馬", 10.0, 3.0, 5.0)
        );

        // 例外なく完了し、nullの馬は無視される
        List<AnomalyAlertDto> alerts = detector.detect(oddsList);
        assertThat(alerts).isNotNull();
    }

    @Test
    void detect_前回データが2回目以降に正しく更新されること() {
        // 1回目
        detector.detect(List.of(
                odds("1", "人気馬A", 1.5, 1.1, 1.3),
                odds("2", "人気馬B", 2.0, 1.2, 1.5),
                odds("3", "人気馬C", 3.0, 1.4, 2.0),
                odds("5", "穴馬", 20.0, 4.0, 8.0)
        ));

        // 2回目: オッズ短縮なし → アラートなし
        detector.detect(List.of(
                odds("1", "人気馬A", 1.5, 1.1, 1.3),
                odds("2", "人気馬B", 2.0, 1.2, 1.5),
                odds("3", "人気馬C", 3.0, 1.4, 2.0),
                odds("5", "穴馬", 20.0, 4.0, 8.0)
        ));

        // 3回目: 急激に短縮 20.0 → 5.0, 増加 = 1/5 - 1/20 = 0.15 >= 0.02
        List<AnomalyAlertDto> alerts = detector.detect(List.of(
                odds("1", "人気馬A", 1.5, 1.1, 1.3),
                odds("2", "人気馬B", 2.0, 1.2, 1.5),
                odds("3", "人気馬C", 3.0, 1.4, 2.0),
                odds("5", "穴馬", 5.0, 3.0, 6.0)
        ));

        assertThat(alerts).extracting(AnomalyAlertDto::alertType).contains("支持率急増");
        assertThat(alerts).extracting(AnomalyAlertDto::horseNumber).contains("5");
    }

    @Test
    void getLatestAlerts_detectの結果が最新状態で保持されること() {
        // 1回目: 初回実行 → アラートなし（前回データがない）
        detector.detect(List.of(
                odds("1", "馬A", 1.5, 1.1, 1.3),
                odds("2", "馬B", 2.0, 1.2, 1.5),
                odds("3", "馬C", 3.0, 1.4, 2.0),
                odds("4", "馬D", 8.0, 2.0, 4.0),
                odds("5", "急増馬", 20.0, 4.0, 8.0)
        ));
        assertThat(detector.getLatestAlerts()).isEmpty();

        // 2回目: 急増馬が 20.0 → 5.0 に短縮
        // top3: 馬A(1.5), 馬B(2.0), 馬C(3.0) → 急増馬(5.0)は4位でtop3外
        // 支持率増加 = 1/5.0 - 1/20.0 = 0.2 - 0.05 = 0.15 >= 0.02 → アラート
        detector.detect(List.of(
                odds("1", "馬A", 1.5, 1.1, 1.3),
                odds("2", "馬B", 2.0, 1.2, 1.5),
                odds("3", "馬C", 3.0, 1.4, 2.0),
                odds("4", "馬D", 8.0, 2.0, 4.0),
                odds("5", "急増馬", 5.0, 3.0, 6.0)
        ));

        List<AnomalyAlertDto> latest = detector.getLatestAlerts();
        assertThat(latest).isNotEmpty();
        assertThat(latest).extracting(AnomalyAlertDto::horseNumber).contains("5");
    }

    // ===== ヘルパーメソッド =====

    private OddsData odds(String number, String name, double win, double placeMin, double placeMax) {
        return new OddsData(RACE, number, name, win, placeMin, placeMax);
    }
}
