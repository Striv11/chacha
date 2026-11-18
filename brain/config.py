import os
from pathlib import Path
from dotenv import load_dotenv

ROOT = Path(__file__).resolve().parent.parent
load_dotenv(ROOT / ".env")

DATA = ROOT / "data"
DATA.mkdir(exist_ok=True)
DB_PATH = DATA / "brain.db"
KILL_FILE = DATA / "KILL"  # create this file to stop all agents instantly

BRAIN_TOKEN = os.getenv("BRAIN_TOKEN", "")
OLLAMA_URL = os.getenv("OLLAMA_URL", "http://localhost:11434")
FAST_MODEL = os.getenv("FAST_MODEL", "qwen3:4b")
SMART_MODEL = os.getenv("SMART_MODEL", "qwen3:8b")
THINK_EVERY_MIN = int(os.getenv("THINK_EVERY_MIN", "5"))

# Safeguards
AGENT_MAX_STEPS = 15
AGENT_MAX_CHILDREN = 3
MAX_RUNNING_AGENTS = 4
