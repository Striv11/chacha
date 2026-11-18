"""Banking and payment content is never stored. The phone filters first; this is the second lock."""
import re

_NAMES = re.compile(
    r"(?i)\b(hdfc\w*|icici\w*|sbi\w*|axis\s?bank|axisbk|kotak\w*|yes\s?bank|pnb\w*|idfc\w*|indusind|canara|"
    r"bank\s?of\s?\w+|paytm|phonepe|gpay|google\s?pay|bhim|cred|razorpay)\b")
_MONEY = re.compile(
    r"(?i)\b(debited|credited|a/c|acct|avl\.?\s?bal|available\s?balance|upi\s?(ref|id|txn)|net\s?banking|"
    r"credit\s?card|debit\s?card|card\s?ending|bank\s?statement|emi\s?due)\b")


def is_banking(person: str, text: str) -> bool:
    return bool(_NAMES.search(person or "") or _MONEY.search(text or ""))
