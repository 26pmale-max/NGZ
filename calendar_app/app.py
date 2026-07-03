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

def load_tasks():
    if not os.path.exists(DATA_FILE):
        return []
    try:
        with open(DATA_FILE, 'r') as f:
            return json.load(f)
    except Exception:
        return []

def save_tasks(tasks):
    try:
        with open(DATA_FILE, 'w') as f:
            json.dump(tasks, f, indent=2)
    except Exception as e:
        print(f"Error saving tasks: {e}")

# Git Sync Helpers
def run_git_command(args, cwd=NGZ_DIR):
    try:
        res = subprocess.run(args, cwd=cwd, capture_output=True, text=True, timeout=10)
        if res.returncode != 0:
            print(f"[GIT ERROR] {args} failed: {res.stderr}")
            return False, res.stderr
        return True, res.stdout
    except Exception as e:
        print(f"[GIT EXCEPTION] {args} failed: {e}")
        return False, str(e)

def sync_pull():
    print("[SYNC] Pulling from GitHub...")
    success, _ = run_git_command(['git', 'pull', 'origin', 'main'])
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
                json.loads(decrypted_json)
                
                with open(DATA_FILE, 'w') as f:
                    f.write(decrypted_json)
                print("[SYNC] Decrypted and synchronized database from GitHub.")
                return True
        except Exception as e:
            print(f"[SYNC ERROR] Decryption failed: {e}")
    return False

def sync_push_async():
    def task():
        print("[SYNC] Starting async push...")
        # 1. Pull first to merge remote changes
        run_git_command(['git', 'pull', 'origin', 'main'])
        
        # 2. Encrypt and write to NGZ
        try:
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
            run_git_command(['git', 'push', 'origin', 'main'])
            print("[SYNC] Successfully pushed encrypted database to GitHub.")
            
    threading.Thread(target=task).start()

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
    try:
        sync_pull()
    except Exception as e:
        print(f"[API] Sync pull during GET failed: {e}")
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
