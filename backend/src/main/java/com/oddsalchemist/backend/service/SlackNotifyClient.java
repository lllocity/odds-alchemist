package com.oddsalchemist.backend.service;

import com.oddsalchemist.backend.config.SlackProperties;
import com.oddsalchemist.backend.dto.AnomalyAlertDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Slack Incoming Webhook を使ったアラート通知クライアント。
 * 送信済みキーセットを保持し、同日・同URL・同馬番・同検知タイプのアラートは初回のみ通知する。
 */
@Service
public class SlackNotifyClient {

    private static final Logger logger = LoggerFactory.getLogger(SlackNotifyClient.class);

    private final SlackProperties properties;
    private final RestClient restClient;

    /** 送信済みキーセット: 同日・同URL・同馬番・同検知タイプは1回のみ通知 */
    private final Set<String> sentKeys = ConcurrentHashMap.newKeySet();

    /** 日次リセット用（日付変更でキャッシュをクリア） */
    private volatile LocalDate lastResetDate = LocalDate.MIN;

    private final Clock clock;

    /** Spring が使用するデフォルトコンストラクタ */
    @Autowired
    public SlackNotifyClient(SlackProperties properties) {
        this(properties, RestClient.builder().build(), Clock.systemDefaultZone());
    }

    /** テスト用（Clock・RestClient を差し替え可能） */
    SlackNotifyClient(SlackProperties properties, RestClient restClient, Clock clock) {
        this.properties = properties;
        this.restClient = restClient;
        this.clock = clock;
    }

    /**
     * アラートリストを受け取り、未通知のものだけを1通にまとめてSlackへ送信します。
     * enabled=false・アラート0件・全件送信済みの場合は何もしません。
     * 通信失敗は try-catch で捕捉し WARN ログのみ出力します（スクレイピングを止めない）。
     *
     * @param alerts    検知されたアラートリスト
     * @param targetUrl 対象URL
     */
    public void notify(List<AnomalyAlertDto> alerts, String targetUrl) {
        if (!properties.enabled()) {
            return;
        }

        if (properties.webhookUrl() == null || properties.webhookUrl().isBlank()) {
            logger.warn("Slack webhook URL が設定されていません。通知をスキップします。");
            return;
        }

        resetIfNewDay();

        List<AnomalyAlertDto> toSend = new ArrayList<>();
        for (AnomalyAlertDto alert : alerts) {
            String key = buildKey(alert, targetUrl);
            if (!sentKeys.contains(key)) {
                toSend.add(alert);
            }
        }

        if (toSend.isEmpty()) {
            return;
        }

        String message = buildMessage(toSend, targetUrl);

        try {
            restClient.post()
                    .uri(properties.webhookUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("text", message))
                    .retrieve()
                    .toBodilessEntity();

            // 送信成功時のみキャッシュに追加（失敗時は次回再試行対象）
            for (AnomalyAlertDto alert : toSend) {
                sentKeys.add(buildKey(alert, targetUrl));
            }
            logger.info("Slack通知を送信しました: {}件, URL={}", toSend.size(), targetUrl);
        } catch (Exception e) {
            logger.warn("Slack通知の送信に失敗しました: URL={}", targetUrl, e);
        }
    }

    private void resetIfNewDay() {
        LocalDate today = LocalDate.now(clock);
        if (!today.equals(lastResetDate)) {
            sentKeys.clear();
            lastResetDate = today;
        }
    }

    private String buildKey(AnomalyAlertDto alert, String targetUrl) {
        String date = alert.detectedAt().substring(0, 10); // "yyyy-MM-dd"
        return targetUrl + ":" + alert.horseNumber() + ":" + alert.alertType() + ":" + date;
    }

    private String buildMessage(List<AnomalyAlertDto> alerts, String targetUrl) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!channel>\n");
        sb.append("🏇 *").append(alerts.size()).append("頭に動きあり！今すぐチェック*\n");
        sb.append(targetUrl).append("\n");

        for (AnomalyAlertDto alert : alerts) {
            sb.append("\n");
            sb.append(alertEmoji(alert.alertType())).append(" *").append(alert.horseName())
              .append("*（").append(alert.horseNumber()).append("番）\n");
            sb.append("　").append(alert.raceName()).append("\n");
            sb.append("　").append(alertCaption(alert.alertType(), alert.value())).append("\n");
        }

        return sb.toString();
    }

    private String alertEmoji(String alertType) {
        return switch (alertType) {
            case "支持率急増"         -> "🔥";
            case "支持率加速"         -> "🚀";
            case "順位乖離"           -> "⚡";
            case "順位乖離[拡大中]"   -> "📊";
            case "順位乖離[解消中]"   -> "📉";
            case "トレンド逸脱"       -> "📈";
            case "フェーズ逸脱[朝]"   -> "🌅";
            case "フェーズ逸脱[30分前]" -> "⏰";
            case "フェーズ逸脱[10分前]" -> "🔔";
            case "オッズ断層[凝縮]"   -> "🎯";
            case "オッズ断層[拡散]"   -> "🌊";
            default                   -> "❓";
        };
    }

    private String alertCaption(String alertType, double value) {
        return switch (alertType) {
            case "支持率急増"   -> String.format("支持率が +%.1f%% 急上昇中！玄人筋の仕込みか", value * 100);
            case "支持率加速"   -> String.format("%.2f%%/分 で加速中。流れが継続しており信頼度高め", value * 100);
            case "順位乖離"     -> String.format("単複ギャップ %.0f 差。「勝ちきれないが来る」市場評価、複勝・ワイドの軸候補", value);
            case "順位乖離[拡大中]" -> String.format("乖離がさらに +%.0f 拡大。複勝/ワイドを仕込み続けるタイミング", value);
            case "順位乖離[解消中]" -> String.format("乖離が %.0f 縮小。単勝/馬単への切り替えを検討", value);
            case "トレンド逸脱" -> String.format("今日イチの動き、支持率 +%.1f%% 乖離。中穴・大穴の注目株", value * 100);
            case "フェーズ逸脱[朝]"   -> String.format("朝から +%.1f%% 継続上昇。長時間資金流入で信頼度高め", value * 100);
            case "フェーズ逸脱[30分前]" -> String.format("30分前比 +%.1f%% 急変。直前の情報筋仕込みの可能性", value * 100);
            case "フェーズ逸脱[10分前]" -> String.format("10分前比 +%.1f%% 急変！最終局面の重要シグナル、今すぐ確認を", value * 100);
            case "オッズ断層[凝縮]" -> String.format("断層が上位へ移動（比率 %.2f倍）。絞り込みが進行、断層内の上位馬を本命視", value);
            case "オッズ断層[拡散]" -> String.format("断層が下位へ移動（比率 %.2f倍）。混戦化進行、穴狙い戦略への切り替えを検討", value);
            default             -> String.format("数値: %.3f", value);
        };
    }
}
