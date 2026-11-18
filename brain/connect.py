"""Opens a step-by-step page in the browser that sets up the phone: Tailscale, the Chacha app, then the
connect code. The connect code holds the secret token, so it is never printed, and the page is deleted
as soon as you're done."""
import html, json, os, subprocess, shutil, time
import segno
from .config import BRAIN_TOKEN, DATA

TAILSCALE_PLAY = "https://play.google.com/store/apps/details?id=com.tailscale.ipn"


def tailscale_ip():
    exe = shutil.which("tailscale") or "C:/Program Files/Tailscale/tailscale.exe"
    try:
        found = subprocess.run([exe, "ip", "-4"], capture_output=True, text=True, timeout=10).stdout.split()
    except (OSError, subprocess.SubprocessError):
        return None
    return found[0] if found else None


def tailscale_account():
    exe = shutil.which("tailscale") or "C:/Program Files/Tailscale/tailscale.exe"
    try:
        st = json.loads(subprocess.run([exe, "status", "--json"], capture_output=True, text=True, timeout=10).stdout)
        uid = str(st["Self"]["UserID"])
        return st["User"][uid]["LoginName"]
    except Exception:
        return None


def qr(data):
    return segno.make(data, error="m").svg_data_uri(scale=7, border=2, dark="#0b0b10", light="#ffffff")


PAGE = """<!doctype html><html><head><meta charset="utf-8"><title>Set up your phone - Chacha</title>
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>
body{margin:0;background:#0b0b10;color:#f4f4f6;font:17px/1.55 "Segoe UI",system-ui,sans-serif}
main{max-width:980px;margin:0 auto;padding:40px 24px 80px}
h1{font-size:38px;font-weight:600;margin:0 0 6px;letter-spacing:-.5px}
.lead{color:#a3a3b2;margin:0 0 34px;font-size:18px}
.step{display:grid;grid-template-columns:1fr 250px;gap:34px;background:#15151d;border:1px solid #262633;border-radius:22px;padding:30px 32px;margin-bottom:22px}
.n{display:inline-block;width:34px;height:34px;border-radius:50%;background:#8b7cff;color:#0b0b10;font-weight:700;text-align:center;line-height:34px;margin-right:10px}
h2{font-size:24px;margin:0 0 14px;font-weight:600}
ol{margin:0;padding-left:22px}li{margin:7px 0}
b{color:#fff}.mute{color:#a3a3b2;font-size:15px}
.qr{background:#fff;border-radius:16px;padding:12px;text-align:center;align-self:start}
.qr img{width:100%;display:block}.qr div{color:#0b0b10;font-size:13px;font-weight:600;margin-top:4px}
.tip{background:#1f1b33;border-left:4px solid #8b7cff;border-radius:10px;padding:12px 16px;margin-top:14px;font-size:15px}
.warn{background:#2b2312;border-left-color:#ffc86b}
.acct{font-family:Consolas,monospace;background:#262633;padding:2px 8px;border-radius:6px}
details{background:#15151d;border:1px solid #262633;border-radius:14px;padding:14px 20px;margin-top:10px}
summary{cursor:pointer;font-weight:600}
@media(max-width:760px){.step{grid-template-columns:1fr}}
</style></head><body><main>
<h1>Set up your phone</h1>
<p class="lead">Three steps, about 5 minutes. Keep this page open and your phone in your hand.<br>
Works with Android phones.</p>

<section class="step"><div>
<h2><span class="n">1</span>Install Tailscale on your phone</h2>
<p class="mute">Tailscale is the private, encrypted link between your phone and this laptop. It works anywhere, even away from home.</p>
<ol>
<li>Open your phone's <b>camera</b> and point it at the code on the right. Tap the link that pops up.<br><span class="mute">(No link? Open the Play Store and search "Tailscale".)</span></li>
<li>Tap <b>Install</b>, then <b>Open</b>.</li>
<li>Tap <b>Get started</b> and sign in with the <b>same account</b> you used on this laptop:<br>__ACCOUNT__</li>
<li>When it asks to set up a VPN connection, tap <b>OK</b>. Make sure the switch at the top says <b>Connected</b>.</li>
</ol>
<div class="tip">Leave Tailscale switched on. Chacha needs it to talk to this laptop.</div>
</div><div class="qr"><img src="__QR_TS__" alt=""><div>Tailscale on Play Store</div></div></section>

<section class="step"><div>
<h2><span class="n">2</span>Install the Chacha app</h2>
<p class="mute">Chacha isn't on the Play Store yet, so your phone downloads it straight from this laptop.</p>
<ol>
<li>Point your phone's <b>camera</b> at the code on the right and tap the link. <span class="mute">(Tailscale from step 1 must be on.)</span></li>
<li>The download starts. If Chrome warns <b>"File might be harmful"</b>, tap <b>Download anyway</b>.</li>
<li>Tap the download when it finishes (or open <b>Files &rarr; Downloads &rarr; Chacha.apk</b>).</li>
<li>If your phone says <b>"For your security, your phone is not allowed to install unknown apps"</b>: tap <b>Settings</b>, turn on <b>Allow from this source</b>, then press back.</li>
<li>Tap <b>Install</b>. If Play Protect asks to scan the app, tap <b>Scan app</b> or <b>Install anyway</b>.</li>
</ol>
<div class="tip warn">Nothing happens when you open the link? Check Tailscale says Connected on the phone, then try again.</div>
</div><div class="qr"><img src="__QR_APP__" alt=""><div>Download Chacha</div></div></section>

<section class="step"><div>
<h2><span class="n">3</span>Connect Chacha to this laptop</h2>
<ol>
<li>Open the <b>Chacha</b> app and tap <b>Get started</b>.</li>
<li>Tap <b>Scan the code</b> and point the phone at the code on the right.<br><span class="mute">Scan it from <b>inside the Chacha app</b>, not with the camera.</span></li>
<li>Chacha then asks for a few permissions, one screen at a time. Each screen says why. Tap <b>Allow</b> for each, or <b>Not now</b> to skip.</li>
</ol>
<div class="tip">For "Help inside your apps" and "Keep up with your messages", the phone opens Settings: find <b>Chacha</b> in the list, switch it on, then press back.<br>
If it says <b>"Restricted setting"</b>: open Settings &rarr; Apps &rarr; Chacha, tap the <b>&#8942;</b> menu (top right) &rarr; <b>Allow restricted settings</b>, then try again.</div>
<div class="tip warn">This code is your private key. Don't share or photograph it. This page deletes itself when you press Enter in the black window.</div>
</div><div class="qr"><img src="__QR_KEY__" alt=""><div>Scan inside Chacha</div></div></section>

<h2 style="margin-top:38px">You're done when&hellip;</h2>
<p>Chacha shows <b>"You're all set"</b>. A small ring floats on your screen: <b>tap it to ask anything</b>, hold it to talk.</p>

<h2 style="margin-top:38px">Something not working?</h2>
<details><summary>"Your phone can't reach your laptop"</summary><ol>
<li>Is the laptop on and awake? Chacha only works while the laptop is on (it can be closed if it doesn't sleep).</li>
<li>Is Tailscale <b>Connected</b> on both the phone and the laptop (icon near the clock)?</li>
<li>Are both signed in to the <b>same</b> Tailscale account?</li>
<li>Still stuck: double-click <b>Install Chacha</b> on the laptop again and click <b>Yes</b> when Windows asks.</li></ol></details>
<details><summary>The download link (step 2) doesn't open</summary><p>Tailscale on the phone must say Connected. Then close Chrome and scan again. Chacha must be running on the laptop: restart the laptop once if you've just installed it.</p></details>
<details><summary>Chacha stops reminding me / the ring disappears</summary><p>Settings &rarr; Apps &rarr; Chacha &rarr; Battery &rarr; <b>Unrestricted</b>. On Samsung also: Settings &rarr; Battery &rarr; Background usage limits &rarr; remove Chacha from "Sleeping apps".</p></details>
<details><summary>Need this page again?</summary><p>Double-click <b>Chacha - Connect phone</b> on the laptop's desktop.</p></details>
<details><summary>Is my data private?</summary><p>Yes. Everything stays on this laptop and your phone. The AI runs on this laptop. Banking apps are never read, and nothing is sent anywhere without your OK.</p></details>
</main></body></html>"""


def main():
    ip = tailscale_ip()
    if not ip:
        raise SystemExit("Tailscale isn't on. Click the Tailscale icon near the clock, sign in, then try again.")
    if not BRAIN_TOKEN:
        raise SystemExit("Chacha isn't set up on this laptop yet. Double-click 'Install Chacha' first.")
    acct = tailscale_account()
    page = (PAGE
            .replace("__ACCOUNT__", f'<span class="acct">{html.escape(acct)}</span>' if acct else "<b>the account you signed in with</b>")
            .replace("__QR_TS__", qr(TAILSCALE_PLAY))
            .replace("__QR_APP__", qr(f"http://{ip}:8765/app"))
            .replace("__QR_KEY__", qr(json.dumps({"url": f"http://{ip}:8765", "token": BRAIN_TOKEN}))))
    path = DATA / "connect_phone.html"
    path.write_text(page, encoding="utf8")
    try:
        os.startfile(path)
        print("\nA page with 3 steps has opened in your browser. Follow it with your phone.")
        input("When Chacha on your phone says \"You're all set\", press Enter here to close.")
    finally:
        time.sleep(0.5)
        path.unlink(missing_ok=True)


if __name__ == "__main__":
    main()
