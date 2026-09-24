import asyncio
import os
import sys
import json

PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if PROJECT_ROOT not in sys.path:
    sys.path.insert(0, PROJECT_ROOT)

from dotenv import load_dotenv
load_dotenv(os.path.join(PROJECT_ROOT, ".env"), override=True)
from google import genai
from google.genai import types
from core.unified_tool_registry import unified_tool_registry

api_key = os.getenv("GEMINI_API_KEY")
client = genai.Client(api_key=api_key)
model_name = "gemini-3.8-live"

declarations = unified_tool_registry.declarations

async def test_tool(decl):
    return await test_tool_batch([decl], decl["name"])

async def test_tool_batch(batch, label="batch"):
    fds = []
    for decl in batch:
        fds.append(types.FunctionDeclaration(
            name=decl["name"],
            description=decl.get("description", ""),
            parameters=decl.get("parameters"),
        ))
    cfg = types.LiveConnectConfig(
        response_modalities=[types.Modality.AUDIO],
        tools=[types.Tool(function_declarations=fds)],
    )
    try:
        async with client.aio.live.connect(model=model_name, config=cfg) as session:
            print(f"[SUCCESS] {label} ({len(batch)} tools)", flush=True)
            return True, None
    except Exception as e:
        print(f"[FAILED] {label} ({len(batch)} tools) -> {e}", flush=True)
        return False, str(e)

async def main():
    print(f"Total declarations in UnifiedToolRegistry: {len(declarations)}", flush=True)
    
    # Test individual tools
    bad_tools = []
    for idx, decl in enumerate(declarations, 1):
        name = decl["name"]
        ok, err = await test_tool(decl)
        if not ok:
            print(f"[REJECTED] {idx}: {name} -> {err}", flush=True)
            bad_tools.append((name, decl, err))
        else:
            print(f"[OK] {idx}/{len(declarations)}: {name}", flush=True)
    
    print(f"\nIndividual test finished. Total bad tools: {len(bad_tools)}", flush=True)
    for b in bad_tools:
        print(f"Bad Tool: {b[0]}\nSchema: {json.dumps(b[1], indent=2)}\nError: {b[2]}\n", flush=True)

    # Test batches
    print("\n--- Testing subsets ---", flush=True)
    for i in range(0, len(declarations), 20):
        sub = declarations[i:i+20]
        await test_tool_batch(sub, f"Chunk {i}-{i+len(sub)}")

if __name__ == "__main__":
    asyncio.run(main())
