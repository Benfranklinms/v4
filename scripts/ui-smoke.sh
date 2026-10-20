#!/bin/bash
# Scripted emulator UI test: drives every screen through uiautomator (Compose test tags are exposed as resource ids).
# usage: ui-smoke.sh <out-dir>     Fails (exit 1) on any crash/ANR or a required step that can't be done.
set -u
PKG=in.benfranklin.itune
O=${1:-out/smoke/ui}
mkdir -p "$O"
STEP=0
FAILS=0
log() { echo "[ui] $*" | tee -a "$O/steps.txt"; }

# whole walk must fit well inside the CI job (60 min incl. build): past the budget every wait fails fast
BUDGET=${UI_BUDGET:-1500}
OVER=0
dump() {
  local t0=$SECONDS
  timeout 25 adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  timeout 10 adb shell cat /sdcard/ui.xml 2>/dev/null > "$O/ui.xml"
  [ $((SECONDS - t0)) -gt 8 ] && echo "[ui] slow dump $((SECONDS - t0))s" >> "$O/steps.txt"
}
# find <attr> <value> [contains] -> prints "x y" of the first match
find_node() {
  python3 - "$O/ui.xml" "$1" "$2" "${3:-}" <<'PY'
import sys, re, xml.etree.ElementTree as ET
path, attr, val, mode = sys.argv[1:5]
try:
    root = ET.parse(path).getroot()
except Exception:
    sys.exit(1)
for n in root.iter('node'):
    v = n.get(attr, '')
    if attr == 'resource-id':
        v = v.split('/')[-1]
    ok = (val in v) if mode == 'contains' else (v == val)
    if ok:
        m = re.findall(r'\d+', n.get('bounds', ''))
        if len(m) == 4:
            x1, y1, x2, y2 = map(int, m)
            if x2 > x1 and y2 > y1:
                print((x1 + x2) // 2, (y1 + y2) // 2)
                sys.exit(0)
sys.exit(1)
PY
}
# nth_node <resource-id> <n> -> "x y" of the n-th match (1-based, document order)
nth_node() {
  python3 - "$O/ui.xml" "$1" "$2" <<'PY'
import sys, re, xml.etree.ElementTree as ET
try: r = ET.parse(sys.argv[1]).getroot()
except Exception: sys.exit(1)
k = 0
for n in r.iter('node'):
    if n.get('resource-id','').split('/')[-1] != sys.argv[2]: continue
    m = list(map(int, re.findall(r'\d+', n.get('bounds',''))))
    if len(m) == 4 and m[2] > m[0] and m[3] > m[1]:
        k += 1
        if k == int(sys.argv[3]): print((m[0]+m[2])//2, (m[1]+m[3])//2); sys.exit(0)
sys.exit(1)
PY
}
# texts of every node with <resource-id>, top to bottom, one per line
texts_of() {
  python3 - "$O/ui.xml" "$1" <<'PY'
import sys, re, xml.etree.ElementTree as ET
try: r = ET.parse(sys.argv[1]).getroot()
except Exception: sys.exit(0)
out = []
for n in r.iter('node'):
    if n.get('resource-id','').split('/')[-1] != sys.argv[2]: continue
    m = list(map(int, re.findall(r'\d+', n.get('bounds',''))))
    if len(m) == 4: out.append((m[1], n.get('text','')))
for _, t in sorted(out): print(t)
PY
}
shot() { STEP=$((STEP + 1)); timeout 20 adb exec-out screencap -p > "$O/$(printf %02d $STEP)-$1.png" 2>/dev/null; }
alive() { timeout 15 adb shell pidof $PKG >/dev/null 2>&1; }
# adb on the CI emulator sometimes drops for a few seconds under load: reconnect before calling anything dead
adb_ok() {
  timeout 15 adb shell true >/dev/null 2>&1 && return 0
  for _ in 1 2 3 4 5 6; do
    timeout 10 adb reconnect offline >/dev/null 2>&1
    timeout 20 adb wait-for-device >/dev/null 2>&1
    timeout 15 adb shell true >/dev/null 2>&1 && { log "adb recovered"; return 0; }
    sleep 5
  done
  return 1
}
crash_check() {
  if ! alive; then
    if adb_ok && alive; then return 0; fi
    if adb_ok; then log "APP DIED after: $1"; else log "EMULATOR/ADB UNRESPONSIVE after: $1"; fi
    timeout 60 adb logcat -d -v threadtime > "$O/logcat-crash.txt" 2>&1
    exit 1
  fi
}
# wait_for <attr> <value> <seconds> [contains]
wait_for() {
  if [ $SECONDS -gt $BUDGET ]; then [ $OVER = 1 ] || { OVER=1; log "TIME BUDGET exceeded at ${SECONDS}s"; }; return 1; fi
  local t0=$SECONDS t=0
  while [ $t -lt "$3" ]; do
    dump
    if find_node "$1" "$2" "${4:-}" >/dev/null; then return 0; fi
    sleep 1; t=$((t + 1))
    [ $((SECONDS - t0)) -ge "$3" ] && break
  done
  return 1
}
# tap <attr> <value> [required:1|0] [contains] [timeout]
tap() {
  local req=${3:-1}
  if wait_for "$1" "$2" "${5:-8}" "${4:-}"; then
    local xy; xy=$(find_node "$1" "$2" "${4:-}")
    adb shell input tap $xy
    sleep 1.2
    crash_check "tap $2"
    log "tap $2 ok"
    return 0
  fi
  if [ "$req" = 1 ]; then log "MISSING (required): $2"; FAILS=$((FAILS + 1)); shot "missing-$2"; else log "skip (optional): $2"; fi
  return 1
}
long_press() {
  if wait_for "$1" "$2" 8 "${3:-}"; then
    local xy; xy=$(find_node "$1" "$2" "${3:-}")
    adb shell input swipe $xy $xy 900
    sleep 1.2
    crash_check "long-press $2"
    log "long-press $2 ok"
    return 0
  fi
  log "MISSING (required): long-press $2"; FAILS=$((FAILS + 1)); return 1
}
id() { tap resource-id "$1" "${2:-1}" "" "${3:-8}"; }
txt() { tap text "$1" "${2:-1}" "" "${3:-8}"; }
desc() { tap content-desc "$1" "${2:-1}" "" "${3:-8}"; }
type_text() { adb shell input text "$(echo "$1" | sed 's/ /%s/g')"; sleep 0.8; }
clear_field() { adb shell input keyevent KEYCODE_MOVE_END; for _ in $(seq 1 30); do adb shell input keyevent KEYCODE_DEL; done; }
back() { adb shell input keyevent KEYCODE_BACK; sleep 1; crash_check back; refocus; }
# screen size (the CI emulator is small, so every coordinate is a fraction of it)
read SW SH < <(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1 | tr 'x' ' ')
SW=${SW:-1080}; SH=${SH:-1920}
px() { echo $(( SW * $1 / 100 )); }
py() { echo $(( SH * $1 / 100 )); }
log "screen ${SW}x${SH}"
# swipes stay inside the content area: on the small CI screen the mini player sits at ~78-87% of the height and a
# swipe that starts on it opens Now Playing instead of scrolling
scroll_down() { adb shell input swipe $(px 50) $(py 68) $(px 50) $(py 22) 300; sleep 1; crash_check scroll; }
scroll_up() { adb shell input swipe $(px 50) $(py 22) $(px 50) $(py 68) 300; sleep 1; crash_check scroll; }
tap_at() { adb shell input tap $(px $1) $(py $2); sleep 0.6; }
# hide the soft keyboard if it is showing (BACK only when it is, so we never navigate by accident)
fg() { timeout 10 adb shell dumpsys window 2>/dev/null | grep -E "mCurrentFocus|mFocusedApp" | grep -q "$PKG"; }
# if a BACK meant for the keyboard left the app (IME state misreported on some images), bring it back
refocus() { fg || { log "app left the foreground: relaunching"; timeout 10 adb shell cmd statusbar collapse >/dev/null 2>&1; timeout 20 adb shell am start -n $PKG/dev.benfranklin.itune.MainActivity >/dev/null 2>&1; sleep 2; }; }
# submit / hide the keyboard WITHOUT Back (Back with the emulator's DummyIme reaches the app and pops the page)
# (ENTER = the field's Search action, which drops focus and the keyboard; no ESCAPE either: it falls back to Back)
kb_done() { adb shell input keyevent KEYCODE_ENTER; sleep 0.8; refocus; }
hide_kb() { if adb shell dumpsys input_method | grep -q "mInputShown=true"; then adb shell input keyevent KEYCODE_BACK; sleep 0.8; refocus; fi; }
# glass chrome: the bottom nav hides while scrolling down and Now Playing covers it. Close NP, then tap the
# tab; if the nav is hidden, a small scroll up springs it back and we retry.
np_open() { find_node resource-id np_close >/dev/null || find_node resource-id np_more >/dev/null || find_node resource-id np_lyrics >/dev/null || find_node resource-id np_upnext >/dev/null; }
close_np() {
  local i
  for i in 1 2 3 4; do
    dump
    if find_node resource-id np_close >/dev/null; then local xy; xy=$(find_node resource-id np_close); adb shell input tap $xy; sleep 1.2; continue; fi
    np_open || return 0
    # scrolled down inside Now Playing: scroll back to the top so the chevron shows, then back as a last resort
    if [ $i -lt 3 ]; then adb shell input swipe $(px 50) $(py 25) $(px 50) $(py 70) 250; sleep 1; else adb shell input keyevent KEYCODE_BACK; sleep 1.2; fi
  done
}
tab() {
  refocus
  close_np
  if wait_for resource-id "tab_$1" 3; then id "tab_$1"; return; fi
  adb shell input swipe $(px 50) $(py 45) $(px 50) $(py 60) 250; sleep 1
  if wait_for resource-id "tab_$1" 3; then id "tab_$1"; return; fi
  scroll_up; scroll_up
  id "tab_$1"
}
home_tab() { tab Home; local i; for i in 1 2 3 4 5 6; do scroll_up; dump; find_node resource-id home_settings >/dev/null && break; done; }
# scroll a page to the top, then down until <resource-id> is on screen
seek_id() { local i; for i in 1 2 3 4 5; do scroll_up; done; for i in 1 2 3 4 5 6 7 8; do dump; find_node resource-id "$1" >/dev/null && return 0; scroll_down; done; return 1; }
# a text in the middle band of the screen (proves a list position), skipping the sheet if one is up
mid_anchor() {
  python3 - "$O/ui.xml" "$SH" <<'PY'
import sys, re, xml.etree.ElementTree as ET
try: r = ET.parse(sys.argv[1]).getroot()
except Exception: sys.exit(0)
H = int(sys.argv[2])
def walk(n, in_sheet):
    rid = n.get('resource-id','').split('/')[-1]
    in_sheet = in_sheet or rid in ('sheet',)
    t = n.get('text','')
    m = re.findall(r'\d+', n.get('bounds',''))
    if not in_sheet and t and len(t) > 3 and len(m) == 4 and not re.match(r'^-?\d+:\d\d$', t):
        y = (int(m[1]) + int(m[3])) // 2
        if 0.28 * H < y < 0.66 * H: print(t); sys.exit(0)
    for k in n: walk(k, in_sheet)
walk(r, False)
PY
}
# menu_back_check <label> <tap|long> <resource-id> [queue]: opening an options menu never navigates, and Back
# closes ONLY the menu: same page, same scroll anchor, same Now Playing state (and the Queue sheet for "queue")
menu_back_check() {
  local label=$1 how=$2 rid=$3 mode=${4:-}
  dump
  if ! find_node resource-id "$rid" >/dev/null; then log "WARN: menu check '$label' skipped ($rid not on screen)"; return; fi
  local anchor npb; anchor=$(mid_anchor); np_open && npb=1 || npb=0
  if [ "$mode" = queue ]; then anchor=$(texts_of queue_user_title | head -1); [ -z "$anchor" ] && anchor=$(python3 - "$O/ui.xml" <<'PY'
import sys, xml.etree.ElementTree as ET
r = ET.parse(sys.argv[1]).getroot()
for n in r.iter('node'):
    if n.get('resource-id','').endswith('queue_recent'):
        for k in n.iter('node'):
            t = k.get('text','')
            if len(t) > 3: print(t); sys.exit(0)
PY
); fi
  if [ "$how" = long ]; then long_press resource-id "$rid" || return; else id "$rid" || return; fi
  if ! wait_for resource-id sheet 6; then log "MISSING (required): menu '$label' did not open"; FAILS=$((FAILS + 1)); shot "missing-menu-$label"; return; fi
  shot "menu-$label"
  [ -n "$anchor" ] && { find_node text "$anchor" >/dev/null || log "WARN: '$label' anchor not in the tree while the menu is up"; }
  back; sleep 1
  dump
  local ok=1 why=""
  if [ "$mode" = queue ]; then
    find_node resource-id queue_sheet >/dev/null || { ok=0; why="queue sheet gone"; }
    find_node resource-id queue_recent >/dev/null || { ok=0; why="$why; Recently played tab not kept"; }
  else
    find_node resource-id sheet >/dev/null && { ok=0; why="menu still open"; }
  fi
  [ -n "$anchor" ] && { find_node text "$anchor" >/dev/null || { ok=0; why="$why; anchor '$anchor' gone (page/scroll changed)"; }; }
  local npa; np_open && npa=1 || npa=0
  [ "$npa" = "$npb" ] || { ok=0; why="$why; Now Playing $npb -> $npa"; }
  if [ $ok = 1 ]; then log "menu back ok: $label (anchor '$anchor', np=$npa)"; else log "MISSING (required): menu back '$label': $why"; FAILS=$((FAILS + 1)); shot "missing-menuback-$label"; fi
  timeout 10 adb logcat -d -s itune-nav:I 2>/dev/null | tail -n 1 | sed 's/^/[ui]   /' | tee -a "$O/steps.txt"
}
# submit a search: IME Enter, and if the suggestions list is still up (Enter not delivered on some images), tap the
# suggestion row that repeats the query
has_results() { find_node resource-id track_row >/dev/null || find_node resource-id search_album >/dev/null || find_node resource-id search_artist >/dev/null || find_node resource-id search_playlist >/dev/null; }
submit_search() {
  adb shell input keyevent KEYCODE_ENTER
  sleep 1; hide_kb
  local i
  for i in $(seq 1 10); do dump; has_results && return 0; sleep 1; done
  local xy
  xy=$(python3 - "$O/ui.xml" "$1" <<'PY'
import sys, re, xml.etree.ElementTree as ET
try: r = ET.parse(sys.argv[1]).getroot()
except Exception: sys.exit(1)
for n in r.iter('node'):
    if n.get('text','') == sys.argv[2] and 'EditText' not in n.get('class',''):
        m = list(map(int, re.findall(r'\d+', n.get('bounds',''))))
        if len(m) == 4: print((m[0]+m[2])//2, (m[1]+m[3])//2); break
PY
)
  if [ -n "$xy" ]; then log "search: Enter not taken, tapping the suggestion"; adb shell input tap $xy; sleep 2; hide_kb; fi
  return 0
}

adb shell am start -W -n $PKG/dev.benfranklin.itune.MainActivity >/dev/null
sleep 12
# dismiss the battery / notification prompts if they appeared
adb shell input keyevent KEYCODE_BACK; sleep 1
adb shell am start -W -n $PKG/dev.benfranklin.itune.MainActivity >/dev/null; sleep 3
crash_check launch

# ---------------------------------------------------------------- Liquid Glass state (tag itune-glass)
# The app logs at start whether this device draws live blur and why. Emulator: false (flat fallback), unless the
# informational leg forces the real Haze path (SMOKE_BLUR=1). Phones: always true (unit test GlassPolicyTest);
# real-device blur itself can't be checked visually on CI.
GL=$(timeout 10 adb logcat -d -s itune-glass:I 2>/dev/null | grep "liveBlur=" | tail -n 1)
[ -z "$GL" ] && GL=$(grep "itune-glass" "$O/../logcat-live.txt" 2>/dev/null | grep "liveBlur=" | tail -n 1)
log "glass: ${GL#*: }"
if [ -z "$GL" ]; then log "MISSING (required): itune-glass start line"; FAILS=$((FAILS + 1))
elif [ "${SMOKE_BLUR:-0}" = 1 ]; then echo "$GL" | grep -q "liveBlur=true" || { log "MISSING (required): forced live blur on the blur leg"; FAILS=$((FAILS + 1)); }
else echo "$GL" | grep -q "liveBlur=false reason=emulator" || { log "MISSING (required): emulator must report liveBlur=false reason=emulator:*"; FAILS=$((FAILS + 1)); }
fi

# ---------------------------------------------------------------- Home
log "== Home"
wait_for resource-id home_list 20 || { log "home_list missing"; FAILS=$((FAILS + 1)); }
shot home
SHELVES=0
for i in $(seq 1 25); do
  dump
  if find_node text "Quick picks" >/dev/null || find_node text "Daily Mixes" >/dev/null || find_node text "Trending" >/dev/null || find_node text "New releases" >/dev/null; then SHELVES=1; break; fi
  if find_node resource-id home_retry >/dev/null; then id home_retry 0; fi
  scroll_down
done
log "home shelves visible: $SHELVES"
# frame stats while flinging Home (emulator renders in software, so recorded, not gated)
gfx() { timeout 20 adb shell dumpsys gfxinfo $PKG > "$O/gfx-$1.txt" 2>&1; log "gfx $1: $(grep -E 'Total frames rendered|Janky frames:' "$O/gfx-$1.txt" | tr -s ' ' | tr '\n' ' ')"; }
timeout 20 adb shell dumpsys meminfo $PKG > "$O/meminfo-home.txt" 2>&1
timeout 10 adb shell dumpsys gfxinfo $PKG reset >/dev/null 2>&1
for _ in 1 2 3 4 5 6; do adb shell input swipe $(px 50) $(py 68) $(px 50) $(py 18) 150; sleep 0.4; done
for _ in 1 2 3 4 5 6; do adb shell input swipe $(px 50) $(py 18) $(px 50) $(py 68) 150; sleep 0.4; done
gfx home
[ $SHELVES = 1 ] || log "WARN: no home shelves seen (emulator network?)"
shot home-scrolled
for _ in 1 2 3 4 5 6 7 8; do scroll_up; dump; find_node resource-id isa_length >/dev/null && break; done

# ---------------------------------------------------------------- Isa command card
log "== Isa"
id isa_length; id isa_length; id isa_length; id isa_length
id isa_field
type_text "focus music"
hide_kb
if [ "${SMOKE_CI:-0}" = 1 ]; then
  # CI only: the software-GPU emulator died right after the send animation several runs in a row, and Isa
  # needs network the runner's IP can't use anyway. Field + chips are still checked; phones run the full flow.
  log "Isa send skipped on the CI emulator"
  ISA_SENT=0
else
  id isa_send; ISA_SENT=1
fi
if [ $ISA_SENT = 1 ] && wait_for resource-id isa_preview 45; then
  shot isa-preview
  id isa_play
  sleep 4
  id isa_save 0
elif [ $ISA_SENT = 1 ]; then
  dump
  if find_node resource-id isa_retry >/dev/null; then log "isa error state shown"; id isa_retry 0; shot isa-error; else log "WARN: isa produced neither preview nor error"; fi
fi
id isa_clear 0
[ "${SMOKE_CI:-0}" = 1 ] || { id mood_Focus; sleep 3; }
shot isa-mood
id isa_clear 0
id isa_mic 0 && back

# ---------------------------------------------------------------- Library: create playlist
log "== Library / playlists"
tab Library
shot library
# show the soft keyboard even with the emulator's hardware keyboard, so the sheet-above-IME check is real
timeout 10 adb shell settings put secure show_ime_with_hard_keyboard 1
timeout 10 adb logcat -c >/dev/null 2>&1
id lib_create
# the name field focuses itself and the sheet rides above the keyboard: type straight away, then tap Create
# while the keyboard is still up (a sheet hidden behind the IME would make this tap miss)
sleep 1.5
dump; find_node resource-id name_field >/dev/null || id name_field
type_text "Smoke list"
shot name-sheet-ime
sleep 2
IME=$(timeout 10 adb logcat -d -s itune-ime:I 2>/dev/null | grep -oE 'ime=[0-9]+' | tail -1 | cut -d= -f2)
dump
if [ -n "$IME" ] && [ "$IME" -gt 0 ] && XY=$(find_node resource-id name_save); then
  read _ SY <<< "$XY"
  if [ "$SY" -lt $(( SH - IME )) ]; then log "name sheet rides above the keyboard (keyboard ${IME}px, Create at y=$SY)"; shot name-sheet-ime-up
  else log "MISSING (required): name sheet above the keyboard (keyboard ${IME}px, Create at y=$SY of $SH)"; FAILS=$((FAILS + 1)); shot missing-ime; fi
else
  log "WARN: no soft keyboard inset reported for the name field (ime='${IME:-}')"
fi
id name_save
sleep 2
shot playlist-new
back
if wait_for resource-id "pl_Smoke list" 6; then log "new playlist in Library live"; shot library-new-playlist
else log "MISSING (required): new playlist in Library without a relaunch"; FAILS=$((FAILS + 1)); shot missing-new-playlist; fi
timeout 10 adb shell settings put secure show_ime_with_hard_keyboard 0
id lib_create
id name_cancel          # cancel creates nothing
id lib_sort; id lib_sort; id lib_sort
id lib_grid; shot library-grid; id lib_grid
id lib_Albums; id lib_Artists; id lib_Playlists

# ---------------------------------------------------------------- Search, play, add to playlist
log "== Search"
tab Search
shot search-empty
id search_field
type_text "adele hello"
submit_search "adele hello"
if wait_for resource-id track_row 30; then
  for f in Songs Albums Artists Playlists Videos All; do id "filter_$f" 0; done
  shot search-results
  # add the first song to the new playlist
  id track_more
  txt "Add to playlist"
  timeout 10 adb logcat -c >/dev/null 2>&1
  ADDED=0
  if wait_for text "Smoke list" 8; then
    adb shell input tap $(find_node text "Smoke list")
    for _ in 1 2 3 4 5; do dump; find_node text "Added to Smoke list" >/dev/null && { ADDED=1; shot added-toast; break; }; done
    [ $ADDED = 1 ] || { timeout 15 adb logcat -d -s itune-toast:I 2>/dev/null | grep -q "Added to Smoke list" && ADDED=2; }
    crash_check "add to playlist"
  fi
  case $ADDED in
    1) log "add to playlist ok (toast seen)";;
    2) log "add to playlist ok (toast logged; gone before the screen dump)";;
    *) log "MISSING (required): Added to Smoke list toast"; FAILS=$((FAILS + 1)); shot missing-added;;
  esac
  # like the first song from its menu (checked live in Library after Now Playing)
  id track_more
  txt "Like"
  back
  # play the first song
  id track_row
  sleep 6
  shot playing
  # two more songs into "Next in queue" (for the reorder check)
  QADD=0
  for n in 2 3; do
    dump
    if XY=$(nth_node track_more $n); then
      adb shell input tap $XY; sleep 1.2
      tap text "Add to queue" 0 && QADD=$((QADD + 1))
    fi
  done
  log "added $QADD songs to the queue"
else
  log "MISSING (required): search results"; FAILS=$((FAILS + 1))
fi

# ---------------------------------------------------------------- Now Playing
log "== Now Playing"
if id mini_player 0 12; then
  shot now-playing
  id np_lyrics; shot lyrics; id np_lyrics
  id np_like; id np_like
  # speed + sleep live in the ... menu now
  id np_more; tap text "Playback speed" 1 contains; shot speed; back
  # a sheet opened from the menu goes back ONE level: to the track menu, not past it
  if wait_for text "Sleep timer" 5; then log "back from Playback speed -> track menu"
  else log "MISSING (required): Back from Playback speed returns to the track menu"; FAILS=$((FAILS + 1)); shot missing-speed-back; id np_more; fi
  txt "Sleep timer"; txt "15m" 0
  id np_more; txt "Sleep timer"; txt "Turn off" 0
  # pause first: on the CI runner every stream hits the bot check and a playing queue keeps auto-skipping,
  # which shifts "Next in queue" under the reorder check and changes the song before the session check
  timeout 10 adb shell input keyevent KEYCODE_MEDIA_PAUSE; sleep 2
  # the play button follows the play intent (Pause is shown while a stream loads/retries): if it still says
  # Pause, the key didn't reach the player, so tap it
  dump
  if XY=$(find_node content-desc "Pause"); then adb shell input tap $XY; sleep 1.5; log "paused with the play button"; fi
  dump; find_node content-desc "Pause" >/dev/null && log "WARN: still shows Pause after pausing" || log "paused for the queue/session checks"
  timeout 10 adb logcat -d -s itune-session:I 2>/dev/null | grep -E "playWhenReady=" | tail -n 3 | sed 's/^/[ui]   /' | tee -a "$O/steps.txt"
  sleep 1
  id np_queue
  shot queue
  # drag-to-reorder: drag the first "Next in queue" row's handle below the second, then check the order
  dump
  mapfile -t QT < <(texts_of queue_user_title)
  # first adjacent pair with different titles (several covers share a title), dragged k below k+1
  K=0
  while [ $((K + 1)) -lt ${#QT[@]} ] && [ "${QT[$K]}" = "${QT[$((K + 1))]}" ]; do K=$((K + 1)); done
  if [ $((K + 1)) -lt ${#QT[@]} ]; then QT=("${QT[$K]}" "${QT[$((K + 1))]}"); else QT=(); fi
  if [ ${#QT[@]} -ge 2 ] && H1=$(nth_node queue_handle $((K + 1))) && H2=$(nth_node queue_handle $((K + 2))); then
    read X1 Y1 <<< "$H1"; read X2 Y2 <<< "$H2"
    Y3=$(( Y2 + (Y2 - Y1) * 6 / 10 ))
    adb shell input swipe $X1 $Y1 $X1 $Y3 1400
    sleep 2.5; crash_check "queue reorder"
    dump
    mapfile -t QA < <(texts_of queue_user_title)
    shot queue-reordered
    # the two dragged songs must now be in swapped order (relative, in case the list moved meanwhile)
    I0=-1; I1=-1
    for k in "${!QA[@]}"; do [ "${QA[$k]}" = "${QT[0]}" ] && [ $I0 -lt 0 ] && I0=$k; [ "${QA[$k]}" = "${QT[1]}" ] && [ $I1 -lt 0 ] && I1=$k; done
    if [ $I0 -ge 0 ] && [ $I1 -ge 0 ] && [ $I1 -lt $I0 ]; then log "queue reorder ok (${QT[0]} <-> ${QT[1]})"
    else
      log "MISSING (required): queue reorder (before: ${QT[0]} | ${QT[1]}; after: ${QA[0]:-?} | ${QA[1]:-?})"; FAILS=$((FAILS + 1))
      timeout 10 adb logcat -d -s itune-session:I 2>/dev/null | grep -E " move |track idx" | tail -n 6 | sed 's/^/[ui]   /' | tee -a "$O/steps.txt"
    fi
  else
    log "MISSING (required): two Next in queue rows to reorder (${#QT[@]} found)"; FAILS=$((FAILS + 1)); shot missing-queue-rows
  fi
  id queue_tab_1; shot queue-recent; id queue_tab_0
  id sheet_close            # obvious close on the queue sheet
  sleep 0.8; dump; find_node resource-id queue_sheet >/dev/null && { log "WARN: queue sheet still open after close: BACK"; back; }
  # Now Playing scrolls on to lyrics + up next
  scroll_down; scroll_down; shot np-scrolled
  dump; find_node resource-id np_upnext >/dev/null && log "np up next visible" || log "WARN: up next not visible after scrolling NP"
  # back to the top with short, slow drags (each < 25% of the height, so at the top they spring back and never
  # minimise Now Playing)
  for _ in 1 2 3 4 5 6 7 8 9 10 11 12; do
    for _ in 1 2 3; do adb shell input swipe $(px 50) $(py 40) $(px 50) $(py 56) 450; sleep 0.5; done
    dump; find_node resource-id np_close >/dev/null && break
    np_open || break
  done
  crash_check "np scroll to top"
  # swipe down anywhere on Now Playing minimises it to the mini player
  dump
  if find_node resource-id np_close >/dev/null; then
    adb shell input swipe $(px 50) $(py 24) $(px 50) $(py 82) 300
    sleep 2; crash_check "np swipe down"
    dump
    if find_node resource-id mini_player >/dev/null && ! find_node resource-id np_close >/dev/null; then log "np swipe down -> mini player ok"; shot np-swiped
    else log "MISSING (required): swipe down on Now Playing back to the mini player"; FAILS=$((FAILS + 1)); shot missing-np-swipe; close_np; fi
  else
    log "MISSING (required): Now Playing top not reachable for the swipe check"; FAILS=$((FAILS + 1)); shot missing-np-top; close_np
  fi
else
  log "WARN: mini player not shown (stream unavailable on the emulator?)"
fi

# ---------------------------------------------------------------- Now Playing stress: stays responsive, title scrolls with the page
# (0.2.2 on a phone: "stuck, can't move" on Now Playing and the title pinned over the controls while scrolling)
log "== Now Playing stress"
# y (centre) of the first node with <resource-id>, or empty
node_y() { local xy; xy=$(find_node resource-id "$1") && echo "${xy#* }"; }
# node_bounds <resource-id> -> "x1 y1 x2 y2" of the first match
node_bounds() {
  python3 - "$O/ui.xml" "$1" <<'PY2'
import sys, re, xml.etree.ElementTree as ET
try: r = ET.parse(sys.argv[1]).getroot()
except Exception: sys.exit(1)
for n in r.iter('node'):
    if n.get('resource-id','').split('/')[-1] != sys.argv[2]: continue
    m = list(map(int, re.findall(r'\d+', n.get('bounds',''))))
    if len(m) == 4 and m[2] > m[0] and m[3] > m[1]: print(*m); sys.exit(0)
sys.exit(1)
PY2
}
# glass_check <name>: the floating mini player + nav must show the content scrolling under them (0.2.7).
# Two screenshots with the list moved ~22% of the screen between them (scrolling UP, so the chrome stays shown);
# inside the mini player / nav bounds (inset, minus the progress hairline) the pixels must change when the glass
# blurs live (SMOKE_BLUR=1 leg). The flat-glass legs draw the opaque fallback there by design: logged only.
glass_check() {
  local name=$1 MB NB
  scroll_down; scroll_down
  adb shell input swipe $(px 50) $(py 30) $(px 50) $(py 52) 400; sleep 1.5; crash_check "glass $name"
  dump; MB=$(node_bounds mini_player); NB=$(node_bounds bottom_nav)
  timeout 20 adb exec-out screencap -p > "$O/glass-$name-a.png" 2>/dev/null; shot "glass-$name"
  adb shell input swipe $(px 50) $(py 30) $(px 50) $(py 52) 400; sleep 1.5; crash_check "glass $name"
  timeout 20 adb exec-out screencap -p > "$O/glass-$name-b.png" 2>/dev/null
  if [ -z "$NB" ]; then log "MISSING (required): bottom nav on $name for the glass check"; FAILS=$((FAILS + 1)); return; fi
  local R; R=$(python3 - "$O/glass-$name-a.png" "$O/glass-$name-b.png" "${MB:-}" "$NB" <<'PY2'
import sys, zlib, struct
def png(path):
    d = open(path, 'rb').read()
    assert d[:8] == b'\x89PNG\r\n\x1a\n'
    i, idat, w = 8, b'', 0
    while i < len(d):
        n, t = struct.unpack('>I4s', d[i:i+8]); c = d[i+8:i+8+n]; i += 12 + n
        if t == b'IHDR': w, h, bd, ct = struct.unpack('>IIBB', c[:10]); bpp = {6: 4, 2: 3}[ct]
        elif t == b'IDAT': idat += c
    raw = zlib.decompress(idat); st = w * bpp; rows = []; prev = bytearray(st); p = 0
    for _ in range(h):
        f = raw[p]; line = bytearray(raw[p+1:p+1+st]); p += 1 + st
        for x in range(st):
            a = line[x-bpp] if x >= bpp else 0; b = prev[x]; cc = prev[x-bpp] if x >= bpp else 0
            if f == 1: line[x] = (line[x] + a) & 255
            elif f == 2: line[x] = (line[x] + b) & 255
            elif f == 3: line[x] = (line[x] + ((a + b) >> 1)) & 255
            elif f == 4:
                pa, pb, pc = abs(b - cc), abs(a - cc), abs(a + b - 2 * cc)
                line[x] = (line[x] + (a if pa <= pb and pa <= pc else b if pb <= pc else cc)) & 255
        rows.append(line); prev = line
    return w, bpp, rows
wa, ba, A = png(sys.argv[1]); wb, bb, B = png(sys.argv[2])
def diff(bounds, inset_bottom):
    if not bounds: return None
    x1, y1, x2, y2 = map(int, bounds.split())
    x1 += 24; x2 -= 24; y1 += 10; y2 -= inset_bottom
    tot = n = 0
    for y in range(y1, y2, 2):
        ra, rb = A[y], B[y]
        for x in range(x1, x2, 2):
            for k in range(3): tot += abs(ra[x*ba+k] - rb[x*bb+k])
            n += 3
    return tot / max(n, 1)
m = diff(sys.argv[3], 16); nv = diff(sys.argv[4], 10)
print('%s %.2f' % ('-' if m is None else '%.2f' % m, nv))
PY2
)
  local MD=${R% *} ND=${R#* }
  log "glass $name: content change under mini player=${MD:-?} nav=${ND:-?} (mean |diff| 0-255)"
  if [ "${SMOKE_BLUR:-0}" = 1 ]; then
    if awk "BEGIN{exit !(${ND:-0} >= 0.6)}" && { [ "$MD" = "-" ] || awk "BEGIN{exit !(${MD:-0} >= 0.6)}"; }; then log "glass $name: rows show through the mini player and nav ok"
    else log "MISSING (required): glass $name shows the content under the mini player / nav (mini=$MD nav=$ND)"; FAILS=$((FAILS + 1)); fi
  else log "glass $name: flat-glass leg (opaque fallback by design), informational"; fi
}
anr_count() { timeout 10 adb logcat -d 2>/dev/null | grep -cE "ANR in $PKG|Input dispatching timed out.*$PKG|Application Not Responding: $PKG"; }
ANR0=$(anr_count)
refocus; close_np
dump
MINI_XY=$(find_node resource-id mini_player || true)
if [ -n "$MINI_XY" ] && id mini_player 0 8; then
  sleep 1; dump
  T0=$(node_y np_title)
  log "np title y at open: ${T0:-none}"
  [ -n "$T0" ] || { log "MISSING (required): np_title on Now Playing"; FAILS=$((FAILS + 1)); }
  timeout 10 adb shell input keyevent KEYCODE_MEDIA_PLAY; sleep 1
  # seek repeatedly (taps along the scrubber, quick succession)
  if SC=$(find_node resource-id np_scrubber); then
    read SCX SCY <<< "$SC"
    W=$(python3 - "$O/ui.xml" <<'PY'
import sys, re, xml.etree.ElementTree as ET
r = ET.parse(sys.argv[1]).getroot()
for n in r.iter('node'):
    if n.get('resource-id','').endswith('np_scrubber'):
        m = list(map(int, re.findall(r'\d+', n.get('bounds','')))); print(m[0], m[2]); break
PY
)
    read SL SR <<< "$W"
    for f in 20 55 30 75 10 45 65 25; do adb shell input tap $(( SL + (SR - SL) * f / 100 )) $SCY; sleep 0.25; done
    sleep 1; crash_check "np repeated seeks"; log "np repeated seeks done"
  else log "WARN: no scrubber for the seek burst"; fi
  # the options menu and sheets open and close, and leave nothing behind that eats taps
  id np_more && { wait_for resource-id sheet 5 && back; }
  id np_queue && { wait_for resource-id queue_sheet 5 && id sheet_close 0; }
  sleep 1
  # close by tapping the scrim above the sheet (top of the screen); Back if the sheet covers that on a short screen
  if id np_more && wait_for resource-id sheet 5; then
    adb shell input tap $(px 50) $(py 4); sleep 1; dump
    find_node resource-id sheet >/dev/null && { log "WARN: scrim tap did not close the menu (sheet covers the top?): Back"; back; }
  fi
  sleep 1; dump
  if find_node resource-id sheet >/dev/null; then log "MISSING (required): a sheet stayed open after closing it on Now Playing"; FAILS=$((FAILS + 1)); shot missing-np-sheet-closed; back
  else log "np sheets open/close ok"; fi
  # a tap right after the sheets reaches Now Playing (no invisible layer left over)
  id np_lyrics && { sleep 0.8; id np_lyrics; } || { log "MISSING (required): tap on Now Playing after closing sheets"; FAILS=$((FAILS + 1)); }
  # scroll the page: the title must move with it (not stay pinned near where it was)
  scroll_down; dump
  T1=$(node_y np_title)
  if [ -n "$T0" ]; then
    if [ -z "$T1" ]; then log "np title scrolled off with the page"
    elif [ $(( T0 - T1 )) -gt $(( SH / 20 )) ]; then log "np title scrolled with the page ($T0 -> $T1)"
    else log "MISSING (required): np title stayed put while the page scrolled ($T0 -> $T1)"; FAILS=$((FAILS + 1)); shot missing-np-title-scroll; fi
  fi
  shot np-stress-scrolled
  # drag inside the lyrics both ways: lyrics scroll, Now Playing stays open (a pull inside them never minimises it)
  if LY=$(find_node resource-id np_lyrics_list); then
    read LX LYY <<< "$LY"
    adb shell input swipe $LX $(( LYY + SH / 12 )) $LX $(( LYY - SH / 12 )) 350; sleep 0.6
    adb shell input swipe $LX $(( LYY - SH / 12 )) $LX $(( LYY + SH / 12 )) 350; sleep 0.6
    adb shell input swipe $LX $(( LYY - SH / 12 )) $LX $(( LYY + SH / 12 )) 200; sleep 1
    dump; np_open && log "lyrics drags kept Now Playing open" || { log "MISSING (required): dragging the lyrics closed Now Playing"; FAILS=$((FAILS + 1)); id mini_player 0; }
  else log "WARN: lyrics list not on screen (no lyrics for this song?)"; fi
  scroll_down; scroll_down; dump
  find_node resource-id np_upnext >/dev/null && log "np up next reached" || log "WARN: up next not visible in the stress pass"
  # Back while scrolled closes Now Playing; reopening shows it exactly as the first time (top, title in place)
  back; sleep 1; dump
  if np_open; then log "MISSING (required): Back on a scrolled Now Playing did not close it"; FAILS=$((FAILS + 1)); close_np; fi
  if id mini_player 0 6; then
    sleep 1; dump; T2=$(node_y np_title)
    if [ -n "$T0" ] && [ -n "$T2" ] && [ $(( T2 > T0 ? T2 - T0 : T0 - T2 )) -le 12 ]; then log "np reopens the same (title y $T0 -> $T2)"
    else log "MISSING (required): Now Playing reopened differently (title y ${T0:-?} -> ${T2:-none})"; FAILS=$((FAILS + 1)); shot missing-np-reopen; fi
  fi
  # swipe down to dismiss, then reopen IMMEDIATELY (inside the collapse): the reopened page must still scroll + go Back
  dump
  if find_node resource-id np_close >/dev/null && [ -n "$MINI_XY" ]; then
    adb shell "input swipe $(px 50) $(py 24) $(px 50) $(py 82) 250; input tap $MINI_XY"
    sleep 1.5; crash_check "np swipe + instant reopen"; dump
    if ! np_open; then id mini_player 0 4; sleep 1; dump; fi
    if np_open; then
      T3=$(node_y np_title); scroll_down; dump; T4=$(node_y np_title)
      if [ -n "$T3" ] && { [ -z "$T4" ] || [ $(( T3 - T4 )) -gt $(( SH / 20 )) ]; }; then log "reopened Now Playing scrolls ($T3 -> ${T4:-off})"
      else log "MISSING (required): reopened Now Playing does not scroll ($T3 -> ${T4:-?})"; FAILS=$((FAILS + 1)); shot missing-np-reopen-scroll; fi
      back; sleep 1; dump
      np_open && { log "MISSING (required): Back on the reopened Now Playing did nothing"; FAILS=$((FAILS + 1)); close_np; } || log "Back closes the reopened Now Playing"
    else log "WARN: Now Playing did not reopen after the swipe"; fi
  fi
  # responsiveness probe: a tab tap after all of that lands within a few seconds
  refocus; close_np
  T_0=$SECONDS
  if tab Search && wait_for resource-id search_field 6; then log "UI responsive after Now Playing stress ($((SECONDS - T_0))s)"
  else log "MISSING (required): UI unresponsive after Now Playing stress"; FAILS=$((FAILS + 1)); shot missing-responsive; fi
  ANR1=$(anr_count)
  if [ "$ANR1" -gt "$ANR0" ]; then log "MISSING (required): ANR during Now Playing stress"; FAILS=$((FAILS + 1)); timeout 10 adb logcat -d 2>/dev/null | grep -E -A12 "ANR in $PKG" | head -40 | sed 's/^/[ui]   /' | tee -a "$O/steps.txt"
  else log "no ANR during Now Playing stress"; fi
  timeout 10 adb logcat -d -s itune-session:I 2>/dev/null | grep -cE " saved " | sed 's/^/[ui]   session saves so far: /' | tee -a "$O/steps.txt"
  gfx np-stress
else
  log "WARN: mini player not shown for the Now Playing stress pass"
fi

# ---------------------------------------------------------------- liked song shows in Library live (no relaunch)
log "== Liked Songs live"
tab Library
LIKED=""
for _ in 1 2 3 4 5 6; do
  dump
  LIKED=$(python3 - "$O/ui.xml" <<'PY'
import sys, xml.etree.ElementTree as ET
try: r = ET.parse(sys.argv[1]).getroot()
except Exception: sys.exit(0)
for n in r.iter('node'):
    if n.get('resource-id','').endswith('lib_liked_count'): print(n.get('text','')); break
PY
)
  [ -n "$LIKED" ] && break
  scroll_up
done
N=$(echo "$LIKED" | grep -oE '^[0-9]+' || true)
if [ -n "$N" ] && [ "$N" -ge 1 ]; then
  log "liked songs tile live: $LIKED"
  id lib_liked
  wait_for resource-id track_row 8 && log "liked songs list shows the song" || { log "MISSING (required): liked song in Liked Songs list"; FAILS=$((FAILS + 1)); }
  shot liked-songs
  back
else
  log "MISSING (required): Liked Songs count >= 1 without relaunch (got '$LIKED')"; FAILS=$((FAILS + 1)); shot missing-liked
fi

# ---------------------------------------------------------------- session restore: force-stop + relaunch keeps the song
log "== Session restore"
refocus; close_np
# seek to 1:00 on the scrubber (tap at 60 s / duration), so the restore can be checked for position too
SEEK=0
if id mini_player 0; then
  sleep 1.5; dump
  read SX1 SX2 SY DUR < <(python3 - "$O/ui.xml" <<'PY'
import sys, re, xml.etree.ElementTree as ET
r = ET.parse(sys.argv[1]).getroot()
sc = None; times = []
for n in r.iter('node'):
    rid = n.get('resource-id','').split('/')[-1]
    m = list(map(int, re.findall(r'\d+', n.get('bounds',''))))
    if rid == 'np_scrubber' and len(m) == 4: sc = m
    t = n.get('text','')
    mm = re.match(r'^(-?)(\d+):(\d\d)$', t)
    if mm and len(times) < 2: times.append(int(mm.group(2)) * 60 + int(mm.group(3)))
if sc and len(times) == 2: print(sc[0], sc[2], (sc[1] + sc[3]) // 2, times[0] + times[1])
PY
)
  if [ -n "${DUR:-}" ] && [ "$DUR" -gt 75 ]; then
    TX=$(( SX1 + (SX2 - SX1) * 60 / DUR ))
    adb shell input tap $TX $SY; sleep 1.5; crash_check "seek"
    SEEK=1; log "seek to ~1:00 (duration ${DUR}s, tap x=$TX)"
  else log "WARN: no scrubber/duration for the 1:00 seek (dur='${DUR:-}')"; fi
  close_np
fi
dump
BEFORE=$(python3 - "$O/ui.xml" <<'PY'
import sys, xml.etree.ElementTree as ET
try: r = ET.parse(sys.argv[1]).getroot()
except Exception: sys.exit(0)
for n in r.iter('node'):
    if n.get('resource-id','').endswith('mini_title'): print(n.get('text','')); break
PY
)
if [ -n "$BEFORE" ]; then
  timeout 10 adb logcat -c >/dev/null 2>&1
  timeout 10 adb shell input keyevent KEYCODE_HOME; sleep 2
  # the app logs every session save (each track change, pause, onStop) with the player's current title; if a
  # stream error moved the player on between the screen read and the kill, the last save is the song it was on
  SAVED=$(timeout 10 adb logcat -d -s itune-session:I 2>/dev/null | grep " saved " | tail -n 1)
  timeout 15 adb shell am force-stop $PKG; sleep 2
  LAST=$(echo "$SAVED" | sed -n 's/.* title=//p' | sed 's/ (sync: [^)]*)$//')
  [ -n "$SAVED" ] && log "  last save: ${SAVED#*: }"
  if [ -n "$LAST" ] && [ "$LAST" != "$BEFORE" ]; then log "WARN: the player moved on before the kill ($BEFORE -> $LAST); expecting $LAST"; BEFORE="$LAST"; fi
  timeout 10 adb logcat -c >/dev/null 2>&1
  timeout 30 adb shell am start -W -n $PKG/dev.benfranklin.itune.MainActivity >/dev/null; sleep 6
  if wait_for text "$BEFORE" 15; then log "session restored: $BEFORE"; shot session-restored
  else log "MISSING (required): restored session ($BEFORE)"; FAILS=$((FAILS + 1)); shot missing-session; fi
  RESTORED=$(timeout 10 adb logcat -d -s itune-session:I 2>/dev/null | grep " restored " | tail -n 1)
  echo "[ui]   ${RESTORED#*: }" | tee -a "$O/steps.txt"
  # same position (+-3 s of the last save, and of 1:00 when the seek took) and the same queue length
  SP=$(echo "$SAVED" | sed -n 's/.* pos=\([0-9]*\).*/\1/p'); SN=$(echo "$SAVED" | sed -n 's/.* core=-\{0,1\}[0-9]*\/\([0-9]*\).*/\1/p')
  RP=$(echo "$RESTORED" | sed -n 's/.* pos=\([0-9]*\).*/\1/p'); RN=$(echo "$RESTORED" | sed -n 's/.* n=\([0-9]*\).*/\1/p')
  if [ -n "$SP" ] && [ -n "$RP" ]; then
    D=$(( RP > SP ? RP - SP : SP - RP ))
    [ $D -le 3000 ] && log "session position kept (${SP} -> ${RP} ms)" || { log "MISSING (required): session position (${SP} -> ${RP} ms)"; FAILS=$((FAILS + 1)); }
    if [ $SEEK = 1 ]; then
      D6=$(( RP > 60000 ? RP - 60000 : 60000 - RP ))
      [ $D6 -le 3000 ] && log "restored at ~1:00 ($RP ms)" || log "WARN: restored at $RP ms, not ~1:00 (the scrubber tap may have missed)"
    fi
  else log "MISSING (required): session save/restore positions in the log (saved='$SP' restored='$RP')"; FAILS=$((FAILS + 1)); fi
  if [ -n "$SN" ] && [ -n "$RN" ]; then
    [ "$SN" = "$RN" ] && log "queue length kept ($RN)" || { log "MISSING (required): queue length ($SN -> $RN)"; FAILS=$((FAILS + 1)); }
  fi
else
  log "WARN: no mini player title to compare (stream unavailable?)"
fi

# ---------------------------------------------------------------- options menus: open never navigates, Back closes only it
log "== Options menus + Back"
home_tab
for _ in 1 2 3 4 5 6; do dump; find_node resource-id recent_item >/dev/null && break; scroll_down; done
menu_back_check home-recent long recent_item
tab Search
menu_back_check search-track tap track_more
tab Library
menu_back_check library-playlist tap lib_more
if id "pl_Smoke list" 0; then sleep 2; menu_back_check playlist-track tap track_more; back; fi
if id mini_player 0; then
  sleep 1.5
  menu_back_check nowplaying tap np_more
  if id np_queue 0; then
    id queue_tab_1 0; sleep 1.5
    menu_back_check queue-recent tap qrecent_more queue
    id queue_tab_0 0
    id sheet_close 0
  fi
  close_np
fi

# ---------------------------------------------------------------- cached Home: offline cold start still shows the shelves
log "== Cached Home"
if [ $SHELVES = 1 ]; then
  home_tab   # the relaunch reopens the last tab
  timeout 10 adb shell input keyevent KEYCODE_HOME; sleep 1
  timeout 15 adb shell am force-stop $PKG; sleep 1
  timeout 10 adb shell svc wifi disable; timeout 10 adb shell svc data disable; sleep 2
  timeout 30 adb shell am start -W -n $PKG/dev.benfranklin.itune.MainActivity >/dev/null; sleep 3
  crash_check "offline relaunch"
  CACHED=0
  for _ in 1 2 3 4 5 6 7 8; do
    dump
    if find_node text "Quick picks" >/dev/null || find_node text "Recently played" >/dev/null || find_node text "Daily Mixes" >/dev/null || find_node resource-id shelf_item >/dev/null || find_node text "Trending" >/dev/null; then CACHED=1; break; fi
    adb shell input swipe $(px 50) $(py 68) $(px 50) $(py 30) 300; sleep 1
  done
  shot home-cached-offline
  timeout 10 adb shell svc wifi enable; timeout 10 adb shell svc data enable; sleep 3
  [ $CACHED = 1 ] && log "cached home shown offline" || { log "MISSING (required): cached Home shelves offline"; FAILS=$((FAILS + 1)); }
  for _ in 1 2 3 4 5 6; do scroll_up; done
else
  log "WARN: cached Home not checked (no shelves were loaded online first)"
fi

# ---------------------------------------------------------------- scroll position kept across push/pop
log "== Scroll restore"
home_tab
for _ in 1 2 3; do scroll_down; done
# a card fully inside the content band (not clipped under the status bar / top bar or behind the mini player + nav)
shelf_in_band() {
  python3 - "$O/ui.xml" "$SH" <<'PY'
import sys, re, xml.etree.ElementTree as ET
try: r = ET.parse(sys.argv[1]).getroot()
except Exception: sys.exit(1)
H = int(sys.argv[2])
for n in r.iter('node'):
    if n.get('resource-id','').split('/')[-1] != 'shelf_item': continue
    m = list(map(int, re.findall(r'\d+', n.get('bounds',''))))
    if len(m) == 4 and m[1] > 0.12 * H and m[3] < 0.86 * H and m[2] > m[0]:
        print((m[0] + m[2]) // 2, (m[1] + m[3]) // 2); sys.exit(0)
sys.exit(1)
PY
}
for _ in 1 2 3 4 5 6 7 8; do dump; shelf_in_band >/dev/null && break; scroll_down; done
dump; cp "$O/ui.xml" "$O/ui-before-push.xml"
# a text in the middle of the screen (not the top bar / mini player), so the check proves the list position
FIRST=$(python3 - "$O/ui.xml" "$SH" <<'PY'
import sys, re, xml.etree.ElementTree as ET
r = ET.parse(sys.argv[1]).getroot(); H = int(sys.argv[2])
for n in r.iter('node'):
    t = n.get('text','')
    m = re.findall(r'\d+', n.get('bounds',''))
    if t and len(t) > 3 and len(m) == 4:
        y = (int(m[1]) + int(m[3])) // 2
        if 0.30 * H < y < 0.65 * H: print(t); break
PY
)
log "scroll anchor: $FIRST"
if [ -z "$FIRST" ]; then
  log "MISSING (required): no anchor text for the scroll check"; FAILS=$((FAILS + 1))
elif SXY=$(shelf_in_band); then
  adb shell input tap $SXY; sleep 2; crash_check "tap shelf_item"; log "tap shelf_item ok"
  dump; find_node resource-id back >/dev/null && log "opened page from shelf (header drawn)" || log "WARN: page header not seen after the shelf tap"
  back; sleep 1.5
  if wait_for text "$FIRST" 5; then log "home scroll kept ($FIRST)"; shot scroll-kept; else log "MISSING (required): home scroll position after back ($FIRST)"; FAILS=$((FAILS + 1)); shot missing-scroll; fi
else
  log "MISSING (required): no album/playlist/artist card on Home to test scroll restore"; FAILS=$((FAILS + 1))
fi

# ---------------------------------------------------------------- bookmark: save / unsave album + artist
log "== Save to Library"
tab Search
id search_field
clear_field
type_text "adele 25"
submit_search "adele 25"
id filter_Albums 0 30
if id search_album 1 30; then
  wait_for resource-id track_number 25 && log "album page: track numbers (no repeated art)" || log "WARN: album tracks not loaded"
  shot album
  # in-list search: case/accent-insensitive filter, "No matches", clear, tap plays the FULL list from that song
  log "== List search"
  id list_search_open 1 10
  if id list_search 1 10; then
    timeout 10 adb logcat -c >/dev/null 2>&1
    type_text "LOVE"; sleep 1.5
    FL=$(timeout 10 adb logcat -d -s itune-filter:I 2>/dev/null | grep "len=4 " | tail -n 1)
    log "filter: ${FL:-none}"
    FH=$(echo "$FL" | sed -n 's/.*hits=\([0-9]*\) of=\([0-9]*\).*/\1/p'); FN=$(echo "$FL" | sed -n 's/.*of=\([0-9]*\).*/\1/p')
    if [ -n "$FH" ] && [ "$FH" -ge 1 ] && [ "$FH" -lt "${FN:-0}" ]; then log "list filter ok ($FH of $FN)"; else log "MISSING (required): list filter narrows the album (got '${FL}')"; FAILS=$((FAILS + 1)); fi
    # keyboard still up: the pill must be pinned at the top and a filtered row visible above the keyboard
    dump; shot list-search-keyboard
    IME=$(timeout 10 adb logcat -d -s itune-filter:I 2>/dev/null | grep "ime=" | tail -n 1 | sed -n 's/.*ime=\([0-9]*\).*/\1/p')
    KBTOP=$(( SH - ${IME:-0} ))
    LSY=$(node_y list_search); TRY=$(node_y track_row)
    log "list search: pill y=${LSY:-none} first row y=${TRY:-none} keyboard px=${IME:-0} (top at $KBTOP of $SH)"
    if [ -n "$LSY" ] && [ "$LSY" -lt $(( SH * 18 / 100 )) ]; then log "search pill at the top ok"; else log "MISSING (required): search pill pinned at the top (y=${LSY:-none})"; FAILS=$((FAILS + 1)); fi
    if [ -n "$TRY" ] && [ "$TRY" -gt "${LSY:-0}" ] && [ "$TRY" -lt "$KBTOP" ]; then log "filtered row visible above the keyboard ok"; else log "MISSING (required): filtered row visible with the keyboard open (row y=${TRY:-none}, keyboard top $KBTOP)"; FAILS=$((FAILS + 1)); fi
    kb_done; shot list-filter
    # submit (Enter) only drops the keyboard: the pill and the filtered rows stay
    dump; if find_node resource-id list_search >/dev/null; then log "search stays open after submit ok"; else log "MISSING (required): search stays open after submit"; FAILS=$((FAILS + 1)); fi
    if wait_for resource-id track_row 5; then
      XY=$(find_node resource-id track_row); adb shell input tap $XY; sleep 2; crash_check "filtered row"
      PL=$(timeout 10 adb logcat -d -s itune-filter:I 2>/dev/null | grep "play idx=" | tail -n 1)
      PI=$(echo "$PL" | sed -n 's/.*play idx=\([0-9]*\).*/\1/p'); PO=$(echo "$PL" | sed -n 's/.*of=\([0-9]*\).*/\1/p')
      TR=$(timeout 10 adb logcat -d 2>/dev/null | grep -E "track idx=${PI:-x} " | tail -n 1)
      if [ -n "$PI" ] && [ "${PO:-0}" = "${FN:-x}" ]; then log "filtered tap plays full list from idx $PI of $PO${TR:+ (player: track idx=$PI)}"; else log "MISSING (required): filtered tap plays the full list ('$PL', full=$FN)"; FAILS=$((FAILS + 1)); fi
      close_np
    else log "MISSING (required): filtered rows shown"; FAILS=$((FAILS + 1)); fi
    dump; if ! find_node resource-id list_search >/dev/null; then for _ in 1 2 3; do scroll_up; done; fi
    dump; find_node resource-id list_search >/dev/null || id list_search_open
    id list_search
    clear_field; type_text "zzqxw"; sleep 1.5; kb_done
    wait_for resource-id list_no_matches 5 && log "no matches state ok" || { log "MISSING (required): No matches"; FAILS=$((FAILS + 1)); shot missing-no-matches; }
    id list_search_clear
    wait_for resource-id track_number 5 && log "clear restores the full list" || { log "MISSING (required): full list after clear"; FAILS=$((FAILS + 1)); }
    find_node resource-id list_no_matches >/dev/null && { log "MISSING (required): No matches still shown after clear"; FAILS=$((FAILS + 1)); }
    # × on the empty field tucks the pill away again (header back to its usual layout)
    id list_search_clear
    sleep 1; dump; find_node resource-id list_search >/dev/null && { log "MISSING (required): search pill closes on × when empty"; FAILS=$((FAILS + 1)); } || log "search pill closed"
    # Back closes search (header springs back), not the album page
    id list_search_open 1 10
    if id list_search 1 10; then
      type_text "a"; sleep 1; kb_done
      adb shell input keyevent KEYCODE_BACK; sleep 1.5; crash_check "back closes search"; dump
      # a soft keyboard still up takes the first Back; the next one is the app's
      if find_node resource-id list_search >/dev/null; then adb shell input keyevent KEYCODE_BACK; sleep 1.5; crash_check "back closes search"; dump; fi
      if find_node resource-id list_search >/dev/null; then log "MISSING (required): Back closes list search"; FAILS=$((FAILS + 1));
      elif find_node resource-id list_search_open >/dev/null; then log "back closes search, page kept ok"; else log "MISSING (required): album page kept after Back closed search"; FAILS=$((FAILS + 1)); shot missing-back-search; fi
    fi
  fi
  # the floating glass shows the album rows scrolling under it (0.2.7: they were hidden by opaque chrome / row backing)
  log "== Glass see-through (album page)"
  glass_check album
  for _ in 1 2 3 4; do scroll_up; done
  id save_button
  wait_for text "Saved to Library" 6 || { log "MISSING (required): Saved to Library toast"; FAILS=$((FAILS + 1)); }
  shot album-saved
  back
  tab Library
  id lib_Albums
  if id lib_album 1 10; then
    id save_button
    # the Undo snack shows ~6 s: tap it straight away (its presence proves the "Removed" snack)
    id snack_action 1 6      # Undo -> saved again
    shot album-undo
    back
  fi
  id lib_Playlists
fi
tab Search
id filter_Artists 0
if id search_artist 0 15; then
  id save_button
  wait_for text "Saved to Library" 6 || { log "MISSING (required): artist Saved to Library toast"; FAILS=$((FAILS + 1)); }
  back
  tab Library; id lib_Artists
  wait_for resource-id lib_artist 8 || { log "MISSING (required): saved artist in Library"; FAILS=$((FAILS + 1)); }
  shot library-artists
  id lib_Playlists
fi
tab Search
id filter_Playlists 0
if id search_playlist 0 15; then
  id save_button 0
  shot playlist-saved
  back
fi
id filter_All 0

# ---------------------------------------------------------------- downloads + import
log "== Downloads / Import"
home_tab
id home_settings
for _ in 1 2 3 4 5 6 7 8; do dump; find_node resource-id "srow_Downloads" >/dev/null && break; scroll_down; done
id "srow_Downloads"
shot downloads
back
for _ in 1 2 3 4; do dump; find_node resource-id "srow_Import playlists" >/dev/null && break; scroll_down; done
id "srow_Import playlists"
if id import_url 0; then
  type_text "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"
  id import_go
  wait_for resource-id import_loading 5 && { log "import loading state shown"; shot import-loading; } || log "WARN: import loading state not seen (finished fast?)"
  sleep 8
  shot import-result
  back
fi
back

# ---------------------------------------------------------------- playlist rename / delete / undo
log "== Playlist CRUD"
tab Library
long_press resource-id "pl_Smoke list"
txt "Rename"
id name_field
clear_field
type_text "Smoke list 2"
id name_save
sleep 1
long_press resource-id "pl_Smoke list 2"
txt "Delete"
id snack_action          # Undo
sleep 1
wait_for resource-id "pl_Smoke list 2" 5 || { log "MISSING (required): playlist back after undo"; FAILS=$((FAILS + 1)); }
shot undo
id lib_select
id select_all
id select_delete
sleep 6                  # let the undo window pass (commit)
shot deleted
id "pl_Smoke list 2" 0 && { log "playlist still there after delete"; FAILS=$((FAILS + 1)); }

# ---------------------------------------------------------------- Stats (0.2.8 redesign)
log "== Stats"
# x1 y1 x2 y2 of the first node with <resource-id>
node_bounds() {
  python3 - "$O/ui.xml" "$1" <<'PY'
import sys, re, xml.etree.ElementTree as ET
try: r = ET.parse(sys.argv[1]).getroot()
except Exception: sys.exit(1)
for n in r.iter('node'):
    if n.get('resource-id','').split('/')[-1] != sys.argv[2]: continue
    m = list(map(int, re.findall(r'\d+', n.get('bounds',''))))
    if len(m) == 4 and m[2] > m[0] and m[3] > m[1]: print(*m); sys.exit(0)
sys.exit(1)
PY
}
stats_log() { timeout 10 adb logcat -d -s itune-stats:I itune-recap:I 2>/dev/null; }
tab Stats
for _ in 1 2 3; do scroll_up; done
wait_for resource-id stats_hero 10 || { log "MISSING (required): stats hero card"; FAILS=$((FAILS + 1)); }
shot stats-top
timeout 10 adb logcat -c >/dev/null 2>&1
for r in month year all week; do id "range_$r"; sleep 0.8; done
sleep 1.5
NP=$(stats_log | grep -c "page range=")
log "stats pages loaded while switching: $NP ($(stats_log | grep "page range=" | tail -n 4 | sed 's/.*page //' | tr '\n' '|'))"
[ "$NP" -ge 3 ] || { log "MISSING (required): stats pages for each range"; FAILS=$((FAILS + 1)); }
shot stats-week
# scrub: press and drag across the bars (hero follows the finger, haptic tick per bar)
dump
if B=$(node_bounds stats_chart); then
  read -r X1 Y1 X2 Y2 <<< "$B"; CY=$(( (Y1 + Y2) / 2 + (Y2 - Y1) / 4 ))
  timeout 10 adb logcat -c >/dev/null 2>&1
  adb shell input motionevent DOWN $((X1 + 12)) $CY; sleep 0.6
  for k in 1 2 3 4 5; do adb shell input motionevent MOVE $(( X1 + 12 + (X2 - X1 - 24) * k / 7 )) $CY; sleep 0.3; done
  shot stats-scrub
  adb shell input motionevent UP $(( X1 + 12 + (X2 - X1 - 24) * 5 / 7 )) $CY; sleep 1
  crash_check "stats scrub"
  if stats_log | grep -q "scrub start" && stats_log | grep -q "scrub end"; then log "stats scrub OK: $(stats_log | grep scrub | tr '\n' ' ' | sed 's/[^ ]*itune-stats: //g')"
  else log "MISSING (required): stats scrub (no scrub start/end)"; FAILS=$((FAILS + 1)); fi
  # tap the last bar (today): that day's plays
  adb shell input tap $(( X2 - (X2 - X1) / 14 )) $CY; sleep 1.5
  if wait_for resource-id stats_day_sheet 4; then log "stats day sheet OK: $(stats_log | grep "day sheet" | tail -n 1 | sed 's/.*itune-stats: //')"; shot stats-day; back
  else log "WARN: today's bar opened no day sheet (no plays today?)"; fi
else log "MISSING (required): stats_chart"; FAILS=$((FAILS + 1)); fi
# clock: tap an hour -> it springs open with that hour's tracks
if seek_id stats_clock_face; then
  dump; read -r X1 Y1 X2 Y2 <<< "$(node_bounds stats_clock_face)"
  if [ $(( (Y1 + Y2) / 2 )) -gt $(py 62) ]; then scroll_down; dump; read -r X1 Y1 X2 Y2 <<< "$(node_bounds stats_clock_face)"; fi
  adb shell input tap $(( X2 - (X2 - X1) / 5 )) $(( Y1 + (Y2 - Y1) / 5 )); sleep 1.5
  if wait_for resource-id stats_hour_detail 6; then log "stats clock hour OK: $(stats_log | grep "clock hour" | tail -n 1 | sed 's/.*itune-stats: //')"; shot stats-clock-hour
  else log "MISSING (required): stats clock hour detail"; FAILS=$((FAILS + 1)); shot missing-clock-hour; fi
else log "MISSING (required): stats clock"; FAILS=$((FAILS + 1)); fi
# daily goal picker
if seek_id goal_edit; then id goal_edit 0 && { id goal_15 0 && log "goal set 15: $(stats_log | grep "goal set" | tail -n 1 | sed 's/.*itune-stats: //')"; }; fi
for _ in 1 2 3 4 5 6; do dump; find_node resource-id stats_skipped >/dev/null && break; scroll_down; done
shot stats-lower
# Recap: 9:16 card rendered to PNG, Save
for _ in 1 2 3 4 5 6; do scroll_up; done
timeout 10 adb logcat -c >/dev/null 2>&1
id stats_recap
if wait_for resource-id recap_card 8; then
  for _ in 1 2 3 4 5 6 7 8; do stats_log | grep -q "rendered range" && break; sleep 1; done
  if stats_log | grep -q "rendered range"; then log "recap OK: $(stats_log | grep "rendered range" | tail -n 1 | sed 's/.*itune-recap: //')"
  else log "MISSING (required): recap card render"; FAILS=$((FAILS + 1)); fi
  shot stats-recap
  id recap_month 0; sleep 2
  id recap_save 0; sleep 2
  log "recap save: $(stats_log | grep "save ->" | tail -n 1 | sed 's/.*itune-recap: //')"
  back
else log "MISSING (required): recap sheet"; FAILS=$((FAILS + 1)); fi
crash_check "stats"
id range_month 0          # leave Stats on Month: the options step below flips it back to Week

# ---------------------------------------------------------------- Account + Settings
log "== Account / Settings"
home_tab
id home_avatar
shot account
back
id home_settings
shot settings
log "== Glass see-through (Settings)"
glass_check settings
for _ in 1 2 3 4 5 6; do scroll_up; done
for _ in 1 2 3 4 5 6; do dump; find_node resource-id "srow_Diagnostics" >/dev/null && break; scroll_down; done
id "srow_Diagnostics"
shot diagnostics
back
scroll_down; scroll_down
back

# ---------------------------------------------------------------- choices + place survive a force-stop
log "== Options + place kept across a kill"
timeout 10 adb logcat -c >/dev/null 2>&1
tab Stats; id range_week 0
home_tab; id home_settings
seek_id toggle_haptics; id toggle_haptics 0
seek_id "srow_Theme, text and fonts"
if id "srow_Theme, text and fonts" 0; then
  seek_id toggle_pure_black; id toggle_pure_black 0; back
fi
back
tab Library
id lib_Playlists 0; id lib_sort 0; id lib_Albums 0
scroll_down; scroll_down
dump; LANCHOR=$(mid_anchor); log "library anchor before kill: '$LANCHOR'"
timeout 10 adb shell input keyevent KEYCODE_HOME; sleep 2
SETS=$(timeout 10 adb logcat -d -s itune-prefs:I 2>/dev/null | grep " set " | sed 's/.* set //' )
timeout 15 adb shell am force-stop $PKG; sleep 2
timeout 10 adb logcat -c >/dev/null 2>&1
timeout 30 adb shell am start -W -n $PKG/dev.benfranklin.itune.MainActivity >/dev/null; sleep 5
crash_check "options relaunch"
START=$(timeout 10 adb logcat -d -s itune-prefs:I 2>/dev/null | grep "library_sort=" | tail -n 1)
log "prefs at start: ${START#*: }"
declare -A LASTSET=()
while read -r kv; do [ -n "$kv" ] && LASTSET[${kv%%=*}]=$kv; done <<< "$SETS"
NK=0
for k in library_sort library_filter stats_range haptics pure_black; do
  kv=${LASTSET[$k]:-}
  [ -z "$kv" ] && { log "WARN: option $k was not flipped"; continue; }
  NK=$((NK + 1))
  echo " $START " | grep -q " $kv " && log "option kept: $kv" || { log "MISSING (required): option $kv after force-stop"; FAILS=$((FAILS + 1)); }
done
[ $NK -ge 3 ] || { log "MISSING (required): could not flip enough options ($NK)"; FAILS=$((FAILS + 1)); }
if wait_for resource-id library_list 10; then log "relaunch reopened Library"; else log "MISSING (required): last tab (Library) after force-stop"; FAILS=$((FAILS + 1)); fi
if [ -n "$LANCHOR" ]; then
  wait_for text "$LANCHOR" 10 && log "library scroll kept across the kill ('$LANCHOR')" || { log "MISSING (required): library scroll after force-stop ('$LANCHOR')"; FAILS=$((FAILS + 1)); shot missing-lib-scroll; }
fi
shot options-kept
# put the visual options back for the remaining screenshots
for _ in 1 2 3; do scroll_up; done
id lib_Playlists 0
home_tab; id home_settings
seek_id toggle_haptics; id toggle_haptics 0
seek_id "srow_Theme, text and fonts"
if id "srow_Theme, text and fonts" 0; then
  seek_id toggle_pure_black; id toggle_pure_black 0; back
fi
back

# ---------------------------------------------------------------- name + font scale 1.3 audit
log "== Name / font scale"
home_tab
id home_settings
for _ in 1 2 3; do scroll_up; done
if id call_name_field 0; then
  clear_field; type_text "Smokey"
  # the field may not have taken focus from the first tap (it can sit under the top bar): check the saved value, retry once
  if ! timeout 10 adb logcat -d -s itune-prefs:I 2>/dev/null | grep -q "set call_name=Smokey"; then
    log "call name not typed yet: tapping the field again"
    dump; XY=$(find_node resource-id call_name_field) && { adb shell input tap $XY; sleep 1; clear_field; type_text "Smokey"; }
  fi
  hide_kb; back
  home_tab
  wait_for text "Smokey" 6 contains && log "greeting uses call name" || { log "MISSING (required): greeting with call name"; FAILS=$((FAILS + 1)); }
  id home_settings; for _ in 1 2 3; do scroll_up; done; id call_name_field; clear_field; hide_kb; back
fi
home_tab
timeout 10 adb shell settings put system font_scale 1.3; sleep 3
id home_avatar; shot account-font130; back
id home_settings; shot settings-font130
for _ in 1 2 3 4; do dump; find_node resource-id "srow_Theme, text and fonts" >/dev/null && break; scroll_down; done
id "srow_Theme, text and fonts" 0 && { shot appearance-font130; id "srow_See all themes" 0 && shot appearance-all; back; }
back
timeout 10 adb shell settings put system font_scale 1.0; sleep 2

# ---------------------------------------------------------------- Start radio from a track menu
# 0.2.3 bug: the menu's own coroutine scope died with the closed sheet, so the radio never started. Required: the
# radio call finishes (itune-radio log) and, when it returns songs, Now Playing says "Playing from <title> radio".
log "== Start radio"
tab Search
if ! wait_for resource-id track_row 10; then
  id search_field 0 && { clear_field; type_text "adele hello"; submit_search "adele hello"; }
fi
if wait_for resource-id track_row 30; then
  timeout 10 adb logcat -c >/dev/null 2>&1
  id track_more
  if txt "Start radio"; then
    RAD=""
    for _ in $(seq 1 30); do
      RAD=$(timeout 10 adb logcat -d -s itune-radio:I 2>/dev/null | grep -oE "n=[0-9]+" | tail -n 1 | cut -d= -f2)
      [ -n "$RAD" ] && break; sleep 1
    done
    if [ -z "$RAD" ]; then log "MISSING (required): Start radio never finished (no itune-radio line)"; FAILS=$((FAILS + 1)); shot missing-radio
    elif [ "$RAD" -gt 0 ]; then
      sleep 2
      if id mini_player 0 12 && wait_for text " radio" 10 contains; then log "radio started: $RAD songs, Playing from '... radio'"; shot radio-np
      else log "MISSING (required): Now Playing source after Start radio"; FAILS=$((FAILS + 1)); shot missing-radio-source; fi
      close_np
    else
      timeout 10 adb logcat -d -s itune-toast:I 2>/dev/null | grep -q "Couldn't start radio" \
        && log "WARN: radio returned no songs (runner network); 'Couldn't start radio' shown" \
        || { log "MISSING (required): toast when radio is empty"; FAILS=$((FAILS + 1)); }
    fi
    crash_check "start radio"
  fi
else
  log "WARN: no search results for the radio check (runner network?)"
fi

# ---------------------------------------------------------------- random poking at the end
log "== monkey"
adb shell monkey -p $PKG --pct-syskeys 0 --pct-appswitch 0 --throttle 350 -s 7 -v 400 > "$O/monkey.txt" 2>&1 || true
sleep 3
crash_check monkey
shot end
[ $OVER = 1 ] && FAILS=$((FAILS + 1))
log "done: required-step failures=$FAILS in ${SECONDS}s"
[ $FAILS -eq 0 ]
