"""Print reviewable public dependency lock/checksum metadata from the CI bootstrap run."""
import json
from pathlib import Path

for path in [Path("app/gradle.lockfile"), Path("gradle/verification-metadata.xml")]:
    if path.is_file():
        text = path.read_text()
        chunks = [text[i:i+8000] for i in range(0, len(text), 8000)]
        for index, chunk in enumerate(chunks):
            print(json.dumps({"build_input": str(path), "part": index, "parts": len(chunks), "content": chunk}))
