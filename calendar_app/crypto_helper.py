import os
import base64
import hashlib
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from cryptography.hazmat.backends import default_backend

PASSWORD = "my_secure_decryption_key_1237"

def get_key(password: str) -> bytes:
    return hashlib.sha256(password.encode('utf-8')).digest()

def encrypt_data(plain_text: str) -> str:
    key = get_key(PASSWORD)
    iv = os.urandom(16)
    
    # Pad plain_text to 16-byte blocks (PKCS7 padding)
    pad_len = 16 - (len(plain_text) % 16)
    padded_data = plain_text + chr(pad_len) * pad_len
    
    cipher = Cipher(algorithms.AES(key), modes.CBC(iv), backend=default_backend())
    encryptor = cipher.encryptor()
    ciphertext = encryptor.update(padded_data.encode('utf-8')) + encryptor.finalize()
    
    return base64.b64encode(iv + ciphertext).decode('utf-8')

def decrypt_data(encrypted_base64: str) -> str:
    key = get_key(PASSWORD)
    encrypted_data = base64.b64decode(encrypted_base64.encode('utf-8'))
    
    iv = encrypted_data[:16]
    ciphertext = encrypted_data[16:]
    
    cipher = Cipher(algorithms.AES(key), modes.CBC(iv), backend=default_backend())
    decryptor = cipher.decryptor()
    decrypted_padded = decryptor.update(ciphertext) + decryptor.finalize()
    
    pad_len = decrypted_padded[-1]
    return decrypted_padded[:-pad_len].decode('utf-8')
