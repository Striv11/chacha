# Chacha: your private assistant

Chacha lives on your **Mac or Windows laptop**, with an optional **Android phone**. It listens, remembers, catches follow-ups and helps get things done.
The AI runs **on your own laptop**. Your data never goes to an online AI unless you switch that on yourself.

---

## What you need

| | |
|---|---|
| Laptop | macOS or Windows 10/11, 8 GB memory or more (16 GB is better), **12 GB free space** |
| Phone | Optional. Android 8 or newer for phone notifications, calls, location and cross-app actions. |
| Internet | For the setup (it downloads about 6 GB once). After that Chacha works without it, as long as phone and laptop can reach each other. |
| Time | About 30 minutes, mostly waiting |

Chacha works while the laptop is **on**. If the laptop is off or asleep, the phone app can't think.

---

## Use Chacha in a browser

The private web dashboard works on macOS and Windows, with or without an Android phone.

1. Start Chacha, then open `http://<your-tailscale-ip>:8765` on the laptop.
2. Paste `BRAIN_TOKEN` from the local `.env` file when the dashboard asks to connect.
3. Use **Ask Chacha**, review source-backed follow-ups, prepare replies, manage tasks and approvals, inspect or correct Chacha's memory, or generate a private daily brief.

The token is stored only in that browser. Phone-only context—notifications, calls, contacts and location—stays empty until an Android phone is connected, but chat, local memory, agents, tasks, vision, voice and the web dashboard work without one.

### Web development

The dashboard source is in `web/` and the production bundle is served by FastAPI from `web/dist/`. Development requires Node.js 20.19 or newer.

```bash
cd web
npm install
npm run dev
```

Set `VITE_API_BASE=http://<your-tailscale-ip>:8765` for the Vite development server. Build the production bundle with `npm run build`, then restart Chacha.

Run the fast unit suite with `.venv/bin/python -m unittest discover -s tests -v`. To measure real local-model follow-up quality against the English/Hinglish product cases, run `.venv/bin/python evals/run_followups.py` while Ollama is running.

### Test phone automation in an Android emulator

On an Apple Silicon Mac, install the Android command-line tools and Java 17 once, then use the repo launcher:

```bash
brew install android-commandlinetools openjdk@17
./scripts/android-emulator.sh
```

The launcher downloads an Android 15 ARM image, creates and boots `chacha_api35`, builds the debug APK, installs it, connects it to the local backend through the Mac's Tailscale address, and enables Chacha's accessibility service. Emulator-only bootstrap credentials are accepted only by a debuggable APK and are never compiled into the app. Start with a safe task such as “Open Settings and show me the battery usage screen.” Chacha will stop before send, payment, purchase, booking, delete, or confirmation actions.

### Connect Gmail and Google Calendar

This is optional and keeps OAuth credentials and tokens inside the ignored `data/` directory.

1. In Google Cloud, enable the Gmail API and Google Calendar API for a project.
2. Configure its OAuth consent screen and create an OAuth client of type **Desktop app**.
3. Download the client JSON to `data/google_client.json`.
4. Open the dashboard → **Connections** → **Connect Google**, then finish Google's consent screen.
5. Select **Sync now** to import upcoming calendar events and check the inbox for follow-ups.

Chacha requests read access to Gmail and Calendar plus permission to send mail and create calendar events. It never sends an email without a separate user approval.

---

## macOS setup

1. Keep the Chacha folder somewhere permanent with at least 10 GB free.
2. Double-click **`Install Chacha.command`**. If macOS blocks it, right-click it and choose **Open** once.
3. The installer checks the Mac, installs local dependencies with Homebrew, downloads the private AI models and starts Chacha at login.
4. When the dashboard opens, paste `BRAIN_TOKEN` from the local `.env` file once.

Run `.venv/bin/python scripts/doctor.py` any time for a read-only setup health check. The macOS installer is safe to rerun and skips work that is already complete.

---

## Windows setup (one double-click)

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
5. At the end a page opens in your browser called **"Set up your phone"**. Follow the optional phone setup below.

## Optional Android phone setup

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
