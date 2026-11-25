#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

python3 - "$ROOT_DIR" <<'PYEOF'
import os
import re
import subprocess
import sys

root = sys.argv[1]
app_src = os.path.join(root, "app", "src")
failures = []
warnings = []

TEXT_SCAN_EXTS = (
    ".kt", ".kts", ".java", ".json", ".xml",
    ".properties", ".yaml", ".yml", ".txt", ".cfg", ".conf",
)

def collect_kt_files(base_dir):
    out = []
    for dirpath, dirnames, filenames in os.walk(base_dir):
        dirnames[:] = [d for d in sorted(dirnames) if d != "build"]
        for fn in sorted(filenames):
            if fn.endswith(".kt"):
                out.append(os.path.join(dirpath, fn))
    return out

def collect_text_files(base_dir):
    out = []
    for dirpath, dirnames, filenames in os.walk(base_dir):
        dirnames[:] = [d for d in sorted(dirnames) if d != "build"]
        for fn in sorted(filenames):
            if fn.endswith(TEXT_SCAN_EXTS):
                out.append(os.path.join(dirpath, fn))
    return out

def rel(path):
    return os.path.relpath(path, root)

def scan_kotlin(src):
    """Strip // and nested /* */ comments while preserving newlines, and extract string literals."""
    n = len(src)
    i = 0
    lineno = 1
    code = []
    literals = []

    while i < n:
        ch = src[i]
        nxt = src[i + 1] if i + 1 < n else ""

        if ch == "/" and nxt == "/":
            code.append("  ")
            i += 2
            while i < n and src[i] != "\n":
                code.append(" ")
                i += 1
            continue

        if ch == "/" and nxt == "*":
            code.append("  ")
            i += 2
            depth = 1
            while i < n and depth > 0:
                if src[i] == "/" and i + 1 < n and src[i + 1] == "*":
                    code.append("  ")
                    i += 2
                    depth += 1
                elif src[i] == "*" and i + 1 < n and src[i + 1] == "/":
                    code.append("  ")
                    i += 2
                    depth -= 1
                else:
                    if src[i] == "\n":
                        code.append("\n")
                        lineno += 1
                    else:
                        code.append(" ")
                    i += 1
            continue

        if src[i:i + 3] == '"""':
            start_line = lineno
            code.append('"""')
            i += 3
            lit = []
            while i < n:
                if src[i:i + 3] == '"""':
                    while i + 3 < n and src[i + 3] == '"':
                        lit.append('"')
                        code.append('"')
                        i += 1
                    code.append('"""')
                    i += 3
                    literals.append((start_line, "".join(lit)))
                    break
                c = src[i]
                lit.append(c)
                code.append(c)
                if c == "\n":
                    lineno += 1
                i += 1
            continue

        if ch == '"':
            start_line = lineno
            code.append('"')
            i += 1
            lit = []
            while i < n:
                c = src[i]
                if c == "\\" and i + 1 < n:
                    esc = src[i:i + 2]
                    lit.append(esc)
                    code.append(esc)
                    if src[i + 1] == "\n":
                        lineno += 1
                    i += 2
                elif c == "$" and i + 1 < n and src[i + 1] == "{":
                    lit.append("${")
                    code.append("${")
                    i += 2
                    brace = 1
                    while i < n and brace > 0:
                        tc = src[i]
                        lit.append(tc)
                        code.append(tc)
                        if tc == "\n":
                            lineno += 1
                        elif tc == "{":
                            brace += 1
                        elif tc == "}":
                            brace -= 1
                        elif tc == '"':
                            i += 1
                            while i < n:
                                qc = src[i]
                                lit.append(qc)
                                code.append(qc)
                                if qc == "\\" and i + 1 < n:
                                    lit.append(src[i + 1])
                                    code.append(src[i + 1])
                                    if src[i + 1] == "\n":
                                        lineno += 1
                                    i += 2
                                    continue
                                if qc == "\n":
                                    lineno += 1
                                i += 1
                                if qc == '"':
                                    break
                            continue
                        i += 1
                elif c == '"':
                    code.append('"')
                    i += 1
                    literals.append((start_line, "".join(lit)))
                    break
                else:
                    lit.append(c)
                    code.append(c)
                    if c == "\n":
                        lineno += 1
                    i += 1
            continue

        if ch == "'":
            code.append("'")
            i += 1
            while i < n:
                c = src[i]
                code.append(c)
                if c == "\\" and i + 1 < n:
                    code.append(src[i + 1])
                    if src[i + 1] == "\n":
                        lineno += 1
                    i += 2
                    continue
                if c == "\n":
                    lineno += 1
                    i += 1
                    break
                i += 1
                if c == "'":
                    break
            continue

        code.append(ch)
        if ch == "\n":
            lineno += 1
        i += 1

    return "".join(code), literals

ESC_RE = re.compile(r'\\(u[0-9a-fA-F]{4}|[nrtbf"\'\\$])')
SIMPLE_ESC = {
    "n": "\n", "r": "\r", "t": "\t", "b": "\b",
    "f": "\f", '"': '"', "'": "'", "\\": "\\", "$": "$",
}

def decode_kotlin_escapes(s):
    def repl(m):
        tok = m.group(1)
        if tok.startswith("u"):
            return chr(int(tok[1:], 16))
        return SIMPLE_ESC.get(tok, m.group(0))
    raw = ESC_RE.sub(repl, s)
    return raw.encode("utf-16", "surrogatepass").decode("utf-16", "replace")

# 1. Line count limits: <= 600 hard max, > 450 warning
all_kt = collect_kt_files(app_src)
for path in all_kt:
    with open(path, "r", encoding="utf-8") as f:
        lines = sum(1 for _ in f)
    if lines > 600:
        failures.append(f"[LINE_LIMIT] {rel(path)}: {lines} lines (> 600 hard max)")
    elif lines > 450:
        warnings.append(f"[LINE_WARN]  {rel(path)}: {lines} lines (> 450 typical target)")

# 2. Zero real API keys or bearer tokens in app/src/ text files, git diff, and untracked files
DUMMY_KEY_RE = re.compile(r"^sk-(test-dummy|e2e-dummy|dummy|mock|fake)-[A-Za-z0-9_-]*$")
SK_KEY_RE = re.compile(r"\bsk-[A-Za-z0-9_-]{16,}\b")
AIZA_KEY_RE = re.compile(r"\bAIza[0-9A-Za-z_-]{35}\b")
BEARER_RE = re.compile(
    r"""Authorization["']?\s*[:=,]\s*["']?Bearer\s+([A-Za-z0-9._-]{12,})"""
)

def is_safe_token(tok):
    if DUMMY_KEY_RE.match(tok):
        return True
    lower = tok.lower()
    if any(x in lower for x in ("dummy", "test", "fake", "mock", "placeholder", "example", "redacted")):
        return True
    return False

def scan_text_for_secrets(label, text, prefix="[SECRET]"):
    for lineno, line in enumerate(text.splitlines(), 1):
        for m in SK_KEY_RE.finditer(line):
            if not is_safe_token(m.group(0)):
                failures.append(f"{prefix} {label}:{lineno} contains potential sk- key")
        if AIZA_KEY_RE.search(line):
            failures.append(f"{prefix} {label}:{lineno} contains potential AIza key")
        for m in BEARER_RE.finditer(line):
            tok = m.group(1)
            if not is_safe_token(tok) and "${" not in line and "$token" not in line:
                failures.append(f"{prefix} {label}:{lineno} contains hardcoded Bearer token")

for path in collect_text_files(app_src):
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        scan_text_for_secrets(rel(path), f.read(), prefix="[SECRET]")

try:
    diff_res = subprocess.run(
        ["git", "-C", root, "diff", "--unified=0", "HEAD", "--", "app/", "e2e/"],
        capture_output=True, text=True, check=False,
    )
    current_file = "<git-diff>"
    for line in diff_res.stdout.splitlines():
        if line.startswith("+++ b/"):
            current_file = line[6:]
        elif line.startswith("+") and not line.startswith("+++"):
            if current_file == "e2e/check_static_hygiene.sh":
                continue
            for m in SK_KEY_RE.finditer(line):
                if not is_safe_token(m.group(0)):
                    failures.append(f"[GIT_DIFF_SECRET] {current_file} adds potential sk- key")
            if AIZA_KEY_RE.search(line):
                failures.append(f"[GIT_DIFF_SECRET] {current_file} adds potential AIza key")
            for m in BEARER_RE.finditer(line):
                if not is_safe_token(m.group(1)) and "${" not in line and "$token" not in line:
                    failures.append(f"[GIT_DIFF_SECRET] {current_file} adds hardcoded Bearer token")

    untracked_res = subprocess.run(
        ["git", "-C", root, "ls-files", "--others", "--exclude-standard", "--", "app/", "e2e/"],
        capture_output=True, text=True, check=False,
    )
    for rel_path in untracked_res.stdout.splitlines():
        rel_path = rel_path.strip()
        if not rel_path or rel_path == "e2e/check_static_hygiene.sh":
            continue
        if not rel_path.endswith(TEXT_SCAN_EXTS):
            continue
        full_path = os.path.join(root, rel_path)
        if os.path.isfile(full_path):
            with open(full_path, "r", encoding="utf-8", errors="replace") as f:
                scan_text_for_secrets(rel_path, f.read(), prefix="[GIT_UNTRACKED_SECRET]")
except Exception as exc:
    warnings.append(f"[GIT_DIFF_WARN] skipped git diff secret scan: {exc}")

# 3. Zero emoji characters in app/src/**/ui/**/*.kt, Main*.kt, and BookStudioController.kt
ui_and_main_kt = [
    p for p in all_kt
    if (
        "/ui/" in p.replace(os.sep, "/")
        or os.path.basename(p).startswith("Main")
        or os.path.basename(p) == "BookStudioController.kt"
    )
    and "/e2eTest/" not in p.replace(os.sep, "/")
    and "/desktopTest/" not in p.replace(os.sep, "/")
]

def is_emoji_cp(cp):
    return (
        (0x1F300 <= cp <= 0x1FAFF)
        or (0x2600 <= cp <= 0x27BF)
        or cp == 0xFE0F
        or cp == 0x200D
        or (0x23E9 <= cp <= 0x23FA)
    )

# 4. Zero preachy helper/instructional strings in UI .kt files and BookStudioController.kt
PREACHY_PATTERNS = [
    r"点击", r"长按", r"试试看", r"小贴士", r"温馨提示", r"提示：",
    r"你可以", r"请先", r"欢迎使用", r"写得真棒", r"真棒", r"加油",
    r"使用说明", r"操作指南", r"点我", r"Tap here", r"Click to", r"Hold to", r"Tip:",
]
PREACHY_RE = re.compile("|".join(PREACHY_PATTERNS))

for path in ui_and_main_kt:
    with open(path, "r", encoding="utf-8") as f:
        src = f.read()
    for lineno, line in enumerate(src.splitlines(), 1):
        found = [f"U+{ord(ch):04X}" for ch in line if is_emoji_cp(ord(ch))]
        if found:
            failures.append(f"[EMOJI] {rel(path)}:{lineno} contains emoji codepoints {found[:4]}")
    _, literals = scan_kotlin(src)
    for lineno, raw_lit in literals:
        decoded = decode_kotlin_escapes(raw_lit)
        found_dec = [f"U+{ord(ch):04X}" for ch in decoded if is_emoji_cp(ord(ch))]
        if found_dec:
            failures.append(f"[EMOJI] {rel(path)}:{lineno} contains decoded emoji codepoints {found_dec[:4]}")
        if PREACHY_RE.search(raw_lit) or PREACHY_RE.search(decoded):
            failures.append(f"[PREACHY_COPY] {rel(path)}:{lineno} contains preachy/instructional UI string: {decoded!r}")

# 5. MainDesktop.kt must NOT contain debug form-factor bar strings
main_desktop = os.path.join(app_src, "desktopMain", "kotlin", "com", "luna", "storynote", "MainDesktop.kt")
if os.path.isfile(main_desktop):
    with open(main_desktop, "r", encoding="utf-8") as f:
        desktop_src = f.read()
    for bad in ("平板对开 1140", "平板单页 760", "手机 400"):
        if bad in desktop_src:
            failures.append(f"[DEBUG_BAR] {rel(main_desktop)} still contains debug form-factor bar string: {bad!r}")
else:
    failures.append(f"[MISSING_FILE] {rel(main_desktop)} not found")

# 6. MainActivity.kt must contain navigationBarsPadding() alongside statusBarsPadding() (uncommented)
main_activity = os.path.join(app_src, "androidMain", "kotlin", "com", "luna", "storynote", "MainActivity.kt")
if os.path.isfile(main_activity):
    with open(main_activity, "r", encoding="utf-8") as f:
        act_src = f.read()
    act_code, _ = scan_kotlin(act_src)
    if "statusBarsPadding()" not in act_code:
        failures.append(f"[INSETS] {rel(main_activity)} missing statusBarsPadding()")
    if "navigationBarsPadding()" not in act_code:
        failures.append(f"[INSETS] {rel(main_activity)} missing navigationBarsPadding()")
else:
    failures.append(f"[MISSING_FILE] {rel(main_activity)} not found")

# 7. Zero external HTTP/MCP listening sockets in production source sets (comment-stripped)
for ss in ("commonMain", "androidMain", "desktopMain"):
    ss_dir = os.path.join(app_src, ss)
    if not os.path.isdir(ss_dir):
        continue
    for path in collect_kt_files(ss_dir):
        with open(path, "r", encoding="utf-8") as f:
            code_only, _ = scan_kotlin(f.read())
        for lineno, line in enumerate(code_only.splitlines(), 1):
            if "HttpServer.create" in line or "ServerSocket(" in line:
                failures.append(f"[LISTENING_SOCKET] {rel(path)}:{lineno} opens external listening socket")

# 8. Android PlatformServices.android.kt and AndroidManifest.xml / res/ contain zero embedded magpie / railway / apiKey defaults
android_scan_targets = []
android_main_dir = os.path.join(app_src, "androidMain")
for dirpath, _, filenames in os.walk(android_main_dir):
    for fn in sorted(filenames):
        if fn.endswith((".kt", ".xml", ".json", ".properties")):
            android_scan_targets.append(os.path.join(dirpath, fn))

ANDROID_FORBIDDEN_RE = re.compile(r"(magpie|railway|up\.railway\.app)", re.IGNORECASE)
ANDROID_APIKEY_DEFAULT_RE = re.compile(r'\bapiKey\s*=\s*"[^"]+"|"api_key"\s*:\s*"[^"]+"')

for path in android_scan_targets:
    with open(path, "r", encoding="utf-8") as f:
        for lineno, line in enumerate(f, 1):
            if ANDROID_FORBIDDEN_RE.search(line):
                failures.append(f"[ANDROID_EMBEDDED_PROVIDER] {rel(path)}:{lineno} references magpie/railway")
            if ANDROID_APIKEY_DEFAULT_RE.search(line):
                failures.append(f"[ANDROID_EMBEDDED_KEY] {rel(path)}:{lineno} embeds non-empty apiKey default")

print("=== StoryNote Static Hygiene Report ===")
for w in warnings:
    print("WARN :", w)
if failures:
    for err in failures:
        print("FAIL :", err)
    print(f"Result: FAILED ({len(failures)} failures, {len(warnings)} warnings)")
    sys.exit(1)
else:
    print(f"Result: PASSED (0 failures, {len(warnings)} warnings)")
    sys.exit(0)
PYEOF
