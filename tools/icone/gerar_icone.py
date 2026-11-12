"""Gera o ícone do Vorticis 3D Studio (variação do ícone do Vorticis Monitor).

Mesmo vórtice de 6 braços em branco/ciano da família Vorticis, mas em bloco com
degradê índigo → violeta e um selo com cubo isométrico (3D) no lugar do pulso.
Uso: python3 -I tools/icone/gerar_icone.py   (requer Pillow)
"""
import math
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

RAIZ = Path(__file__).resolve().parents[2]
RES = RAIZ / "app" / "src" / "main" / "res"
SS = 4
TOPO, BASE = (17, 24, 82), (88, 52, 186)          # degradê índigo -> violeta
CIANO, BRANCO = (103, 232, 249), (255, 255, 255)
SELO = (255, 138, 61)                              # laranja (contraste com o violeta)
CUBO = [(255, 255, 255), (255, 226, 200), (255, 196, 150)]


def degrade(t: int) -> Image.Image:
    grad = Image.new("RGBA", (t, t))
    px = grad.load()
    for y in range(t):
        for x in range(t):
            k = (x + y) / (2 * t)
            px[x, y] = tuple(round(TOPO[i] + (BASE[i] - TOPO[i]) * k) for i in range(3)) + (255,)
    return grad


def bloco(t: int) -> Image.Image:
    mask = Image.new("L", (t, t), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, t - 1, t - 1), radius=round(t * 0.22), fill=255)
    out = Image.new("RGBA", (t, t), (0, 0, 0, 0))
    out.paste(degrade(t), (0, 0), mask)
    return out


def vortice(t: int, cx: float, cy: float, escala: float, cor_unica=None) -> Image.Image:
    img = Image.new("RGBA", (t, t), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    bracos, n = 6, 160
    for b in range(bracos):
        fase = 2 * math.pi * b / bracos
        esq, dir_ = [], []
        for i in range(n):
            s = i / (n - 1)
            r = t * escala * (0.035 + 0.335 * s)
            ang = fase + 1.55 * math.pi * s
            x, y = cx + r * math.cos(ang), cy + r * math.sin(ang)
            dr, da = t * escala * 0.335, 1.55 * math.pi
            tx = dr * math.cos(ang) - r * da * math.sin(ang)
            ty = dr * math.sin(ang) + r * da * math.cos(ang)
            norma = math.hypot(tx, ty)
            nx, ny = -ty / norma, tx / norma
            meia = t * escala * (0.004 + 0.034 * math.sin(math.pi * min(1, s * 1.15)) ** 0.8)
            esq.append((x + nx * meia, y + ny * meia))
            dir_.append((x - nx * meia, y - ny * meia))
        cor = cor_unica or (BRANCO if b % 2 == 0 else CIANO)
        d.polygon(esq + dir_[::-1], fill=cor + (255,))
    rr = t * escala * 0.05
    d.ellipse((cx - rr, cy - rr, cx + rr, cy + rr), fill=(cor_unica or BRANCO) + (255,))
    return img


def selo(t: int, x0: float, y0: float, diam: float, fundo=TOPO) -> Image.Image:
    img = Image.new("RGBA", (t, t), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    borda = diam * 0.055
    d.ellipse((x0 - borda, y0 - borda, x0 + diam + borda, y0 + diam + borda), fill=fundo + (255,))
    d.ellipse((x0, y0, x0 + diam, y0 + diam), fill=SELO + (255,))
    # cubo isométrico
    cx, cy, s = x0 + diam / 2, y0 + diam / 2, diam * 0.26
    topo = [(cx, cy - s), (cx + s * 0.87, cy - s * 0.5), (cx, cy), (cx - s * 0.87, cy - s * 0.5)]
    esq = [(cx - s * 0.87, cy - s * 0.5), (cx, cy), (cx, cy + s), (cx - s * 0.87, cy + s * 0.5)]
    dir_ = [(cx + s * 0.87, cy - s * 0.5), (cx, cy), (cx, cy + s), (cx + s * 0.87, cy + s * 0.5)]
    for poly, cor in zip([topo, esq, dir_], CUBO):
        d.polygon(poly, fill=cor + (255,))
    return img


def sombra_de(img: Image.Image, t: int) -> Image.Image:
    s = Image.new("RGBA", (t, t), (0, 0, 0, 0))
    s.putalpha(img.getchannel("A").point(lambda a: a // 3))
    return s.filter(ImageFilter.GaussianBlur(t * 0.01))


def icone_completo(lado: int) -> Image.Image:
    t = lado * SS
    img = bloco(t)
    v = vortice(t, t * 0.45, t * 0.45, 1.0)
    img.alpha_composite(sombra_de(v, t), (round(t * 0.008), round(t * 0.012)))
    img.alpha_composite(v)
    diam = t * 0.40
    img.alpha_composite(selo(t, t - diam - t * 0.04, t - diam - t * 0.04, diam))
    return img.resize((lado, lado), Image.LANCZOS)


def adaptativo_frente(lado: int = 432) -> Image.Image:
    # zona segura: círculo central de 66/108 do lado
    t = lado * SS
    img = Image.new("RGBA", (t, t), (0, 0, 0, 0))
    v = vortice(t, t * 0.47, t * 0.47, 0.66)
    img.alpha_composite(sombra_de(v, t), (round(t * 0.006), round(t * 0.009)))
    img.alpha_composite(v)
    diam = t * 0.22
    img.alpha_composite(selo(t, t * 0.53, t * 0.53, diam))
    return img.resize((lado, lado), Image.LANCZOS)


def adaptativo_mono(lado: int = 432) -> Image.Image:
    t = lado * SS
    return vortice(t, t * 0.5, t * 0.5, 0.66, cor_unica=BRANCO).resize((lado, lado), Image.LANCZOS)


if __name__ == "__main__":
    for pasta, lado in {"mipmap-mdpi": 48, "mipmap-hdpi": 72, "mipmap-xhdpi": 96, "mipmap-xxhdpi": 144, "mipmap-xxxhdpi": 192}.items():
        (RES / pasta).mkdir(parents=True, exist_ok=True)
        icone_completo(lado).save(RES / pasta / "ic_launcher.png")
    nodpi = RES / "drawable-nodpi"
    nodpi.mkdir(parents=True, exist_ok=True)
    degrade(432).save(nodpi / "ic_launcher_background.png")
    adaptativo_frente().save(nodpi / "ic_launcher_foreground.png")
    adaptativo_mono().save(nodpi / "ic_launcher_monochrome.png")
    icone_completo(256).save(nodpi / "logo_vorticis.png")
    icone_completo(512).save(Path(__file__).resolve().parent / "Vorticis3DStudio-512.png")
    print("ícone gerado")
