#!/usr/bin/env python3
"""Renders the README / store mockups from raw device screenshots.

Usage:  python3 scripts/make_mockups.py
Input:  docs/screenshots/raw/*.webp  (720x1612 captures, demo-mode status bar, no personal content)
Output: docs/screenshots/*.jpg       (feature cards + banner)
Needs Google Chrome (headless) and ImageMagick.
"""
import os, subprocess, tempfile, pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
RAW = ROOT / "docs/screenshots/raw"
OUT = ROOT / "docs/screenshots"
FONT = ROOT / "core/designsystem/src/main/res/font"
CHROME = "google-chrome"

CSS = f"""
@font-face {{ font-family: J; src: url('file://{FONT}/jakarta_500.ttf'); font-weight: 500; }}
@font-face {{ font-family: J; src: url('file://{FONT}/jakarta_700.ttf'); font-weight: 700; }}
@font-face {{ font-family: J; src: url('file://{FONT}/jakarta_800.ttf'); font-weight: 800; }}
* {{ margin: 0; padding: 0; box-sizing: border-box; }}
html, body {{ width: 100%; height: 100%; overflow: hidden; font-family: J, sans-serif; color: #EDF1F5; }}
body {{ background: #0B0E13; position: relative; }}
.glow {{ position: absolute; border-radius: 50%; filter: blur(140px); opacity: .55; }}
.phone {{ --w: 520px; position: absolute; width: var(--w); padding: calc(var(--w) * .028);
  background: linear-gradient(145deg, #2b313a, #0d1014 40%, #1b2027);
  border-radius: calc(var(--w) * .135);
  box-shadow: 0 60px 120px rgba(0,0,0,.6), 0 20px 40px rgba(0,0,0,.35), inset 0 0 0 2px rgba(255,255,255,.07); }}
.phone::before, .phone::after {{ content: ''; position: absolute; right: -4px; width: 5px; border-radius: 3px; background: #2a3039; }}
.phone::before {{ top: 22%; height: 9%; }}
.phone::after {{ top: 34%; height: 15%; }}
.screen {{ position: relative; border-radius: calc(var(--w) * .11); overflow: hidden; background: #000; }}
.screen img {{ display: block; width: 100%; }}
.cam {{ position: absolute; top: calc(var(--w) * .022); left: 50%; width: calc(var(--w) * .032); height: calc(var(--w) * .032);
  margin-left: calc(var(--w) * -.016); border-radius: 50%; background: #05070a; box-shadow: inset 0 0 0 2px #151a20; }}
h1 {{ font-weight: 800; letter-spacing: -.035em; line-height: 1.03; }}
p.sub {{ font-weight: 500; color: #9AA6B2; }}
.grad {{ background: linear-gradient(90deg, #1DD8B8, #6C8CFF); -webkit-background-clip: text; background-clip: text; color: transparent; }}
.chip {{ display: inline-block; padding: 12px 22px; border-radius: 999px; background: rgba(255,255,255,.07);
  border: 1px solid rgba(255,255,255,.1); font-weight: 700; font-size: 22px; margin: 0 10px 12px 0; }}
"""

def phone(img, w, x, y, rot=0, z=1):
    return (f'<div class="phone" style="--w:{w}px; left:{x}px; top:{y}px; transform: rotate({rot}deg); z-index:{z}">'
            f'<div class="screen"><img src="file://{RAW / img.replace('.png', '.webp')}"><div class="cam"></div></div></div>')

def glows(*specs):
    return "".join(f'<div class="glow" style="left:{x}px; top:{y}px; width:{s}px; height:{s}px; background:{c}"></div>'
                   for x, y, s, c in specs)

def render(name, w, h, body):
    html = f"<!doctype html><html><head><meta charset='utf-8'><style>{CSS}</style></head><body>{body}</body></html>"
    with tempfile.NamedTemporaryFile("w", suffix=".html", delete=False) as f:
        f.write(html)
    png = OUT / f"{name}.png"
    out = OUT / f"{name}.jpg"
    subprocess.run([CHROME, "--headless=new", "--disable-gpu", "--hide-scrollbars", "--force-device-scale-factor=1",
                    "--allow-file-access-from-files", f"--window-size={w},{h}", "--virtual-time-budget=4000",
                    f"--screenshot={png}", f"file://{f.name}"], check=True, capture_output=True)
    os.unlink(f.name)
    # High-quality JPEG keeps the repository small (gradients + photos compress far better than PNG).
    subprocess.run(["magick", str(png), "-strip", "-quality", "90", "-sampling-factor", "4:4:4", str(out)], check=True)
    png.unlink()
    print(f"{out.relative_to(ROOT)}  {out.stat().st_size // 1024} KB")

CARDS = [
    ("01_home", "home.png", "Download from<br><span class=grad>anywhere</span>", "Search, paste a link, or share from any app",
     ("#1DD8B8", "#6C8CFF")),
    ("02_formats", "sheet.png", "Every format,<br><span class=grad>every quality</span>", "MP4 from 144p to 4K, or just the audio",
     ("#6C8CFF", "#B98CFF")),
    ("03_browser", "browser.png", "<span class=grad>1,800+ sites</span><br>supported", "The built-in browser finds the video you're watching",
     ("#1DD8B8", "#2FCB7A")),
    ("04_watch", "video.png", "Watch before<br>you <span class=grad>download</span>", "Fast HD player, comments and related videos",
     ("#FF7AA8", "#6C8CFF")),
    ("05_music", "music.png", "Music that keeps<br><span class=grad>playing</span>", "Background audio with a mini player",
     ("#B98CFF", "#1DD8B8")),
    ("06_themes", "appearance.png", "Make it<br><span class=grad>yours</span>", "Dark, light, pure black and 7 accent colours",
     ("#FF9257", "#FF7AA8")),
]

def card(name, img, title, sub, colors):
    W, H = 1080, 1920
    a, b = colors
    body = glows((-260, 900, 900, a), (560, 1200, 800, b), (300, -300, 600, b))
    body += f'<div style="position:absolute; top:120px; width:100%; text-align:center; z-index:2">' \
            f'<h1 style="font-size:96px">{title}</h1><p class="sub" style="font-size:36px; margin-top:28px">{sub}</p></div>'
    body += phone(img, 720, (W - 720) // 2, 470, 0, 3)
    render(name, W, H, body)

def banner():
    W, H = 2400, 1200
    body = glows((-200, 600, 1000, "#1DD8B8"), (1500, -300, 1000, "#6C8CFF"), (1900, 700, 700, "#1DD8B8"))
    body += f'''<div style="position:absolute; left:140px; top:250px; width:1000px; z-index:5">
      <div style="width:120px; height:120px; border-radius:36px; background:linear-gradient(135deg,#1DD8B8,#5B6CFF);
        display:flex; align-items:center; justify-content:center; box-shadow:0 20px 60px rgba(29,216,184,.35)">
        <svg width="66" height="66" viewBox="0 -960 960 960" fill="#fff"><path d="M480-337q-8 0-15-2.5t-13-8.5L308-492q-12-12-11.5-28t11.5-28q12-12 28.5-12.5T365-549l75 75v-286q0-17 11.5-28.5T480-800q17 0 28.5 11.5T520-760v286l75-75q12-12 28.5-11.5T652-548q11 12 11.5 28T652-492L508-348q-6 6-13 8.5t-15 2.5ZM240-160q-33 0-56.5-23.5T160-240v-80q0-17 11.5-28.5T200-360q17 0 28.5 11.5T240-320v80h480v-80q0-17 11.5-28.5T760-360q17 0 28.5 11.5T800-320v80q0 33-23.5 56.5T720-160H240Z"/></svg>
      </div>
      <h1 class="grad" style="font-size:150px; margin-top:44px">QuickTube</h1>
      <p class="sub" style="font-size:44px; margin-top:22px; line-height:1.35">Search, watch and download videos<br>and music from YouTube and 1,800+ sites.</p>
      <div style="margin-top:46px"><span class="chip">Open source</span><span class="chip">No ads</span><span class="chip">No account</span><span class="chip">Android 7+</span></div>
    </div>'''
    body += phone("feed.png", 440, 1300, 230, -7, 2)
    body += phone("sheet.png", 440, 1880, 230, 7, 2)
    body += phone("home.png", 500, 1560, 120, 0, 4)
    render("banner", W, H, body)

def light_pair():
    W, H = 2160, 1920
    body = '<div style="position:absolute; inset:0; background:linear-gradient(160deg,#F7F8FA,#E3F7F2 60%,#E6EBFF)"></div>'
    body += f'<div style="position:absolute; top:120px; width:100%; text-align:center; z-index:2; color:#12161B">' \
            f'<h1 style="font-size:96px">Beautiful in <span class=grad>light</span> too</h1>' \
            f'<p class="sub" style="font-size:36px; margin-top:28px; color:#5D6873">Follows your system theme automatically</p></div>'
    body += phone("home_light.png", 600, 380, 440, -4, 3)
    body += phone("more_light.png", 600, 1180, 440, 4, 3)
    render("07_light", W, H, body)

if __name__ == "__main__":
    for c in CARDS:
        card(*c)
    light_pair()
    banner()
