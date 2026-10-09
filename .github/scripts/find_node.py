"""Print the tap position of a UI node whose text or content-desc matches.

Usage: find_node.py <ui.xml> <label>
  "~label"   substring match
  "label#2"  second matching node (1-based)
"""
import re
import sys
import xml.etree.ElementTree as ET

path, label = sys.argv[1], sys.argv[2]
index = 1
match = re.fullmatch(r"(.*)#(\d+)", label)
if match:
    label, index = match.group(1), int(match.group(2))
contains = label.startswith("~")
if contains:
    label = label[1:]

found = 0
for node in ET.parse(path).iter("node"):
    values = (node.get("text", ""), node.get("content-desc", ""))
    if any((contains and label in v) or (not contains and v == label) for v in values):
        found += 1
        if found == index:
            x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
            print((x1 + x2) // 2, (y1 + y2) // 2)
            sys.exit(0)
sys.exit(1)
