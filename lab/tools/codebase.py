"""문서 자동화 도구들이 공유하는 '코드에서 사실 읽기' 함수 모음 (표준 라이브러리만 사용)."""
import ast
import os
import re

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
JAVA = os.path.join(ROOT, "src/main/java/idvlab")
DOCS = os.path.join(ROOT, "docs")


def read(rel):
    with open(os.path.join(ROOT, rel), encoding="utf-8") as f:
        return f.read()


def result_codes(rel):
    """XxxCode enum → [(이름, HttpStatus, 메시지)]"""
    pat = re.compile(r'^\s+([A-Z_]+)\(HttpStatus\.([A-Z_]+),\s*"([^"]*)"\)', re.M)
    return pat.findall(read(rel))


def enum_with_comments(rel):
    """단순 enum → [(이름, 주석)]"""
    out = []
    for line in read(rel).splitlines():
        m = re.match(r"^\s+([A-Z][A-Z_]+)\s*[,;]?\s*(?://\s*(.*))?$", line)
        if m:
            out.append((m.group(1), (m.group(2) or "").strip()))
    return out


def mock_codes():
    """모의 기관 server.py의 CODES 딕셔너리 → {코드: (HTTP, 의미)}"""
    tree = ast.parse(read("lab/mock-identity-provider/server.py"))
    for node in tree.body:
        if isinstance(node, ast.Assign) and getattr(node.targets[0], "id", None) == "CODES":
            return ast.literal_eval(node.value)
    raise RuntimeError("CODES not found")


def lab_endpoints():
    """이 프로젝트 컨트롤러의 (메서드, 경로) 목록"""
    out = []
    for dirpath, _, files in os.walk(JAVA):
        for name in files:
            if not name.endswith("Controller.java"):
                continue
            src = open(os.path.join(dirpath, name), encoding="utf-8").read()
            base = re.search(r'@RequestMapping\("([^"]+)"\)', src)
            base = base.group(1) if base else ""
            for verb, sub in re.findall(r'@(Get|Post|Put|Patch|Delete)Mapping(?:\((?:value\s*=\s*)?"([^"]*)")?', src):
                out.append((verb.upper(), base + (sub or "")))
    return out


def lab_config_keys():
    """application.yml의 lab.* 하위 설정 키 (2단계까지)"""
    keys, stack = [], []
    for line in read("src/main/resources/application.yml").splitlines():
        if not line.strip() or line.strip().startswith("#"):
            continue
        indent = len(line) - len(line.lstrip())
        key = line.strip().split(":")[0]
        stack = [s for s in stack if s[0] < indent] + [(indent, key)]
        path = ".".join(k for _, k in stack)
        if path.startswith("lab.") and ":" in line and line.split(":", 1)[1].strip():
            keys.append(path)
    return keys
