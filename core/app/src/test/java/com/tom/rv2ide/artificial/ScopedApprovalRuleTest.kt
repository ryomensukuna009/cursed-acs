package com.tom.rv2ide.artificial

import com.tom.rv2ide.artificial.policy.ActionRiskLevel
import com.tom.rv2ide.artificial.policy.ActionType
import com.tom.rv2ide.artificial.policy.ScopedApprovalRule
import com.tom.rv2ide.artificial.policy.UserApprovalResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ScopedApprovalRuleTest {

    @Test
    fun testSerializationAndDeserialization() {
        val rule = ScopedApprovalRule(
            projectPath = "/sdcard/AppProjects/MySample",
            actionType = ActionType.MODIFY_FILE.name,
            resourcePattern = "MainActivity.kt"
        )
        val serialized = rule.serialize()
        assertEquals("/sdcard/AppProjects/MySample|MODIFY_FILE|MainActivity.kt", serialized)

        val deserialized = ScopedApprovalRule.deserialize(serialized)
        assertNotNull(deserialized)
        assertEquals(rule.projectPath, deserialized!!.projectPath)
        assertEquals(rule.actionType, deserialized.actionType)
        assertEquals(rule.resourcePattern, deserialized.resourcePattern)
    }

    @Test
    fun testInvalidDeserializationReturnsNull() {
        assertNull(ScopedApprovalRule.deserialize(""))
        assertNull(ScopedApprovalRule.deserialize("invalid_no_pipes"))
        assertNull(ScopedApprovalRule.deserialize("only|one_pipe"))
    }

    @Test
    fun testExactMatchRule() {
        val rule = ScopedApprovalRule(
            projectPath = "/sdcard/AppProjects/MySample",
            actionType = ActionType.MODIFY_FILE.name,
            resourcePattern = "MainActivity.kt"
        )

        assertTrue(
            rule.matches(
                targetProjectPath = "/sdcard/AppProjects/MySample",
                targetActionType = ActionType.MODIFY_FILE,
                targetResource = "/sdcard/AppProjects/MySample/app/src/main/java/MainActivity.kt"
            )
        )

        assertFalse(
            rule.matches(
                targetProjectPath = "/sdcard/AppProjects/OtherSample",
                targetActionType = ActionType.MODIFY_FILE,
                targetResource = "MainActivity.kt"
            )
        )

        assertFalse(
            rule.matches(
                targetProjectPath = "/sdcard/AppProjects/MySample",
                targetActionType = ActionType.DELETE_FILE,
                targetResource = "MainActivity.kt"
            )
        )

        assertFalse(
            rule.matches(
                targetProjectPath = "/sdcard/AppProjects/MySample",
                targetActionType = ActionType.MODIFY_FILE,
                targetResource = "BuildScript.gradle"
            )
        )
    }

    @Test
    fun testWildcardMatchingRules() {
        val wildcardProject = ScopedApprovalRule(
            projectPath = "*",
            actionType = ActionType.EXECUTE_BUILD.name,
            resourcePattern = "*"
        )

        assertTrue(
            wildcardProject.matches(
                targetProjectPath = "/any/path",
                targetActionType = ActionType.EXECUTE_BUILD,
                targetResource = "assembleDebug"
            )
        )

        assertFalse(
            wildcardProject.matches(
                targetProjectPath = "/any/path",
                targetActionType = ActionType.MODIFY_FILE,
                targetResource = "Any.kt"
            )
        )
    }

    @Test
    fun testRegexResourceMatching() {
        val regexRule = ScopedApprovalRule(
            projectPath = "/project",
            actionType = ActionType.MODIFY_FILE.name,
            resourcePattern = ".*\\.kt$"
        )

        assertTrue(
            regexRule.matches(
                targetProjectPath = "/project",
                targetActionType = ActionType.MODIFY_FILE,
                targetResource = "src/main/Foo.kt"
            )
        )

        assertFalse(
            regexRule.matches(
                targetProjectPath = "/project",
                targetActionType = ActionType.MODIFY_FILE,
                targetResource = "src/main/AndroidManifest.xml"
            )
        )
    }

    @Test
    fun testUserApprovalResultValues() {
        assertEquals(4, UserApprovalResult.values().size)
        assertTrue(UserApprovalResult.values().contains(UserApprovalResult.ALLOW_ONCE))
        assertTrue(UserApprovalResult.values().contains(UserApprovalResult.ALLOW_ALWAYS))
        assertTrue(UserApprovalResult.values().contains(UserApprovalResult.REFUSE))
        assertTrue(UserApprovalResult.values().contains(UserApprovalResult.DENIED_BY_POLICY))
    }

    @Test
    fun testActionTypeRiskClassifications() {
        val types = ActionType.values()
        assertTrue(types.contains(ActionType.MODIFY_FILE))
        assertTrue(types.contains(ActionType.CREATE_FILE))
        assertTrue(types.contains(ActionType.DELETE_FILE))
        assertTrue(types.contains(ActionType.EXECUTE_COMMAND))
        assertTrue(types.contains(ActionType.EXECUTE_BUILD))
        assertTrue(types.contains(ActionType.RESOLVE_IMPORT))
        assertTrue(types.contains(ActionType.GIT_OPERATION))
        assertTrue(types.contains(ActionType.ROLLBACK_BATCH))

        val riskLevels = ActionRiskLevel.values()
        assertTrue(riskLevels.contains(ActionRiskLevel.READ_ONLY))
        assertTrue(riskLevels.contains(ActionRiskLevel.SAFE_PROJECT_MUTATION))
        assertTrue(riskLevels.contains(ActionRiskLevel.BUILD_OR_TEST))
        assertTrue(riskLevels.contains(ActionRiskLevel.COMMAND_NON_DESTRUCTIVE))
        assertTrue(riskLevels.contains(ActionRiskLevel.COMMAND_DESTRUCTIVE))
        assertTrue(riskLevels.contains(ActionRiskLevel.DESTRUCTIVE_FILE_OP))
        assertTrue(riskLevels.contains(ActionRiskLevel.SENSITIVE_OR_OUT_OF_WORKSPACE))
    }
}
