package com.fsck.k9.ui.managefolders

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import org.junit.Test

class FolderNameValidatorTest {
    private val testSubject = FolderNameValidator

    @Test
    fun `valid name should return null`() {
        val result = testSubject.validate("Projects 2026", folderPathDelimiter = "/", isTopLevel = true)

        assertThat(result).isNull()
    }

    @Test
    fun `non-ASCII name should be valid`() {
        val result = testSubject.validate("Entwürfe ✉", folderPathDelimiter = "/", isTopLevel = true)

        assertThat(result).isNull()
    }

    @Test
    fun `blank name should return EMPTY`() {
        val result = testSubject.validate("   ", folderPathDelimiter = "/", isTopLevel = true)

        assertThat(result).isEqualTo(FolderNameError.EMPTY)
    }

    @Test
    fun `name containing delimiter should return CONTAINS_DELIMITER`() {
        val result = testSubject.validate("a.b", folderPathDelimiter = ".", isTopLevel = false)

        assertThat(result).isEqualTo(FolderNameError.CONTAINS_DELIMITER)
    }

    @Test
    fun `name containing other hierarchy delimiter should be valid`() {
        val result = testSubject.validate("a/b", folderPathDelimiter = ".", isTopLevel = false)

        assertThat(result).isNull()
    }

    @Test
    fun `name containing LIST wildcards should return INVALID_CHARACTER`() {
        assertThat(testSubject.validate("50%", folderPathDelimiter = "/", isTopLevel = true))
            .isEqualTo(FolderNameError.INVALID_CHARACTER)
        assertThat(testSubject.validate("a*", folderPathDelimiter = "/", isTopLevel = true))
            .isEqualTo(FolderNameError.INVALID_CHARACTER)
    }

    @Test
    fun `name containing control characters should return INVALID_CHARACTER`() {
        val result = testSubject.validate("a\r\nb", folderPathDelimiter = "/", isTopLevel = true)

        assertThat(result).isEqualTo(FolderNameError.INVALID_CHARACTER)
    }

    @Test
    fun `INBOX at top level should return RESERVED_NAME regardless of case`() {
        val result = testSubject.validate("inBox", folderPathDelimiter = "/", isTopLevel = true)

        assertThat(result).isEqualTo(FolderNameError.RESERVED_NAME)
    }

    @Test
    fun `INBOX as subfolder name should be valid`() {
        val result = testSubject.validate("Inbox", folderPathDelimiter = "/", isTopLevel = false)

        assertThat(result).isNull()
    }
}

class FolderServerIdHelperTest {
    private val testSubject = FolderServerIdHelper

    @Test
    fun `buildServerId() without parent should return name`() {
        assertThat(testSubject.buildServerId(parentServerId = null, name = "New", folderPathDelimiter = "/"))
            .isEqualTo("New")
    }

    @Test
    fun `buildServerId() with parent should join with delimiter`() {
        assertThat(testSubject.buildServerId(parentServerId = "INBOX", name = "New", folderPathDelimiter = "."))
            .isEqualTo("INBOX.New")
    }

    @Test
    fun `getParentServerId() and getName() for nested folder`() {
        assertThat(testSubject.getParentServerId("a/b/c", "/")).isEqualTo("a/b")
        assertThat(testSubject.getName("a/b/c", "/")).isEqualTo("c")
    }

    @Test
    fun `getParentServerId() and getName() for top-level folder`() {
        assertThat(testSubject.getParentServerId("abc", "/")).isNull()
        assertThat(testSubject.getName("abc", "/")).isEqualTo("abc")
    }

    @Test
    fun `isSubfolder() should only match descendants`() {
        assertThat(testSubject.isSubfolder("Work/Projects", "Work", "/")).isTrue()
        assertThat(testSubject.isSubfolder("Work/Projects/2026", "Work", "/")).isTrue()
        assertThat(testSubject.isSubfolder("Workshop", "Work", "/")).isFalse()
        assertThat(testSubject.isSubfolder("Work", "Work", "/")).isFalse()
    }
}
