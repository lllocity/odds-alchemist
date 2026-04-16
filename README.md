# Odds Alchemist

JRA（日本中央競馬会）のオッズ情報を定期的に取得・分析し、投資妙味のある異常オッズをリアルタイムで検知・通知するシステム。

> **免責事項**: 本システムは個人的な学習・研究目的で開発したものです。不特定多数での利用は想定していません。対象サービスの利用規約を遵守し、自己責任でご利用ください。

---

## 機能概要

- **スケジュール監視**: 発走時刻に応じてポーリング間隔を自動調整（30分 / 5分 / 1分）しながらオッズを継続取得
- **異常検知**: 以下の3種類の異常を自動検知
  - **支持率急増**: 前回比 +2.0%以上の急激な人気集中
  - **順位乖離**: 単勝と複勝の人気順位が3つ以上ずれている馬
  - **トレンド逸脱**: 初回取得時から支持率が +5.0%以上変化した中穴・大穴馬（5〜12番人気）
- **Slack 通知**: 異常検知時に Slack へアラートを送信（日次ユニーク）
- **オッズ推移グラフ**: URLと馬名を選択して単勝・複勝オッズの時系列グラフを表示
- **永続化**: 取得データ・アラート・監視URLを Google Sheets に記録（再起動後も自動復元）

---

## セットアップ・起動

初回セットアップ（GCP キー取得・`.env` 設定・Docker 起動・LAN アクセス設定など）は **[docs/setup_guide.md](docs/setup_guide.md)** を参照してください。

---

## フロントエンド構成

本システムのフロントエンドは用途別に2つに分かれている。

| FE | ディレクトリ | 起動環境 | 機能 |
|---|---|---|---|
| 管理用 FE | `frontend/` | 自宅 Docker Compose | URL登録・削除、データ管理（シートクリア） |
| 閲覧用 FE | `frontend-viewer/` | Vercel（外部公開） | オッズ推移グラフ、検知アラート、買いの掟 |

閲覧用 FE は Google OAuth（NextAuth v5）で認証し、Google Sheets を直接読み取る（バックエンド不要）。

---

## 使い方

### 管理操作（自宅ローカルから）

1. **監視URL を登録する**: 管理用 FE の「スケジュール監視対象URL」フォームに Yahoo!スポナビ競馬のオッズページURLを入力して登録する
   - 例: `https://sports.yahoo.co.jp/keiba/race/odds/tfw/2606020211`
   - 登録直後に初回オッズ取得・異常検知が即時（非同期）で実行される
2. **自動監視**: 以降はスケジューラーが発走時刻に応じた間隔で自動取得・異常検知を継続する
3. **Slack 通知**: 設定済みの場合、異常検知時に Slack へ通知が届く

### 閲覧（外出先からも可）

4. **アラートを確認する**: 閲覧用 FE（Vercel）にアクセスし、右カラムのアラート一覧でリアルタイム確認する（10秒ごとに自動更新）
5. **オッズ推移グラフを表示する**: 閲覧用 FE の左カラムでURLと馬名を選択してグラフを描画する

---

## Google Sheets の構成

| シート名 | 用途 | 列 |
|---|---|---|
| `OddsData` | 取得したオッズデータ | A:取得日時 / B:URL / C:レース名 / D:馬番 / E:馬名 / F:単勝 / G:複勝下限 / H:複勝上限 |
| `Alerts` | 検知アラート | A:検知日時 / B:URL / C:レース名 / D:馬番 / E:馬名 / F:検知タイプ / G:数値 |
| `Targets` | 監視URL（再起動時復元用） | A:URL / B:最終実行時間 / C:次回実行予定時間 |

---

## API エンドポイント

バックエンド（Spring Boot）が提供する REST API。

| メソッド | パス | 説明 |
|---|---|---|
| `GET` | `/api/odds/targets` | 登録済み監視URL一覧を取得 |
| `POST` | `/api/odds/targets` | 監視URLを追加 |
| `DELETE` | `/api/odds/targets` | 監視URLを削除 |
| `DELETE` | `/api/odds/sheets` | シートデータをクリア（`?sheet=OddsData\|Alerts`） |

---

## 技術スタック

| レイヤー | 技術 |
|---|---|
| バックエンド | Java 21, Spring Boot 4.0.x |
| 管理用 FE (`frontend/`) | Next.js 16 (App Router), TypeScript, Tailwind CSS |
| 閲覧用 FE (`frontend-viewer/`) | Next.js 16 (App Router), TypeScript, Tailwind CSS, Recharts, NextAuth v5, googleapis |
| HTML解析 | Jsoup |
| 永続化 | Google Sheets API |
| インフラ | Docker Compose（管理用）, Vercel（閲覧用） |

---

## 開発

```bash
# バックエンドテスト
cd backend && ./gradlew test

# 管理用 FE ビルド確認
cd frontend && npm run build

# 閲覧用 FE ビルド確認
cd frontend-viewer && npm run build
```
