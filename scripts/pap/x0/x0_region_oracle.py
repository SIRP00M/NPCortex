"""X0 independent oracle: count chunk entries and Status values in region files of a DISPOSABLE world (read-only)."""
import json, os, struct, sys, zlib, re, collections
STATUS = re.compile(rb"\x08\x00\x06Status\x00(.)", re.S)
def scan(world):
    out = {}
    for dim, sub in (("overworld", "region"), ("nether", "DIM-1/region"), ("end", "DIM1/region")):
        d = os.path.join(world, sub)
        files = bytes_ = chunks = 0
        status = collections.Counter()
        if os.path.isdir(d):
            for f in sorted(os.listdir(d)):
                if not f.endswith(".mca"): continue
                p = os.path.join(d, f); files += 1; bytes_ += os.path.getsize(p)
                with open(p, "rb") as fh:
                    hdr = fh.read(4096)
                    if len(hdr) < 4096: continue
                    for i in range(1024):
                        off = int.from_bytes(hdr[i*4:i*4+3], "big"); n = hdr[i*4+3]
                        if off == 0 or n == 0: continue
                        chunks += 1
                        fh.seek(off * 4096)
                        raw = fh.read(5)
                        if len(raw) < 5: continue
                        ln, ct = struct.unpack(">IB", raw)
                        data = fh.read(ln - 1)
                        try: nbt = zlib.decompress(data) if ct == 2 else (data if ct == 3 else b"")
                        except zlib.error: nbt = b""
                        m = STATUS.search(nbt)
                        if m:
                            ln2 = nbt[m.end()-1] if False else None
                            k = nbt.find(b"\x08\x00\x06Status")
                            slen = struct.unpack(">H", nbt[k+9:k+11])[0]
                            status[nbt[k+11:k+11+slen].decode("ascii", "replace")] += 1
                        else: status["<unreadable>"] += 1
        out[dim] = {"region_files": files, "region_bytes": bytes_, "chunk_entries": chunks, "status": dict(status)}
    return out
if __name__ == "__main__":
    print(json.dumps(scan(sys.argv[1]), sort_keys=True))
