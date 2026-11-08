package tun.client

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText

/**
 * Writes [text] to [file], private to the user from creation, via a temp file and an atomic move: mode 0600 in a
 * directory created 0700 where the file system has POSIX permissions, else (Windows) an ACL for the owner alone.
 */
internal fun writePrivate(file: Path, text: String) {
    val dir = file.toAbsolutePath().parent
    val posix = "posix" in dir.fileSystem.supportedFileAttributeViews()
    Files.createDirectories(dir, ownerOnly(dir, posix, "rwx------"))
    val tmp = Files.createTempFile(dir, ".config", ".tmp", ownerOnly(dir, posix, "rw-------"))
    try {
        // Windows merges the parent's inheritable entries into an ACL given at creation; setting it again replaces them
        if (!posix) aclView(tmp).acl = ownerAcl(tmp)
        tmp.writeText(text)
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE) // replaces an existing file
    } finally {
        tmp.deleteIfExists()
    }
}

private fun ownerOnly(at: Path, posix: Boolean, mode: String): FileAttribute<*> {
    if (posix) return PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(mode))
    val acl = ownerAcl(at)
    return object : FileAttribute<List<AclEntry>> {
        override fun name() = "acl:acl"

        override fun value() = acl
    }
}

/** One entry: the current user, full control, nothing inherited. */
private fun ownerAcl(at: Path): List<AclEntry> {
    val user = at.fileSystem.userPrincipalLookupService.lookupPrincipalByName(System.getProperty("user.name"))
    return listOf(
        AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(user)
            .setPermissions(AclEntryPermission.entries.toSet()).build(),
    )
}

private fun aclView(path: Path): AclFileAttributeView =
    Files.getFileAttributeView(path, AclFileAttributeView::class.java)
        ?: throw UnsupportedOperationException("no ACL support on ${path.fileSystem}")
