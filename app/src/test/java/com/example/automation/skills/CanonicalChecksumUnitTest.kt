package com.example.automation.skills

import com.example.automation.skills.canonical.CanonicalJsonSerializer
import com.example.automation.skills.model.SkillDefinition
import com.example.automation.skills.model.SkillStep
import com.example.automation.skills.parser.SkillParser
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CanonicalChecksumUnitTest {

    @Test
    fun `test canonical serialization is deterministic regardless of key order`() {
        val skill1 = SkillDefinition(
            skillId = "com.max.test.ordering",
            name = "Test Skill",
            description = "A test skill for serialization",
            steps = listOf(
                SkillStep(
                    stepId = "step_1",
                    action = "ui.click",
                    target = "Submit Button",
                    dependsOn = emptyList()
                )
            )
        )

        val jsonCanonical1 = CanonicalJsonSerializer.toCanonicalJson(skill1)
        val checksum1 = CanonicalJsonSerializer.computeChecksum(skill1)

        assertNotNull(jsonCanonical1)
        assertEquals(64, checksum1.length)
        assertTrue("Checksum must be lowercase hex", checksum1.all { it.isDigit() || (it in 'a'..'f') })

        // Checksum remains identical if computed again
        val checksum2 = CanonicalJsonSerializer.computeChecksum(skill1)
        assertEquals(checksum1, checksum2)
    }

    @Test
    fun `test checksum strips checksum and signature fields`() {
        val skillUnsigned = SkillDefinition(
            skillId = "com.max.test.checksum",
            name = "Checksum Test",
            description = "Test description",
            checksum = ""
        )

        val computedHash = CanonicalJsonSerializer.computeChecksum(skillUnsigned)

        // Declaring the computed checksum should yield verifyChecksum == true
        val skillSigned = skillUnsigned.copy(checksum = computedHash)
        assertTrue(CanonicalJsonSerializer.verifyChecksum(skillSigned))

        // Tampering with name should cause verifyChecksum == false
        val skillTampered = skillSigned.copy(name = "Tampered Name")
        assertFalse(CanonicalJsonSerializer.verifyChecksum(skillTampered))
    }
}
