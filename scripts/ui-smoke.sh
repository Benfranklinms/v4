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
shot() { STEP=$((STEP + 1)); timeout 20 adb exec-out screencap -p > "$O/$(printf %02d $STEP)-$1.png" 2>/dev/null; }
alive() { timeout 15 adb shell pidof $PKG >/dev/null 2>&1; }
crash_check() {
  if ! alive; then
    if timeout 15 adb shell true >/dev/null 2>&1; then log "APP DIED after: $1"; else log "EMULATOR/ADB UNRESPONSIVE after: $1"; fi
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
back() { adb shell input keyevent KEYCODE_BACK; sleep 1; crash_check back; }
# screen size (the CI emulator is small, so every coordinate is a fraction of it)
read SW SH < <(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1 | tr 'x' ' ')
SW=${SW:-1080}; SH=${SH:-1920}
px() { echo $(( SW * $1 / 100 )); }
py() { echo $(( SH * $1 / 100 )); }
log "screen ${SW}x${SH}"
scroll_down() { adb shell input swipe $(px 50) $(py 80) $(px 50) $(py 30) 300; sleep 1; crash_check scroll; }
scroll_up() { adb shell input swipe $(px 50) $(py 30) $(px 50) $(py 80) 300; sleep 1; crash_check scroll; }
tap_at() { adb shell input tap $(px $1) $(py $2); sleep 0.6; }
# hide the soft keyboard if it is showing (BACK only when it is, so we never navigate by accident)
hide_kb() { if adb shell dumpsys input_method | grep -q "mInputShown=true"; then adb shell input keyevent KEYCODE_BACK; sleep 0.8; fi; }
# glass chrome: the bottom nav hides while scrolling down and Now Playing covers it. Close NP, then tap the
# tab; if the nav is hidden, a small scroll up springs it back and we retry.
close_np() { dump; if find_node resource-id np_close >/dev/null; then local xy; xy=$(find_node resource-id np_close); adb shell input tap $xy; sleep 1.2; fi; }
tab() {
  close_np
  if wait_for resource-id "tab_$1" 3; then id "tab_$1"; return; fi
  adb shell input swipe $(px 50) $(py 45) $(px 50) $(py 60) 250; sleep 1
  if wait_for resource-id "tab_$1" 3; then id "tab_$1"; return; fi
  scroll_up; scroll_up
  id "tab_$1"
}
home_tab() { tab Home; scroll_up; scroll_up; }

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
timeout 10 adb shell dumpsys gfxinfo $PKG reset >/dev/null 2>&1
for _ in 1 2 3 4 5 6; do adb shell input swipe $(px 50) $(py 80) $(px 50) $(py 20) 150; sleep 0.4; done
for _ in 1 2 3 4 5 6; do adb shell input swipe $(px 50) $(py 20) $(px 50) $(py 80) 150; sleep 0.4; done
gfx home
[ $SHELVES = 1 ] || log "WARN: no home shelves seen (emulator network?)"
shot home-scrolled
for _ in 1 2 3 4; do scroll_up; done

# ---------------------------------------------------------------- Isa command card
log "== Isa"
id isa_length; id isa_length; id isa_length; id isa_length
id isa_field
type_text "focus music"
id isa_send
hide_kb
if wait_for resource-id isa_preview 45; then
  shot isa-preview
  id isa_play
  sleep 4
  id isa_save 0
else
  dump
  if find_node resource-id isa_retry >/dev/null; then log "isa error state shown"; id isa_retry 0; shot isa-error; else log "WARN: isa produced neither preview nor error"; fi
fi
id isa_clear 0
id mood_Focus
sleep 3
shot isa-mood
id isa_clear 0
id isa_mic 0 && back

# ---------------------------------------------------------------- Library: create playlist
log "== Library / playlists"
tab Library
shot library
id lib_create
id name_field
type_text "Smoke list"
id name_save
sleep 2
shot playlist-new
back
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
adb shell input keyevent KEYCODE_ENTER
sleep 1; hide_kb
if wait_for resource-id track_row 30; then
  for f in Songs Albums Artists Playlists Videos All; do id "filter_$f" 0; done
  shot search-results
  # add the first song to the new playlist
  id track_more
  txt "Add to playlist"
  txt "Smoke list"
  wait_for text "Added to Smoke list" 5 && log "add to playlist ok" || { log "MISSING (required): Added to Smoke list toast"; FAILS=$((FAILS + 1)); }
  # play the first song
  id track_row
  sleep 6
  shot playing
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
  id queue_tab_1; shot queue-recent; id queue_tab_0
  id sheet_close            # obvious close on the queue sheet
  # Now Playing scrolls on to lyrics + up next
  scroll_down; scroll_down; shot np-scrolled
  dump; find_node resource-id np_upnext >/dev/null && log "np up next visible" || log "WARN: up next not visible after scrolling NP"
  scroll_up; scroll_up
  back
else
  log "WARN: mini player not shown (stream unavailable on the emulator?)"
fi

# ---------------------------------------------------------------- session restore: force-stop + relaunch keeps the song
log "== Session restore"
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

# ---------------------------------------------------------------- scroll position kept across push/pop
log "== Scroll restore"
home_tab
for _ in 1 2 3; do scroll_down; done
dump; cp "$O/ui.xml" "$O/ui-before-push.xml"
FIRST=$(python3 - "$O/ui.xml" <<'PY'
import sys, re, xml.etree.ElementTree as ET
r = ET.parse(sys.argv[1]).getroot()
for n in r.iter('node'):
    t = n.get('text','')
    if t and len(t) > 3: print(t); break
PY
)
if id shelf_item 0 5 || id home_card 0 3; then
  sleep 2; back; sleep 1.5
  if wait_for text "$FIRST" 5; then log "home scroll kept ($FIRST)"; else log "MISSING (required): home scroll position after back ($FIRST)"; FAILS=$((FAILS + 1)); shot missing-scroll; fi
else
  log "WARN: no tappable home card to test scroll restore"
fi

# ---------------------------------------------------------------- bookmark: save / unsave album + artist
log "== Save to Library"
tab Search
id search_field
clear_field
type_text "adele 25"
adb shell input keyevent KEYCODE_ENTER
sleep 1; hide_kb
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
