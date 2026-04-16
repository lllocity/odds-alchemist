import { google } from 'googleapis';

const SPREADSHEET_ID = process.env.GOOGLE_SHEETS_SPREADSHEET_ID!;

function getAuth() {
  const key = process.env.GOOGLE_SA_KEY;
  if (!key) throw new Error('GOOGLE_SA_KEY が設定されていません');
  const credentials = JSON.parse(key);
  return new google.auth.GoogleAuth({
    credentials,
    scopes: ['https://www.googleapis.com/auth/spreadsheets.readonly'],
  });
}

/** Alerts!A2:G を全件取得する */
export async function getAlerts(): Promise<string[][]> {
  try {
    const auth = getAuth();
    const sheets = google.sheets({ version: 'v4', auth });
    const res = await sheets.spreadsheets.values.get({
      spreadsheetId: SPREADSHEET_ID,
      range: 'Alerts!A2:G',
    });
    return (res.data.values as string[][] | null | undefined) ?? [];
  } catch (e) {
    console.warn('Alerts シートの取得に失敗しました', e);
    return [];
  }
}

/** OddsData!A2:H を全件取得する */
export async function getOddsData(): Promise<string[][]> {
  try {
    const auth = getAuth();
    const sheets = google.sheets({ version: 'v4', auth });
    const res = await sheets.spreadsheets.values.get({
      spreadsheetId: SPREADSHEET_ID,
      range: 'OddsData!A2:H',
    });
    return (res.data.values as string[][] | null | undefined) ?? [];
  } catch (e) {
    console.warn('OddsData シートの取得に失敗しました', e);
    return [];
  }
}
