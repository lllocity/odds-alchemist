import NextAuth from 'next-auth';
import Google from 'next-auth/providers/google';
import { authConfig } from './auth.config';

export const { handlers, auth, signIn, signOut } = NextAuth({
  ...authConfig,
  providers: [
    Google({
      clientId: process.env.AUTH_GOOGLE_ID!,
      clientSecret: process.env.AUTH_GOOGLE_SECRET!,
      authorization: {
        params: {
          prompt: 'select_account',
        },
      },
    }),
  ],
  callbacks: {
    ...authConfig.callbacks,
    signIn({ profile }) {
      const allowedEmail = process.env.ALLOWED_EMAIL;
      if (!allowedEmail) {
        console.warn('ALLOWED_EMAIL が設定されていません');
        return false;
      }
      return profile?.email === allowedEmail;
    },
  },
});
