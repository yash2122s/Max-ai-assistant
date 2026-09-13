"""
Unit tests for TaskGroup structured concurrency lifecycle.
Tests stdlib-compatible semantics.
"""

import asyncio
import unittest
from core.task_group import TaskGroup


class TestTaskGroup(unittest.IsolatedAsyncioTestCase):

    async def test_successful_tasks_complete(self):
        results = []

        async def worker(val, delay):
            await asyncio.sleep(delay)
            results.append(val)

        async with TaskGroup() as tg:
            tg.create_task(worker(1, 0.01), name="w1")
            tg.create_task(worker(2, 0.02), name="w2")

        self.assertEqual(results, [1, 2])

    async def test_child_exception_cancels_siblings(self):
        sibling_cancelled = False

        async def failing_worker():
            await asyncio.sleep(0.01)
            raise ValueError("Task failed on purpose")

        async def long_worker():
            nonlocal sibling_cancelled
            try:
                await asyncio.sleep(1.0)
            except asyncio.CancelledError:
                sibling_cancelled = True
                raise

        with self.assertRaises(ValueError):
            async with TaskGroup() as tg:
                tg.create_task(failing_worker(), name="failing")
                tg.create_task(long_worker(), name="long")

        self.assertTrue(sibling_cancelled)

    async def test_caller_exception_cancels_children(self):
        child_cancelled = False

        async def child_worker():
            nonlocal child_cancelled
            try:
                await asyncio.sleep(1.0)
            except asyncio.CancelledError:
                child_cancelled = True
                raise

        with self.assertRaises(RuntimeError):
            async with TaskGroup() as tg:
                tg.create_task(child_worker(), name="child")
                await asyncio.sleep(0.01)
                raise RuntimeError("Caller crash")

        self.assertTrue(child_cancelled)

    async def test_manual_task_cancellation(self):
        cancelled_count = 0

        async def worker():
            nonlocal cancelled_count
            try:
                await asyncio.sleep(1.0)
            except asyncio.CancelledError:
                cancelled_count += 1
                raise

        tasks = []
        async with TaskGroup() as tg:
            t1 = tg.create_task(worker(), name="w1")
            t2 = tg.create_task(worker(), name="w2")
            tasks.extend([t1, t2])
            await asyncio.sleep(0.01)
            for t in tasks:
                t.cancel()

        self.assertEqual(cancelled_count, 2)


if __name__ == "__main__":
    unittest.main()
