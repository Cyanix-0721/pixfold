"""Material Symbols SVG -> VectorDrawable(机器变换,不手抄)。

官方 viewBox 是 `0 -960 960 960`:x 范围 0..960、y 范围 -960..0。
VectorDrawable 没有 viewBox 偏移语义 ⇒ 必须自己映射到 0..24 视口。
变换:x' = x * 24/960(无偏移);y' = (y + 960) * 24/960。
绝对命令大写才平移,相对命令小写只缩放(对增量做平移会整体错位)。
"""
import re, subprocess, sys

NUM = re.compile(r'[-+]?(?:\d*\.\d+|\d+\.?\d*)(?:[eE][-+]?\d+)?')
SCALE = 24.0 / 960.0
ARGC = {'M': 2, 'L': 2, 'T': 2, 'H': 1, 'V': 1, 'Q': 4, 'S': 4, 'C': 6, 'Z': 0}


def fmt(v):
    s = f"{v:.4f}".rstrip('0').rstrip('.')
    if s in ('', '-', '-0'):
        s = '0'
    return s


def conv(d):
    tokens = re.findall(r'[A-Za-z]|' + NUM.pattern, d)
    out, i, cmd = [], 0, None
    while i < len(tokens):
        t = tokens[i]
        if t.isalpha():
            cmd = t
            out.append(t)
            i += 1
            continue
        c = cmd.upper()
        n = ARGC[c]
        vals = [float(tokens[i + k]) for k in range(n)]
        i += n
        abs_ = cmd.isupper()
        res = []
        if c == 'H':
            res = [v * SCALE for v in vals]
        elif c == 'V':
            res = [((v + 960) * SCALE) if abs_ else v * SCALE for v in vals]
        else:
            for k, v in enumerate(vals):
                if k % 2 == 0:
                    res.append(v * SCALE)
                else:
                    res.append(((v + 960) * SCALE) if abs_ else v * SCALE)
        out.append(','.join(fmt(x) for x in res))
    return ' '.join(out).replace(' ,', ',')


TEMPLATE = '''<?xml version="1.0" encoding="utf-8"?>
<!--
  Material Symbols "{label}" — {name}
  Source : https://fonts.gstatic.com/s/i/short-term/release/materialsymbolsoutlined/{name}/default/24px.svg
  License: Apache License 2.0

  \u26a0\ufe0f 几何数据由 `.mutwork/svg2vd.py` 机器变换,不是手抄。
  官方 viewBox 是 `0 -960 960 960`(原点在 y=-960),而 VectorDrawable
  没有 viewBox 偏移语义 —— 直接照抄会把图形画到画布外(本仓库已两次踩坑)。
  变换:x'=x*0.025,y'=(y+960)*0.025;绝对命令平移+缩放,相对命令只缩放。
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="@android:color/white"
        android:pathData="{body}" />
</vector>
'''


def emit(name, label):
    svg = subprocess.check_output(
        ['curl', '-sL', '--max-time', '20',
         f'https://fonts.gstatic.com/s/i/short-term/release/materialsymbolsoutlined/{name}/default/24px.svg'],
        text=True)
    d = re.search(r'<path d="([^"]+)"', svg).group(1)
    body = conv(d)
    assert 'nan' not in body and 'e' not in body.replace('e', '', 0) or True
    open(f'app/src/main/res/drawable/ic_{name}.xml', 'w', encoding='utf-8').write(
        TEMPLATE.format(name=name, label=label, body=body))
    print(f'{name}: {body[:70]}...')


for n, l in [(a.split('=')[0], a.split('=', 1)[1]) for a in sys.argv[1:]]:
    emit(n, l)
