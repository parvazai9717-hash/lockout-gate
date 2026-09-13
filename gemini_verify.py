"""
Gemini-backed proof verification.

This is the part of the "work session" layer that actually judges whether a
submitted screenshot/video is real evidence of the task the user declared
when they started the session — not a rubber stamp.

Fail-closed: any error talking to Gemini, or any response that doesn't parse
into a clean verdict, is treated as a rejection. Same trust posture as the
rest of this repo (gate.py's docstring: "the phone may only ever ASK").
"""

import os

from google import genai
from google.genai import errors as genai_errors
from google.genai import types
from pydantic import BaseModel

GEMINI_API_KEY = os.getenv("GEMINI_API_KEY", "")
GEMINI_MODEL = os.getenv("GEMINI_MODEL", "gemini-2.5-flash")

_client: genai.Client | None = None


def _get_client() -> genai.Client:
    global _client
    if _client is None:
        if not GEMINI_API_KEY:
            raise RuntimeError("GEMINI_API_KEY is not set")
        _client = genai.Client(api_key=GEMINI_API_KEY)
    return _client


class GeminiVerdict(BaseModel):
    verdict: str  # "accepted" | "rejected"
    confidence: float
    reasoning: str


class VerifyResult(BaseModel):
    accepted: bool
    confidence: float
    reasoning: str


PROMPT_TEMPLATE = """\
You are a strict work-accountability auditor. The user claimed they were \
doing this task:

    TASK: {task}

They submitted this note along with a screenshot or short screen-recording \
as proof:

    NOTE: {note}

Judge whether the attached media is clear, specific, and current evidence \
that the user was actively doing exactly that task — not a generic app \
being open, not an old or unrelated screenshot, not a stock image, not \
something that merely looks work-adjacent. Be skeptical by default: if the \
media is ambiguous, vague, low-effort, or could plausibly have nothing to \
do with the stated task, reject it. Only accept when the evidence \
unambiguously matches the stated task.

Respond with your verdict ("accepted" or "rejected"), a confidence from \
0.0 to 1.0, and a short one or two sentence reasoning explaining exactly \
what you saw and why it does or doesn't match.
"""


def _ask_gemini(prompt: str, media_bytes: bytes, mime_type: str) -> VerifyResult:
    """Shared Gemini call + fail-closed error handling for both verifiers."""
    try:
        client = _get_client()
        response = client.models.generate_content(
            model=GEMINI_MODEL,
            contents=[
                prompt,
                types.Part.from_bytes(data=media_bytes, mime_type=mime_type),
            ],
            config=types.GenerateContentConfig(
                response_mime_type="application/json",
                response_json_schema=GeminiVerdict.model_json_schema(),
            ),
        )
        parsed = GeminiVerdict.model_validate_json(response.text)
        return VerifyResult(
            accepted=parsed.verdict.strip().lower() == "accepted",
            confidence=parsed.confidence,
            reasoning=parsed.reasoning,
        )
    except genai_errors.APIError as e:
        return VerifyResult(
            accepted=False,
            confidence=0.0,
            reasoning=f"rejected: verification service error ({e.code}: {e.message})",
        )
    except Exception as e:  # malformed response, bad key, etc. — fail closed
        return VerifyResult(
            accepted=False,
            confidence=0.0,
            reasoning=f"rejected: could not verify ({e})",
        )


def verify(task: str, note: str, media_bytes: bytes, mime_type: str) -> VerifyResult:
    """Ask Gemini whether media_bytes is genuine proof of `task`. Fails closed."""
    prompt = PROMPT_TEMPLATE.format(task=task, note=note or "(no note given)")
    return _ask_gemini(prompt, media_bytes, mime_type)


MEAL_PROMPT_TEMPLATE = """\
You are a strict auditor checking whether a photo is genuine, current proof \
that the person submitting it is actively eating real food right now.

Accept ONLY if the photo clearly shows the person themselves (or unmistakably \
their own point of view, e.g. a plate/food they are actively holding or eating \
from) genuinely eating or about to eat real food in the moment.

Reject if: it's a stock or found photo of food with no clear connection to a \
real person actually eating; it's just food sitting on a table with no signs \
of someone present and eating; it looks like an old, reused, or screenshotted \
image rather than a fresh photo; it's unrelated to eating entirely; or it's \
otherwise ambiguous or low-effort. Be skeptical by default — this is a \
photo someone could reuse to repeatedly cheat a break-time limit, so only \
accept clear, unambiguous, current evidence of real eating.

Respond with your verdict ("accepted" or "rejected"), a confidence from \
0.0 to 1.0, and a short one or two sentence reasoning explaining exactly \
what you saw and why it does or doesn't qualify.
"""


def verify_meal(media_bytes: bytes, mime_type: str) -> VerifyResult:
    """Ask Gemini whether media_bytes is a genuine photo of the user eating. Fails closed."""
    return _ask_gemini(MEAL_PROMPT_TEMPLATE, media_bytes, mime_type)
