# PC falso da porta da área de transferência do MWB (15100) para testar o MwbTransfer.
# Uso: python3 fakeclip.py PORTA CHAVE MODO [arquivo]
#   MODO = file (manda o arquivo ao cliente), text, image, push (recebe do cliente e mostra o hash)
import socket, struct, sys, os, hashlib, zlib
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

port, key, mode = int(sys.argv[1]), sys.argv[2], sys.argv[3]
arg = sys.argv[4] if len(sys.argv) > 4 else None
ITER = 100000
def derive(salt): return hashlib.pbkdf2_hmac('sha512', key.encode(), salt, ITER, 32)

srv = socket.socket(); srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
srv.bind(('127.0.0.1', port)); srv.listen(1)
print('CLIP listening', flush=True)
s, _ = srv.accept(); s.settimeout(20)

def recvn(n):
    b = b''
    while len(b) < n:
        c = s.recv(n - len(b))
        if not c: raise EOFError
        b += c
    return b

hdr = os.urandom(32); s.sendall(hdr)
enc = Cipher(algorithms.AES(derive(hdr[:16])), modes.CBC(hdr[16:])).encryptor()
def send(b): s.sendall(enc.update(b))
send(os.urandom(16))
pkg = bytearray(64); pkg[0] = 79; struct.pack_into('<I', pkg, 8, 777001); pkg[32:64] = b'TESTPC'.ljust(32)
send(bytes(pkg))

rh = recvn(32)
dec = Cipher(algorithms.AES(derive(rh[:16])), modes.CBC(rh[16:])).decryptor()
def rd(n): return dec.update(recvn(n))
rd(16)
cp = rd(64)
ctype = cp[0]; src = struct.unpack_from('<I', cp, 8)[0]; post = struct.unpack_from('<I', cp, 16)[0]
print('CLIP client type=%d src=%d post=%d name=%s' % (ctype, src, post, cp[32:64].decode().strip()), flush=True)

if ctype == 69:   # cliente quer receber
    if mode == 'file':
        data = open(arg, 'rb').read(); name = 'C:\\Users\\fabio\\Desktop\\' + os.path.basename(arg)
    elif mode == 'text':
        SEP = '{4CFF57F7-BEDD-43d5-AE8F-27A61E886F2F}'
        co = zlib.compressobj(wbits=-15)
        data = co.compress(('TXT' + 'Texto grande ' * 20000 + 'fim ção' + SEP).encode('utf-16le')) + co.flush(); name = 'text'
    elif mode == 'image':
        data = open(arg, 'rb').read(); name = 'image'
    else:
        data = b''; name = 'C:\\pasta - Folder is not supported, zip it first!'
    h = bytearray(1024); hb = ('%d*%s' % (len(data), name)).encode('utf-16le'); h[:len(hb)] = hb
    send(bytes(h)); send(data)
    pad = 32 - (len(data) % 32); send(b'\0' * pad)
    s.shutdown(socket.SHUT_WR)
    print('CLIP sent %d bytes sha=%s' % (len(data), hashlib.sha256(data).hexdigest()[:16]), flush=True)
    try:
        while s.recv(1024): pass
    except Exception: pass
elif ctype == 79:  # cliente vai enviar
    h = rd(1024).decode('utf-16le').replace('\0', '')
    size, name = h.split('*', 1); size = int(size)
    buf = b''
    try:
        while True:
            c = s.recv(65536)
            if not c: break
            buf += c
    except Exception as e:
        print('CLIP recv err', e, flush=True)
    whole = len(buf) - len(buf) % 16
    data = dec.update(buf[:whole])[:size]
    print('CLIP got header=%r size=%d received=%d sha=%s' % (name, size, len(data), hashlib.sha256(data).hexdigest()[:16]), flush=True)
s.close()
print('CLIP done', flush=True)
