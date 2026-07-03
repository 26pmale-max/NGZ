#!/usr/bin/env python3
"""
Upcoming Event Reminders Bot Script.
Checks for upcoming tasks/events that start within 30 minutes and sends a Telegram reminder.
Runs every 5 minutes via cron.
Timezone-aware (Europe/Budapest).
"""

import json
import urllib.request
import time
import os
import sys
from datetime import datetime
from zoneinfo import ZoneInfo

# Configuration
CALENDAR_API_URL = "http://localhost:8085/api/tasks"
BOT_TOKEN = "8763543001:AAHdrI3Ge899tUdB5-1UyvH4F56iScP_5_M"
CHAT_ID = "7049820365"
STATE_FILE = "/home/ale/.openclaw/workspace/reminder_state.json"
LOCAL_TZ = ZoneInfo("Europe/Budapest")

def send_telegram(message: str):
    payload = json.dumps({
        "chat_id": CHAT_ID,
        "text": message,
        "parse_mode": "HTML"
    }).encode("utf-8")
    url = f"https://api.telegram.org/bot{BOT_TOKEN}/sendMessage"
    req = urllib.request.Request(url, data=payload, headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            result = json.loads(resp.read().decode("utf-8"))
            if not result.get("ok"):
                raise RuntimeError(f"Telegram API error: {result}")
            return result
    except Exception as e:
        print(f"[ERROR] Failed to send Telegram message: {e}")
        raise

def load_state():
    if not os.path.exists(STATE_FILE):
        return {"sent_event_ids": []}
    try:
        with open(STATE_FILE, "r") as f:
            return json.load(f)
    except Exception:
        return {"sent_event_ids": []}

def save_state(state):
    try:
        with open(STATE_FILE, "w") as f:
            json.dump(state, f, indent=2)
    except Exception as e:
        print(f"[ERROR] Failed to save state file: {e}")

def main():
    try:
        req = urllib.request.Request(CALENDAR_API_URL, headers={"User-Agent": "UpcomingRemindersBot/1.0"})
        with urllib.request.urlopen(req, timeout=5) as resp:
            tasks = json.loads(resp.read().decode("utf-8"))
    except Exception as e:
        print(f"[ERROR] Failed to connect to Calendar API: {e}")
        sys.exit(1)

    state = load_state()
    sent_ids = state.get("sent_event_ids", [])
    new_sent_ids = []
    
    # Get timezone-aware current time in Europe/Budapest
    now_local = datetime.now(LOCAL_TZ)
    
    for t in tasks:
        # Skip completed tasks
        if t.get("completed"):
            continue
            
        task_id = t.get("id")
        desc = t.get("description")
        date_str = t.get("date")
        time_str = t.get("time")
        
        try:
            # Parse event time and localize to Europe/Budapest
            event_dt = datetime.strptime(f"{date_str} {time_str}", "%Y-%m-%d %H:%M")
            event_dt = event_dt.replace(tzinfo=LOCAL_TZ)
        except Exception as pe:
            print(f"[WARN] Failed to parse event time for task {task_id}: {pe}")
            continue
            
        diff = (event_dt - now_local).total_seconds()
        
        # If the event starts in 30 minutes or less (1800s), and hasn't started yet (diff > 0)
        if 0 < diff <= 1800:
            if task_id not in sent_ids:
                # Send reminder
                minutes_left = int(diff // 60)
                if minutes_left == 0:
                    time_desc = "starting now!"
                else:
                    time_desc = f"starting in {minutes_left} minutes!"
                    
                msg = (
                    f"🔔 <b>Upcoming Task Reminder!</b>\n\n"
                    f"Your task <b>\"{desc}\"</b> is {time_desc}\n"
                    f"📅 Scheduled: <code>{date_str}</code> at <code>{time_str}</code>"
                )
                print(f"[INFO] Sending reminder for task {task_id}...")
                try:
                    send_telegram(msg)
                    new_sent_ids.append(task_id)
                except Exception as te:
                    print(f"[ERROR] Failed to send telegram alert: {te}")
            else:
                # Keep in sent list if it is still in the active window
                new_sent_ids.append(task_id)
        elif diff < 0:
            # Event is in the past, no longer need to track sent status
            pass
        else:
            # Event is more than 30 minutes away, keep tracking sent status if already marked
            if task_id in sent_ids:
                new_sent_ids.append(task_id)
                
    # Update and save state
    state["sent_event_ids"] = new_sent_ids
    save_state(state)

if __name__ == "__main__":
    main()
