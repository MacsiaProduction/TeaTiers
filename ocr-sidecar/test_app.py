"""
Sidecar request-contract + guard tests (review F9/#108-followup). These deliberately do NOT use
TestClient as a context manager, so the lifespan (which loads the ONNX models) never runs — the
recognizer is stubbed, so the tests need no models and run fast in CI. They cover the request
validation, the decompression-bomb guard (F1), and the output length cap (F8).
"""
import io

from fastapi.testclient import TestClient
from PIL import Image

import app as app_module

client = TestClient(app_module.app)


def _png(width: int, height: int, color=(200, 200, 200)) -> bytes:
    buf = io.BytesIO()
    Image.new("RGB", (width, height), color).save(buf, format="PNG")
    return buf.getvalue()


def test_empty_file_returns_400():
    r = client.post("/ocr", files={"file": ("x.png", b"", "image/png")})
    assert r.status_code == 400


def test_oversized_bytes_returns_413(monkeypatch):
    monkeypatch.setattr(app_module, "MAX_IMAGE_BYTES", 10)
    r = client.post("/ocr", files={"file": ("x.png", b"x" * 64, "image/png")})
    assert r.status_code == 413


def test_pixel_bomb_returns_413(monkeypatch):
    # A small-in-bytes image whose decoded pixel count exceeds the budget must 413 before decode.
    monkeypatch.setattr(app_module, "MAX_IMAGE_PIXELS", 100)  # 50x50 = 2500 > 100
    r = client.post("/ocr", files={"file": ("x.png", _png(50, 50), "image/png")})
    assert r.status_code == 413


def test_valid_image_returns_recognized_text(monkeypatch):
    monkeypatch.setattr(app_module, "_recognize", lambda data: "Зелёный чай")
    r = client.post("/ocr", files={"file": ("x.png", _png(40, 20), "image/png")})
    assert r.status_code == 200
    assert r.json() == {"text": "Зелёный чай"}


def test_within_pixel_budget_passes_small_and_undecodable():
    assert app_module.within_pixel_budget(_png(40, 20)) is True
    # A non-image blob is not rejected here (the recognizer handles it) — guard is bomb-only.
    assert app_module.within_pixel_budget(b"not an image") is True


def test_recognize_caps_text_length(monkeypatch):
    monkeypatch.setattr(app_module, "MAX_TEXT_CHARS", 10)

    class FakeResult:
        txts = ["a" * 100]

    monkeypatch.setattr(app_module, "_engine", lambda arr: FakeResult())
    assert app_module._recognize(_png(20, 20)) == "a" * 10


def test_health_ok_without_models():
    r = client.get("/health")
    assert r.status_code == 200
    assert r.json()["status"] in ("ok", "loading")
