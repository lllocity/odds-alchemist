'use client';

import { useState, useEffect } from 'react';

type HorseAnalysis = {
  number: number;
  name: string;
  verdict: '軸候補' | '相手候補' | '対象外';
  comment: string;
};

type AnalysisResult = {
  horses: HorseAnalysis[];
  summary: string;
  model: string;
};

const VERDICT_STYLES: Record<string, string> = {
  '軸候補':   'bg-emerald-100 text-emerald-800 border border-emerald-200',
  '相手候補': 'bg-blue-100 text-blue-800 border border-blue-200',
  '対象外':   'bg-gray-100 text-gray-400 border border-gray-200',
};

const MODELS = [
  { id: 'gemini-3-flash-preview', label: 'Gemini 3 Flash' },
  { id: 'gemini-3.1-flash-lite',  label: 'Gemini 3.1 Flash Lite' },
  { id: 'gemini-2.5-flash',       label: 'Gemini 2.5 Flash' },
] as const;

type CacheEntry = { result: AnalysisResult; analyzedAt: Date; elapsedMs: number };

export default function OddsAnalysis({ url, onAnalyzingChange }: { url: string; onAnalyzingChange?: (v: boolean) => void }) {
  const [cache, setCache] = useState<Map<string, CacheEntry>>(new Map());
  const [selectedModel, setSelectedModel] = useState<string>(MODELS[0].id);
  const [result, setResult] = useState<AnalysisResult | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [elapsedMs, setElapsedMs] = useState<number | null>(null);
  const [analyzedAt, setAnalyzedAt] = useState<Date | null>(null);
  const [debugPrompt, setDebugPrompt] = useState<{ systemPrompt: string; userPrompt: string } | null>(null);
  const [isDebugLoading, setIsDebugLoading] = useState(false);

  const cacheKey = `${url}:${selectedModel}`;

  useEffect(() => {
    const entry = cache.get(cacheKey) ?? null;
    setResult(entry?.result ?? null);
    setElapsedMs(entry?.elapsedMs ?? null);
    setAnalyzedAt(entry?.analyzedAt ?? null);
    setError(null);
    setDebugPrompt(null);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [url, selectedModel]);

  const handleAnalyze = async () => {
    setIsLoading(true);
    onAnalyzingChange?.(true);
    setError(null);
    setResult(null);
    setElapsedMs(null);
    setAnalyzedAt(null);
    setDebugPrompt(null);
    const startedAt = Date.now();
    try {
      const res = await fetch(`/api/odds/analysis?url=${encodeURIComponent(url)}&model=${encodeURIComponent(selectedModel)}`);
      if (!res.ok) {
        const body = await res.json().catch(() => ({}));
        throw new Error((body as { error?: string }).error ?? `エラー: ${res.status}`);
      }
      const data: AnalysisResult = await res.json();
      const elapsed = Date.now() - startedAt;
      const at = new Date();
      setElapsedMs(elapsed);
      setAnalyzedAt(at);
      setCache(prev => new Map(prev).set(cacheKey, { result: data, analyzedAt: at, elapsedMs: elapsed }));
      setResult(data);
    } catch (e) {
      console.warn('AI分析の取得に失敗しました', e);
      setError(e instanceof Error ? e.message : 'AI分析に失敗しました');
    } finally {
      setIsLoading(false);
      onAnalyzingChange?.(false);
    }
  };

  const handleDebug = async () => {
    setIsDebugLoading(true);
    setDebugPrompt(null);
    try {
      const res = await fetch(`/api/odds/analysis?url=${encodeURIComponent(url)}&debug=true`);
      if (!res.ok) throw new Error(`エラー: ${res.status}`);
      const data = await res.json();
      setDebugPrompt(data);
    } catch (e) {
      console.warn('プロンプト取得に失敗しました', e);
    } finally {
      setIsDebugLoading(false);
    }
  };

  return (
    <div className="bg-white rounded-xl shadow-md p-4 sm:p-6">
      <div className="flex items-center justify-between mb-1">
        <h2 className="text-base font-semibold text-gray-800">AI オッズ分析</h2>
        <div className="flex items-center gap-2">
          <select
            value={selectedModel}
            onChange={e => setSelectedModel(e.target.value)}
            disabled={isLoading}
            className="px-2 py-1.5 text-xs border border-gray-300 rounded-lg focus:outline-none focus:ring-2 focus:ring-indigo-500 disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {MODELS.map(m => (
              <option key={m.id} value={m.id}>{m.label}</option>
            ))}
          </select>
          <button
            onClick={handleDebug}
            disabled={isDebugLoading || isLoading}
            className="px-3 py-2 text-xs font-medium rounded-lg bg-gray-100 text-gray-600 hover:bg-gray-200 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
          >
            {isDebugLoading ? '取得中...' : 'プロンプト確認'}
          </button>
          <button
            onClick={handleAnalyze}
            disabled={isLoading || cache.has(cacheKey)}
            className="px-4 py-2 text-sm font-medium rounded-lg bg-indigo-600 text-white hover:bg-indigo-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
          >
            {isLoading ? '分析中...' : 'AI分析を実行'}
          </button>
        </div>
      </div>
      <div className="flex flex-col items-end gap-0.5 mb-2 min-h-[1.25rem]">
        {analyzedAt && (
          <p className="text-xs text-gray-400">
            {analyzedAt.toLocaleString('ja-JP')} 取得 / モデル: {result?.model ?? ''}
            {elapsedMs !== null && `（${(elapsedMs / 1000).toFixed(1)}秒）`}
          </p>
        )}
        {cache.has(cacheKey) && !isLoading && (
          <p className="text-xs text-gray-400">再度分析する際はモデルを切り替えるか、画面をリロードしてください</p>
        )}
      </div>

      {/* 評価の定義 */}
      <details className="mb-3 text-xs text-gray-500 border border-gray-100 rounded-lg bg-gray-50">
        <summary className="px-3 py-2 cursor-pointer select-none list-none flex items-center gap-1 font-medium">
          <span className="text-gray-400 text-[10px]">▶</span>
          評価の定義
        </summary>
        <div className="px-3 pb-3 pt-1 space-y-1.5">
          <div className="flex items-start gap-2">
            <span className={`mt-0.5 shrink-0 text-xs font-bold px-2 py-0.5 rounded-full ${VERDICT_STYLES['軸候補']}`}>軸候補</span>
            <span className="leading-relaxed">人気上位でも最下位でもない中穴ゾーンで、[直前] 区間のオッズが安定または下落している馬（1〜2頭）</span>
          </div>
          <div className="flex items-start gap-2">
            <span className={`mt-0.5 shrink-0 text-xs font-bold px-2 py-0.5 rounded-full ${VERDICT_STYLES['相手候補']}`}>相手候補</span>
            <span className="leading-relaxed">[直前] 区間でオッズが下落傾向、またはアラートが出ている馬（3〜5頭）</span>
          </div>
          <div className="flex items-start gap-2">
            <span className={`mt-0.5 shrink-0 text-xs font-bold px-2 py-0.5 rounded-full ${VERDICT_STYLES['対象外']}`}>対象外</span>
            <span className="leading-relaxed">明確な支持の根拠がない馬</span>
          </div>
        </div>
      </details>

      {error && (
        <p className="text-sm text-red-600 bg-red-50 rounded-lg p-3">{error}</p>
      )}

      {isLoading && (
        <p className="text-sm text-gray-400 text-center py-6">Gemini が分析しています... しばらくお待ちください</p>
      )}

      {result && (
        <div className="space-y-3">
          {/* 全馬評価 */}
          <div className="space-y-1.5">
            {result.horses.map((horse) => (
              <div key={horse.number} className="flex items-start gap-3 px-3 py-2 border border-gray-200 rounded-lg">
                <span className={`mt-0.5 shrink-0 text-xs font-bold px-2 py-0.5 rounded-full ${VERDICT_STYLES[horse.verdict] ?? VERDICT_STYLES['対象外']}`}>
                  {horse.verdict}
                </span>
                <div className="min-w-0 flex-1">
                  <p className="text-sm font-medium text-gray-800">{horse.number}番 {horse.name}</p>
                  <p className="text-xs text-gray-600 mt-0.5 leading-relaxed">{horse.comment}</p>
                </div>
              </div>
            ))}
          </div>

          {/* 全体所感 */}
          <div className="bg-gray-50 rounded-lg p-3">
            <p className="text-xs text-gray-600 leading-relaxed">{result.summary}</p>
          </div>
        </div>
      )}

      {/* デバッグ: プロンプト表示エリア */}
      {debugPrompt && (
        <div className="mt-4 space-y-2">
          <div className="flex items-center justify-between">
            <p className="text-xs font-semibold text-gray-500">プロンプト確認（デバッグ用）</p>
            <button onClick={() => setDebugPrompt(null)} className="text-xs text-gray-400 hover:text-gray-600">閉じる</button>
          </div>
          <div>
            <p className="text-xs font-medium text-gray-500 mb-1">システムプロンプト</p>
            <pre className="text-xs bg-gray-50 border border-gray-200 rounded p-2 overflow-x-auto whitespace-pre-wrap break-words">{debugPrompt.systemPrompt}</pre>
          </div>
          <div>
            <p className="text-xs font-medium text-gray-500 mb-1">ユーザープロンプト</p>
            <pre className="text-xs bg-gray-50 border border-gray-200 rounded p-2 overflow-x-auto whitespace-pre-wrap break-words max-h-[600px] overflow-y-auto">{debugPrompt.userPrompt}</pre>
          </div>
        </div>
      )}
    </div>
  );
}
