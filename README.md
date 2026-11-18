# Chacha: your private assistant

Chacha lives on your **Windows laptop** and your **Android phone**. It listens, reads your screen when you ask, remembers things and does them for you.
The AI runs **on your own laptop**. Your data never goes to an online AI unless you switch that on yourself.

---

## What you need

| | |
|---|---|
| Laptop | Windows 10 or 11, 8 GB memory or more (16 GB is better), **12 GB free space** |
| Phone | Android 8 or newer |
| Internet | For the setup (it downloads about 6 GB once). After that Chacha works without it, as long as phone and laptop can reach each other. |
| Time | About 30 minutes, mostly waiting |

Chacha works while the laptop is **on**. If the laptop is off or asleep, the phone app can't think.

---

## Use Chacha in a browser

The private web dashboard works on macOS and Windows, with or without an Android phone.

1. Start Chacha, then open `http://<your-tailscale-ip>:8765` on the laptop.
2. Paste `BRAIN_TOKEN` from the local `.env` file when the dashboard asks to connect.
3. Use **Ask Chacha**, review tasks and approvals, or generate a private daily brief.

The token is stored only in that browser. Phone-only context—notifications, calls, contacts and location—stays empty until an Android phone is connected, but chat, local memory, agents, tasks, vision, voice and the web dashboard work without one.

### Web development

The dashboard source is in `web/` and the production bundle is served by FastAPI from `web/dist/`. Development requires Node.js 20.19 or newer.

```bash
cd web
npm install
npm run dev
```

Set `VITE_API_BASE=http://<your-tailscale-ip>:8765` for the Vite development server. Build the production bundle with `npm run build`, then restart Chacha.

---

## Part 1: The laptop (one double-click)

1. **Unzip** the Chacha folder somewhere you'll keep it, for example `Documents\Chacha`. (Right-click the zip → *Extract All*.) Don't run it from inside the zip.
2. Double-click **`Install Chacha`**.
   - If Windows shows a blue box *"Windows protected your PC"*: click **More info** → **Run anyway**.
3. A black window walks through **7 steps**. Just let it run. It will:
   - check the laptop is big enough
   - install Python, Ollama (the private AI) and Tailscale (the private link to your phone)
   - download the AI (a few GB, with a progress bar)
4. **Two moments need you:**
   - **Tailscale sign-in**: a browser opens. Sign in with Google, Microsoft or Apple. **Remember which account**, because you'll use the same one on your phone.
   - **"Do you want to allow this app to make changes?"**: click **Yes**. This lets only your own phone reach Chacha.
5. At the end a page opens in your browser called **"Set up your phone"**. Go to Part 2.

## Part 2: The phone (follow the page)

The page has 3 steps, each with a code you scan:

1. **Install Tailscale.** Scan with the phone camera, install it, and sign in with the **same account** as the laptop. Allow the VPN. It should say *Connected*.
2. **Install Chacha.** Scan with the camera. The app downloads straight from your laptop.
   - *"File might be harmful"* → **Download anyway**
   - *"Not allowed to install unknown apps"* → **Settings** → turn on **Allow from this source** → back
   - Play Protect → **Install anyway**
3. **Connect.** Open Chacha → **Get started** → **Scan the code**, scanning from **inside Chacha**. Then allow the permissions it asks for, one screen at a time.
   - *"Restricted setting"*: Settings → Apps → Chacha → **⋮** (top right) → **Allow restricted settings**, then try again.

You're done when Chacha says **"You're all set"** and a small ring floats on your screen.
**Tap the ring** to ask anything. **Hold it** to talk.

Need the phone page again later? Double-click **Chacha - Connect phone** on the desktop.

---

## If something goes wrong

| Problem | Fix |
|---|---|
| The installer stopped with a red message | Just double-click **Install Chacha** again. It continues where it stopped. |
| "Your phone can't reach your laptop" | Laptop on and awake? Tailscale *Connected* on both? Same account on both? Still stuck: run **Install Chacha** again and click **Yes**. |
| The download code (step 2) does nothing | Turn Tailscale on in the phone, then scan again. |
| Reminders stop / the ring disappears | Settings → Apps → Chacha → Battery → **Unrestricted**. On Samsung also remove Chacha from *Sleeping apps*. |
| Chacha is slow | Normal on laptops with 8 GB. Close big programs (games, many Chrome tabs). |

Still stuck? Send the file **`data\install_log.txt`** from the Chacha folder to the person who gave you Chacha.

## Privacy

- The AI runs on your laptop. No online AI is used unless you turn it on in settings.
- Phone and laptop talk over Tailscale, which is encrypted and private to your account.
- **Banking and payment apps are never read.**
- Nothing is sent, posted or bought without your OK.

## Remove Chacha

Delete the Chacha folder, and delete `Chacha.vbs` from `shell:startup` (type that into the Windows Run box: Win + R). Uninstall Tailscale and Ollama from *Settings → Apps* if you don't use them for anything else. On the phone, uninstall the Chacha app.
