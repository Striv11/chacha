import * as React from "react";
import { Slot } from "@radix-ui/react-slot";
import { cva, type VariantProps } from "class-variance-authority";
import { cn } from "../../lib/utils";

const buttonVariants = cva(
  "inline-flex items-center justify-center gap-2 rounded-full text-sm font-semibold transition-all outline-none disabled:pointer-events-none disabled:opacity-50 focus-visible:ring-2 focus-visible:ring-stone-900/20",
  {
    variants: {
      variant: {
        default: "bg-stone-950 px-5 py-2.5 text-stone-50 hover:-translate-y-0.5 hover:bg-stone-800",
        outline: "border border-stone-300 bg-white/70 px-4 py-2 text-stone-800 hover:border-stone-500 hover:bg-white",
        ghost: "px-3 py-2 text-stone-600 hover:bg-stone-200/70 hover:text-stone-950",
        danger: "bg-red-50 px-4 py-2 text-red-700 hover:bg-red-100",
      },
      size: {
        default: "h-10",
        sm: "h-8 text-xs",
        icon: "size-10 p-0",
      },
    },
    defaultVariants: { variant: "default", size: "default" },
  },
);

export interface ButtonProps
  extends React.ButtonHTMLAttributes<HTMLButtonElement>,
    VariantProps<typeof buttonVariants> {
  asChild?: boolean;
}

export function Button({ className, variant, size, asChild = false, ...props }: ButtonProps) {
  const Comp = asChild ? Slot : "button";
  return <Comp className={cn(buttonVariants({ variant, size, className }))} {...props} />;
}
