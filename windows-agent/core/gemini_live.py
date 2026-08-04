"""
JARVIS Gemini Live API Session
WebSocket connection to Gemini for real-time bidirectional voice + function calling.
Includes exponential backoff reconnection.
"""

import asyncio
import base64
import json
import logging
import random
import traceback

from google import genai
from google.genai import types

from config import (
    GEMINI_API_KEY,
    GEMINI_MODEL,
    SYSTEM_PROMPT,
    JARVIS_VOICE,
    INPUT_SAMPLE_RATE,
    OUTPUT_SAMPLE_RATE,
    RECONNECT_BASE_DELAY,
    RECONNECT_MAX_DELAY,
    RECONNECT_MAX_ATTEMPTS,
)
from core.audio_handler import AudioCapture, AudioPlayer
from core.tool_executor import ToolRegistry

logger = logging.getLogger("jarvis.gemini")

def safe_print(*args, **kwargs):
    """Print helper that safely handles Windows console encoding errors."""
    try:
        print(*args, **kwargs)
    except UnicodeEncodeError:
        safe_args = []
        for a in args:
            if isinstance(a, str):
                safe_args.append(a.encode("ascii", errors="replace").decode("ascii"))
            else:
                safe_args.append(a)
        print(*safe_args, **kwargs)


class GeminiLiveSession:
    """
    Manages the real-time WebSocket connection to Gemini Live API.
    
    Handles:
    - Streaming mic audio to Gemini
    - Receiving and playing audio responses
    - Tool call dispatch and response
    - Barge-in detection
    - Automatic reconnection with exponential backoff
    """
    
    def __init__(self, tool_registry: ToolRegistry):
        self.tools = tool_registry
        self.audio_capture = AudioCapture()
        self.audio_player = AudioPlayer()
        
        self._client: genai.Client | None = None
        self._session = None
        self._running = False
        self._consecutive_failures = 0
        self._is_printing_jarvis = False
        self._is_first_connect = True
    
    async def start(self):
        """Initialize and start the live session."""
        if not GEMINI_API_KEY or GEMINI_API_KEY == "your_api_key_here":
            logger.error("❌ No Gemini API key found! Set GEMINI_API_KEY in .env file.")
            print("\n" + "=" * 60)
            print("❌ ERROR: Gemini API key not configured!")
            print("   1. Go to https://aistudio.google.com/")
            print("   2. Generate an API key")
            print("   3. Add it to .env file: GEMINI_API_KEY=your_key")
            print("=" * 60 + "\n")
            return
        
        self._client = genai.Client(api_key=GEMINI_API_KEY)
        self._running = True
        
        # Start audio I/O
        loop = asyncio.get_event_loop()
        self.audio_capture.start(loop)
        self.audio_player.start()
        
        # Main session loop with reconnection
        while self._running:
            try:
                await self._run_session()
            except Exception as e:
                if not self._running:
                    break
                logger.info(f"Session reconnecting: {e}")
                await self._handle_reconnect()
        
        # Cleanup
        self.audio_capture.stop()
        self.audio_player.stop()
    
    async def _run_session(self):
        """Run a single Gemini Live session."""
        # Build tool declarations for Gemini
        function_declarations = []
        for decl in self.tools.declarations:
            fd = types.FunctionDeclaration(
                name=decl["name"],
                description=decl.get("description", ""),
                parameters=decl.get("parameters"),
            )
            function_declarations.append(fd)
        
        # Configure the live session
        config = types.LiveConnectConfig(
            response_modalities=[types.Modality.AUDIO],
            system_instruction=types.Content(
                parts=[types.Part(text=SYSTEM_PROMPT)]
            ),
            speech_config=types.SpeechConfig(
                voice_config=types.VoiceConfig(
                    prebuilt_voice_config=types.PrebuiltVoiceConfig(
                        voice_name=JARVIS_VOICE,
                    )
                )
            ),
            tools=[types.Tool(function_declarations=function_declarations)],
            input_audio_transcription=types.AudioTranscriptionConfig(),
            output_audio_transcription=types.AudioTranscriptionConfig(),
        )
        
        logger.debug(f"🔌 Connecting to Gemini Live API (model: {GEMINI_MODEL})...")
        
        async with self._client.aio.live.connect(
            model=GEMINI_MODEL,
            config=config,
        ) as session:
            self._session = session
            self._consecutive_failures = 0
            
            logger.debug("✅ Connected to Gemini Live API!")
            if self._is_first_connect:
                safe_print("\n" + "=" * 60)
                safe_print("🤖 JARVIS is online and ready!")
                safe_print("   🎤 Speak into mic OR 💬 Type text below & press Enter")
                safe_print("   Type 'exit' or press Ctrl+C to shutdown.")
                safe_print("=" * 60 + "\n")
                self._is_first_connect = False
            
            # Run audio send, text send, and receive loops concurrently
            send_audio_task = asyncio.create_task(self._send_audio_loop(session))
            send_text_task = asyncio.create_task(self._send_text_loop(session))
            receive_task = asyncio.create_task(self._receive_loop(session))
            
            try:
                # Wait for core voice session tasks (audio send or receive) to complete/fail
                done, pending = await asyncio.wait(
                    [send_audio_task, receive_task],
                    return_when=asyncio.FIRST_COMPLETED,
                )
                
                # Check if core tasks failed with exception
                for task in done:
                    exc = task.exception()
                    if exc:
                        raise exc
                
                # If receive loop finished normally while running, connection closed
                if receive_task in done and self._running:
                    raise ConnectionResetError("Gemini Live API connection closed by server.")
            except asyncio.CancelledError:
                pass
            finally:
                send_audio_task.cancel()
                send_text_task.cancel()
                receive_task.cancel()
    
    async def _send_text_loop(self, session):
        """Continuously read user text input from console and send to Gemini."""
        logger.info("💬 Text input loop started")
        
        while self._running:
            try:
                # Read line from console asynchronously in worker thread
                user_text = await asyncio.to_thread(input)
                user_text = user_text.strip()
                
                if not user_text:
                    continue
                
                # Check for exit command
                if user_text.lower() in ("exit", "quit", "shutdown"):
                    print("\n🛑 Shutdown requested via text command.")
                    self.stop()
                    break
                
                print(f"\n💬 You (Text): {user_text}")
                
                # If awaiting confirmation for dangerous tool, process confirmation
                if self.tools.has_pending_confirmation:
                    await self._handle_confirmation(session, user_text.lower())
                    continue
                
                # Send text input turn to Gemini Live session
                if hasattr(session, "send_client_content"):
                    await session.send_client_content(
                        turns=[
                            types.Content(
                                role="user",
                                parts=[types.Part(text=user_text)],
                            )
                        ],
                        turn_complete=True,
                    )
                else:
                    await session.send(
                        input=types.LiveClientContent(
                            turns=[
                                types.Content(
                                    role="user",
                                    parts=[types.Part(text=user_text)],
                                )
                            ],
                            turn_complete=True,
                        )
                    )
            except EOFError:
                logger.info("💬 Console text input unavailable (non-interactive stdin). Voice mode active.")
                while self._running:
                    await asyncio.sleep(1.0)
                break
            except KeyboardInterrupt:
                self.stop()
                break
            except Exception as e:
                if self._running:
                    logger.error(f"Text send error: {e}")
                await asyncio.sleep(0.5)
    
    async def _send_audio_loop(self, session):
        """Continuously stream microphone audio to Gemini."""
        logger.info("🎤 Audio send loop started")
        
        while self._running:
            try:
                # Get audio chunk from microphone queue
                audio_data = await asyncio.wait_for(
                    self.audio_capture.queue.get(),
                    timeout=0.1,
                )
                
                # Send raw PCM audio to Gemini
                chunk = types.Blob(
                    data=audio_data,
                    mime_type=f"audio/pcm;rate={INPUT_SAMPLE_RATE}",
                )
                if hasattr(session, "send_realtime_input"):
                    await session.send_realtime_input(audio=chunk)
                else:
                    await session.send(
                        input=types.LiveClientRealtimeInput(audio=chunk)
                    )
            except asyncio.TimeoutError:
                # Send silent PCM keepalive chunk to maintain active WebSocket stream during silence
                chunk = types.Blob(
                    data=b"\x00" * 1024,
                    mime_type=f"audio/pcm;rate={INPUT_SAMPLE_RATE}",
                )
                try:
                    if hasattr(session, "send_realtime_input"):
                        await session.send_realtime_input(audio=chunk)
                    else:
                        await session.send(
                            input=types.LiveClientRealtimeInput(audio=chunk)
                        )
                except Exception:
                    pass
                continue
            except Exception as e:
                err_str = str(e)
                if "1000" in err_str or "closed" in err_str.lower():
                    # WebSocket closed normally — exit send loop cleanly
                    break
                if self._running:
                    logger.debug(f"Audio chunk send warning: {e}")
                await asyncio.sleep(0.05)
    
    async def _receive_loop(self, session):
        """Handle incoming messages from Gemini (audio, text, tool calls)."""
        logger.info("📥 Receive loop started")
        
        try:
            async for response in session.receive():
                if not self._running:
                    break
                
                server_content = response.server_content
                tool_call = response.tool_call
                
                # ── Handle audio + text responses ───────────────────────
                if server_content:
                    if server_content.interrupted:
                        # Barge-in detected — user started speaking
                        self.audio_player.interrupt()
                        if self._is_printing_jarvis:
                            safe_print()
                            self._is_printing_jarvis = False
                        logger.debug("🔇 Barge-in: playback interrupted")
                        continue
                    
                    if server_content.model_turn:
                        for part in server_content.model_turn.parts:
                            # Audio response
                            if part.inline_data:
                                self.audio_player.resume()
                                self.audio_player.play_chunk(part.inline_data.data)
                    
                    # Check for input transcription (what the user said)
                    if server_content.input_transcription:
                        text = server_content.input_transcription.text
                        if text and text.strip():
                            if self._is_printing_jarvis:
                                safe_print()
                                self._is_printing_jarvis = False
                            safe_print(f"\n🗣️  You: {text}")
                            
                            # Check for confirmation of pending dangerous tool
                            if self.tools.has_pending_confirmation:
                                await self._handle_confirmation(session, text.strip().lower())
                    
                    # Output transcription (what JARVIS said — streamed on single line)
                    if server_content.output_transcription:
                        text = server_content.output_transcription.text
                        if text:
                            if not self._is_printing_jarvis:
                                safe_print("\n🤖 JARVIS: ", end="", flush=True)
                                self._is_printing_jarvis = True
                            safe_print(text, end="", flush=True)
                    
                    if server_content.turn_complete:
                        if self._is_printing_jarvis:
                            safe_print()
                            self._is_printing_jarvis = False
                
                # ── Handle tool calls ───────────────────────────────────
                if tool_call:
                    if self._is_printing_jarvis:
                        safe_print()
                        self._is_printing_jarvis = False
                    await self._handle_tool_calls(session, tool_call)
        
        except Exception as e:
            if self._running:
                logger.error(f"Receive error: {e}")
                traceback.print_exc()
            raise
    
    async def _handle_tool_calls(self, session, tool_call):
        """Process tool calls from Gemini and send results back."""
        function_responses = []
        
        for fc in tool_call.function_calls:
            logger.info(f"🔧 Tool call: {fc.name}({json.dumps(fc.args, default=str) if fc.args else '{}'})")
            safe_print(f"\n⚙️  Executing: {fc.name}({json.dumps(fc.args, default=str) if fc.args else ''})")
            
            # Execute via tool registry (handles confirmation gate)
            args = dict(fc.args) if fc.args else {}
            result = self.tools.execute(fc.name, args)
            
            logger.info(f"📤 Tool result: {json.dumps(result, default=str)[:200]}")
            
            # If awaiting confirmation, the model needs to ask the user
            if result.get("status") == "awaiting_confirmation":
                safe_print(f"⚠️  {result['message']}")
            
            function_responses.append(
                types.FunctionResponse(
                    name=fc.name,
                    id=fc.id,
                    response=result,
                )
            )
        
        # Send results back to Gemini
        if hasattr(session, "send_tool_response"):
            await session.send_tool_response(function_responses=function_responses)
        else:
            await session.send(
                input=types.LiveClientToolResponse(
                    function_responses=function_responses,
                )
            )
    
    async def _handle_confirmation(self, session, user_text: str):
        """Handle user confirmation/denial for pending dangerous tools."""
        confirm_words = {"yes", "yeah", "yep", "confirm", "do it", "go ahead", "proceed", "sure", "ok", "okay"}
        cancel_words = {"no", "nope", "cancel", "abort", "stop", "don't", "nevermind", "never mind"}
        
        if any(word in user_text for word in confirm_words):
            result = self.tools.confirm_pending()
            safe_print(f"✅ Confirmed: {json.dumps(result, default=str)[:200]}")
            # Send the result to Gemini so it can respond
            msg = f"Tool execution result: {json.dumps(result, default=str)}"
            if hasattr(session, "send_client_content"):
                await session.send_client_content(
                    turns=[types.Content(parts=[types.Part(text=msg)], role="user")],
                    turn_complete=True,
                )
            else:
                await session.send(input=types.LiveClientContent(
                    turns=[types.Content(parts=[types.Part(text=msg)], role="user")]
                ))
        elif any(word in user_text for word in cancel_words):
            result = self.tools.cancel_pending()
            safe_print(f"❌ Cancelled: {result['message']}")
            msg = f"User cancelled the action: {result['message']}"
            if hasattr(session, "send_client_content"):
                await session.send_client_content(
                    turns=[types.Content(parts=[types.Part(text=msg)], role="user")],
                    turn_complete=True,
                )
            else:
                await session.send(input=types.LiveClientContent(
                    turns=[types.Content(parts=[types.Part(text=msg)], role="user")]
                ))
    
    async def _handle_reconnect(self):
        """Reconnect with exponential backoff + jitter."""
        self._consecutive_failures += 1
        
        if self._consecutive_failures > RECONNECT_MAX_ATTEMPTS:
            logger.error(f"❌ Max reconnection attempts ({RECONNECT_MAX_ATTEMPTS}) reached. Giving up.")
            self._running = False
            return
        
        # Fast instant refresh for gemini-3.1-flash-live-preview turn reset
        if self._consecutive_failures <= 2:
            total_delay = 0.05
        else:
            delay = min(
                RECONNECT_BASE_DELAY * (2 ** (self._consecutive_failures - 1)),
                RECONNECT_MAX_DELAY,
            )
            total_delay = delay + random.uniform(0, delay * 0.3)
        
        logger.debug(
            f"🔄 Reconnecting in {total_delay:.2f}s "
            f"(attempt {self._consecutive_failures}/{RECONNECT_MAX_ATTEMPTS})"
        )
        await asyncio.sleep(total_delay)
    
    def stop(self):
        """Signal the session to stop."""
        self._running = False
        self.audio_capture.stop()
        self.audio_player.stop()
        logger.info("🛑 JARVIS session stopping...")
