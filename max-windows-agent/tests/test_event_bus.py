import os
import sys
import unittest
import asyncio

# Add max-windows-agent root path to sys.path
sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from server.event_bus import EventBus
from protocol.event_types import EventType

class TestEventBus(unittest.TestCase):
    def test_publish_subscribe(self):
        bus = EventBus()
        queue = asyncio.Queue()
        
        # Subscribe
        bus.subscribe(queue)
        evt_id = bus.publish(EventType.HEARTBEAT, {"val": 42})
        
        # Verify buffer stores the event correctly
        self.assertEqual(len(bus.buffer), 1)
        self.assertEqual(bus.buffer[0]["id"], evt_id)
        self.assertEqual(bus.buffer[0]["type"], EventType.HEARTBEAT.value)
        self.assertEqual(bus.buffer[0]["payload"]["val"], 42)
        
        # Verify queue receives the event
        self.assertFalse(queue.empty())
        event = queue.get_nowait()
        self.assertEqual(event["id"], evt_id)
        self.assertEqual(event["payload"]["val"], 42)
        self.assertEqual(event["schema_version"], 1)
        self.assertTrue(isinstance(event["timestamp"], float))
        
        # Unsubscribe and verify no more events are pushed
        bus.unsubscribe(queue)
        bus.publish(EventType.TOOL_REQUESTED, {})
        self.assertTrue(queue.empty())

    def test_immutability(self):
        bus = EventBus()
        payload = {"nested": {"value": 1}}
        
        # Publish
        bus.publish(EventType.TOOL_COMPLETED, payload)
        
        # Mutate the source dictionary
        payload["nested"]["value"] = 999
        
        # Verify the event bus copy remains unaffected
        self.assertEqual(bus.buffer[0]["payload"]["nested"]["value"], 1)

    def test_replay_buffer(self):
        bus = EventBus()
        ids = []
        types = [
            EventType.CLIENT_CONNECTED,
            EventType.PAIR_SUCCESS,
            EventType.TOOL_REQUESTED,
            EventType.TOOL_PROGRESS,
            EventType.TOOL_COMPLETED
        ]
        for i in range(5):
            evt_id = bus.publish(types[i], {"num": i})
            ids.append(evt_id)
            
        # Retrieve events after the 2nd event (index 1)
        replayed = bus.get_events_after(ids[1])
        self.assertEqual(len(replayed), 3)
        self.assertEqual(replayed[0]["type"], EventType.TOOL_REQUESTED.value)
        self.assertEqual(replayed[1]["type"], EventType.TOOL_PROGRESS.value)
        self.assertEqual(replayed[2]["type"], EventType.TOOL_COMPLETED.value)
        
        # Retrieve events after a non-existent or expired ID (should return all events)
        all_replayed = bus.get_events_after("evt_expired_id")
        self.assertEqual(len(all_replayed), 5)

if __name__ == "__main__":
    unittest.main()
