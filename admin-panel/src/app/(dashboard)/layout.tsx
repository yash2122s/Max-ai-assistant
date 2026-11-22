"use client";

import React, { useEffect } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth-context";
import { Sidebar } from "@/components/Sidebar";
import { LoadingSpinner } from "@/components/LoadingSpinner";

export default function DashboardLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  const router = useRouter();
  const { user, isAdmin, isLoading } = useAuth();

  useEffect(() => {
    if (!isLoading) {
      if (!user || !isAdmin) {
        router.replace("/login");
      }
    }
  }, [user, isAdmin, isLoading, router]);

  if (isLoading) {
    return (
      <div className="h-screen w-screen flex items-center justify-center bg-jarvis-darkest">
        <LoadingSpinner label="Authenticating administrative session..." size="lg" />
      </div>
    );
  }

  if (!user || !isAdmin) {
    return (
      <div className="h-screen w-screen flex items-center justify-center bg-jarvis-darkest">
        <LoadingSpinner label="Redirecting to secure login..." size="sm" />
      </div>
    );
  }

  return (
    <div className="flex min-h-screen bg-jarvis-darkest text-slate-100">
      <Sidebar />
      <div className="flex-1 flex flex-col min-w-0 overflow-y-auto">
        {children}
      </div>
    </div>
  );
}
