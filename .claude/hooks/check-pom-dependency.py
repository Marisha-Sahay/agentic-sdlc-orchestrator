#!/usr/bin/env python3
"""PreToolUse(Edit|Write) hook for pom.xml: verify added or changed dependency/plugin versions.

Blocks (exit 2) when a literal version is LATEST/RELEASE/SNAPSHOT-range-like, or when the
artifact/version does not exist on Maven Central. Notes a newer release as context (no block).
Fails open on network errors or anything it cannot parse; the orchestrator's own
'dependency-change' policy rule remains the authorization gate for agent runs.
"""
import json, os, re, sys, urllib.request, urllib.error
import xml.etree.ElementTree as ET

CENTRAL = "https://repo1.maven.org/maven2"


def strip_ns(root):
    for el in root.iter():
        if isinstance(el.tag, str) and "}" in el.tag:
            el.tag = el.tag.split("}", 1)[1]
    return root


def coords(xml):
    """{(group, artifact, kind): version-or-None} for dependencies and plugins with a version."""
    try:
        root = strip_ns(ET.fromstring(xml))
    except ET.ParseError:
        return None
    props = {c.tag: (c.text or "").strip() for p in root.findall("properties") for c in p}
    out = {}
    for kind, tag in (("dependency", "dependency"), ("plugin", "plugin")):
        for el in root.iter(tag):
            a = (el.findtext("artifactId") or "").strip()
            if not a:
                continue
            g = (el.findtext("groupId") or "").strip()
            if not g and kind == "plugin":
                g = "org.apache.maven.plugins"
            v = (el.findtext("version") or "").strip()
            m = re.fullmatch(r"\$\{(.+)\}", v)
            if m:
                v = props.get(m.group(1), "")
            if g and v and "${" not in v:
                out[(g, a, kind)] = v
    return out


def metadata_versions(g, a):
    url = f"{CENTRAL}/{g.replace('.', '/')}/{a}/maven-metadata.xml"
    try:
        with urllib.request.urlopen(url, timeout=8) as r:
            root = ET.fromstring(r.read())
    except urllib.error.HTTPError as e:
        return [] if e.code == 404 else None
    except Exception:
        return None
    return [v.text for v in root.iter("version")] + [("RELEASE:" + (root.findtext("versioning/release") or ""))]


def main():
    try:
        data = json.load(sys.stdin)
    except ValueError:
        return 0
    ti = data.get("tool_input", {})
    path = ti.get("file_path", "")
    if os.path.basename(path) != "pom.xml":
        return 0
    before = open(path).read() if os.path.exists(path) else ""
    if data.get("tool_name") == "Write":
        after = ti.get("content", "")
    else:
        old, new = ti.get("old_string", ""), ti.get("new_string", "")
        after = before.replace(old, new) if ti.get("replace_all") else before.replace(old, new, 1)
    now = coords(after)
    if not now:
        return 0
    was = coords(before) or {}
    problems, notes = [], []
    for (g, a, kind), v in sorted(now.items()):
        if was.get((g, a, kind)) == v:
            continue
        if v in ("LATEST", "RELEASE") or re.search(r"[\[\](),]", v):
            problems.append(f"{g}:{a} uses an unpinned version '{v}'")
            continue
        versions = metadata_versions(g, a)
        if versions is None:
            notes.append(f"could not reach Maven Central to verify {g}:{a}:{v}")
            continue
        release = next((x[8:] for x in versions if x.startswith("RELEASE:")), "")
        versions = [x for x in versions if not x.startswith("RELEASE:")]
        if not versions:
            problems.append(f"{g}:{a} was not found on Maven Central")
        elif v not in versions:
            problems.append(f"{g}:{a}:{v} does not exist on Maven Central "
                            f"(latest release: {release or versions[-1]})")
        elif release and release != v:
            notes.append(f"{g}:{a}:{v} is not the latest release ({release})")
    if problems:
        print("Blocked pom.xml change: " + "; ".join(problems), file=sys.stderr)
        return 2
    if notes:
        print(json.dumps({"hookSpecificOutput": {"hookEventName": "PreToolUse",
              "additionalContext": "Dependency check: " + "; ".join(notes)}}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
