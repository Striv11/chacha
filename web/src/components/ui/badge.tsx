import * as React from "react";
import { cn } from "../../lib/utils";

export function Badge({ className, ...props }: React.HTMLAttributes<HTMLSpanElement>) {
  return <span className={cn("inline-flex items-center rounded-full bg-stone-100 px-2.5 py-1 text-[11px] font-bold uppercase tracking-[.12em] text-stone-600", className)} {...props} />;
}
