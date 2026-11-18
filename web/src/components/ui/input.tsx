import * as React from "react";
import { cn } from "../../lib/utils";

export function Input({ className, ...props }: React.InputHTMLAttributes<HTMLInputElement>) {
  return <input className={cn("h-11 w-full rounded-xl border border-stone-300 bg-white px-3.5 text-sm outline-none transition focus:border-stone-500 focus:ring-4 focus:ring-stone-900/5", className)} {...props} />;
}
