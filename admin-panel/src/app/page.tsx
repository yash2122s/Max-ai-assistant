"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth-context";
import { LoadingSpinner } from "@/components/LoadingSpinner";

export default function RootPage() {
  const router = useRouter();
  const { user, isAdmin, isLoading } = useAuth();

  useEffect(() => {
    if (!isLoading) {
      if (user && isAdmin) {
        router.replace("/overview");
      } else {
        router.replace("/login");
      }
    }
  }, [user, isAdmin, isLoading, router]);

  return (
    <div className="h-screen w-screen flex items-center justify-center bg-jarvis-darkest">
      <LoadingSpinner label="Initializing MAX Console..." size="lg" />
    </div>
  );
}
