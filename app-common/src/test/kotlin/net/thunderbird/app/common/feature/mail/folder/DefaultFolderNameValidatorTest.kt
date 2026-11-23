package net.thunderbird.app.common.feature.mail.folder

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import kotlin.test.Test
import net.thunderbird.feature.mail.folder.api.FolderNameError

class DefaultFolderNameValidatorTest {
    private val testSubject = DefaultFolderNameValidator()

    @Test
    fun `valid name should return null`() {
        // Act
        val result = testSubject.validate("Projects 2026", folderPathDelimiter = "/", isTopLevel = true)

        // Assert
        assertThat(result).isNull()
    }

    @Test
    fun `non-ASCII name should be valid`() {
        // Act
        val result = testSubject.validate("Entwürfe ✉", folderPathDelimiter = "/", isTopLevel = true)

        // Assert
        assertThat(result).isNull()
    }

    @Test
    fun `blank name should return EMPTY`() {
        // Act
        val result = testSubject.validate("   ", folderPathDelimiter = "/", isTopLevel = true)

        // Assert
        assertThat(result).isEqualTo(FolderNameError.EMPTY)
    }

    @Test
    fun `name containing delimiter should return CONTAINS_DELIMITER`() {
        // Act
        val result = testSubject.validate("a.b", folderPathDelimiter = ".", isTopLevel = false)

        // Assert
        assertThat(result).isEqualTo(FolderNameError.CONTAINS_DELIMITER)
    }

    @Test
    fun `name containing other hierarchy delimiter should be valid`() {
        // Act
        val result = testSubject.validate("a/b", folderPathDelimiter = ".", isTopLevel = false)

        // Assert
        assertThat(result).isNull()
    }

    @Test
    fun `name containing percent wildcard should return INVALID_CHARACTER`() {
        // Act
        val result = testSubject.validate("50%", folderPathDelimiter = "/", isTopLevel = true)

        // Assert
        assertThat(result).isEqualTo(FolderNameError.INVALID_CHARACTER)
    }

    @Test
    fun `name containing asterisk wildcard should return INVALID_CHARACTER`() {
        // Act
        val result = testSubject.validate("a*", folderPathDelimiter = "/", isTopLevel = true)

        // Assert
        assertThat(result).isEqualTo(FolderNameError.INVALID_CHARACTER)
    }

    @Test
    fun `name containing control characters should return INVALID_CHARACTER`() {
        // Act
        val result = testSubject.validate("a\r\nb", folderPathDelimiter = "/", isTopLevel = true)

        // Assert
        assertThat(result).isEqualTo(FolderNameError.INVALID_CHARACTER)
    }

    @Test
    fun `INBOX at top level should return RESERVED_NAME regardless of case`() {
        // Act
        val result = testSubject.validate("inBox", folderPathDelimiter = "/", isTopLevel = true)

        // Assert
        assertThat(result).isEqualTo(FolderNameError.RESERVED_NAME)
    }

    @Test
    fun `INBOX as subfolder name should be valid`() {
        // Act
        val result = testSubject.validate("Inbox", folderPathDelimiter = "/", isTopLevel = false)

        // Assert
        assertThat(result).isNull()
    }
}
