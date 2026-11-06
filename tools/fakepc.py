import socket, struct, sys, os, hashlib, threading, time
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

fmt, port, key = sys.argv[1], int(sys.argv[2]), sys.argv[3]
ITER = {'NOVO': 100000, 'INTERMEDIARIO': 50000, 'ANTIGO': 50000}[fmt]
HEADER = fmt != 'ANTIGO'
OLD = "18446744073709551615"

def magic(k):
    b = bytearray(32)
    for i in range(32):
        if i < len(k): b[i] = ord(k[i]) & 0xFF
    h = hashlib.sha512(bytes(b)).digest()
    for _ in range(50000): h = hashlib.sha512(h).digest()
    v = (h[0] << 23) + (h[1] << 16) + (h[-1] << 8) + h[2]
    return v & 0xFFFFFFFF
MAGIC = magic(key)

def derive(salt): return hashlib.pbkdf2_hmac('sha512', key.encode(), salt, ITER, 32)

def is_big(t):
    return t in (3,21,20,51,126,127,79,69,78,125,124,76) or (t & 128) == 128

def build(t, src=0, des=0, name='', extra=None, pid=0):
    size = 64 if is_big(t) else 32
    raw = bytearray(size); raw[0] = t
    struct.pack_into('<i', raw, 4, pid); struct.pack_into('<I', raw, 8, src); struct.pack_into('<I', raw, 12, des)
    if extra: extra(raw)
    if size == 64: raw[32:64] = name.ljust(32).encode()[:32]
    raw[1] = 0; raw[2] = (MAGIC >> 16) & 0xFF; raw[3] = (MAGIC >> 24) & 0xFF
    raw[1] = sum(raw[2:32]) & 0xFF
    return bytes(raw)

def recvn(s, n):
    buf = b''
    while len(buf) < n:
        c = s.recv(n - len(buf))
        if not c: raise EOFError
        buf += c
    return buf

srv = socket.socket(); srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
srv.bind(('127.0.0.1', port)); srv.listen(1)
print('PEER listening', flush=True)
s, _ = srv.accept(); s.settimeout(15)
PCID = 777001; PCNAME = 'TESTPC'

# criptografia de envio
if HEADER:
    hdr = os.urandom(32); s.sendall(hdr)
    ek = derive(hdr[:16]); eiv = hdr[16:]
else:
    ek = derive(OLD.encode('utf-16le')); eiv = OLD[:16].encode()
enc = Cipher(algorithms.AES(ek), modes.CBC(eiv)).encryptor()
def send(p): s.sendall(enc.update(p))
send(os.urandom(16))

# handshake do "PC"
nonce = os.urandom(16)
def hs(raw): raw[16:32] = nonce
for _ in range(10): send(build(126, PCID, 0, PCNAME, hs))
inv = bytes((~b) & 0xFF for b in nonce)

# criptografia de recebimento
if HEADER:
    rh = recvn(s, 32); dk = derive(rh[:16]); div = rh[16:]
else:
    dk = ek; div = eiv
dec = Cipher(algorithms.AES(dk), modes.CBC(div)).decryptor()
def rd(n): return dec.update(recvn(s, n))
rd(16)  # bloco aleatório

def read_pkt():
    first = bytearray(rd(32))
    m = (first[3] << 24) + (first[2] << 16)
    ok = (m == (MAGIC & 0xFFFF0000)) and (sum(first[2:32]) & 0xFF) == first[1]
    first[1:4] = b'\0\0\0'
    t = first[0]
    if not ok: print('PEER invalid packet', flush=True); return None
    if is_big(t): first += rd(32)
    return bytes(first)

client_id = None; trusted = False; hbs = 0
try:
    while True:
        p = read_pkt()
        if p is None: break
        t = p[0]
        if t == 126:   # handshake do cliente -> ack
            def ackx(raw):
                for i in range(16, 32): raw[i] = (~p[i]) & 0xFF
            send(build(127, PCID, 0, PCNAME, ackx))
        elif t == 127:
            if p[16:32] == inv:
                client_id = struct.unpack_from('<I', p, 8)[0]
                trusted = True
                print('PEER trusted client name=%s id=%d' % (p[32:64].decode().strip(), client_id), flush=True)
        elif t == 20 and trusted:
            hbs += 1
            if hbs == 1:
                CLIENTNAME = p[32:64].decode().strip()
                print('PEER got heartbeat name=%s' % CLIENTNAME, flush=True)
                def mouse(x, y, w, f):
                    def fn(raw): struct.pack_into('<iiii', raw, 16, x, y, w, f)
                    return fn
                send(build(123, PCID, client_id, '', mouse(32768, 16384, 0, 0x200), 1))
                send(build(123, PCID, client_id, '', mouse(32768, 16384, 0, 0x201), 2))
                send(build(123, PCID, client_id, '', mouse(32768, 16384, 0, 0x202), 3))
                send(build(123, PCID, client_id, '', mouse(100, 100, 120, 0x20A), 4))
                def key(vk, fl):
                    def fn(raw): struct.pack_into('<ii', raw, 24, vk, fl)
                    return fn
                send(build(122, PCID, client_id, '', key(65, 0), 5))
                send(build(122, PCID, client_id, '', key(65, 0x80), 6))
                send(build(123, PCID, 12345, '', mouse(1, 1, 0, 0x200), 7))  # para outro destino: deve ser ignorado
                # Layout (Matrix) reenviado várias vezes, como o PowerToys em ciclo
                pid = 100
                for _ in range(12):
                    for i, nm in enumerate([PCNAME, 'VAIO', CLIENTNAME, '']):
                        pid += 1
                        send(build(128 | 4, i + 1, 255, nm, None, pid))
                # Área de transferência: texto do PC -> Android (como o MWB: TXT + separador, UTF-16LE, deflate, 48 bytes/pacote)
                import zlib
                SEP = '{4CFF57F7-BEDD-43d5-AE8F-27A61E886F2F}'
                co = zlib.compressobj(wbits=-15)
                blob = co.compress(('TXT' + 'Olá ~ação^ do PC' + SEP + 'RTF{\\rtf1 x}' + SEP).encode('utf-16le')) + co.flush()
                blob += b'\0' * 7
                for i in range(0, len(blob), 48):
                    chunk = blob[i:i+48]
                    def fill(raw, chunk=chunk): raw[16:16+len(chunk)] = chunk
                    pid += 1
                    raw = bytearray(build(124, PCID, 255, '', None, pid))
                    # o nome sobrescreve 32..63: monta à mão
                    raw = bytearray(64); raw[0] = 124
                    struct.pack_into('<iII', raw, 4, pid, PCID, 255)
                    raw[16:16+len(chunk)] = chunk
                    raw[2] = (MAGIC >> 16) & 0xFF; raw[3] = (MAGIC >> 24) & 0xFF
                    raw[1] = sum(raw[2:32]) & 0xFF
                    send(bytes(raw))
                pid += 1
                send(build(76, PCID, 255, '', None, pid))
                # espera o texto de volta do Android
                s.settimeout(5)
                got = bytearray(); 
                while True:
                    q = read_pkt()
                    if q is None: break
                    if q[0] == 124: got += q[16:64]
                    elif q[0] == 76:
                        txt = zlib.decompressobj(wbits=-15).decompress(bytes(got)).decode('utf-16le')
                        print('PEER clipboard from client: %r' % txt, flush=True)
                        break
                send(build(50, PCID, client_id, '', None, 8))                # HideMouse
                s.settimeout(3)
                try:
                    while True:
                        q = read_pkt()
                        if q is None: break
                        if q[0] == 121:
                            x, y, w = struct.unpack_from('<iii', q, 16)
                            des = struct.unpack_from('<I', q, 12)[0]
                            print('PEER NextMachine des=%d x=%d y=%d machine=%d' % (des, x, y, w), flush=True)
                            break
                except Exception as e:
                    print('PEER sem NextMachine:', type(e).__name__, flush=True)
                send(build(4, PCID, 255, PCNAME, None, 9))                   # ByeBye
                time.sleep(0.3)
                break
except EOFError:
    print('PEER eof', flush=True)
print('PEER done', flush=True)
