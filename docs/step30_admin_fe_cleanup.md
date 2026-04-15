# Step 30: 管理用 frontend/ から閲覧系機能を削除

閲覧機能を frontend-viewer/ に移管したため、管理用 FE から不要になった機能を削除します。

---

## 削除対象（frontend/app/page.tsx）

### State
- `alerts: AnomalyAlert[]`
- `lastUpdated: Date | null`

### 関数
- `fetchAlerts` 関数全体

### useEffect
- ポーリング内の `fetchAlerts()` 呼び出しを削除
- `fetchTargetUrls` のみ残す（ポーリングは維持）

### Import
- `AlertList` コンポーネントの import
- `AnomalyAlert` 型の import（他で使っていなければ）

### JSX
- 「検知アラート」パネル（AlertList コンポーネント）
- 「買いの掟」パネル
- 右カラム全体（上記2つを含む div）

### コンポーネントファイル（削除）
- `frontend/app/components/AlertList.tsx`
- `frontend/app/components/OddsTrendChart.tsx`
- `frontend/app/types/oddsAlert.ts`

---

## レイアウト変更

2カラム → 1カラムに変更する。

```tsx
// 変更前
<div className="grid grid-cols-2 gap-6 items-start">
  {/* 左カラム */}
  {/* 右カラム（買いの掟 + アラート） */}
</div>

// 変更後
<div className="max-w-2xl mx-auto space-y-6">
  {/* スケジュール監視対象URL管理パネル */}
  {/* データ管理パネル */}
</div>
```

---

## 削除後に残るもの

- ヘッダーバー（ロゴ・タイトル）
- スケジュール監視対象URL管理パネル（登録フォーム・URL一覧・削除ボタン）
- データ管理パネル（クリアボタン・スプレッドシートリンク）
- `fetchTargetUrls` とそのポーリング
- `TargetUrlInfo` 型の import

---

## 確認

- [ ] `npm run dev` でエラーなく起動する
- [ ] URL 登録・削除が正常に動作する
- [ ] データ管理（クリア・シートリンク）が正常に動作する
- [ ] グラフ・アラート・買いの掟が表示されないことを確認

---

## 次のステップ

→ [Step 31: Vercel デプロイ](step31_vercel_deploy.md)
