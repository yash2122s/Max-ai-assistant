"use client";

import React from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import {
  LayoutDashboard,
  Users,
  Activity,
  Layers,
  AlertTriangle,
  Download,
  LogOut,
  ShieldCheck,
  Cpu
} from "lucide-react";
import { useAuth } from "@/lib/auth-context";
import { cn, truncateUid } from "@/lib/utils";

const NAV_ITEMS = [
  { name: "Overview", href: "/overview", icon: LayoutDashboard },
  { name: "User Management", href: "/users", icon: Users },
  { name: "Live Activity", href: "/activity", icon: Activity },
  { name: "Feature Analytics", href: "/features", icon: Layers },
  { name: "Errors & Latency", href: "/errors", icon: AlertTriangle },
  { name: "Export Reports", href: "/export", icon: Download },
];

export function Sidebar() {
  const pathname = usePathname();
  const { user, signOut } = useAuth();

  return (
    <aside className="w-64 bg-jarvis-dark border-r border-jarvis-border flex flex-col h-screen select-none sticky top-0 z-30">
      {/* Brand Header */}
      <div className="h-16 flex items-center px-6 border-b border-jarvis-border gap-3 bg-jarvis-darkest/40">
        <div className="relative flex items-center justify-center w-8 h-8 rounded-lg bg-cyan-950/60 border border-cyan-500/40 text-cyan-400">
          <Cpu className="w-5 h-5 animate-pulse" />
          <span className="absolute -top-1 -right-1 flex h-2.5 w-2.5">
            <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-cyan-400 opacity-75"></span>
            <span className="relative inline-flex rounded-full h-2.5 w-2.5 bg-cyan-500"></span>
          </span>
        </div>
        <div>
          <div className="font-bold text-sm tracking-wider text-slate-100 flex items-center gap-1.5">
            MAX <span className="text-cyan-400 font-mono text-xs">AI CORE</span>
          </div>
          <div className="text-[10px] uppercase font-mono tracking-widest text-slate-400">
            Admin Console v2.0
          </div>
        </div>
      </div>

      {/* Navigation */}
      <nav className="flex-1 px-3 py-4 space-y-1 overflow-y-auto">
        <div className="px-3 pb-2 text-[10px] font-mono uppercase tracking-wider text-slate-400 font-semibold">
          Analytics & Control
        </div>
        {NAV_ITEMS.map((item) => {
          const isActive = pathname === item.href;
          const Icon = item.icon;
          return (
            <Link
              key={item.href}
              href={item.href}
              className={cn(
                "flex items-center gap-3 px-3.5 py-2.5 rounded-lg text-xs font-medium transition-all group",
                isActive
                  ? "bg-cyan-950/40 text-cyan-400 border border-cyan-500/30 shadow-[0_0_12px_rgba(0,240,255,0.1)]"
                  : "text-slate-400 hover:text-slate-200 hover:bg-slate-800/40"
              )}
            >
              <Icon
                className={cn(
                  "w-4 h-4 transition-colors",
                  isActive ? "text-cyan-400" : "text-slate-400 group-hover:text-slate-200"
                )}
              />
              <span>{item.name}</span>
            </Link>
          );
        })}
      </nav>

      {/* User Section & Sign Out */}
      <div className="p-3 border-t border-jarvis-border bg-jarvis-darkest/60">
        <div className="flex items-center gap-2.5 p-2 rounded-lg bg-slate-900/60 border border-slate-800/80 mb-2">
          <div className="w-8 h-8 rounded-full bg-slate-800 border border-cyan-500/20 flex items-center justify-center text-cyan-400 font-mono text-xs">
            <ShieldCheck className="w-4 h-4" />
          </div>
          <div className="flex-1 min-w-0">
            <div className="text-xs font-medium text-slate-200 truncate">
              {user?.email || (user ? truncateUid(user.uid) : "Admin")}
            </div>
            <div className="text-[10px] font-mono text-cyan-400/80 flex items-center gap-1">
              <span className="w-1.5 h-1.5 rounded-full bg-cyan-400"></span>
              Superadmin
            </div>
          </div>
        </div>

        <button
          onClick={() => signOut()}
          className="w-full flex items-center justify-center gap-2 px-3 py-2 text-xs font-medium text-rose-400/90 hover:text-rose-300 hover:bg-rose-950/20 rounded-lg transition-colors border border-transparent hover:border-rose-900/40"
        >
          <LogOut className="w-3.5 h-3.5" />
          <span>Disconnect Console</span>
        </button>
      </div>
    </aside>
  );
}
