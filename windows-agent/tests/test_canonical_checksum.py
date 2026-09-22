"""
Unit tests for CanonicalJsonSerializer & SHA-256 Checksums.
"""

import pytest
from core.skills.skill_definition import SkillDefinition, SkillStep
from core.skills.canonical_serializer import CanonicalJsonSerializer


def test_canonical_checksum_deterministic():
    skill = SkillDefinition(
        skill_id="com.max.test.ordering",
        name="Test Skill",
        description="A test skill for serialization",
        steps=[
            SkillStep(
                step_id="step_1",
                action="ui.click",
                target="Submit Button",
                depends_on=[]
            )
        ]
    )

    c_json = CanonicalJsonSerializer.to_canonical_json(skill)
    checksum1 = CanonicalJsonSerializer.compute_checksum(skill)
    checksum2 = CanonicalJsonSerializer.compute_checksum(skill)

    assert len(checksum1) == 64
    assert checksum1 == checksum2


def test_checksum_strips_checksum_and_signature():
    skill = SkillDefinition(
        skill_id="com.max.test.checksum",
        name="Checksum Test",
        description="Test description",
        checksum=""
    )

    computed = CanonicalJsonSerializer.compute_checksum(skill)
    skill.checksum = computed
    assert CanonicalJsonSerializer.verify_checksum(skill) is True

    # Tampering with name
    skill.name = "Tampered Name"
    assert CanonicalJsonSerializer.verify_checksum(skill) is False
