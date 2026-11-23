package net.thunderbird.app.common.feature.mail.folder

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import kotlin.test.Test

class FolderServerIdHelperTest {
    private val testSubject = FolderServerIdHelper

    @Test
    fun `buildServerId() without parent should return name`() {
        // Act
        val result = testSubject.buildServerId(parentServerId = null, name = "New", folderPathDelimiter = "/")

        // Assert
        assertThat(result).isEqualTo("New")
    }

    @Test
    fun `buildServerId() with parent should join with delimiter`() {
        // Act
        val result = testSubject.buildServerId(parentServerId = "INBOX", name = "New", folderPathDelimiter = ".")

        // Assert
        assertThat(result).isEqualTo("INBOX.New")
    }

    @Test
    fun `getParentServerId() and getName() for nested folder`() {
        // Act
        val parentServerId = testSubject.getParentServerId("a/b/c", "/")
        val name = testSubject.getName("a/b/c", "/")

        // Assert
        assertThat(parentServerId).isEqualTo("a/b")
        assertThat(name).isEqualTo("c")
    }

    @Test
    fun `getParentServerId() and getName() for top-level folder`() {
        // Act
        val parentServerId = testSubject.getParentServerId("abc", "/")
        val name = testSubject.getName("abc", "/")

        // Assert
        assertThat(parentServerId).isNull()
        assertThat(name).isEqualTo("abc")
    }

    @Test
    fun `isSubfolder() should match descendants`() {
        // Act
        val child = testSubject.isSubfolder("Work/Projects", "Work", "/")
        val grandchild = testSubject.isSubfolder("Work/Projects/2026", "Work", "/")

        // Assert
        assertThat(child).isTrue()
        assertThat(grandchild).isTrue()
    }

    @Test
    fun `isSubfolder() should not match the folder itself or folders sharing a name prefix`() {
        // Act
        val sharingPrefix = testSubject.isSubfolder("Workshop", "Work", "/")
        val itself = testSubject.isSubfolder("Work", "Work", "/")

        // Assert
        assertThat(sharingPrefix).isFalse()
        assertThat(itself).isFalse()
    }
}
