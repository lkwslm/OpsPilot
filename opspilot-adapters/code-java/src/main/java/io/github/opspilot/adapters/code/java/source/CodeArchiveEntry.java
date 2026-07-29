package io.github.opspilot.adapters.code.java.source;

import java.util.Arrays;

/** A decoded hosting archive entry. Platform DTOs never leave the hosting client. */
public record CodeArchiveEntry(String path, byte[] content, EntryKind kind) {
    public CodeArchiveEntry {
        content = content == null ? new byte[0] : Arrays.copyOf(content, content.length);
        if (kind == null) throw new IllegalArgumentException("kind must not be null");
    }
    @Override public byte[] content() { return Arrays.copyOf(content, content.length); }
    public enum EntryKind { FILE, SYMBOLIC_LINK, SUBMODULE, LFS_POINTER }
}
