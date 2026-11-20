import glob, os, struct, sys

ROOT = r"C:\Users\Dipanjan\Downloads\Workspace\AURALIS-FULL\app\build\intermediates\stripped_native_libs\debug"
PAGE_16K = 16384


def load_segments(path):
    with open(path, "rb") as f:
        data = f.read()
    if data[:4] != b"\x7fELF":
        return None
    is64 = data[4] == 2
    little = data[5] == 1
    end = "<" if little else ">"
    if is64:
        e_phoff = struct.unpack_from(end + "Q", data, 0x20)[0]
        e_phentsize = struct.unpack_from(end + "H", data, 0x36)[0]
        e_phnum = struct.unpack_from(end + "H", data, 0x38)[0]
        segs = []
        for i in range(e_phnum):
            off = e_phoff + i * e_phentsize
            p_type = struct.unpack_from(end + "I", data, off)[0]
            p_align = struct.unpack_from(end + "Q", data, off + 0x30)[0]
            if p_type == 1:  # PT_LOAD
                segs.append(p_align)
        return segs
    else:
        e_phoff = struct.unpack_from(end + "I", data, 0x1C)[0]
        e_phentsize = struct.unpack_from(end + "H", data, 0x2A)[0]
        e_phnum = struct.unpack_from(end + "H", data, 0x2C)[0]
        segs = []
        for i in range(e_phnum):
            off = e_phoff + i * e_phentsize
            p_type = struct.unpack_from(end + "I", data, off)[0]
            p_align = struct.unpack_from(end + "I", data, off + 0x1C)[0]
            if p_type == 1:
                segs.append(p_align)
        return segs


def main():
    files = glob.glob(os.path.join(ROOT, "**", "*.so"), recursive=True)
    if not files:
        print("no .so files found")
        return
    bad = 0
    for path in sorted(files):
        segs = load_segments(path)
        rel = os.path.relpath(path, ROOT)
        if segs is None:
            print(f"  ?? not ELF: {rel}")
            continue
        ok = all(a >= PAGE_16K for a in segs)
        align_hex = ",".join(hex(a) for a in segs)
        status = "OK 16KB" if ok else "FAIL(<16KB)"
        if not ok:
            bad += 1
        print(f"  [{status}] {rel}: LOAD p_align=[{align_hex}]")
    print()
    print("ALL ALIGNED FOR 16 KB PAGES" if bad == 0 else f"{bad} LIBRARY(IES) NOT 16 KB ALIGNED")


main()
