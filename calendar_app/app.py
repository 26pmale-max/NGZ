from flask import Flask, jsonify, request, render_template, send_from_directory
from flask_cors import CORS
import json
import os
import subprocess
import threading

app = Flask(__name__, template_folder='templates')
CORS(app)

DATA_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'things_to_do.json')
NGZ_DIR = '/home/ale/NGZ'
SSH_KEY = '/home/ale/.ssh/id_ed25519'

# Threading Lock to prevent race conditions during async git syncs
file_lock = threading.Lock()
# Serialise git operations to avoid concurrent push/pull conflicts
git_lock = threading.Lock()

def load_tasks():
    with file_lock:
        if not os.path.exists(DATA_FILE):
            return []
        try:
            with open(DATA_FILE, 'r') as f:
                return json.load(f)
        except Exception:
            return []

def save_tasks(tasks):
    with file_lock:
        try:
            with open(DATA_FILE, 'w') as f:
                json.dump(tasks, f, indent=2)
        except Exception as e:
            print(f"Error saving tasks: {e}")

# Git Sync Helpers — always pass the SSH key explicitly so it works
# even when Flask runs as a systemd service without a login shell.
def run_git_command(args, cwd=NGZ_DIR):
    env = os.environ.copy()
    env['GIT_SSH_COMMAND'] = f'ssh -i {SSH_KEY} -o StrictHostKeyChecking=no'
    env['HOME'] = '/home/ale'
    try:
        res = subprocess.run(args, cwd=cwd, capture_output=True, text=True,
                             timeout=15, env=env)
        if res.returncode != 0:
            print(f"[GIT ERROR] {args} failed: {res.stderr.strip()}")
            return False, res.stderr
        return True, res.stdout
    except Exception as e:
        print(f"[GIT EXCEPTION] {args} failed: {e}")
        return False, str(e)

def sync_pull():
    """Pull latest from GitHub and decrypt tasks.enc into the local database."""
    with git_lock:
        print("[SYNC] Pulling from GitHub...")
        success, _ = run_git_command(['git', 'pull', '--rebase', 'origin', 'main'])
        if not success:
            return False

        enc_file = os.path.join(NGZ_DIR, 'tasks.enc')
        if os.path.exists(enc_file):
            try:
                with open(enc_file, 'r') as f:
                    enc_data = f.read().strip()
                if enc_data:
                    from crypto_helper import decrypt_data
                    decrypted_json = decrypt_data(enc_data)

                    # Verify it is valid JSON before overwriting
                    remote_tasks = json.loads(decrypted_json)

                    # Merge: keep local tasks that aren't in remote, add remote ones
                    local_tasks = load_tasks()
                    local_ids = {t['id'] for t in local_tasks}
                    remote_ids = {t['id'] for t in remote_tasks}

                    # Start with remote as base, add any local-only tasks
                    merged = list(remote_tasks)
                    for lt in local_tasks:
                        if lt['id'] not in remote_ids:
                            merged.append(lt)

                    save_tasks(merged)
                    print(f"[SYNC] Merged database: {len(remote_tasks)} remote + {len(merged) - len(remote_tasks)} local-only = {len(merged)} total")
                    return True
            except Exception as e:
                print(f"[SYNC ERROR] Decryption/merge failed: {e}")
        return False

def sync_pull_async():
    threading.Thread(target=sync_pull, daemon=True).start()

def sync_push():
    """Encrypt the local database and push to GitHub."""
    with git_lock:
        print("[SYNC] Starting push...")

        # 1. Pull first to merge any phone-pushed changes
        run_git_command(['git', 'pull', '--rebase', 'origin', 'main'])

        # 2. Encrypt and write to NGZ
        try:
            with file_lock:
                with open(DATA_FILE, 'r') as f:
                    plain_text = f.read()
            from crypto_helper import encrypt_data
            enc_data = encrypt_data(plain_text)

            enc_file = os.path.join(NGZ_DIR, 'tasks.enc')
            with open(enc_file, 'w') as f:
                f.write(enc_data)
        except Exception as e:
            print(f"[SYNC ERROR] Encryption/Write failed: {e}")
            return

        # 3. Add, commit, and push
        run_git_command(['git', 'add', 'tasks.enc'])
        success_commit, _ = run_git_command(['git', 'commit', '-m', 'Sync update'])
        if success_commit:
            ok, err = run_git_command(['git', 'push', 'origin', 'main'])
            if ok:
                print("[SYNC] Successfully pushed encrypted database to GitHub.")
            else:
                print(f"[SYNC] Push failed: {err}")
        else:
            print("[SYNC] Nothing to commit (database unchanged).")

def sync_push_async():
    threading.Thread(target=sync_push, daemon=True).start()


# ─── Startup: pull latest from GitHub (picks up phone changes) ────
def startup_pull():
    print("[STARTUP] Importing latest changes from GitHub...")
    sync_pull()

threading.Thread(target=startup_pull, daemon=True).start()

# ─── Periodic Background Sync (keeps PC server in sync with phone) ────
def periodic_pull_loop():
    import time
    while True:
        time.sleep(30)
        try:
            sync_pull()
        except Exception as e:
            print(f"[PERIODIC SYNC ERROR] {e}")

threading.Thread(target=periodic_pull_loop, daemon=True).start()


@app.route('/')
def index():
    return render_template('index.html')

@app.route('/manifest.json')
def serve_manifest():
    manifest_data = {
        "name": "Homelab Calendar & Tasks",
        "short_name": "Calendar",
        "description": "Local Glassmorphic Calendar & Task Manager",
        "start_url": "/",
        "display": "standalone",
        "background_color": "#0f172a",
        "theme_color": "#0f172a",
        "orientation": "portrait",
        "icons": [
            {
                "src": "https://cdn-icons-png.flaticon.com/512/3652/3652191.png",
                "sizes": "512x512",
                "type": "image/png",
                "purpose": "any maskable"
            }
        ]
    }
    return jsonify(manifest_data)

@app.route('/sw.js')
def serve_sw():
    sw_code = """
    const CACHE_NAME = 'calendar-cache-v1';
    const urlsToCache = [
        '/',
        'https://cdn.jsdelivr.net/npm/fullcalendar@6.1.11/index.global.min.js',
        'https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700&display=swap'
    ];
    self.addEventListener('install', event => {
        event.waitUntil(
            caches.open(CACHE_NAME).then(cache => cache.addAll(urlsToCache))
        );
    });
    self.addEventListener('fetch', event => {
        event.respondWith(
            caches.match(event.request).then(response => response || fetch(event.request))
        );
    });
    """
    return app.response_class(sw_code, mimetype='application/javascript')

@app.route('/api/tasks', methods=['GET'])
def get_tasks():
    # Trigger pull asynchronously to import phone changes without blocking
    try:
        sync_pull_async()
    except Exception as e:
        print(f"[API] Async pull trigger failed: {e}")
    return jsonify(load_tasks())

@app.route('/api/tasks', methods=['POST'])
def add_task():
    data = request.json
    if not data or 'description' not in data or 'date' not in data or 'time' not in data:
        return jsonify({"error": "Missing fields"}), 400

    tasks = load_tasks()
    new_id = max([t['id'] for t in tasks]) + 1 if tasks else 1
    new_task = {
        "id": new_id,
        "description": data['description'],
        "date": data['date'],
        "time": data['time'],
        "completed": data.get('completed', False)
    }
    tasks.append(new_task)
    save_tasks(tasks)

    # Push update to GitHub asynchronously
    sync_push_async()

    return jsonify(new_task), 201

@app.route('/api/tasks/<int:task_id>', methods=['DELETE'])
def delete_task(task_id):
    tasks = load_tasks()
    filtered_tasks = [t for t in tasks if t['id'] != task_id]
    if len(filtered_tasks) == len(tasks):
        return jsonify({"error": "Task not found"}), 404
    save_tasks(filtered_tasks)

    # Push update to GitHub asynchronously
    sync_push_async()

    return jsonify({"success": True})

@app.route('/api/tasks/<int:task_id>/toggle', methods=['POST'])
def toggle_task(task_id):
    tasks = load_tasks()
    found = False
    for t in tasks:
        if t['id'] == task_id:
            t['completed'] = not t.get('completed', False)
            found = True
            break
    if not found:
        return jsonify({"error": "Task not found"}), 404
    save_tasks(tasks)

    # Push update to GitHub asynchronously
    sync_push_async()

    return jsonify({"success": True})

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=8085, debug=True)
