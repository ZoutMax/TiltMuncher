# Finds the phone's wireless-debugging connect port (random 30000-49999): python3 tools/find-adb-port.py <phone-ip> <port-to-skip>
import socket, sys
from concurrent.futures import ThreadPoolExecutor
host = sys.argv[1]; skip = int(sys.argv[2])
def probe(p):
    if p == skip: return None
    s = socket.socket(); s.settimeout(0.4)
    try:
        s.connect((host, p)); return p
    except OSError: return None
    finally: s.close()
with ThreadPoolExecutor(300) as ex:
    hits = [p for p in ex.map(probe, range(30000, 50000)) if p]
print(" ".join(map(str, hits)))
