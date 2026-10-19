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

dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb shell cat /sdcard/ui.xml 2>/dev/null > "$O/ui.xml"; }
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
alive() { adb shell pidof $PKG >/dev/null 2>&1; }
shot() { STEP=$((STEP + 1)); adb exec-out screencap -p > "$O/$(printf %02d $STEP)-$1.png" 2>/dev/null; }
crash_check() {
  if ! alive; then log "APP DIED after: $1"; adb logcat -d -v threadtime > "$O/logcat-crash.txt"; exit 1; fi
}
# wait_for <attr> <value> <seconds> [contains]
wait_for() {
  local t=0
  while [ $t -lt "$3" ]; do
    dump
    if find_node "$1" "$2" "${4:-}" >/dev/null; then return 0; fi
    sleep 1; t=$((t + 1))
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
scroll_down() { adb shell input swipe 540 1700 540 600 300; sleep 1; crash_check scroll; }
scroll_up() { adb shell input swipe 540 600 540 1700 300; sleep 1; crash_check scroll; }
home_tab() { id tab_Home; }

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
[ $SHELVES = 1 ] || log "WARN: no home shelves seen (emulator network?)"
shot home-scrolled
for _ in 1 2 3 4; do scroll_up; done

# ---------------------------------------------------------------- Isa command card
log "== Isa"
id isa_length; id isa_length; id isa_length; id isa_length
id isa_field
type_text "focus music"
id isa_send
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
id tab_Library
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
id tab_Search
shot search-empty
id search_field
type_text "adele hello"
adb shell input keyevent KEYCODE_ENTER
if wait_for resource-id track_row 30; then
  for f in Songs Albums Artists Playlists Videos All; do id "filter_$f" 0; done
  shot search-results
  # add the first song to the new playlist
  id track_more
  txt "Add to playlist"
  txt "Smoke list"
  sleep 1
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
  id np_speed; back
  id np_sleep; txt "15m" 0
  id np_sleep; txt "Turn off" 0
  id np_queue
  shot queue
  id queue_tab_1; shot queue-recent; id queue_tab_0
  back
  back
else
  log "WARN: mini player not shown (stream unavailable on the emulator?)"
fi

# ---------------------------------------------------------------- bookmark: save / unsave album + artist
log "== Save to Library"
id tab_Search
id search_field
clear_field
type_text "adele 25"
adb shell input keyevent KEYCODE_ENTER
id filter_Albums 0 30
if id search_album 1 30; then
  wait_for resource-id track_number 25 && log "album page: track numbers (no repeated art)" || log "WARN: album tracks not loaded"
  shot album
  id save_button
  wait_for text "Saved to Library" 6 || { log "MISSING (required): Saved to Library toast"; FAILS=$((FAILS + 1)); }
  shot album-saved
  back
  id tab_Library
  id lib_Albums
  if id lib_album 1 10; then
    id save_button
    wait_for text "Removed from Library" 6 || { log "MISSING (required): Removed from Library toast"; FAILS=$((FAILS + 1)); }
    id snack_action          # Undo -> saved again
    shot album-undo
    back
  fi
  id lib_Playlists
fi
id tab_Search
id filter_Artists 0
if id search_artist 0 15; then
  id save_button
  wait_for text "Saved to Library" 6 || { log "MISSING (required): artist Saved to Library toast"; FAILS=$((FAILS + 1)); }
  back
  id tab_Library; id lib_Artists
  wait_for resource-id lib_artist 8 || { log "MISSING (required): saved artist in Library"; FAILS=$((FAILS + 1)); }
  shot library-artists
  id lib_Playlists
fi
id tab_Search
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
for _ in 1 2 3 4; do dump; find_node resource-id "srow_Downloads" >/dev/null && break; scroll_down; done
id "srow_Downloads"
shot downloads
back
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
id tab_Library
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
id tab_Stats
shot stats-top
for r in week month year all; do id "range_$r"; done
scroll_down; scroll_down; scroll_up; scroll_up
adb shell input tap 300 900; adb shell input tap 800 900; adb shell input tap 540 1400; sleep 1; crash_check "stats taps"
shot stats
txt "Your month"
sleep 2
adb shell input tap 900 1000; adb shell input tap 900 1000; adb shell input tap 900 1000; adb shell input tap 100 1000
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
scroll_down
txt "Diagnostics" 1 "" 6 || { scroll_down; txt "Diagnostics"; }
shot diagnostics
back
scroll_down; scroll_down
back

# ---------------------------------------------------------------- random poking at the end
log "== monkey"
adb shell monkey -p $PKG --pct-syskeys 0 --pct-appswitch 0 --throttle 350 -s 7 -v 400 > "$O/monkey.txt" 2>&1 || true
sleep 3
crash_check monkey
shot end
log "done: required-step failures=$FAILS"
[ $FAILS -eq 0 ]
