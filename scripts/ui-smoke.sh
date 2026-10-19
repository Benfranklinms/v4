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
  id np_more; txt "Sleep timer"; txt "15m" 0
  id np_more; txt "Sleep timer"; txt "Turn off" 0
  id np_queue
  shot queue
  # drag-to-reorder: drag the first "Next in queue" row's handle below the second, then check the order
  dump
  mapfile -t QT < <(texts_of queue_user_title)
  if [ ${#QT[@]} -ge 2 ] && H1=$(nth_node queue_handle 1) && H2=$(nth_node queue_handle 2); then
    read X1 Y1 <<< "$H1"; read X2 Y2 <<< "$H2"
    Y3=$(( Y2 + (Y2 - Y1) * 6 / 10 ))
    adb shell input swipe $X1 $Y1 $X1 $Y3 1400
    sleep 2.5; crash_check "queue reorder"
    dump
    mapfile -t QA < <(texts_of queue_user_title)
    shot queue-reordered
    if [ "${QA[0]:-}" = "${QT[1]}" ] && [ "${QA[1]:-}" = "${QT[0]}" ]; then log "queue reorder ok (${QT[0]} <-> ${QT[1]})"
    else log "MISSING (required): queue reorder (before: ${QT[0]} | ${QT[1]}; after: ${QA[0]:-?} | ${QA[1]:-?})"; FAILS=$((FAILS + 1)); fi
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
  for _ in 1 2 3 4 5 6 7; do adb shell input swipe $(px 50) $(py 40) $(px 50) $(py 56) 450; sleep 0.6; done
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
    log "MISSING (required): Now Playing top not reachable for the swipe check"; FAILS=$((FAILS + 1)); close_np
  fi
else
  log "WARN: mini player not shown (stream unavailable on the emulator?)"
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
  timeout 10 adb shell input keyevent KEYCODE_HOME; sleep 2
  timeout 15 adb shell am force-stop $PKG; sleep 2
  timeout 30 adb shell am start -W -n $PKG/dev.benfranklin.itune.MainActivity >/dev/null; sleep 6
  if wait_for text "$BEFORE" 15; then log "session restored: $BEFORE"; shot session-restored
  else log "MISSING (required): restored session ($BEFORE)"; FAILS=$((FAILS + 1)); shot missing-session; fi
else
  log "WARN: no mini player title to compare (stream unavailable?)"
fi

# ---------------------------------------------------------------- cached Home: offline cold start still shows the shelves
log "== Cached Home"
if [ $SHELVES = 1 ]; then
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

# ---------------------------------------------------------------- Stats with zero/low data
log "== Stats"
tab Stats
shot stats-top
for r in week month year all; do id "range_$r"; done
scroll_down; scroll_down; scroll_up; scroll_up
tap_at 28 45; tap_at 75 45; tap_at 50 70; sleep 1; crash_check "stats taps"
shot stats
tab Stats                # a stray tap may have opened an artist: re-selecting the tab pops back to Stats
for _ in 1 2 3; do scroll_up; done
id stats_wrapped
sleep 2
tap_at 85 50; tap_at 85 50; tap_at 85 50; tap_at 10 50
shot wrapped
back

# ---------------------------------------------------------------- Account + Settings
log "== Account / Settings"
home_tab
id home_avatar
shot account
back
id home_settings
shot settings
for _ in 1 2 3 4 5 6; do dump; find_node resource-id "srow_Diagnostics" >/dev/null && break; scroll_down; done
id "srow_Diagnostics"
shot diagnostics
back
scroll_down; scroll_down
back

# ---------------------------------------------------------------- name + font scale 1.3 audit
log "== Name / font scale"
home_tab
id home_settings
for _ in 1 2 3; do scroll_up; done
if id call_name_field 0; then
  clear_field; type_text "Smokey"; hide_kb; back
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

# ---------------------------------------------------------------- random poking at the end
log "== monkey"
adb shell monkey -p $PKG --pct-syskeys 0 --pct-appswitch 0 --throttle 350 -s 7 -v 400 > "$O/monkey.txt" 2>&1 || true
sleep 3
crash_check monkey
shot end
[ $OVER = 1 ] && FAILS=$((FAILS + 1))
log "done: required-step failures=$FAILS in ${SECONDS}s"
[ $FAILS -eq 0 ]
