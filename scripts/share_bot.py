#!/usr/bin/env python3
"""
Share-to-download bot: drives an installed QuickTube build on a connected phone exactly like a user does.

For every link it sends the same SEND intent other apps send when you tap Share -> QuickTube, waits for the
format sheet, taps the best video (up to --max-height) or the audio, waits for the download to finish, pulls the
file and checks it with ffprobe + a full ffmpeg decode. Nothing inside the app is touched, so it works on
release builds (R8 + resource shrinking), which is where reflection and keep-rule problems show up.

    ./gradlew :app:installQa            # release build, signed with the debug key, package ...quicktube.qa
    python3 scripts/share_bot.py links.txt --fresh --report bot-report.json

links.txt: one test per line, "name<TAB>url" (or just a url); lines starting with # are skipped.
Needs adb and ffmpeg/ffprobe on the computer. Test downloads are deleted from the phone afterwards
unless --keep is given.
"""
import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

DOWNLOAD_DIR = "/storage/emulated/0/Download/QuickTube"


def adb(*args, timeout=60, binary=False):
    r = subprocess.run(["adb", *args], capture_output=True, timeout=timeout)
    return r.stdout if binary else r.stdout.decode("utf-8", "replace")


def shell(cmd, timeout=60):
    return adb("shell", cmd, timeout=timeout)


def ui_nodes():
    """The current screen as (text, desc, clickable, (x1, y1, x2, y2)) tuples."""
    shell("uiautomator dump /sdcard/qt-bot.xml >/dev/null 2>&1", timeout=30)
    raw = adb("exec-out", "cat", "/sdcard/qt-bot.xml", timeout=30)
    try:
        root = ET.fromstring(raw)
    except ET.ParseError:
        return []
    out = []
    for n in root.iter("node"):
        b = re.findall(r"\d+", n.get("bounds", ""))
        if len(b) != 4:
            continue
        out.append((n.get("text", ""), n.get("content-desc", ""), n.get("clickable") == "true", tuple(map(int, b))))
    return out


def tap(x, y):
    shell(f"input tap {x} {y}")


def share(package, text):
    shell(
        "am start -a android.intent.action.SEND -t text/plain "
        f"--es android.intent.extra.TEXT '{text}' -n {package}/com.edureminder.quicktube.share.ShareActivity"
    )


ERRORS = ("No video found", "Couldn't", "Something went wrong", "can't be downloaded", "Live streams")


def sheet_options(nodes):
    """Format rows on screen: {title, kind (Video/Music), height, row bounds}."""
    rows = [b for _, _, c, b in nodes if c]
    section, options = None, []
    for t, _, _, b in nodes:
        # Group titles sit further left than format names (which may themselves be called "Video").
        if t in ("Video", "Music") and b[0] < 130:
            section = t
            continue
        if section is None or not t or t == "More formats" or "options" in t or t.startswith("All ") or t.startswith("Download ·"):
            continue
        # Format chips (MP4, M4A), size labels (~5.9 MB) and badges (Low, HD) aren't format names.
        if (t.isupper() and len(t) <= 5) or re.fullmatch(r"~?[\d.,]+ ?[KMG]?B", t) or (len(t) <= 4 and b[0] > 300):
            continue
        cy = (b[1] + b[3]) // 2
        row = next((r for r in rows if r[1] <= cy <= r[3] and r[0] <= b[0] and r[2] >= b[2]), None)
        if row is None:
            continue
        m = re.search(r"(\d{3,4})p", t)
        height = int(m.group(1)) if m else (720 if "HD" in t else 360 if "SD" in t else 0)
        # Rows at the bottom edge can be partly off screen; their visible part still takes the tap.
        if row[3] - row[1] >= 24:
            options.append({"title": t, "kind": section, "height": height, "row": row})
    return options


def wait_sheet(timeout):
    """('options', [{title, kind, height}]) once the sheet has formats, or ('error', message)."""
    end = time.time() + timeout
    while time.time() < end:
        time.sleep(2)
        nodes = ui_nodes()
        texts = [t for t, _, _, _ in nodes if t]
        err = next((t for t in texts if any(e in t for e in ERRORS)), None)
        if err:
            return "error", err
        # Ready once the sheet's "Download · 720p · 19 MB" button is there (a format is preselected).
        if not any(t.startswith("Download ·") for t in texts) or not any(t in ("Video", "Music") for t in texts):
            continue
        options = sheet_options(nodes)
        if options:
            # The title is the line above the site name (e.g. "facebook.com") in the header.
            hosts = [i for i, t in enumerate(texts) if re.fullmatch(r"[a-z0-9-]+(\.[a-z0-9-]+)+", t)]
            title = texts[hosts[0] - 1] if hosts and hosts[0] > 0 else ""
            return "options", title, options
    return "error", "timed out waiting for the format sheet"


def tap_option(title):
    """Selects the format's row, then presses the sheet's Download button."""
    o = next((o for o in sheet_options(ui_nodes()) if o["title"] == title), None)
    if not o:
        return False
    r = o["row"]
    tap((r[0] + r[2]) // 2, (r[1] + r[3]) // 2)
    time.sleep(0.8)
    button = next((b for t, _, _, b in ui_nodes() if t.startswith("Download ·")), None)
    if not button:
        return False
    tap((button[0] + button[2]) // 2, (button[1] + button[3]) // 2)
    return True


def pick(options, max_height, audio):
    if audio:
        return next((o for o in options if o["kind"] == "Music"), None)
    videos = [o for o in options if o["kind"] == "Video"]
    if videos and all(o["height"] == 0 for o in videos):
        return videos[-1]  # sizes unknown: the list runs from worst to best
    ok = [o for o in videos if o["height"] <= max_height]
    return max(ok, key=lambda o: o["height"]) if ok else (min(videos, key=lambda o: o["height"]) if videos else None)


def list_downloads():
    out = shell(f"ls -l {DOWNLOAD_DIR}/ 2>/dev/null")
    files = {}
    for line in out.splitlines():
        parts = line.split(None, 7)
        if len(parts) == 8 and parts[0].startswith("-"):
            files[parts[7]] = int(parts[4])
    return files


def failed_notifications(package):
    out = shell("dumpsys notification --noredact", timeout=30)
    found = set()
    for block in out.split("NotificationRecord")[1:]:
        if f"pkg={package}" in block and "Download failed" in block:
            m = re.search(r"android\.text=String \((.*?)\)", block)
            found.add(m.group(1) if m else "Download failed")
    return found


def norm(text):
    return "".join(c for c in text.lower() if c.isalnum())


def wait_download(package, before, timeout, title=""):
    # File names are built from the video's title; only that file counts (an older download finishing in the
    # background must not pass for this one).
    want = norm(title)[:12]
    old_failures = failed_notifications(package)
    end = time.time() + timeout
    sizes = {}
    while time.time() < end:
        time.sleep(3)
        now = list_downloads()
        new = {n: s for n, s in now.items() if n not in before and not n.endswith(".part") and ".part." not in n
               and (not want or norm(n).startswith(want))}
        for name, size in new.items():
            if size > 0 and sizes.get(name) == size:
                return name, None
        sizes = new
        new_failures = failed_notifications(package) - old_failures
        if new_failures:
            return None, sorted(new_failures)[0]
    return None, "timed out waiting for the download"


def probe(path, want_video):
    info = json.loads(subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "stream=codec_type,codec_name,width,height:format=duration",
         "-of", "json", path], capture_output=True, text=True).stdout or "{}")
    streams = info.get("streams", [])
    v = [s for s in streams if s.get("codec_type") == "video" and s.get("codec_name") not in ("mjpeg", "png")]
    a = [s for s in streams if s.get("codec_type") == "audio"]
    decode = subprocess.run(["ffmpeg", "-v", "error", "-i", path, "-f", "null", "-"], capture_output=True, text=True)
    # "[null @ …] non monotonically increasing dts" is ffmpeg's *output* checker tripping over variable frame
    # rates; it says nothing about the file. Missing or undecodable frames are checked directly instead.
    errors = [l for l in decode.stderr.splitlines() if l.strip() and not l.startswith("[null @")]
    if v:
        counted = subprocess.run(["ffprobe", "-v", "error", "-count_frames", "-select_streams", "v:0", "-show_entries",
                                  "stream=nb_frames,nb_read_frames", "-of", "csv=p=0", path], capture_output=True, text=True).stdout.strip()
        parts = [x for x in counted.split(",") if x.isdigit()]
        if len(parts) == 2 and int(parts[1]) < int(parts[0]):
            errors.append(f"only {parts[1]} of {parts[0]} video frames decode")
    # A few AAC complaints at the very end are common in sites' own files (e.g. Facebook); anything in the
    # video decoder, or many audio errors, means a damaged file.
    audio_tail = [l for l in errors if "aac" in l.lower() or "aist#" in l]
    problems, notes = [], []
    if want_video and not v:
        problems.append("no video track")
    if not want_video and not a:
        problems.append("no audio track")
    if len(errors) > len(audio_tail) or len(audio_tail) > 6:
        problems.append(f"{len(errors)} decode errors: {errors[0][:120]}")
    elif audio_tail:
        notes.append(f"{len(audio_tail)} AAC warnings (also in source files)")
    duration = float(info.get("format", {}).get("duration") or 0)
    if duration <= 0:
        problems.append("zero duration")
    desc = ", ".join(
        [f"{s['codec_name']} {s.get('width')}x{s.get('height')}" for s in v] + [s["codec_name"] for s in a]
    ) + f", {duration:.0f}s" + "".join(f" [{n}]" for n in notes)
    return problems, desc


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("links")
    ap.add_argument("--package", default="com.edureminder.quicktube.qa")
    ap.add_argument("--fresh", action="store_true", help="clear the app's data first (first-launch path)")
    ap.add_argument("--audio", action="store_true", help="download the audio option instead of video")
    ap.add_argument("--max-height", type=int, default=1080)
    ap.add_argument("--sheet-timeout", type=int, default=120)
    ap.add_argument("--download-timeout", type=int, default=600)
    ap.add_argument("--keep", action="store_true", help="leave the downloaded files on the phone")
    ap.add_argument("--report")
    args = ap.parse_args()

    tests = []
    for line in open(args.links, encoding="utf-8"):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        name, _, url = line.partition("\t") if "\t" in line else (line, "", line)
        tests.append((name.strip(), url.strip()))

    if args.fresh:
        shell(f"pm clear {args.package}")
    results = []
    tmp = tempfile.mkdtemp(prefix="qt-bot-")
    for i, (name, url) in enumerate(tests, 1):
        print(f"[{i}/{len(tests)}] {name}: ", end="", flush=True)
        started = time.time()
        r = {"name": name, "url": url}
        # Start from the home screen so nothing is ever tapped in another app.
        shell("input keyevent KEYCODE_HOME")
        time.sleep(1)
        before = list_downloads()
        share(args.package, url)
        result = wait_sheet(args.sheet_timeout)
        r["sheet_seconds"] = round(time.time() - started)
        if result[0] == "error":
            r.update(ok=False, stage="sheet", error=result[1])
        else:
            _, title, value = result
            r["title"] = title
            r["options"] = [o["title"] for o in value]
            choice = pick(value, args.max_height, args.audio)
            if choice is None:
                r.update(ok=False, stage="sheet", error="no " + ("audio" if args.audio else "video") + " option")
            else:
                r["chosen"] = choice["title"]
                if not tap_option(choice["title"]):
                    file, err = None, "couldn't tap the format"
                else:
                    file, err = wait_download(args.package, before, args.download_timeout, title)
                r["total_seconds"] = round(time.time() - started)
                if err:
                    r.update(ok=False, stage="download", error=err)
                else:
                    local = os.path.join(tmp, "f" + os.path.splitext(file)[1])
                    adb("pull", f"{DOWNLOAD_DIR}/{file}", local, timeout=600)
                    problems, desc = probe(local, want_video=not args.audio)
                    r.update(file=file, media=desc, bytes=os.path.getsize(local), ok=not problems, stage="file",
                             error="; ".join(problems) or None)
                    os.remove(local)
                    if not args.keep:
                        shell(f"rm '{DOWNLOAD_DIR}/{file}'")
        results.append(r)
        print(("PASS " if r["ok"] else "FAIL ") + (r.get("media") or r.get("error") or ""), f"({r.get('total_seconds', r['sheet_seconds'])}s)")

    passed = sum(r["ok"] for r in results)
    print(f"\n{passed}/{len(results)} passed")
    for r in results:
        if not r["ok"]:
            print(f"  FAIL {r['name']} [{r['stage']}]: {r['error']}")
    if args.report:
        with open(args.report, "w", encoding="utf-8") as f:
            json.dump(results, f, indent=2, ensure_ascii=False)
    sys.exit(0 if passed == len(results) else 1)


if __name__ == "__main__":
    main()
