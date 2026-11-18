import { FormEvent, useCallback, useEffect, useMemo, useState } from "react";
import {
  Activity,
  ArrowRight,
  Bot,
  Check,
  CheckCircle2,
  Circle,
  Clock3,
  Inbox,
  KeyRound,
  ListTodo,
  LoaderCircle,
  LockKeyhole,
  LogOut,
  MessageCircle,
  RefreshCw,
  Send,
  ShieldCheck,
  Sparkles,
  X,
} from "lucide-react";
import { api, ApiError } from "./lib/api";
import { cn } from "./lib/utils";
import { Badge } from "./components/ui/badge";
import { Button } from "./components/ui/button";
import { Card, CardContent, CardHeader } from "./components/ui/card";
import { Input } from "./components/ui/input";

type Task = { id: number; title: string; due: string; kind: string; status: string };
type Action = { id: number; kind: string; summary: string; payload: string };
type Notice = { id: number; ts: number; title: string; body: string; kind: string; seen: number };
type Agent = { id: number; goal: string; status: string; result?: string; question?: string; steps: number };
type Settings = { brain: string; claude_ready: boolean };
type AskResult = { id: number; status: string; result?: string; question?: string };
type Dashboard = { tasks: Task[]; actions: Action[]; notices: Notice[]; agents: Agent[]; settings: Settings };
type ChatMessage = { role: "user" | "assistant"; text: string };

const TOKEN_KEY = "chacha.brain-token";

function titleForToday() {
  const hour = new Date().getHours();
  if (hour < 12) return "Good morning";
  if (hour < 18) return "Good afternoon";
  return "Good evening";
}

function formatDate() {
  return new Intl.DateTimeFormat("en-IN", {
    weekday: "long",
    day: "numeric",
    month: "long",
  }).format(new Date());
}

function timeAgo(timestamp: number) {
  const seconds = Math.max(1, Math.floor(Date.now() / 1000 - timestamp));
  if (seconds < 60) return "just now";
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ago`;
  if (seconds < 86400) return `${Math.floor(seconds / 3600)}h ago`;
  return `${Math.floor(seconds / 86400)}d ago`;
}

function parsePayload(value: string) {
  try {
    return JSON.parse(value) as Record<string, unknown>;
  } catch {
    return {};
  }
}

function Connect({ onConnect }: { onConnect: (token: string) => Promise<void> }) {
  const [token, setToken] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError("");
    try {
      await onConnect(token.trim());
    } catch (err) {
      setError(err instanceof ApiError && err.status === 401 ? "That token does not match this Chacha brain." : "Chacha is not reachable right now.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="relative flex min-h-screen items-center justify-center overflow-hidden bg-[#f5f2ea] px-5 py-12 text-stone-950">
      <div className="absolute left-[-10rem] top-[-10rem] size-[28rem] rounded-full bg-orange-200/50 blur-3xl" />
      <div className="absolute bottom-[-14rem] right-[-10rem] size-[32rem] rounded-full bg-emerald-200/45 blur-3xl" />
      <section className="relative grid w-full max-w-5xl overflow-hidden rounded-[2rem] border border-white/80 bg-white/65 shadow-[0_30px_100px_rgba(41,37,36,.12)] backdrop-blur-xl md:grid-cols-[1.15fr_.85fr]">
        <div className="flex min-h-[34rem] flex-col justify-between bg-stone-950 p-8 text-stone-50 md:p-12">
          <div className="flex items-center gap-3 text-lg font-semibold">
            <span className="grid size-10 place-items-center rounded-full bg-orange-400 text-stone-950"><Sparkles size={19} /></span>
            Chacha
          </div>
          <div>
            <Badge className="mb-5 bg-white/10 text-orange-200">Private by default</Badge>
            <h1 className="max-w-lg text-4xl font-semibold leading-[1.05] tracking-[-.045em] md:text-6xl">Nothing important slips through.</h1>
            <p className="mt-5 max-w-md text-base leading-7 text-stone-300">Your private follow-through assistant is running on this Mac. Connect this browser to see what needs your attention.</p>
          </div>
          <div className="flex items-center gap-2 text-xs text-stone-400"><ShieldCheck size={15} /> The token stays in this browser.</div>
        </div>
        <form onSubmit={submit} className="flex flex-col justify-center p-8 md:p-12">
          <KeyRound className="mb-7 text-orange-500" size={28} />
          <h2 className="text-2xl font-semibold tracking-[-.03em]">Connect your brain</h2>
          <p className="mt-2 text-sm leading-6 text-stone-500">Paste the <code className="rounded bg-stone-100 px-1.5 py-0.5 text-xs">BRAIN_TOKEN</code> from your local <code className="rounded bg-stone-100 px-1.5 py-0.5 text-xs">.env</code> file.</p>
          <label className="mt-7 text-xs font-bold uppercase tracking-[.14em] text-stone-500" htmlFor="token">Private token</label>
          <Input id="token" type="password" autoFocus value={token} onChange={(event) => setToken(event.target.value)} className="mt-2" placeholder="Paste token" required />
          {error && <p className="mt-3 text-sm text-red-600">{error}</p>}
          <Button className="mt-5 w-full" disabled={busy || !token.trim()}>
            {busy ? <LoaderCircle className="animate-spin" size={16} /> : <LockKeyhole size={16} />}
            Connect securely
          </Button>
        </form>
      </section>
    </main>
  );
}

function Stat({ icon: Icon, label, value, tone }: { icon: typeof Inbox; label: string; value: number; tone: string }) {
  return (
    <Card className="flex min-h-32 flex-col justify-between p-5">
      <div className={cn("grid size-9 place-items-center rounded-full", tone)}><Icon size={17} /></div>
      <div className="mt-5 flex items-end justify-between">
        <span className="text-sm text-stone-500">{label}</span>
        <strong className="text-3xl font-semibold tracking-[-.05em]">{value}</strong>
      </div>
    </Card>
  );
}

function Empty({ children }: { children: string }) {
  return <div className="flex min-h-28 items-center justify-center rounded-2xl border border-dashed border-stone-200 bg-stone-50/60 px-6 text-center text-sm text-stone-400">{children}</div>;
}

function DashboardApp({ token, disconnect }: { token: string; disconnect: () => void }) {
  const [data, setData] = useState<Dashboard | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [chatOpen, setChatOpen] = useState(false);
  const [question, setQuestion] = useState("");
  const [asking, setAsking] = useState(false);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [brief, setBrief] = useState("");
  const [briefing, setBriefing] = useState(false);

  const load = useCallback(async () => {
    setError("");
    try {
      const [tasks, actions, notices, agents, settings] = await Promise.all([
        api<Task[]>("/tasks", token),
        api<Action[]>("/actions", token),
        api<Notice[]>("/notifications?since=0", token),
        api<Agent[]>("/agents", token),
        api<Settings>("/settings", token),
      ]);
      setData({ tasks, actions, notices, agents, settings });
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) disconnect();
      else setError("Could not refresh Chacha. The local brain may be restarting.");
    } finally {
      setLoading(false);
    }
  }, [disconnect, token]);

  useEffect(() => { void load(); }, [load]);

  const activeAgents = useMemo(() => data?.agents.filter((agent) => ["queued", "running", "waiting", "proposed"].includes(agent.status)) || [], [data]);
  const latestNotices = useMemo(() => [...(data?.notices || [])].sort((a, b) => b.id - a.id).slice(0, 6), [data]);

  async function done(task: Task) {
    await api(`/tasks/${task.id}/done`, token, { method: "POST", body: "{}" });
    setData((current) => current ? { ...current, tasks: current.tasks.filter((item) => item.id !== task.id) } : current);
  }

  async function reject(action: Action) {
    await api(`/actions/${action.id}/result`, token, { method: "POST", body: JSON.stringify({ status: "rejected", note: "Dismissed from web dashboard" }) });
    setData((current) => current ? { ...current, actions: current.actions.filter((item) => item.id !== action.id) } : current);
  }

  async function ask(event: FormEvent) {
    event.preventDefault();
    const text = question.trim();
    if (!text || asking) return;
    setMessages((current) => [...current, { role: "user", text }]);
    setQuestion("");
    setAsking(true);
    try {
      const answer = await api<AskResult>("/ask", token, { method: "POST", body: JSON.stringify({ text }) });
      setMessages((current) => [...current, { role: "assistant", text: answer.question || answer.result || "I finished, but there was no response." }]);
      await load();
    } catch (err) {
      setMessages((current) => [...current, { role: "assistant", text: err instanceof Error ? err.message : "Something went wrong." }]);
    } finally {
      setAsking(false);
    }
  }

  async function createBrief() {
    setBriefing(true);
    try {
      const result = await api<{ brief: string }>("/brief", token, { method: "POST", body: "{}" });
      setBrief(result.brief);
      await load();
    } finally {
      setBriefing(false);
    }
  }

  if (loading) {
    return <div className="grid min-h-screen place-items-center bg-[#f5f2ea]"><LoaderCircle className="animate-spin text-orange-500" /></div>;
  }

  return (
    <div className="min-h-screen bg-[#f5f2ea] text-stone-950">
      <header className="sticky top-0 z-20 border-b border-stone-200/70 bg-[#f5f2ea]/85 backdrop-blur-xl">
        <div className="mx-auto flex h-16 max-w-[1480px] items-center justify-between px-5 lg:px-8">
          <div className="flex items-center gap-8">
            <div className="flex items-center gap-2.5 font-semibold"><span className="grid size-8 place-items-center rounded-full bg-stone-950 text-orange-300"><Sparkles size={15} /></span>Chacha</div>
            <nav className="hidden items-center gap-1 md:flex">
              <button className="rounded-full bg-white px-4 py-2 text-sm font-semibold shadow-sm">Today</button>
              <button className="rounded-full px-4 py-2 text-sm text-stone-500 hover:bg-white/60" onClick={() => setChatOpen(true)}>Ask</button>
            </nav>
          </div>
          <div className="flex items-center gap-2">
            <div className="mr-2 hidden items-center gap-2 text-xs font-medium text-emerald-700 sm:flex"><span className="size-2 rounded-full bg-emerald-500 shadow-[0_0_0_4px_rgba(16,185,129,.12)]" />Local brain online</div>
            <Button variant="ghost" size="icon" aria-label="Refresh" onClick={() => void load()}><RefreshCw size={17} /></Button>
            <Button variant="ghost" size="icon" aria-label="Disconnect" onClick={disconnect}><LogOut size={17} /></Button>
          </div>
        </div>
      </header>

      <main className="mx-auto max-w-[1480px] px-5 py-9 lg:px-8 lg:py-12">
        <section className="mb-9 flex flex-col justify-between gap-6 md:flex-row md:items-end">
          <div>
            <p className="text-sm font-medium text-stone-500">{formatDate()}</p>
            <h1 className="mt-2 text-4xl font-semibold tracking-[-.045em] md:text-6xl">{titleForToday()}.</h1>
            <p className="mt-3 text-base text-stone-500">Here’s what deserves your attention.</p>
          </div>
          <Button onClick={() => setChatOpen(true)}><MessageCircle size={17} /> Ask Chacha</Button>
        </section>

        {error && <div className="mb-6 rounded-2xl border border-red-200 bg-red-50 px-5 py-4 text-sm text-red-700">{error}</div>}

        <section className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
          <Stat icon={ListTodo} label="Open tasks" value={data?.tasks.length || 0} tone="bg-orange-100 text-orange-700" />
          <Stat icon={Inbox} label="Pending approvals" value={data?.actions.length || 0} tone="bg-violet-100 text-violet-700" />
          <Stat icon={Bot} label="Agents working" value={activeAgents.length} tone="bg-sky-100 text-sky-700" />
          <Stat icon={Activity} label="Recent signals" value={latestNotices.length} tone="bg-emerald-100 text-emerald-700" />
        </section>

        <section className="mt-4 grid gap-4 xl:grid-cols-[1.2fr_.8fr]">
          <Card>
            <CardHeader>
              <div><Badge>Focus</Badge><h2 className="mt-3 text-xl font-semibold tracking-[-.025em]">What needs doing</h2></div>
              <span className="text-xs text-stone-400">{data?.tasks.length || 0} open</span>
            </CardHeader>
            <CardContent className="space-y-2">
              {!data?.tasks.length && <Empty>You’re clear. New commitments will show up here.</Empty>}
              {data?.tasks.map((task) => (
                <div key={task.id} className="group flex items-start gap-3 rounded-2xl border border-stone-100 bg-stone-50/70 p-4 transition hover:border-stone-200 hover:bg-white">
                  <button className="mt-0.5 text-stone-300 transition hover:text-emerald-600" aria-label={`Complete ${task.title}`} onClick={() => void done(task)}><Circle size={20} /></button>
                  <div className="min-w-0 flex-1">
                    <p className="font-medium leading-6">{task.title}</p>
                    <div className="mt-1.5 flex flex-wrap items-center gap-2 text-xs text-stone-400">
                      <span>{task.kind || "task"}</span>
                      {task.due && <><span>·</span><span className="flex items-center gap-1"><Clock3 size={12} />{task.due}</span></>}
                    </div>
                  </div>
                  <Button variant="ghost" size="sm" className="opacity-0 group-hover:opacity-100" onClick={() => void done(task)}><Check size={14} /> Done</Button>
                </div>
              ))}
            </CardContent>
          </Card>

          <Card className="overflow-hidden bg-stone-950 text-stone-50">
            <CardHeader>
              <div><Badge className="bg-white/10 text-orange-200">Daily brief</Badge><h2 className="mt-3 text-xl font-semibold tracking-[-.025em]">Your sharpest next move</h2></div>
              <Sparkles className="text-orange-300" size={20} />
            </CardHeader>
            <CardContent>
              {brief ? <p className="whitespace-pre-wrap text-sm leading-7 text-stone-300">{brief}</p> : <p className="text-sm leading-7 text-stone-400">Generate a private summary of your priorities, conversations, calendar and agent work.</p>}
              <Button className="mt-6 bg-orange-300 text-stone-950 hover:bg-orange-200" onClick={() => void createBrief()} disabled={briefing}>
                {briefing ? <LoaderCircle className="animate-spin" size={16} /> : <Sparkles size={16} />}
                {brief ? "Refresh brief" : "Generate today’s brief"}
              </Button>
            </CardContent>
          </Card>
        </section>

        <section className="mt-4 grid gap-4 lg:grid-cols-2">
          <Card>
            <CardHeader><div><Badge>Approvals</Badge><h2 className="mt-3 text-xl font-semibold tracking-[-.025em]">Ready for your call</h2></div></CardHeader>
            <CardContent className="space-y-3">
              {!data?.actions.length && <Empty>Nothing is waiting for approval.</Empty>}
              {data?.actions.map((action) => {
                const payload = parsePayload(action.payload);
                return (
                  <div key={action.id} className="rounded-2xl border border-stone-200 p-4">
                    <div className="flex items-start justify-between gap-3"><div><Badge className="bg-violet-50 text-violet-700">{action.kind}</Badge><p className="mt-2 font-medium">{action.summary}</p></div><Button variant="ghost" size="icon" onClick={() => void reject(action)} aria-label="Dismiss"><X size={16} /></Button></div>
                    {typeof payload.text === "string" && <p className="mt-3 rounded-xl bg-stone-50 p-3 text-sm leading-6 text-stone-600">{payload.text}</p>}
                    <p className="mt-3 text-xs text-stone-400">Final execution still requires your approval in the connected client.</p>
                  </div>
                );
              })}
            </CardContent>
          </Card>

          <Card>
            <CardHeader><div><Badge>Activity</Badge><h2 className="mt-3 text-xl font-semibold tracking-[-.025em]">What Chacha noticed</h2></div></CardHeader>
            <CardContent className="space-y-1">
              {!latestNotices.length && <Empty>No signals yet. Chacha is listening quietly.</Empty>}
              {latestNotices.map((notice) => (
                <div key={notice.id} className="flex gap-3 border-b border-stone-100 py-3.5 last:border-0">
                  <div className="mt-1 grid size-8 shrink-0 place-items-center rounded-full bg-stone-100"><CheckCircle2 size={15} className="text-stone-600" /></div>
                  <div className="min-w-0 flex-1"><div className="flex items-start justify-between gap-3"><p className="font-medium">{notice.title}</p><span className="shrink-0 text-[11px] text-stone-400">{timeAgo(notice.ts)}</span></div><p className="mt-1 line-clamp-2 text-sm leading-6 text-stone-500">{notice.body}</p></div>
                </div>
              ))}
            </CardContent>
          </Card>
        </section>

        <footer className="mt-8 flex flex-col justify-between gap-3 border-t border-stone-200 py-6 text-xs text-stone-400 sm:flex-row">
          <span className="flex items-center gap-1.5"><ShieldCheck size={14} /> Private data stays on this Mac in offline mode.</span>
          <span>Brain: {data?.settings.brain || "offline"}</span>
        </footer>
      </main>

      {chatOpen && (
        <div className="fixed inset-0 z-50 bg-stone-950/20 backdrop-blur-[2px]" onMouseDown={() => setChatOpen(false)}>
          <aside className="ml-auto flex h-full w-full max-w-xl flex-col bg-[#fbfaf7] shadow-2xl" onMouseDown={(event) => event.stopPropagation()}>
            <header className="flex h-16 items-center justify-between border-b border-stone-200 px-5">
              <div><p className="font-semibold">Ask Chacha</p><p className="text-xs text-stone-400">Private conversation with your local brain</p></div>
              <Button variant="ghost" size="icon" onClick={() => setChatOpen(false)}><X size={18} /></Button>
            </header>
            <div className="flex-1 space-y-4 overflow-y-auto p-5">
              {!messages.length && (
                <div className="mt-16 text-center"><span className="mx-auto grid size-12 place-items-center rounded-full bg-orange-100 text-orange-600"><Sparkles size={20} /></span><h3 className="mt-4 font-semibold">What’s on your mind?</h3><p className="mx-auto mt-2 max-w-sm text-sm leading-6 text-stone-500">Ask about your tasks, request a draft, or tell Chacha something worth remembering.</p></div>
              )}
              {messages.map((message, index) => <div key={index} className={cn("max-w-[86%] rounded-2xl px-4 py-3 text-sm leading-6", message.role === "user" ? "ml-auto bg-stone-950 text-white" : "bg-white shadow-sm")}>{message.text}</div>)}
              {asking && <div className="flex items-center gap-2 text-sm text-stone-400"><LoaderCircle className="animate-spin" size={15} /> Chacha is thinking…</div>}
            </div>
            <form onSubmit={ask} className="border-t border-stone-200 bg-white p-4">
              <div className="flex items-center gap-2 rounded-2xl border border-stone-200 bg-stone-50 p-2 pl-4 focus-within:border-stone-400">
                <input className="h-10 min-w-0 flex-1 bg-transparent text-sm outline-none" value={question} onChange={(event) => setQuestion(event.target.value)} placeholder="Message Chacha…" />
                <Button size="icon" disabled={!question.trim() || asking} aria-label="Send"><Send size={16} /></Button>
              </div>
            </form>
          </aside>
        </div>
      )}
    </div>
  );
}

export default function App() {
  const [token, setToken] = useState(() => localStorage.getItem(TOKEN_KEY) || "");

  async function connect(value: string) {
    await api<Settings>("/settings", value);
    localStorage.setItem(TOKEN_KEY, value);
    setToken(value);
  }

  const disconnect = useCallback(() => {
    localStorage.removeItem(TOKEN_KEY);
    setToken("");
  }, []);

  return token ? <DashboardApp token={token} disconnect={disconnect} /> : <Connect onConnect={connect} />;
}
