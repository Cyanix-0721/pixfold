#!/bin/bash
cd /mnt/d/Programing/Personal/pixfold/pixfold-d1
timeout 90 cmd.exe /c "D:\\Programing\\Personal\\pixfold\\pixfold-d1\\dumpui.bat _x" >/dev/null 2>&1
python3 - "$1" <<'PY'
import re,sys
pat=sys.argv[1]
s=open('artifacts/_x.xml',encoding='utf-8').read()
for m in re.finditer(r'<node[^>]*?>',s):
    frag=m.group(0)
    t=re.search(r'text="([^"]*)"',frag); cd=re.search(r'content-desc="([^"]*)"',frag)
    val=(t.group(1) if t else '')+'|'+(cd.group(1) if cd else '')
    b=re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',frag)
    if b and pat in val:
        x1,y1,x2,y2=map(int,b.groups())
        if x2>x1 and y2>y1: print('%d %d'%((x1+x2)//2,(y1+y2)//2)); break
PY
