#!/usr/bin/env bash
# リリース APK をエミュレータに入れて一通り操作し、落ちないことと主要な動作を確認する
set -uo pipefail

PKG=com.aistudio.pomodorotimer.app
ACTIVITY=$PKG/com.example.MainActivity
OUT=smoke-out
mkdir -p "$OUT"
APK=$(ls app/build/outputs/apk/release/*.apk | head -1)
FAILED=0

# エミュレータの不調で adb が応答しなくなっても全体が止まらないよう、すべての adb 呼び出しに時間制限を付ける
ADB_BIN=$(command -v adb)
adb() { timeout 90 "$ADB_BIN" "$@"; }

note() { echo "== $*" | tee -a "$OUT/summary.txt"; }
check() { # check <description> <command...>
  local desc=$1; shift
  if "$@" >/dev/null 2>&1; then note "OK   $desc"; else note "FAIL $desc"; FAILED=1; fi
}
shot() { adb exec-out screencap -p > "$OUT/$1.png" || true; }
raw_dump_ui() {
  for _ in 1 2 3 4 5; do
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 && adb pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null 2>&1 && return 0
    sleep 1
  done
  return 1
}
# エミュレータ自身の「～ isn't responding」ダイアログ（ランチャー等）が被さっていたら「Wait」で閉じてから取り直す
dump_ui() {
  local pos
  for _ in 1 2 3; do
    raw_dump_ui || return 1
    python3 .github/scripts/find_node.py "$OUT/ui.xml" "~isn't responding" >/dev/null || return 0
    note "INFO dismissing a system 'not responding' dialog"
    pos=$(python3 .github/scripts/find_node.py "$OUT/ui.xml" "Wait") && adb shell input tap $pos
    sleep 3
  done
  return 0
}
has() { # 画面の切り替わり直後は取りこぼすことがあるので、少し待って数回確かめる
  for _ in 1 2 3; do
    dump_ui && python3 .github/scripts/find_node.py "$OUT/ui.xml" "$1" >/dev/null && return 0
    sleep 1
  done
  return 1
}
tap() {
  local pos=""
  for attempt in 1 2 3 4 5; do   # 画面の切り替わり直後は取りこぼすことがあるので、少し待って取り直す
    dump_ui && pos=$(python3 .github/scripts/find_node.py "$OUT/ui.xml" "$1") && break
    pos=""
    if [ "$attempt" -ge 3 ]; then
      # 小さい画面では目的の要素が画面の下に隠れていることがあるので、少しスクロールして探す
      adb shell input swipe "$((SCREEN_W / 2))" "$((SCREEN_H * 7 / 10))" "$((SCREEN_W / 2))" "$((SCREEN_H * 4 / 10))" 400
    fi
    sleep 1.5
  done
  if [ -z "$pos" ]; then
    note "FAIL node not found: '$1'"
    cp "$OUT/ui.xml" "$OUT/notfound_$(date +%s).xml" 2>/dev/null
    shot "notfound_$(date +%s)"
    FAILED=1
    return 1
  fi
  adb shell input tap $pos
  sleep 2
}
try_tap() { # like tap, but a miss is not a failure
  dump_ui || return 1
  local pos
  pos=$(python3 .github/scripts/find_node.py "$OUT/ui.xml" "$1") || return 1
  adb shell input tap $pos
  sleep 2
}
set_number() { # set_number <current value shown> <new value>
  tap "$1" && adb shell input text "$2" && sleep 1 && tap "保存"
}
notifications() { adb shell dumpsys notification --noredact; }
in_picker() { has "Files in Downloads" || has "~Recent"; }
# どの画面にいてもカレンダー画面に戻す（ファイル選択画面などから戻れなかった場合の立て直し）
ensure_calendar() {
  for _ in 1 2 3 4; do
    has "メニュー" && return 0
    if has "設定"; then
      try_tap "カレンダー・統計"
    else
      adb shell input keyevent KEYCODE_BACK
    fi
    sleep 2
  done
  has "メニュー"
}
# システムのファイル保存画面で保存を確定する。古い Android では保存先を選ぶまで保存できない
confirm_save_in_picker() {
  for label in "SAVE" "Save" "保存"; do
    try_tap "$label" && sleep 3 && has "カレンダー" && return 0
  done
  if try_tap "Show roots"; then
    try_tap "Downloads"
    sleep 2
    # 古い保存画面では、保存先を選ぶとファイル名の入力欄にキーボードが出て保存ボタンが隠れ、
    # 入力欄のカーソルが点滅し続けるため画面の内容も取得できない。
    # Enter でキーボードを閉じ、右下にある保存ボタンを位置で押す
    adb shell input keyevent KEYCODE_ENTER
    sleep 3
    adb shell input tap "$((SCREEN_W * 9 / 10))" "$((SCREEN_H * 963 / 1000))"
    sleep 4
    has "カレンダー" && return 0
  fi
  return 1
}
sleep_until() { local now; now=$(date +%s); [ "$1" -gt "$now" ] && sleep $(($1 - now)); return 0; }
last_recorded_seconds() {
  adb shell "sqlite3 /data/data/$PKG/databases/pomodoro_timer.db 'select durationSeconds from work_sessions order by id desc limit 1'" 2>/dev/null | tr -d '\r'
}
# システムのファイル選択画面で、エクスポートしたファイルを開く（端末によって効く操作が違うため順に試す）
pick_exported_file() {
  dump_ui || return 1
  local pos x y
  # 1つ目に見つかるのはプレビューボタンなので、2つ目（ファイル名）を使う
  pos=$(python3 .github/scripts/find_node.py "$OUT/ui.xml" "~pomodoro_#2") ||
    pos=$(python3 .github/scripts/find_node.py "$OUT/ui.xml" "~pomodoro_") || return 1
  read -r x y <<< "$pos"
  adb shell input swipe "$x" "$y" "$x" "$y" 120; sleep 3
  in_picker || { note "INFO picked the file by tapping its name"; return 0; }
  adb shell input tap "$x" "$((y - 280))"; sleep 3
  in_picker || { note "INFO picked the file by tapping its thumbnail"; return 0; }
  adb shell input swipe "$x" "$y" "$x" "$y" 900; sleep 2
  shot 21b_picker_selected
  for label in "~OPEN" "~Open" "~SELECT" "~Select"; do
    if try_tap "$label"; then
      sleep 3
      in_picker || { note "INFO picked the file by selecting it and tapping $label"; return 0; }
    fi
  done
  return 1
}
alive() { adb shell pidof "$PKG"; }
# アプリがこれまでに再生を始めた音（MediaPlayer）の回数
count_players() { adb shell dumpsys audio 2>/dev/null | tr -d '\r' | grep -E "new player.*:$APP_UID/" | grep -c "MediaPlayer"; }
# アプリ（またはアプリの通知）が要求したバイブの回数
count_vibrations() { { adb shell dumpsys vibrator_manager 2>/dev/null; adb shell dumpsys vibrator 2>/dev/null; } | tr -d '\r' | grep -c "$PKG"; }
# アプリがライト（トーチ）を点灯させた回数
count_torch_on() {
  local pid
  pid=$(adb shell pidof "$PKG" </dev/null | tr -d '\r')
  adb shell dumpsys media.camera 2>/dev/null </dev/null | tr -d '\r' | grep -ci "torch.*turned on.*PID $pid\b"
}
device_time() { adb shell "date +'%m-%d %H:%M:%S.000'" </dev/null | tr -d '\r'; }
# 指定時刻以降に MP3 デコーダが起動した回数（アラート音 alert.mp3 の再生。古い Android では再生の履歴が残らないため）
mp3_decodes_since() { adb logcat -d -t "$1" 2>/dev/null </dev/null | grep -ci "mp3.decoder"; }
save_alert_dumps() { # save_alert_dumps <suffix>
  adb shell dumpsys audio > "$OUT/audio_$1.txt" 2>/dev/null
  { adb shell dumpsys vibrator_manager 2>/dev/null; adb shell dumpsys vibrator 2>/dev/null; } > "$OUT/vibrator_$1.txt"
  adb shell dumpsys media.camera > "$OUT/camera_$1.txt" 2>/dev/null
}
# 何も操作しない10秒間に、描画フレーム数・CPU 時間・ウィンドウ数を測る（アニメーションが回り続けていないかの確認）
measure() {
  local pid j1 j2 frames windows popups
  pid=$(adb shell pidof "$PKG" | tr -d '\r')
  adb shell dumpsys gfxinfo "$PKG" reset >/dev/null
  j1=$(adb shell cat "/proc/$pid/stat" | awk '{print $14+$15}')
  sleep 10
  j2=$(adb shell cat "/proc/$pid/stat" | awk '{print $14+$15}')
  frames=$(adb shell dumpsys gfxinfo "$PKG" | grep "Total frames rendered" | head -1 | tr -dc 0-9)
  adb shell dumpsys window windows > "$OUT/windows_$(echo "$1" | tr ' ()' '___').txt"
  windows=$(grep -c "Window{.*$PKG" "$OUT/windows_$(echo "$1" | tr ' ()' '___').txt")
  popups=$(grep -c "Window{.*PopupWindow" "$OUT/windows_$(echo "$1" | tr ' ()' '___').txt")
  note "MEASURE $1: frames_in_10s=$frames cpu_ticks_in_10s=$((j2 - j1)) app_window_lines=$windows popup_window_lines=$popups"
}

# 起動直後のエミュレータが落ち着くのを待つ。システム側アプリの「応答なし」ダイアログは操作の邪魔になるので出さない
adb shell settings put global hide_error_dialogs 1
sleep 45
# プロセスの一時凍結と記録の直接確認に使う（AOSP 版のイメージでのみ root になれる）
adb root >/dev/null 2>&1
sleep 3
adb wait-for-device
IS_ROOT=$(adb shell id -u | tr -d '\r')
adb uninstall "$PKG" >/dev/null 2>&1   # 端末上のテスト（デバッグ版）が残っていれば消す
adb install -r "$APK"
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS
API=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
read -r SCREEN_W SCREEN_H <<< "$(adb shell wm size | tr -d '\r' | grep -oE '[0-9]+x[0-9]+' | tail -1 | tr 'x' ' ')"
APP_UID=$(adb shell dumpsys package "$PKG" | tr -d '\r' | grep -m1 -oE "(userId|appId)=[0-9]+" | cut -d= -f2)
note "INFO Android API $API, screen ${SCREEN_W}x${SCREEN_H}, app uid $APP_UID, adb uid $IS_ROOT"
adb logcat -c
adb shell am start -W -n "$ACTIVITY"
sleep 8
dump_ui
shot 01_main
check "app starts" alive
check "setup screen is shown" has "開始"

# --- シナリオA: 1分×1セット。バックグラウンドで完了まで進める -------------------
measure "main idle"
tap "25"
measure "number dialog open"
shot 01b_number_dialog
adb shell input text "1"
sleep 1
tap "保存"
shot 02_work_1min
set_number "4" "1"
shot 03_sets_1
tap "ライト切り替え"   # サウンド・バイブ（初期値オン）に加えてライトもオンにする
PLAYERS_BEFORE=$(count_players); VIBRATIONS_BEFORE=$(count_vibrations); TORCH_BEFORE=$(count_torch_on)
ALERT_SINCE=$(device_time)
tap "開始"
sleep 4
shot 04_running
notifications > "$OUT/notif_running.txt"
check "live notification shows the running timer" grep -q "作業中 \[1/1\] - 残り 00:5" "$OUT/notif_running.txt"
adb shell dumpsys activity services "$PKG" > "$OUT/services_running.txt"
check "service runs in the foreground" grep -q "isForeground=true" "$OUT/services_running.txt"
adb shell dumpsys alarm > "$OUT/alarm_running.txt"
check "phase-end alarm is scheduled" grep -q "$PKG" "$OUT/alarm_running.txt"

adb shell input keyevent KEYCODE_HOME
sleep 68
notifications > "$OUT/notif_completed.txt"
check "all sets completed while in the background" grep -q "全セット完了" "$OUT/notif_completed.txt"
check "the completion notification is posted only once" test "$(grep -c "android.title=String (全セット完了)" "$OUT/notif_completed.txt")" = "1"
shot 05_home_after_complete

# フェーズ終了時に、サウンド・バイブ・ライトが実際に要求されたこと
save_alert_dumps "alerts_on"
PLAYERS_AFTER=$(count_players); VIBRATIONS_AFTER=$(count_vibrations); TORCH_AFTER=$(count_torch_on)
MP3_DECODES=$(mp3_decodes_since "$ALERT_SINCE")
note "INFO alerts on: players $PLAYERS_BEFORE->$PLAYERS_AFTER mp3_decodes=$MP3_DECODES vibrations $VIBRATIONS_BEFORE->$VIBRATIONS_AFTER torch_on $TORCH_BEFORE->$TORCH_AFTER"
if [ "$PLAYERS_AFTER" -gt "$PLAYERS_BEFORE" ] || [ "$MP3_DECODES" -gt 0 ]; then
  note "OK   alert sound was played at the end of the phase"
else
  note "FAIL alert sound was played at the end of the phase"
  FAILED=1
fi
if grep -qi "previous" "$OUT/vibrator_alerts_on.txt"; then
  check "vibration was requested at the end of the phase" test "$VIBRATIONS_AFTER" -gt "$VIBRATIONS_BEFORE"
else
  note "SKIP this Android version does not log past vibrations"
fi
if grep -qi "torch for camera" "$OUT/camera_alerts_on.txt"; then
  check "the light was flashed at the end of the phase (without the camera permission)" test "$TORCH_AFTER" -gt "$TORCH_BEFORE"
else
  note "N/A  this emulator image has no flash unit, so there is nothing to flash (see camera_alerts_on.txt)"
fi
if [ "$API" -ge 26 ]; then
  check "the phase notification channel is silent" grep -Eq "pomodoro_events_silent_channel.*mSound=null" "$OUT/notif_completed.txt"
fi

adb shell am start -W -n "$ACTIVITY"
sleep 4
shot 06_completed
check "completed screen is shown" has "全セット完了"

# 完了画面でトグルを操作しても完了画面のまま
tap "サウンド切り替え"
check "completed screen survives a quick toggle" has "全セット完了"
tap "サウンド切り替え"

# 完了 → ストップウォッチを使って停止 → ポモドーロに戻って「次へ」（以前はここでクラッシュ）
tap "ストップウォッチ"
tap "開始"
sleep 4
shot 07_stopwatch_running
if [ "$API" -le 30 ]; then
  # Android 11 以前は「戻る」で画面が破棄される。計測を続けたまま開き直しても、ストップウォッチの画面に戻ること
  adb shell input keyevent KEYCODE_BACK
  sleep 3
  adb shell am start -W -n "$ACTIVITY"
  sleep 4
  shot 07b_stopwatch_after_reopen
  check "the stopwatch screen is shown again after the screen was recreated" has "計測中"
fi
tap "一時停止"
shot 08_stopwatch_paused
tap "停止して記録"
tap "ポモドーロタイマー#2"   # 1つ目は画面タイトル、2つ目がモード切り替えのタブ
tap "次へ"
sleep 3
shot 09_back_to_setup
check "app is alive after dismissing the completed screen" alive
check "setup screen is shown again" has "開始"

# --- シナリオB: 1分×2セット。画面を出したまま休憩に入る ---------------------------
set_number "1" "2"   # セット数 1 → 2（先に見つかるのがセット数）
shot 10_sets_2
# サウンド・バイブ・ライトをすべてオフにして、フェーズ終了時に何も鳴らないことを確認する
tap "サウンド切り替え"
tap "バイブ切り替え"
tap "ライト切り替え"
shot 10b_alerts_off
PLAYERS_BEFORE=$(count_players); VIBRATIONS_BEFORE=$(count_vibrations)
ALERT_SINCE=$(device_time)
tap "開始"
sleep 66
shot 11_break_running
check "break phase started automatically" has "休憩中"
save_alert_dumps "alerts_off"
PLAYERS_AFTER=$(count_players); VIBRATIONS_AFTER=$(count_vibrations)
MP3_DECODES=$(mp3_decodes_since "$ALERT_SINCE")
note "INFO alerts off: players $PLAYERS_BEFORE->$PLAYERS_AFTER mp3_decodes=$MP3_DECODES vibrations $VIBRATIONS_BEFORE->$VIBRATIONS_AFTER"
check "no sound is played when sound is off" test "$PLAYERS_AFTER" = "$PLAYERS_BEFORE" -a "$MP3_DECODES" = "0"
if [ "$API" -ge 26 ]; then
  check "no vibration when vibration is off (including the notification)" test "$VIBRATIONS_AFTER" = "$VIBRATIONS_BEFORE"
fi
tap "一時停止"
shot 12_break_paused
check "paused badge is shown" has "一時停止中"
tap "再開"
tap "停止"
sleep 2
check "setup screen after stop" has "開始"

# --- シナリオC: 画面オフ＋Doze（端末アイドル）状態でフェーズが切り替わること -------------
tap "開始"
sleep 3
adb shell dumpsys battery unplug
adb shell input keyevent KEYCODE_SLEEP
sleep 3
adb shell dumpsys deviceidle enable > "$OUT/deviceidle.txt" 2>&1
adb shell dumpsys deviceidle force-idle >> "$OUT/deviceidle.txt" 2>&1
DOZE_STATE=$(adb shell dumpsys deviceidle get deep 2>/dev/null | tr -d '\r')
check "the device entered Doze (screen off, on battery)" test "$DOZE_STATE" = "IDLE"
sleep 66
note "INFO device idle state at the end of the phase: $(adb shell dumpsys deviceidle get deep 2>/dev/null | tr -d '\r')"
notifications > "$OUT/notif_doze.txt"
check "phase switched while the screen was off and the device was in Doze" grep -q "休憩中 \[1/2\]" "$OUT/notif_doze.txt"
adb shell dumpsys alarm > "$OUT/alarm_after_doze.txt"
note "INFO alarm stats: $(grep -m1 "ALARM_COMPLETE" "$OUT/alarm_after_doze.txt" | tr -s ' ' | cut -c1-160)"
# 古い Android では、この状態で端末が実際にスリープして adb の応答が止まることがある。
# 電源につないだ状態に戻して起こすところまでを、応答するまで繰り返す
for _ in 1 2 3 4 5 6 7 8; do
  timeout 20 "$ADB_BIN" shell dumpsys battery reset >/dev/null 2>&1 &&
    timeout 20 "$ADB_BIN" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 && break
  sleep 3
done
adb shell dumpsys deviceidle unforce >/dev/null 2>&1
adb shell wm dismiss-keyguard >/dev/null 2>&1
adb shell input keyevent 82
adb shell input swipe "$((SCREEN_W / 2))" "$((SCREEN_H * 8 / 10))" "$((SCREEN_W / 2))" "$((SCREEN_H * 2 / 10))" 300
sleep 2
adb shell am start -W -n "$ACTIVITY"
sleep 4
shot 12b_after_doze
check "break is running after waking the device" has "休憩中"
tap "停止"
sleep 2
check "setup screen after the Doze scenario" has "開始"

# --- シナリオD: フェーズ終了をまたいでアプリのプロセスが止まっていた場合 -----------------
# 端末のスリープで CPU が止まるのと同じく、ティッカーもアラームの受信も動かない状態を作る。
# 再開時には「止まっていたティッカー」と「遅れて届くアラーム」の両方がフェーズ終了を処理しようとする
WORK_MINUTES=3
if [ "$IS_ROOT" = "0" ]; then
  tap "開始"
  T0=$(date +%s)
  APP_PID=$(adb shell pidof "$PKG" | tr -d '\r')
  sleep_until $((T0 + 48))
  adb shell kill -STOP "$APP_PID"
  note "INFO app process frozen about 50s into the 60s work phase"
  sleep_until $((T0 + 64))
  adb shell kill -CONT "$APP_PID"
  sleep 8
  notifications > "$OUT/notif_frozen.txt"
  check "the break is running after the process was frozen across the phase end" grep -q "休憩中 \[1/2\]" "$OUT/notif_frozen.txt"
  if grep -q "作業中 \[2/2\]" "$OUT/notif_frozen.txt"; then
    note "FAIL the late alarm skipped the break"
    FAILED=1
  else
    note "OK   the late alarm did not skip the break"
  fi
  LAST_SECONDS=$(last_recorded_seconds)
  if [ -n "$LAST_SECONDS" ]; then
    check "the full 60 seconds were recorded although the ticker was frozen (got $LAST_SECONDS)" test "$LAST_SECONDS" = "60"
  else
    note "SKIP sqlite3 is not available on this image, the recorded time is checked on the calendar instead"
  fi
  check "app is alive after being frozen" alive
  adb shell am start -W -n "$ACTIVITY"
  sleep 3
  shot 12c_after_freeze
  tap "停止"
  sleep 2
  check "setup screen after the freeze scenario" has "開始"
  WORK_MINUTES=4
else
  note "SKIP cannot freeze the process on this image (adb is not root)"
fi

# --- 記録・カレンダー・統計・回転 ------------------------------------------------
tap "カレンダー・統計"
sleep 3
shot 13_calendar
dump_ui && cp "$OUT/ui.xml" "$OUT/calendar_ui.xml"
check "today's work is recorded ($WORK_MINUTES work minutes + stopwatch)" grep -Eq "00:0$WORK_MINUTES:[0-9][0-9]" "$OUT/calendar_ui.xml"

adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1
sleep 4
shot 14_calendar_landscape
check "app is alive after rotation" alive
tap "グラフを見る"
sleep 3
has "カレンダーに戻る" || tap "グラフを見る"   # 回転直後でタップがずれた場合はやり直す
shot 15_stats_landscape
check "statistics screen is shown in landscape" has "カレンダーに戻る"
adb shell settings put system user_rotation 0
sleep 4
shot 16_stats_portrait
tap "カレンダーに戻る"
sleep 2
check "back on the calendar screen" ensure_calendar

# --- ダイアログを開いたまま放置しても、裏でアニメーションが回り続けないこと ---------
measure "calendar idle"
tap "メニュー"
tap "インポート"
measure "import dialog open"
shot 16b_import_dialog_idle
tap "キャンセル"
tap "作業時間を変更"
measure "edit dialog open"
tap "キャンセル"

# --- JSON のファイル入出力（システムのファイル選択画面を経由するのでベストエフォート）----
tap "メニュー"
tap "エクスポート"
shot 17_export_dialog
tap "ファイルに保存"
sleep 5
shot 18_picker_save
if confirm_save_in_picker; then
  shot 19_after_save
  note "OK   back in the app after saving a file"
  tap "メニュー"
  tap "インポート"
  shot 20_import_dialog
  tap "ファイルを選択"
  sleep 5
  shot 21_picker_open
  if pick_exported_file; then
    sleep 4
    shot 22_after_import
    check "back in the app after importing a file" has "カレンダー"
    dump_ui && cp "$OUT/ui.xml" "$OUT/calendar_after_import_ui.xml"
    check "records are intact after re-importing the exported file" grep -Eq "00:0$WORK_MINUTES:[0-9][0-9]" "$OUT/calendar_after_import_ui.xml"
  else
    note "SKIP could not pick the exported file in the system picker"
    adb shell input keyevent KEYCODE_BACK
    sleep 2
    try_tap "キャンセル"
  fi
else
  shot 19_save_not_confirmed
  note "SKIP could not confirm the save dialog of the system picker"
fi
ensure_calendar

# --- 3ボタンナビゲーションで、下部のボタンがナビゲーションバーに重ならないこと -----------
adb shell cmd overlay enable com.android.internal.systemui.navbar.threebutton >/dev/null 2>&1
sleep 5
adb shell am start -W -n "$ACTIVITY"
sleep 3
for _ in 1 2 3; do   # カレンダーやダイアログにいる場合はホームまで戻る
  has "設定" && break
  adb shell input keyevent KEYCODE_BACK
  sleep 2
done
shot 30_main_3button
adb shell dumpsys window windows > "$OUT/windows_3button.txt"
NAV_TOP=$(python3 .github/scripts/nav_top.py "$OUT/windows_3button.txt")
BUTTON_BOTTOM=""
dump_ui && BUTTON_BOTTOM=$(python3 .github/scripts/find_node.py "$OUT/ui.xml" "設定" --bounds | awk '{print $4}')
note "INFO navigation bar top=$NAV_TOP, settings button bottom=$BUTTON_BOTTOM"
if [ -n "$NAV_TOP" ] && [ -n "$BUTTON_BOTTOM" ] && [ "$NAV_TOP" -gt 0 ]; then
  check "bottom buttons stay above the 3-button navigation bar" test "$BUTTON_BOTTOM" -le "$NAV_TOP"
else
  note "SKIP could not read the navigation bar position (see 30_main_3button.png)"
fi
adb shell settings put system user_rotation 1
sleep 4
shot 31_main_3button_landscape
check "settings button is reachable in landscape with 3-button navigation" has "設定"
adb shell settings put system user_rotation 0
sleep 3

# --- 後始末と判定 ----------------------------------------------------------------
adb logcat -d > "$OUT/logcat.txt"
check "process is still alive" alive
if grep -q "FATAL EXCEPTION" "$OUT/logcat.txt" && grep -A3 "FATAL EXCEPTION" "$OUT/logcat.txt" | grep -q "$PKG"; then
  note "FAIL crash found in logcat"
  grep -A25 "FATAL EXCEPTION" "$OUT/logcat.txt" | head -80 | tee -a "$OUT/summary.txt"
  FAILED=1
else
  note "OK   no crash in logcat"
fi
if grep -q "ANR in $PKG" "$OUT/logcat.txt"; then note "FAIL ANR found"; FAILED=1; fi

echo; cat "$OUT/summary.txt"
exit $FAILED
