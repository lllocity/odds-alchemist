import { NextRequest, NextResponse } from 'next/server';
import { getToken } from 'next-auth/jwt';

export default async function proxy(req: NextRequest) {
  const { pathname } = req.nextUrl;

  // 認証不要なパスはスキップ
  if (
    pathname.startsWith('/api/auth/') ||
    pathname.startsWith('/_next/') ||
    pathname === '/login' ||
    pathname === '/favicon.ico'
  ) {
    return NextResponse.next();
  }

  const token = await getToken({
    req,
    secret: process.env.NEXTAUTH_SECRET ?? process.env.AUTH_SECRET,
  });

  const allowedEmail = process.env.ALLOWED_EMAIL;

  if (!token?.email || token.email !== allowedEmail) {
    const response = NextResponse.redirect(new URL('/login', req.url));
    // 不正なセッションクッキーが残っている場合は削除する
    response.cookies.delete('authjs.session-token');
    response.cookies.delete('__Secure-authjs.session-token');
    return response;
  }
}

export const config = {
  matcher: ['/((?!_next/static|_next/image|favicon.ico).*)'],
};
