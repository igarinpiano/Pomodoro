"""Print the top edge (y) of the navigation bar from `dumpsys window windows` output."""
import re
import sys

block = []
inside = False
for line in open(sys.argv[1], encoding="utf-8", errors="replace"):
    if re.search(r"Window #\d+ Window\{", line):
        if inside:
            break
        inside = "NavigationBar" in line
    if inside:
        block.append(line)

match = re.search(r"[fF]rame=\[\d+,(\d+)\]\[\d+,\d+\]", "".join(block))
if not match:
    sys.exit(1)
print(match.group(1))
