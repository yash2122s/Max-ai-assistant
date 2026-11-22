"use client";

import React, { createContext, useContext, useEffect, useState } from "react";
import {
  User,
  onAuthStateChanged,
  signInWithEmailAndPassword,
  signInWithPopup,
  signInWithRedirect,
  getRedirectResult,
  GoogleAuthProvider,
  signOut as firebaseSignOut
} from "firebase/auth";
import { auth } from "./firebase";

interface AuthContextType {
  user: User | null;
  isAdmin: boolean;
  isLoading: boolean;
  error: string | null;
  signInWithEmail: (email: string, pass: string) => Promise<void>;
  signInWithGoogle: () => Promise<void>;
  signOut: () => Promise<void>;
  refreshClaims: () => Promise<boolean>;
}

const AuthContext = createContext<AuthContextType>({
  user: null,
  isAdmin: false,
  isLoading: true,
  error: null,
  signInWithEmail: async () => {},
  signInWithGoogle: async () => {},
  signOut: async () => {},
  refreshClaims: async () => false,
});

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [isAdmin, setIsAdmin] = useState(false);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    // Check for redirect result on load
    getRedirectResult(auth)
      .then(async (result) => {
        if (result && result.user) {
          const tokenResult = await result.user.getIdTokenResult(true);
          const adminClaim = tokenResult.claims.admin === true;
          setIsAdmin(adminClaim);
          if (!adminClaim) {
            setError("Account authenticated, but lacks administrator privileges (admin: true claim missing).");
          } else {
            setError(null);
          }
        }
      })
      .catch((err: unknown) => {
        const message = err instanceof Error ? err.message : "Redirect sign-in failed";
        console.warn("getRedirectResult info:", message);
      });

    const unsubscribe = onAuthStateChanged(auth, async (currentUser) => {
      setUser(currentUser);
      if (currentUser) {
        try {
          const tokenResult = await currentUser.getIdTokenResult(true);
          const adminClaim = tokenResult.claims.admin === true;
          setIsAdmin(adminClaim);
          if (!adminClaim) {
            setError("Account authenticated, but lacks administrator privileges (admin: true claim missing).");
          } else {
            setError(null);
          }
        } catch (err: unknown) {
          setIsAdmin(false);
          const message = err instanceof Error ? err.message : "Failed to verify claims";
          setError(message);
        }
      } else {
        setIsAdmin(false);
        setError(null);
      }
      setIsLoading(false);
    });

    return () => unsubscribe();
  }, []);

  const refreshClaims = async (): Promise<boolean> => {
    if (!auth.currentUser) return false;
    try {
      const tokenResult = await auth.currentUser.getIdTokenResult(true);
      const adminClaim = tokenResult.claims.admin === true;
      setIsAdmin(adminClaim);
      if (adminClaim) setError(null);
      return adminClaim;
    } catch {
      return false;
    }
  };

  const signInWithEmail = async (email: string, pass: string) => {
    setError(null);
    setIsLoading(true);
    try {
      const cred = await signInWithEmailAndPassword(auth, email, pass);
      const tokenResult = await cred.user.getIdTokenResult(true);
      const adminClaim = tokenResult.claims.admin === true;
      setIsAdmin(adminClaim);
      if (!adminClaim) {
        setError("Account authenticated, but lacks administrator privileges.");
      }
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : "Authentication failed";
      setError(message);
      throw err;
    } finally {
      setIsLoading(false);
    }
  };

  const signInWithGoogle = async () => {
    setError(null);
    setIsLoading(true);
    try {
      const provider = new GoogleAuthProvider();
      provider.setCustomParameters({ prompt: 'select_account' });
      try {
        const cred = await signInWithPopup(auth, provider);
        const tokenResult = await cred.user.getIdTokenResult(true);
        const adminClaim = tokenResult.claims.admin === true;
        setIsAdmin(adminClaim);
        if (!adminClaim) {
          setError("Account authenticated, but lacks administrator privileges.");
        }
      } catch (popupErr: any) {
        // If popup was blocked or unavailable in the environment, fallback to redirect
        if (
          popupErr.code === "auth/popup-blocked" ||
          popupErr.code === "auth/popup-closed-by-user" ||
          popupErr.code === "auth/cancelled-popup-request"
        ) {
          console.warn("Popup blocked or closed; initiating signInWithRedirect fallback...");
          await signInWithRedirect(auth, provider);
          return;
        }
        throw popupErr;
      }
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : "Google sign-in failed";
      setError(message);
      throw err;
    } finally {
      setIsLoading(false);
    }
  };

  const signOut = async () => {
    try {
      await firebaseSignOut(auth);
      setUser(null);
      setIsAdmin(false);
      setError(null);
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : "Sign out failed";
      setError(message);
    }
  };

  return (
    <AuthContext.Provider
      value={{
        user,
        isAdmin,
        isLoading,
        error,
        signInWithEmail,
        signInWithGoogle,
        signOut,
        refreshClaims,
      }}
    >
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  return useContext(AuthContext);
}
