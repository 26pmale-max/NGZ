#!/usr/bin/env python3
"""
Daily Task Reminders Bot Script.
Fetches tasks scheduled for today from the local calendar app API and sends a summary to Telegram.
Timezone-aware (Europe/Budapest).
"""

import json
import urllib.request
import sys
from datetime import datetime
from zoneinfo import ZoneInfo

# Configuration
CALENDAR_API_URL = "http://localhost:8085/api/tasks"
BOT_TOKEN = "8763543001:AAHdrI3Ge899tUdB5-1UyvH4F56iScP_5_M"
CHAT_ID = "7049820365"
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

def main():
    try:
        # Fetch tasks from the calendar API
        req = urllib.request.Request(CALENDAR_API_URL, headers={"User-Agent": "DailyRemindersBot/1.0"})
        with urllib.request.urlopen(req, timeout=5) as resp:
            tasks = json.loads(resp.read().decode("utf-8"))
    except Exception as e:
        print(f"[ERROR] Failed to connect to Calendar API: {e}")
        # Send failure alert to Telegram
        send_telegram(f"⚠️ <b>Daily Reminders Failed:</b> Calendar API is unreachable (error: {e}).")
        sys.exit(1)

    # Localized date checking
    now_local = datetime.now(LOCAL_TZ)
    today_str = now_local.strftime("%Y-%m-%d")
    today_readable = now_local.strftime("%A, %B %d, %Y")
    
    # Filter tasks for today
    today_tasks = [t for t in tasks if t.get("date") == today_str]
    
    # Format message
    if today_tasks:
        # Sort by time
        today_tasks.sort(key=lambda x: x.get("time", "00:00"))
        
        task_lines = []
        for t in today_tasks:
            time_str = t.get("time", "--:--")
            desc = t.get("description", "No description")
            completed_mark = " (Completed)" if t.get("completed") else ""
            task_lines.append(f"• <code>{time_str}</code> - <b>{desc}</b>{completed_mark}")
            
        tasks_list_str = "\n".join(task_lines)
        message = (
            f"📅 <b>Your Agenda for {today_readable}</b>\n\n"
            f"{tasks_list_str}\n\n"
            f"Have a productive day!"
        )
    else:
        message = f"📅 <b>Agenda for {today_readable}</b>\n\nNo tasks scheduled for today! Enjoy your free time."

    print("[INFO] Sending agenda to Telegram...")
    send_telegram(message)
    print("[OK] Agenda sent successfully.")

if __name__ == "__main__":
    main()
