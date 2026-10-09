#!/usr/bin/env bash
# リリース APK をエミュレータに入れて一通り操作し、落ちないことと主要な動作を確認する
set -uo pipefail

PKG=com.aistudio.pomodorotimer.app
ACTIVITY=$PKG/com.example.MainActivity
OUT=smoke-out
mkdir -p "$OUT"
APK=$(ls app/build/outputs/apk/release/*.apk | head -1)
FAILED=0

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
has() { dump_ui && python3 .github/scripts/find_node.py "$OUT/ui.xml" "$1" >/dev/null; }
tap() {
  dump_ui || { note "FAIL ui dump before tapping '$1'"; FAILED=1; return 1; }
  local pos
  pos=$(python3 .github/scripts/find_node.py "$OUT/ui.xml" "$1") || { note "FAIL node not found: '$1'"; cp "$OUT/ui.xml" "$OUT/notfound_$(date +%s).xml"; FAILED=1; return 1; }
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
# システムのファイル選択画面で、エクスポートしたファイルを開く（端末によって効く操作が違うため順に試す）
pick_exported_file() {
  dump_ui || return 1
  local pos x y
  # 1つ目に見つかるのはプレビューボタンなので、2つ目（ファイル名）を使う
  pos=$(python3 .github/scripts/find_node.py "$OUT/ui.xml" "~pomodoro_#2") || return 1
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
adb install -r "$APK"
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS
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
shot 05_home_after_complete

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
tap "開始"
sleep 66
shot 11_break_running
check "break phase started automatically" has "休憩中"
tap "一時停止"
shot 12_break_paused
check "paused badge is shown" has "一時停止中"
tap "再開"
tap "停止"
sleep 2
check "setup screen after stop" has "開始"

# --- 記録・カレンダー・統計・回転 ------------------------------------------------
tap "カレンダー・統計"
sleep 3
shot 13_calendar
dump_ui && cp "$OUT/ui.xml" "$OUT/calendar_ui.xml"
check "today's work is recorded (2 work minutes + stopwatch)" grep -Eq '00:02:[0-9][0-9]' "$OUT/calendar_ui.xml"

adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1
sleep 4
shot 14_calendar_landscape
check "app is alive after rotation" alive
tap "グラフを見る"
sleep 3
shot 15_stats_landscape
adb shell settings put system user_rotation 0
sleep 4
shot 16_stats_portrait
adb shell input keyevent KEYCODE_BACK
sleep 2

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
if try_tap "SAVE" || try_tap "Save" || try_tap "保存"; then
  sleep 3
  shot 19_after_save
  check "back in the app after saving a file" has "カレンダー"
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
    check "records are intact after re-importing the exported file" grep -Eq '00:02:[0-9][0-9]' "$OUT/calendar_after_import_ui.xml"
  else
    note "SKIP could not pick the exported file in the system picker"
    adb shell input keyevent KEYCODE_BACK
  fi
else
  note "SKIP could not confirm the save dialog of the system picker"
  adb shell input keyevent KEYCODE_BACK
fi

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
