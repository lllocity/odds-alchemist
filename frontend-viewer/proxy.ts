import { auth } from '@/auth';
import { NextResponse } from 'next/server';

export default auth((req) => {
  const allowedEmail = process.env.ALLOWED_EMAIL;
  const sessionEmail = req.auth?.user?.email;

  if (!sessionEmail || sessionEmail !== allowedEmail) {
    const signInUrl = new URL('/api/auth/signin', req.url);
    return NextResponse.redirect(signInUrl);
  }
});

export const config = {
  matcher: ['/((?!api/auth|_next/static|_next/image|favicon.ico).*)'],
};
