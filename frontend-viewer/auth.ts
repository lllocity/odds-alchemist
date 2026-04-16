import NextAuth from 'next-auth';
import Google from 'next-auth/providers/google';

export const { handlers, auth, signIn, signOut } = NextAuth({
  providers: [
    Google({
      clientId: process.env.AUTH_GOOGLE_ID!,
      clientSecret: process.env.AUTH_GOOGLE_SECRET!,
    }),
  ],
  callbacks: {
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
