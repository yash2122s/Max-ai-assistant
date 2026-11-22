"use client";

import React, { useEffect, useState } from "react";
import {
  Users as UsersIcon,
  Search,
  Filter,
  ExternalLink,
  Copy,
  Check,
  ChevronLeft,
  ChevronRight
} from "lucide-react";
import { Header } from "@/components/Header";
import { StatusBadge } from "@/components/StatusBadge";
import { LoadingSpinner } from "@/components/LoadingSpinner";
import { UserDetailModal } from "@/components/UserDetailModal";
import { getUsers, UserItem } from "@/lib/api";
import { formatNumber, formatTimestamp, truncateUid } from "@/lib/utils";

export default function UsersPage() {
  const [loading, setLoading] = useState(true);
  const [users, setUsers] = useState<UserItem[]>([]);
  const [searchQuery, setSearchQuery] = useState("");
  const [filterPlan, setFilterPlan] = useState("ALL");
  const [selectedUserId, setSelectedUserId] = useState<string | null>(null);
  const [copiedUid, setCopiedUid] = useState<string | null>(null);
  const [refreshing, setRefreshing] = useState(false);

  const loadUsers = async () => {
    try {
      const res = await getUsers({
        searchQuery: searchQuery || undefined,
        filterPlan: filterPlan !== "ALL" ? filterPlan : undefined,
      });
      setUsers(res.users);
    } catch (err) {
      console.error("Failed to load users", err);
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  };

  useEffect(() => {
    loadUsers();
  }, [filterPlan]);

  const handleSearchSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    setLoading(true);
    loadUsers();
  };

  const handleCopy = (uid: string) => {
    navigator.clipboard.writeText(uid);
    setCopiedUid(uid);
    setTimeout(() => setCopiedUid(null), 2000);
  };

  return (
    <>
      <Header
        title="User Management"
        subtitle="Registered clients, license status, and per-user diagnostic history"
        onRefresh={() => {
          setRefreshing(true);
          loadUsers();
        }}
        isRefreshing={refreshing}
      />

      <main className="p-8 space-y-6">
        {/* Search & Filter Toolbar */}
        <div className="flex flex-col sm:flex-row gap-4 items-center justify-between">
          <form onSubmit={handleSearchSubmit} className="relative w-full sm:w-96">
            <Search className="w-4 h-4 text-slate-500 absolute left-3.5 top-3" />
            <input
              type="text"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              placeholder="Search by UID..."
              className="w-full bg-slate-900/80 border border-slate-800 rounded-xl py-2 pl-10 pr-4 text-xs text-slate-100 placeholder:text-slate-500 focus:outline-none focus:border-cyan-500/60 font-mono"
            />
          </form>

          <div className="flex items-center gap-3 w-full sm:w-auto">
            <div className="flex items-center gap-2 text-xs text-slate-400 font-mono">
              <Filter className="w-3.5 h-3.5 text-cyan-400" />
              <span>Plan:</span>
            </div>
            <select
              value={filterPlan}
              onChange={(e) => setFilterPlan(e.target.value)}
              className="bg-slate-900 border border-slate-800 rounded-xl py-2 px-3 text-xs text-slate-200 font-mono focus:outline-none focus:border-cyan-500"
            >
              <option value="ALL">All Plans</option>
              <option value="STANDARD">Standard</option>
              <option value="PRO">Pro</option>
              <option value="LIFETIME">Lifetime</option>
            </select>
          </div>
        </div>

        {/* Users Table */}
        <div className="glass-panel rounded-2xl border border-jarvis-border overflow-hidden">
          {loading ? (
            <LoadingSpinner label="Fetching user records..." />
          ) : users.length === 0 ? (
            <div className="p-12 text-center text-slate-500 font-mono text-xs">
              No registered user records match the given criteria.
            </div>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-left text-xs">
                <thead className="bg-slate-900/80 text-[10px] font-mono uppercase text-slate-400 border-b border-jarvis-border">
                  <tr>
                    <th className="py-3 px-4">User UID</th>
                    <th className="py-3 px-4">Plan & Status</th>
                    <th className="py-3 px-4">App Version</th>
                    <th className="py-3 px-4">Last Feature</th>
                    <th className="py-3 px-4 text-right">AI Requests</th>
                    <th className="py-3 px-4 text-right">Tools</th>
                    <th className="py-3 px-4">Last Active</th>
                    <th className="py-3 px-4 text-center">Diagnostics</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-jarvis-border font-mono text-[11px] text-slate-300">
                  {users.map((u) => (
                    <tr key={u.uid} className="hover:bg-slate-900/50 transition-colors">
                      <td className="py-3 px-4">
                        <div className="flex items-center gap-2">
                          <span className="text-slate-100 font-medium">{truncateUid(u.uid, 14)}</span>
                          <button
                            onClick={() => handleCopy(u.uid)}
                            className="p-1 rounded hover:bg-slate-800 text-slate-500 hover:text-slate-300 transition-colors"
                            title="Copy full UID"
                          >
                            {copiedUid === u.uid ? (
                              <Check className="w-3 h-3 text-emerald-400" />
                            ) : (
                              <Copy className="w-3 h-3" />
                            )}
                          </button>
                        </div>
                      </td>
                      <td className="py-3 px-4">
                        <div className="flex items-center gap-1.5">
                          <StatusBadge status={u.licensePlan || "STANDARD"} type="plan" />
                          <StatusBadge status={u.licenseStatus || "ACTIVE"} type="status" />
                        </div>
                      </td>
                      <td className="py-3 px-4 text-slate-400">{u.appVersion}</td>
                      <td className="py-3 px-4 text-cyan-400 font-semibold">{u.lastFeatureUsed}</td>
                      <td className="py-3 px-4 text-right text-slate-200 font-bold">{formatNumber(u.totalAiRequests)}</td>
                      <td className="py-3 px-4 text-right text-slate-200">{formatNumber(u.totalToolExecutions)}</td>
                      <td className="py-3 px-4 text-slate-400">{formatTimestamp(u.lastActiveAt)}</td>
                      <td className="py-3 px-4 text-center">
                        <button
                          onClick={() => setSelectedUserId(u.uid)}
                          className="px-2.5 py-1 rounded-lg bg-cyan-950/60 border border-cyan-500/30 text-cyan-300 hover:bg-cyan-900 hover:text-cyan-200 text-[11px] font-medium transition-all inline-flex items-center gap-1.5"
                        >
                          <span>Inspect</span>
                          <ExternalLink className="w-3 h-3" />
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>

        {/* Modal */}
        <UserDetailModal
          userId={selectedUserId}
          onClose={() => setSelectedUserId(null)}
        />
      </main>
    </>
  );
}
