from pathlib import Path
import base64,hashlib,json,shutil
root=Path(__file__).resolve().parent
blob=base64.b64decode((root/'provider.wasm.base64').read_text().strip(),validate=True)
lock=json.loads((root/'artifact-lock.json').read_text())['provider.wasm']
assert len(blob)==lock['size'] and hashlib.sha256(blob).hexdigest()==lock['sha256']
assets=root/'app/src/main/assets';assets.mkdir(parents=True,exist_ok=True)
(assets/'provider.wasm').write_bytes(blob)
for op in ['plan','parse']:
 for kind in ['input','output']:shutil.copy2(root/f'{op}-{kind}.json',assets)
print('Verified Rust fixture SHA-256:',lock['sha256'])
