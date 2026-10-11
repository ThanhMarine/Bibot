import tarfile, os, sys

tar_path = r"H:\Pi4_Xiaozhi\android_app\Giao ngon ngu Piper\sherpa-onnx-v1.13.8-android.tar.bz2"
extract_dir = r"H:\Pi4_Xiaozhi\android_app\Giao ngon ngu Piper\extracted"

if not os.path.exists(extract_dir):
    os.makedirs(extract_dir, exist_ok=True)

try:
    tar = tarfile.open(tar_path, 'r:bz2')
    tar.extractall(extract_dir)
    tar.close()
    print("Extracted OK")
    
    # List first level
    for item in os.listdir(extract_dir):
        full = os.path.join(extract_dir, item)
        if os.path.isfile(full):
            size = os.path.getsize(full)
            print(f"  FILE: {item} ({size} bytes)")
        elif os.path.isdir(full):
            print(f"  DIR: {item}")
except Exception as e:
    print(f"ERROR: {e}")
    sys.exit(1)