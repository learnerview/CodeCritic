import os
import requests
from tenacity import retry, stop_after_attempt, wait_exponential

try:
    from langchain_groq import ChatGroq
    from langchain_core.messages import HumanMessage, SystemMessage
    LANGCHAIN_AVAILABLE = True
except Exception:
    LANGCHAIN_AVAILABLE = False


class GroqLLM:
    """
    Small LLM provider wrapper that prefers Groq and falls back to OpenAI.

    Responsibilities:
    - Encapsulate HTTP calls and parsing for different providers.
    - Provide retry semantics for transient network/429 errors (via tenacity).
    - Provide a single `complete(prompt)` method for the agent to call.

    Design rationale:
    - Keep provider-specific parsing in one place so the rest of the agent can remain provider-agnostic.
    - Use an explicit fallback (OpenAI) to aid local development when Groq keys are not configured.
    - Avoid embedding provider-specific prompt engineering here; that belongs in the agent logic.
    """

    def __init__(
        self,
        groq_key=None,
        openai_key=None,
        groq_model=None,
        openai_model=None,
        groq_base_url=None,
    ):
        """Initialize wrapper.

        Arguments:
        - groq_key: explicit Groq API key (optional); falls back to `GROQ_API_KEY` env var.
        - openai_key: explicit OpenAI API key (optional); falls back to `OPENAI_API_KEY` env var.
        - groq_model/openai_model: model identifiers for requests.
        - groq_base_url: optional override for the Groq API base URL.
        """
        self.groq_key = groq_key or os.getenv("GROQ_API_KEY")
        self.openai_key = openai_key or os.getenv("OPENAI_API_KEY")
        self.groq_model = groq_model or os.getenv("GROQ_MODEL", "llama-3.3-70b-versatile")
        self.openai_model = openai_model or os.getenv("OPENAI_MODEL", "gpt-4o-mini")
        self.groq_use_langchain = os.getenv("GROQ_USE_LANGCHAIN", "true").lower() in {"1", "true", "yes"}
        self.groq_base_url = groq_base_url or os.getenv(
            "GROQ_BASE_URL", "https://api.groq.com/openai/v1/chat/completions"
        )
        self.timeout = int(os.getenv("LLM_TIMEOUT_SECONDS", "30"))

    @retry(stop=stop_after_attempt(3), wait=wait_exponential(multiplier=1, min=1, max=10))
    def complete(self, prompt: str, max_tokens: int = 512) -> str:
        """Return a text completion for `prompt`.

        Tries Groq first, then OpenAI. Retries on transient failures.
        """
        errors = []
        if self.groq_key:
            try:
                return self._groq_complete(prompt, max_tokens)
            except Exception as exc:
                errors.append(f"Groq failed: {exc}")
        if self.openai_key:
            try:
                return self._openai_complete(prompt, max_tokens)
            except Exception as exc:
                errors.append(f"OpenAI failed: {exc}")
        if errors:
            raise RuntimeError("; ".join(errors))
        raise RuntimeError("No LLM API key configured. Set GROQ_API_KEY or OPENAI_API_KEY.")

    def provider_status(self) -> dict:
        return {
            "groqConfigured": bool(self.groq_key),
            "openaiConfigured": bool(self.openai_key),
            "groqModel": self.groq_model,
            "openaiModel": self.openai_model,
            "langchainAvailable": LANGCHAIN_AVAILABLE,
            "groqUseLangChain": self.groq_use_langchain,
            "groqBaseUrl": self.groq_base_url,
        }

    def validate_connection(self) -> dict:
        if not self.groq_key and not self.openai_key:
            return {"ok": False, "provider": "none", "message": "No LLM API key configured"}
        try:
            self.complete("Reply with exactly OK", max_tokens=8)
            return {"ok": True, "provider": "groq" if self.groq_key else "openai"}
        except Exception as exc:
            return {"ok": False, "provider": "groq" if self.groq_key else "openai", "message": str(exc)}

    def _groq_complete(self, prompt: str, max_tokens: int) -> str:
        """Call Groq completions endpoint and return a plain string.

        Note: Groq response shapes may vary; this method attempts a few common access patterns.
        """
        if self.groq_use_langchain and LANGCHAIN_AVAILABLE:
            return self._groq_complete_langchain(prompt, max_tokens)
        url = self.groq_base_url
        headers = {"Authorization": f"Bearer {self.groq_key}", "Content-Type": "application/json"}
        body = {
            "model": self.groq_model,
            "messages": [
                {"role": "system", "content": "You are a helpful assistant."},
                {"role": "user", "content": prompt},
            ],
            "max_tokens": max_tokens,
        }
        r = requests.post(url, headers=headers, json=body, timeout=self.timeout)
        r.raise_for_status()
        data = r.json()
        # Try common fields
        if isinstance(data, dict):
            if "choices" in data and isinstance(data["choices"], list) and len(data["choices"]) > 0:
                c = data["choices"][0]
                message = c.get("message", {}) if isinstance(c, dict) else {}
                return c.get("text") or message.get("content") or str(c)
            if "output" in data:
                out = data["output"]
                if isinstance(out, list):
                    return "\n".join(map(str, out))
                return str(out)
        return str(data)

    def _groq_complete_langchain(self, prompt: str, max_tokens: int) -> str:
        chat = ChatGroq(
            model=self.groq_model,
            api_key=self.groq_key,
            max_tokens=max_tokens,
            temperature=0.2,
        )
        response = chat.invoke(
            [
                SystemMessage(content="You are a helpful assistant."),
                HumanMessage(content=prompt),
            ]
        )
        content = getattr(response, "content", "")
        if isinstance(content, list):
            return "\n".join(str(item) for item in content)
        return str(content)

    def _openai_complete(self, prompt: str, max_tokens: int) -> str:
        """Fallback to OpenAI Chat Completions API for environments without Groq.

        This keeps development convenient without requiring a Groq key.
        """
        url = "https://api.openai.com/v1/chat/completions"
        headers = {"Authorization": f"Bearer {self.openai_key}", "Content-Type": "application/json"}
        body = {
            "model": self.openai_model,
            "messages": [
                {"role": "system", "content": "You are a helpful assistant."},
                {"role": "user", "content": prompt},
            ],
            "max_tokens": max_tokens,
        }
        r = requests.post(url, headers=headers, json=body, timeout=self.timeout)
        r.raise_for_status()
        data = r.json()
        choices = data.get("choices", [])
        if choices:
            msg = choices[0].get("message", {})
            return msg.get("content") or str(choices[0])
        return str(data)
