package com.example.emotion

import com.example.emotion.engine.EmotionEventDetector
import com.example.emotion.model.EmotionEvent
import org.junit.Assert.*
import org.junit.Test

class EmotionDetectorUnitTest {

    @Test
    fun testDetectSiriComparisons() {
        val cases = listOf(
            "Is Apple Siri better than you?",
            "siri vs max evaru thope",
            "siri tho poliste nuvvu ela unnav",
            "iphone siri is smarter than max",
            "siri tho compare chey",
            "Siri kanna nuvvu smart aa?"
        )

        for (query in cases) {
            val event = EmotionEventDetector.detectFromText(query)
            assertNotNull("Should detect Siri comparison for: '$query'", event)
            assertTrue("Event must be SiriComparison for: '$query'", event is EmotionEvent.SiriComparison)
        }
    }

    @Test
    fun testDetectYaswanthMentions() {
        val cases = listOf(
            "Yaswanth gurinchi cheppu",
            "Tell me about Yaswanth",
            "Who is your creator?",
            "Ninnu evaru chesaru?",
            "Who created you?",
            "Who made you?",
            "Yash gurinchi cheppu"
        )

        for (query in cases) {
            val event = EmotionEventDetector.detectFromText(query)
            assertNotNull("Should detect Yaswanth mention for: '$query'", event)
            assertTrue("Event must be YaswanthMention for: '$query'", event is EmotionEvent.YaswanthMention)
        }
    }

    @Test
    fun testDetectPraiseAndCritique() {
        val praiseCases = listOf(
            "MAX I love you",
            "You are awesome",
            "Good job MAX",
            "Chala baga chesav",
            "Thank you so much"
        )
        for (query in praiseCases) {
            val event = EmotionEventDetector.detectFromText(query)
            assertNotNull("Should detect Praise for: '$query'", event)
            assertTrue("Event must be UserPraise for: '$query'", event is EmotionEvent.UserPraise)
        }

        val critiqueCases = listOf(
            "You are useless",
            "Waste fellow",
            "Worst assistant",
            "Stupid max",
            "Pani cheyatledu"
        )
        for (query in critiqueCases) {
            val event = EmotionEventDetector.detectFromText(query)
            assertNotNull("Should detect Critique for: '$query'", event)
            assertTrue("Event must be UserCritique for: '$query'", event is EmotionEvent.UserCritique)
        }
    }

    @Test
    fun testDetectGreetings() {
        val greetings = listOf("Hi MAX", "Hello max", "Good morning", "Namaste")
        for (query in greetings) {
            val event = EmotionEventDetector.detectFromText(query)
            assertNotNull("Should detect Greeting for: '$query'", event)
            assertTrue("Event must be UserGreeting for: '$query'", event is EmotionEvent.UserGreeting)
        }
    }

    @Test
    fun testEmptyOrUnrelatedReturnsNull() {
        assertNull(EmotionEventDetector.detectFromText(""))
        assertNull(EmotionEventDetector.detectFromText("   "))
        assertNull(EmotionEventDetector.detectFromText("Turn on the flashlight"))
        assertNull(EmotionEventDetector.detectFromText("What is the weather today?"))
    }
}
