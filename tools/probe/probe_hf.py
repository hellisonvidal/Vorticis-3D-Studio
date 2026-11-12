"""Descobre os endpoints dos espaços 3D gratuitos do Hugging Face e faz uma geração de teste.
Roda no GitHub Actions (workflow 'Sondar nuvem grátis'). Usa só imagem sintética do próprio app."""
import json, os, sys, time, traceback
from gradio_client import Client, handle_file

SPACES = sys.argv[1].split(",") if len(sys.argv) > 1 else [
    "trellis-community/TRELLIS", "JeffreyXiang/TRELLIS", "tencent/Hunyuan3D-2", "tencent/Hunyuan3D-2.1",
    "stabilityai/stable-fast-3d", "stabilityai/TripoSR",
]
TRY = os.environ.get("TRY_GENERATE", "")
token = os.environ.get("HF_TOKEN") or None

def make(sp):
    try:
        return Client(sp, token=token, verbose=False)
    except TypeError:
        return Client(sp, hf_token=token, verbose=False)

for sp in SPACES:
    print("=" * 80, "\n", sp, flush=True)
    try:
        c = make(sp)
        api = c.view_api(return_format="dict", print_info=False)
        for name, ep in (api.get("named_endpoints") or {}).items():
            params = [(p.get("parameter_name"), p.get("python_type", {}).get("type"), p.get("parameter_default")) for p in ep.get("parameters", [])]
            rets = [(r.get("label"), r.get("python_type", {}).get("type")) for r in ep.get("returns", [])]
            print(f"  {name}\n     params={params}\n     returns={rets}", flush=True)
        print("  config api_prefix:", getattr(c, "api_prefix", None), " src:", c.src, flush=True)
    except Exception as e:
        print("  ERRO:", repr(e)[:400], flush=True)

if TRY:
    sp, steps = TRY.split("|", 1)
    print("#" * 80, "\nGERAÇÃO DE TESTE em", sp, flush=True)
    c = make(sp)
    img = handle_file(os.path.join(os.path.dirname(__file__), "teste_foguete.png"))
    for step in json.loads(steps):
        args = [img if a == "$IMG" else a for a in step.get("args", [])]
        kw = {k: (img if v == "$IMG" else v) for k, v in step.get("kwargs", {}).items()}
        t0 = time.time()
        try:
            r = c.predict(*args, api_name=step["api"], **kw)
            print(f"  {step['api']} OK em {time.time()-t0:.0f}s ->", json.dumps(r, default=str)[:800], flush=True)
            if isinstance(r, (list, tuple)):
                for x in r:
                    p = x.get("value") if isinstance(x, dict) else x
                    if isinstance(p, str) and os.path.exists(p): print("     arquivo:", p, os.path.getsize(p), "bytes")
            elif isinstance(r, str) and os.path.exists(r): print("     arquivo:", r, os.path.getsize(r), "bytes")
        except Exception:
            print(f"  {step['api']} FALHOU em {time.time()-t0:.0f}s:", traceback.format_exc()[-1200:], flush=True)
