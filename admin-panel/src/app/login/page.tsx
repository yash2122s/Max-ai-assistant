"use client";

import React, { useState, useEffect } from "react";
import { useRouter } from "next/navigation";
import { Cpu, Lock, Mail, ShieldAlert, ArrowRight, RefreshCw } from "lucide-react";
import { useAuth } from "@/lib/auth-context";

export default function LoginPage() {
  const router = useRouter();
  const { user, isAdmin, isLoading, error, signInWithEmail, signInWithGoogle, refreshClaims } = useAuth();
  
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [localError, setLocalError] = useState<string | null>(null);

  useEffect(() => {
    if (!isLoading && user && isAdmin) {
      router.replace("/overview");
    }
  }, [user, isAdmin, isLoading, router]);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!email || !password) {
      setLocalError("Please enter both email and password.");
      return;
    }

    setSubmitting(true);
    setLocalError(null);
    try {
      await signInWithEmail(email, password);
    } catch (err: any) {
      setLocalError(err.message || "Failed to authenticate.");
    } finally {
      setSubmitting(false);
    }
  };

  const handleGoogle = async () => {
    setSubmitting(true);
    setLocalError(null);
    try {
      await signInWithGoogle();
    } catch (err: any) {
      if (err?.code === 'auth/unauthorized-domain') {
        const hostname = typeof window !== 'undefined' ? window.location.hostname : 'current domain';
        setLocalError(`Unauthorized domain: "${hostname}" is not authorized in Firebase Authentication. Add "${hostname}" in Firebase Console > Authentication > Settings > Authorized domains.`);
      } else {
        setLocalError(err.message || "Google sign-in failed.");
      }
    } finally {
      setSubmitting(false);
    }
  };

  const handleCheckClaims = async () => {
    setSubmitting(true);
    const hasAdmin = await refreshClaims();
    setSubmitting(false);
    if (hasAdmin) {
      router.replace("/overview");
    } else {
      setLocalError("Claims refreshed, but 'admin: true' custom claim is still absent.");
    }
  };

  return (
    <div className="min-h-screen w-screen flex items-center justify-center p-4 bg-jarvis-darkest relative overflow-hidden">
      {/* Background cyber grid */}
      <div className="absolute inset-0 bg-[radial-gradient(ellipse_at_top,_var(--tw-gradient-stops))] from-cyan-950/20 via-jarvis-darkest to-jarvis-darkest pointer-events-none" />
      <div className="absolute inset-0 bg-[linear-gradient(to_right,#1e293b15_1px,transparent_1px),linear-gradient(to_bottom,#1e293b15_1px,transparent_1px)] bg-[size:4rem_4rem] [mask-image:radial-gradient(ellipse_60%_50%_at_50%_50%,#000_70%,transparent_100%)] pointer-events-none" />

      <div className="w-full max-w-md relative z-10">
        {/* Brand Header */}
        <div className="text-center mb-8">
          <div className="inline-flex items-center justify-center w-14 h-14 rounded-2xl bg-cyan-950/60 border border-cyan-500/40 text-cyan-400 mb-4 shadow-[0_0_20px_rgba(0,240,255,0.25)]">
            <Cpu className="w-8 h-8 animate-pulse" />
          </div>
          <h1 className="text-xl font-bold tracking-wider text-slate-100 flex items-center justify-center gap-2">
            MAX <span className="text-cyan-400 font-mono">AI CONSOLE</span>
          </h1>
          <p className="text-xs font-mono text-slate-400 uppercase tracking-widest mt-1">
            Restricted Operational Command
          </p>
        </div>

        {/* Card */}
        <div className="glass-panel p-8 rounded-2xl border border-cyan-500/20 shadow-2xl relative">
          {(error || localError) && (
            <div className="mb-6 p-3.5 rounded-xl bg-rose-950/60 border border-rose-500/40 flex items-start gap-3 text-rose-300 text-xs">
              <ShieldAlert className="w-4 h-4 flex-shrink-0 mt-0.5 text-rose-400" />
              <div className="flex-1">
                <span className="font-semibold block mb-0.5">Access Denied</span>
                <span>{localError || error}</span>
              </div>
            </div>
          )}

          {user && !isAdmin && (
            <div className="mb-6 p-4 rounded-xl bg-amber-950/40 border border-amber-500/30 text-amber-300 text-xs space-y-2">
              <div className="font-semibold flex items-center gap-2">
                <ShieldAlert className="w-4 h-4 text-amber-400" />
                Missing Admin Claim
              </div>
              <p className="text-slate-300 text-[11px]">
                Authenticated as <code className="text-cyan-400 font-mono">{user.email || user.uid}</code>, but this account is not provisioned with administrative privileges.
              </p>
              <div className="pt-2">
                <button
                  onClick={handleCheckClaims}
                  disabled={submitting}
                  className="w-full py-2 px-3 rounded-lg bg-amber-900/40 border border-amber-500/40 text-amber-200 hover:bg-amber-900/60 text-xs font-mono flex items-center justify-center gap-2 transition-all"
                >
                  <RefreshCw className="w-3.5 h-3.5 animate-spin" />
                  Re-check Token Claims
                </button>
              </div>
            </div>
          )}

          <form onSubmit={handleSubmit} className="space-y-4">
            <div>
              <label className="block text-[11px] font-mono uppercase tracking-wider text-slate-400 mb-1.5">
                Admin Email
              </label>
              <div className="relative">
                <Mail className="w-4 h-4 text-slate-500 absolute left-3.5 top-3" />
                <input
                  type="email"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  placeholder="admin@max-ai.agent"
                  required
                  className="w-full bg-slate-900/80 border border-slate-800 rounded-xl py-2.5 pl-10 pr-4 text-xs text-slate-100 placeholder:text-slate-600 focus:outline-none focus:border-cyan-500/60 focus:ring-1 focus:ring-cyan-500/30 transition-all font-mono"
                />
              </div>
            </div>

            <div>
              <label className="block text-[11px] font-mono uppercase tracking-wider text-slate-400 mb-1.5">
                Passcode
              </label>
              <div className="relative">
                <Lock className="w-4 h-4 text-slate-500 absolute left-3.5 top-3" />
                <input
                  type="password"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  placeholder="••••••••••••"
                  required
                  className="w-full bg-slate-900/80 border border-slate-800 rounded-xl py-2.5 pl-10 pr-4 text-xs text-slate-100 placeholder:text-slate-600 focus:outline-none focus:border-cyan-500/60 focus:ring-1 focus:ring-cyan-500/30 transition-all font-mono"
                />
              </div>
            </div>

            <button
              type="submit"
              disabled={submitting}
              className="w-full mt-2 py-2.5 px-4 rounded-xl bg-gradient-to-r from-cyan-500 to-blue-600 text-slate-950 font-semibold text-xs tracking-wide uppercase hover:opacity-95 shadow-[0_0_15px_rgba(0,240,255,0.3)] transition-all flex items-center justify-center gap-2 disabled:opacity-50"
            >
              <span>Authenticate Session</span>
              <ArrowRight className="w-4 h-4" />
            </button>
          </form>

          <div className="relative my-6">
            <div className="absolute inset-0 flex items-center">
              <div className="w-full border-t border-slate-800"></div>
            </div>
            <div className="relative flex justify-center text-[10px] uppercase font-mono">
              <span className="bg-jarvis-card px-2 text-slate-500">Or Federated ID</span>
            </div>
          </div>

          <button
            type="button"
            onClick={handleGoogle}
            disabled={submitting}
            className="w-full py-2.5 px-4 rounded-xl bg-slate-900 border border-slate-800 hover:border-slate-700 text-slate-300 font-medium text-xs transition-all flex items-center justify-center gap-2.5 hover:bg-slate-800/80"
          >
            <svg
              width="18"
              height="18"
              viewBox="0 0 24 24"
              className="w-4 h-4 flex-shrink-0"
              style={{ minWidth: "16px", minHeight: "16px", width: "16px", height: "16px" }}
            >
              <path
                fill="#4285F4"
                d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92c-.26 1.37-1.04 2.53-2.21 3.31v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.09z"
              />
              <path
                fill="#34A853"
                d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z"
              />
              <path
                fill="#FBBC05"
                d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.06H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.94l2.85-2.22.81-.63z"
              />
              <path
                fill="#EA4335"
                d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.06l3.66 2.84c.87-2.6 3.3-4.52 6.16-4.52z"
              />
            </svg>
            <span>Sign in with Google Admin Account</span>
          </button>
        </div>

        {/* Security Notice */}
        <p className="text-center text-[11px] font-mono text-slate-400 mt-6">
          Privileged endpoint. All interactions are cryptographically attested and logged.
        </p>
      </div>
    </div>
  );
}
