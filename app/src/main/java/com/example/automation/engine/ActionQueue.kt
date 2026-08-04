package com.example.automation.engine

import com.example.automation.state.ActionStep
import java.util.concurrent.ConcurrentLinkedQueue

class ActionQueue {
    private val queue = ConcurrentLinkedQueue<ActionStep>()

    fun enqueue(step: ActionStep) {
        queue.add(step)
    }

    fun enqueueAll(steps: List<ActionStep>) {
        queue.addAll(steps)
    }

    fun dequeue(): ActionStep? {
        return queue.poll()
    }

    fun peek(): ActionStep? {
        return queue.peek()
    }

    fun isEmpty(): Boolean {
        return queue.isEmpty()
    }

    fun size(): Int {
        return queue.size
    }

    fun clear() {
        queue.clear()
    }

    fun toList(): List<ActionStep> {
        return queue.toList()
    }

    fun replaceQueue(newSteps: List<ActionStep>) {
        queue.clear()
        queue.addAll(newSteps)
    }
}
