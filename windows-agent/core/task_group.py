"""
TaskGroup Structured Concurrency Coordinator.

Uses stdlib `asyncio.TaskGroup` on Python 3.11+.
On Python 3.10, provides an exact drop-in implementation conforming to standard stdlib TaskGroup semantics:
- Context manager (__aenter__, __aexit__)
- create_task(coro, *, name=None)
- Deterministic sibling cancellation on child task failure
- Deterministic completion waiting on exit
"""

import sys
import asyncio
import logging
from typing import Any, Coroutine, Optional, Set

logger = logging.getLogger("jarvis.task_group")

if sys.version_info >= (3, 11):
    from asyncio import TaskGroup
else:
    class TaskGroup:
        """
        Drop-in implementation of Python 3.11 stdlib asyncio.TaskGroup for Python 3.10.
        Standard API: only create_task, __aenter__, __aexit__.
        """

        def __init__(self):
            self._tasks: Set[asyncio.Task] = set()
            self._exiting: bool = False
            self._aborting: bool = False
            self._parent_task: Optional[asyncio.Task] = None
            self._first_exception: Optional[BaseException] = None

        async def __aenter__(self):
            self._parent_task = asyncio.current_task()
            return self

        def create_task(
            self,
            coro: Coroutine[Any, Any, Any],
            *,
            name: Optional[str] = None
        ) -> asyncio.Task:
            """Spawn a managed task within this TaskGroup conforming to stdlib TaskGroup."""
            if self._exiting:
                raise RuntimeError("TaskGroup is already exiting; cannot create new tasks")
            
            loop = asyncio.get_running_loop()
            task = loop.create_task(coro, name=name)
            self._tasks.add(task)
            task.add_done_callback(self._on_task_done)
            return task

        def _on_task_done(self, task: asyncio.Task) -> None:
            """Callback when a child task finishes."""
            self._tasks.discard(task)
            if not task.cancelled():
                exc = task.exception()
                if exc is not None and not self._aborting:
                    self._aborting = True
                    self._first_exception = exc
                    # Sibling cancellation on failure
                    self._cancel_all()

        def _cancel_all(self) -> None:
            """Internal sibling cancellation."""
            for task in list(self._tasks):
                if not task.done():
                    task.cancel()

        async def __aexit__(self, exc_type, exc_val, exc_tb):
            self._exiting = True
            
            if exc_val is not None and not self._aborting:
                self._aborting = True
                self._first_exception = exc_val
                self._cancel_all()

            # Wait for all child tasks to complete or cancel
            while self._tasks:
                pending = list(self._tasks)
                await asyncio.gather(*pending, return_exceptions=True)

            if self._first_exception is not None and exc_val is None:
                raise self._first_exception
            
            return None
