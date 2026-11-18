"""Run: .venv/bin/python evals/run_followups.py"""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from brain.think import classify_message

CASES = Path(__file__).with_name("followup_cases.json")
NOW = "Thu 2026-11-19 10:00"


def evaluate(case, result):
    checks = {
        "category": result.get("category") in case["categories"],
        "task": bool(result.get("tasks")) == case["needs_task"],
    }
    if "min_importance" in case:
        checks["importance"] = result.get("importance", 0) >= case["min_importance"]
    if "max_importance" in case:
        checks["importance"] = result.get("importance", 5) <= case["max_importance"]
    return checks


def main():
    cases = json.loads(CASES.read_text(encoding="utf8"))
    passed = 0
    for case in cases:
        result = classify_message(
            case["text"], case.get("person", ""), case.get("direction", "in"), NOW, "none")
        checks = evaluate(case, result)
        ok = all(checks.values())
        passed += int(ok)
        mark = "PASS" if ok else "FAIL"
        print(f"{mark:4} {case['name']}: category={result.get('category')} "
              f"importance={result.get('importance')} tasks={len(result.get('tasks') or [])} checks={checks}")
    score = passed / len(cases)
    print(f"\nFollow-up quality: {passed}/{len(cases)} ({score:.0%})")
    raise SystemExit(0 if score >= 0.8 else 1)


if __name__ == "__main__":
    main()
