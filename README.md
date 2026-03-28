# Odds Alchemist

JRA（日本中央競馬会）のオッズ情報を定期的に取得・分析し、投資妙味のある異常オッズをリアルタイムで検知・通知するシステム。

> **免責事項**: 本システムは個人的な学習・研究目的で開発したものです。不特定多数での利用は想定していません。対象サービスの利用規約を遵守し、自己責任でご利用ください。

---

## 機能概要

- **スケジュール監視**: 発走時刻に応じてポーリング間隔を自動調整（30分 / 15分 / 5分 / 1分）しながらオッズを継続取得
- **異常検知**: 以下の3種類の異常を自動検知
  - **支持率急増**: 前回比 +2.0%以上の急激な人気集中
  - **順位乖離**: 単勝と複勝の人気順位が3つ以上ずれている馬
  - **トレンド逸脱**: 初回取得時から支持率が +5.0%以上変化した中穴・大穴馬（5〜12番人気）
- **Slack 通知**: 異常検知時に Slack へアラートを送信（日次ユニーク）
- **オッズ推移グラフ**: URLと馬名を選択して単勝・複勝オッズの時系列グラフを表示
- **永続化**: 取得データ・アラート・監視URLを Google Sheets に記録（再起動後も自動復元）

---

## 起動方法

**前提条件**: Docker Desktop（`brew install --cask docker` でインストール）

1. `.env` を作成して秘密情報を設定する

   ```bash
   cp .env.example .env
   # .env を編集（詳細は docs/setup_guide.md を参照）
   ```

2. Docker Desktop を起動する

3. 以下のコマンドを実行する

   ```bash
   docker compose up --build   # 初回 / コード変更時
   docker compose up -d        # 2回目以降（バックグラウンド）
   ```

4. ブラウザで http://localhost:3000 にアクセス

### 運用コマンド

```bash
# ログを確認する
docker compose logs -f           # 全サービス
docker compose logs -f backend   # バックエンドのみ
docker compose logs -f frontend  # フロントエンドのみ

# 停止
docker compose down
```

### ローカル開発（Docker なし）

**前提条件**: Java 21、Node.js 18+、Google Cloud 認証情報

1. `backend/src/main/resources/application-secret.yaml` を作成:

   ```yaml
   google:
     sheets:
       spreadsheet-id: "YOUR_SPREADSHEET_ID"
       credentials-path: "/path/to/credentials.json"
   slack:
     webhook-url: "https://hooks.slack.com/services/xxx/yyy/zzz"
   ```

2. 起動:

   ```bash
   # バックエンド
   cd backend && ./gradlew bootRun

   # フロントエンド（別ターミナル）
   cd frontend && npm install && npm run dev
   ```

---

## 使い方

1. **監視URL を登録する**: フロントエンドの「スケジュール監視対象URL」フォームに Yahoo!スポナビ競馬のオッズページURLを入力して登録する
   - 例: `https://sports.yahoo.co.jp/keiba/race/odds/tfw/2606020211`
   - 登録直後に初回オッズ取得・異常検知が即時（非同期）で実行される
2. **自動監視**: 以降はスケジューラーが発走時刻に応じた間隔で自動取得・異常検知を継続する
3. **アラートを確認する**: 検知されたアラートは右カラムのアラート一覧にリアルタイム表示される（10秒ごとに自動更新）
4. **Slack 通知**: 設定済みの場合、異常検知時に Slack へ通知が届く
5. **オッズ推移グラフを表示する**: 左カラム下部の「オッズ推移グラフ」パネルでURLと馬名を選択してグラフを描画する
6. **即時取得（任意）**: 「即時取得」フォームから任意のURLを指定してワンショット取得も可能

---

## Google Sheets の構成

| シート名 | 用途 | 列 |
|---|---|---|
| `OddsData` | 取得したオッズデータ | A:取得日時 / B:URL / C:レース名 / D:馬番 / E:馬名 / F:単勝 / G:複勝下限 / H:複勝上限 |
| `Alerts` | 検知アラート | A:検知日時 / B:URL / C:レース名 / D:馬番 / E:馬名 / F:検知タイプ / G:数値 |
| `Targets` | 監視URL（再起動時復元用） | A:URL / B:最終実行時間 / C:次回実行予定時間 |

---

## API エンドポイント

| メソッド | パス | 説明 |
|---|---|---|
| `POST` | `/api/odds/fetch` | 指定URLのオッズを即時取得・検知 |
| `GET` | `/api/odds/alerts` | 最新のアラート一覧を取得 |
| `GET` | `/api/odds/targets` | 登録済み監視URL一覧を取得 |
| `POST` | `/api/odds/targets` | 監視URLを追加 |
| `DELETE` | `/api/odds/targets` | 監視URLを削除 |
| `GET` | `/api/odds/history/urls` | OddsData に存在するURLの一覧を取得 |
| `GET` | `/api/odds/history/horses` | 指定URL（`?url=`）の馬一覧を取得 |
| `GET` | `/api/odds/history` | 指定URL・馬名（`?url=&horseName=`）のオッズ時系列データを取得 |

---

## 技術スタック

| レイヤー | 技術 |
|---|---|
| バックエンド | Java 21, Spring Boot 4.0.x |
| フロントエンド | Next.js 14+ (App Router), TypeScript, Tailwind CSS, Recharts |
| HTML解析 | Jsoup |
| 永続化 | Google Sheets API |
| インフラ | Docker Compose |

---

## 開発

```bash
# バックエンドテスト
cd backend && ./gradlew test

# フロントエンドビルド確認
cd frontend && npm run build
```
