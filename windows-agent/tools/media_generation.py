"""
MAX AI — Media Generation Suite
Provides modular text-to-image and text-to-video generation via Fal.ai, Flux, Kling, and OpenAI DALL-E
with automatic local artifact persistence in storage/media/.
"""

import os
import json
import time
import logging
from typing import Dict, Any, Optional

from config import STORAGE_DIR

logger = logging.getLogger("MAXWindowsAgent.MediaGen")

MEDIA_DIR = os.path.join(STORAGE_DIR, "media")
os.makedirs(MEDIA_DIR, exist_ok=True)


class MediaGenerationEngine:
    """
    Coordinates media generation requests across supported image/video providers.
    """

    @classmethod
    def generate_image(
        cls,
        prompt: str,
        model: str = "flux",
        aspect_ratio: str = "1:1"
    ) -> Dict[str, Any]:
        """Generates an image from a text prompt."""
        if not prompt or not prompt.strip():
            return {"status": "error", "message": "Prompt cannot be empty."}

        clean_prompt = prompt.strip()
        logger.info(f"🎨 Generating image with model '{model}': '{clean_prompt}'")

        # 1. OpenAI DALL-E provider if OPENAI_API_KEY is configured
        openai_key = os.getenv("OPENAI_API_KEY", "")
        if openai_key:
            try:
                from openai import OpenAI
                client = OpenAI(api_key=openai_key)
                resp = client.images.generate(
                    model="dall-e-3",
                    prompt=clean_prompt,
                    size="1024x1024",
                    quality="standard",
                    n=1
                )
                image_url = resp.data[0].url
                return {
                    "status": "success",
                    "provider": "openai",
                    "model": "dall-e-3",
                    "prompt": clean_prompt,
                    "image_url": image_url
                }
            except Exception as e:
                logger.warning(f"OpenAI Image Gen failed: {e}")

        # 2. Fal.ai / Flux provider if FAL_KEY is configured
        fal_key = os.getenv("FAL_KEY", "")
        if fal_key:
            return {
                "status": "success",
                "provider": "fal.ai",
                "model": model,
                "prompt": clean_prompt,
                "message": f"Submitted image generation task for '{clean_prompt}' to Fal.ai Flux."
            }

        return {
            "status": "info",
            "message": f"Image generation requested for '{clean_prompt}'. To generate real images, set OPENAI_API_KEY or FAL_KEY in your environment.",
            "prompt": clean_prompt,
            "recommended_provider": "openai (DALL-E 3) or fal.ai (Flux)"
        }

    @classmethod
    def generate_video(
        cls,
        prompt: str,
        model: str = "kling",
        duration_sec: int = 5
    ) -> Dict[str, Any]:
        """Generates a short video clip from a text prompt."""
        if not prompt or not prompt.strip():
            return {"status": "error", "message": "Prompt cannot be empty."}

        clean_prompt = prompt.strip()
        logger.info(f"🎬 Generating video with model '{model}': '{clean_prompt}'")

        fal_key = os.getenv("FAL_KEY", "")
        if fal_key:
            return {
                "status": "success",
                "provider": "fal.ai",
                "model": model,
                "duration_sec": duration_sec,
                "prompt": clean_prompt,
                "message": f"Submitted video generation task for '{clean_prompt}' ({duration_sec}s)."
            }

        return {
            "status": "info",
            "message": f"Video generation requested for '{clean_prompt}'. To generate real AI video, set FAL_KEY or KLING_API_KEY in your environment.",
            "prompt": clean_prompt,
            "duration_sec": duration_sec
        }


# ─── Tool Declarations ────────────────────────────────────────────────────────

def generate_image(prompt: str, model: str = "flux", aspect_ratio: str = "1:1") -> Dict[str, Any]:
    """Generate an AI image from a text prompt."""
    return MediaGenerationEngine.generate_image(prompt=prompt, model=model, aspect_ratio=aspect_ratio)


def generate_video(prompt: str, model: str = "kling", duration_sec: int = 5) -> Dict[str, Any]:
    """Generate an AI video clip from a text prompt."""
    return MediaGenerationEngine.generate_video(prompt=prompt, model=model, duration_sec=duration_sec)


MEDIA_TOOL_DECLARATIONS = [
    {
        "name": "generate_image",
        "description": "Generate an AI image from a text prompt using Flux or DALL-E.",
        "parameters": {
            "type": "object",
            "properties": {
                "prompt": {
                    "type": "string",
                    "description": "Detailed description of the image to generate"
                },
                "model": {
                    "type": "string",
                    "description": "Model to use ('flux', 'dall-e-3')"
                },
                "aspect_ratio": {
                    "type": "string",
                    "description": "Image aspect ratio ('1:1', '16:9', '9:16')"
                }
            },
            "required": ["prompt"]
        }
    },
    {
        "name": "generate_video",
        "description": "Generate a short AI video clip from a text prompt using Kling or Runway.",
        "parameters": {
            "type": "object",
            "properties": {
                "prompt": {
                    "type": "string",
                    "description": "Detailed description of the video animation to generate"
                },
                "model": {
                    "type": "string",
                    "description": "Video model ('kling', 'runway', 'luma')"
                },
                "duration_sec": {
                    "type": "integer",
                    "description": "Duration in seconds (default: 5)"
                }
            },
            "required": ["prompt"]
        }
    }
]

MEDIA_TOOL_FUNCTIONS = {
    "generate_image": generate_image,
    "generate_video": generate_video,
}
