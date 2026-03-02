# 専門知識: 実装方針

---

## バックエンド

### スクレイピングのルール (Jsoup)
- **対象サイト**: 現在は「Yahoo!スポナビ競馬」を対象としている。
- **パース方針**: HTMLのクラス名や構造は変更されやすいため、特定のクラス名に過度に依存せず、**「テキストのパターンマッチ（正規表現）」** や **「テーブル列のインデックス」** を組み合わせた柔軟で堅牢な抽出ロジック（`RaceOddsParser.java`）を維持すること。
- **データ抽出要件**: 馬番（数値）、馬名（文字列）、単勝オッズ（数値）、複勝オッズ（下限・上限の数値）を確実に抽出する。

### Google Sheets APIのルール
- **データマッピング**: `OddsSyncService.java` でスプレッドシートに書き込む際、以下の列順序を厳守すること。
  - A列: 取得日時 (形式: `yyyy/MM/dd HH:mm:ss`)
  - B列: 馬番
  - C列: 馬名
  - D列: 単勝オッズ
  - E列: 複勝オッズ（下限）
  - F列: 複勝オッズ（上限）

### アーキテクチャと品質
- クラスの責務を単一にし、肥大化を防ぐ（例: 取得処理、パース処理、保存処理は別クラスに分割する）。
- オッズパース処理などの複雑なロジックに対しては、JUnit 5を用いた単体テストを必ず記述し、想定されるHTMLパターン（正常系・異常系・一部データ欠損）を網羅すること。

### CORS設定パターン
- 各 `@RestController` に `@CrossOrigin(origins = "http://localhost:3000")` を付与するシンプルなパターンを採用している。
- 複数のコントローラーが同じ `/api/odds` パスを共有してもSpringは正常に処理できる（HTTPメソッドとパスの組み合わせが一意であればよい）。

### Spring Boot バージョン注意事項
- 本プロジェクトは **Spring Boot 4.0.x** を使用している（Jackson 3 / `tools.jackson.*` 名前空間）。
- `application.yaml` の `spring.jackson.serialization` によるJackson設定はSpring Boot 4では正常に機能しない場合がある。Jackson の挙動を変更したい場合は Java の `@Configuration` クラスで行うか、DTOフィールドを `String` 型に変換してサービス層でフォーマットする方針を取ること。

---

## フロントエンド

### TypeScriptの型定義ルール
- バックエンドのDTOが変更された場合は、対応する `frontend/app/types/` 配下の型定義ファイルを**必ず同時に更新**すること。型の不一致はランタイムエラーの原因となる。
- DTOの日時フィールドはバックエンドで `DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")` でフォーマットした `String` として渡し、TypeScript側でも `string` 型で受け取る。
- `alertType` など値が有限のフィールドは `type AlertType = '支持率急増' | '順位乖離' | 'トレンド逸脱'` のようにリテラル型 Union で定義し、`any` や `string` で曖昧にしない。

### ディレクトリ構成
- 型定義: `frontend/app/types/` 配下に機能単位で配置（例: `oddsAlert.ts`）。
- UIコンポーネント: `frontend/app/components/` 配下に配置（例: `AlertList.tsx`）。
- `@/app/...` の絶対パスインポートを使用（`tsconfig.json` の `"paths": {"@/*": ["./*"]}` 参照）。

### ポーリング実装パターン
- `useCallback` でフェッチ関数を定義し、`useEffect` で初回即時実行 + `setInterval` でポーリングを設定する。
- クリーンアップ関数 `return () => clearInterval(timer)` を忘れずに書くこと（コンポーネントアンマウント時のメモリリーク防止）。
- `apiBaseUrl` は `process.env.NEXT_PUBLIC_API_BASE_URL` 経由で取得し、`useCallback` の依存配列に含めること。

### 検知タイプ別スタイリングのパターン
- `Record<AlertType, StyleObject>` 形式の設定オブジェクトで、検知タイプごとの色を一元管理する。
- 未知の検知タイプには `?? fallback` でデフォルトスタイルを適用し、型エラーを防ぐ。
